#include "npv10_dmc_native.h"
#include "npv10_lossless_chunk_codec.h"
#include "npv10_writeback_policy.h"
#include "npv10_aligned_record_writer.h"
#include "npv10_realtime_aligned_writer.h"
#include "npv10_inflight_io.h"
#include "npv10_record_io_barrier.h"
#include "npv10_record_io_priority.h"
#include "npv10_record_write_lifetime.h"
#include "npv10_lossless_storage_pressure.h"

#include <android/log.h>
#include <android/trace.h>
#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <thread>
#include <unistd.h>
#include <unordered_map>
#include <vector>

namespace {

class ScopedDmcTraceSection final {
public:
    explicit ScopedDmcTraceSection(const char* name) noexcept {
        ATrace_beginSection(name);
    }

    ~ScopedDmcTraceSection() {
        ATrace_endSection();
    }

    ScopedDmcTraceSection(const ScopedDmcTraceSection&) = delete;
    ScopedDmcTraceSection& operator=(const ScopedDmcTraceSection&) = delete;
};

thread_local std::string gLastDmcError;

void clearError() noexcept {
    gLastDmcError.clear();
}

void setError(const char* operation) noexcept {
    const int errorNumber = errno;
    try {
        gLastDmcError = operation;
        gLastDmcError += ": ";
        gLastDmcError += std::strerror(errorNumber);
        gLastDmcError += " (errno=";
        gLastDmcError += std::to_string(errorNumber);
        gLastDmcError += ')';
    } catch (...) {
        gLastDmcError = "native DMC error";
    }
}

void setContractError(const char* message) noexcept {
    try {
        gLastDmcError = message;
    } catch (...) {
        gLastDmcError = "native DMC contract error";
    }
}

bool checkedAdd(std::int64_t left, std::int64_t right, std::int64_t* output) noexcept {
    if (output == nullptr || left < 0 || right < 0 ||
        left > std::numeric_limits<std::int64_t>::max() - right) {
        return false;
    }
    *output = left + right;
    return true;
}

bool checkedMultiply(std::int64_t left, std::int64_t right, std::int64_t* output) noexcept {
    if (output == nullptr || left < 0 || right < 0 ||
        (right != 0 && left > std::numeric_limits<std::int64_t>::max() / right)) {
        return false;
    }
    *output = left * right;
    return true;
}

bool alignUp(std::size_t value, std::size_t alignment, std::size_t* output) noexcept {
    if (output == nullptr || alignment == 0 || value > std::numeric_limits<std::size_t>::max() -
        (alignment - 1u)) {
        return false;
    }
    *output = ((value + alignment - 1u) / alignment) * alignment;
    return true;
}

constexpr std::size_t kNpv10FrameHeaderBytes = 4u + 8u + 4u * 4u;
static_assert(sizeof(off_t) >= 8u, "NPV10 masters require 64-bit file offsets on every ABI");

void putBe32(std::uint8_t* target, std::uint32_t value) noexcept {
    target[0] = static_cast<std::uint8_t>((value >> 24u) & 0xffu);
    target[1] = static_cast<std::uint8_t>((value >> 16u) & 0xffu);
    target[2] = static_cast<std::uint8_t>((value >> 8u) & 0xffu);
    target[3] = static_cast<std::uint8_t>(value & 0xffu);
}

void putBe64(std::uint8_t* target, std::uint64_t value) noexcept {
    putBe32(target, static_cast<std::uint32_t>(value >> 32u));
    putBe32(target + 4u, static_cast<std::uint32_t>(value & 0xffff'ffffULL));
}

class DirectMappedCommitEngine final {
public:
    enum class SlotState : std::uint8_t {
        Empty,
        Claimed,
        Processing,
        Prepared,
        Queued,
        Committing
    };

    struct Slot {
        void* reservationBase = MAP_FAILED;
        std::uint8_t* mappingBase = nullptr;
        std::uint8_t* payload = nullptr;
        std::size_t mappingLength = 0u;
        std::int64_t frameIndex = -1;
        std::int64_t payloadFileOffset = -1;
        std::int64_t lastRetiredFrameIndex = -1;
        std::uint32_t crc32 = 0u;
        std::int64_t storedBytes = 0;
        std::int32_t compressedChunks = 0;
        std::int32_t rawChunks = 0;
        std::int64_t timestampNs = -1;
        std::int32_t width = 0;
        std::int32_t height = 0;
        std::int64_t reservationLimit = 0;
        bool fusedLosslessPreparation = false;
        bool unretiredKernelReader = false;
        neoproc::npv10::LosslessStoragePressurePolicy::Decision fusedCompressionDecision{};
        SlotState state = SlotState::Empty;
    };

    static std::shared_ptr<DirectMappedCommitEngine> create(
        const char* path,
        std::int64_t dataStartOffset,
        std::int32_t frameHeaderBytes,
        std::int32_t inputPayloadBytes,
        std::int32_t payloadCapacityBytes,
        std::int32_t sampleCount,
        bool chunkedLosslessCompression,
        std::int32_t slotCount,
        std::int64_t reservedBytes
    ) {
        if (path == nullptr || path[0] == '\0' || dataStartOffset < 0 || frameHeaderBytes <= 0 ||
            inputPayloadBytes <= 0 || payloadCapacityBytes < inputPayloadBytes || sampleCount <= 0 ||
            slotCount <= 0 || reservedBytes < dataStartOffset) {
            setContractError("invalid DMC initialization parameters");
            return nullptr;
        }
        if (chunkedLosslessCompression &&
            neoproc::npv10::lossless::maximumStoredBytes(inputPayloadBytes, sampleCount) !=
                payloadCapacityBytes) {
            setContractError("DMC lossless payload geometry is inconsistent");
            return nullptr;
        }
        const long rawPageSize = ::sysconf(_SC_PAGESIZE);
        if (rawPageSize <= 0) {
            setError("sysconf(_SC_PAGESIZE)");
            return nullptr;
        }
        const std::size_t pageSize = static_cast<std::size_t>(rawPageSize);
        const std::size_t recordAlignment = std::max<std::size_t>(4096u, pageSize);
        // At most two alignment sectors plus the frame header, NOT another frame or queue slot.
        // They allow variable-length NLC2 packets to be prepared at their eventual file residue.
        if (recordAlignment > (SIZE_MAX - static_cast<std::size_t>(payloadCapacityBytes) -
                static_cast<std::size_t>(frameHeaderBytes)) / 2u) {
            setContractError("DMC owned record headroom overflow");
            return nullptr;
        }
        const auto ownedRecordCapacity = static_cast<std::size_t>(payloadCapacityBytes) +
            (chunkedLosslessCompression ? recordAlignment * 2u + frameHeaderBytes : 0u);
        std::size_t mappingCapacity = 0u;
        if (!alignUp(ownedRecordCapacity, pageSize, &mappingCapacity) ||
            pageSize > std::numeric_limits<std::size_t>::max() / 2u ||
            mappingCapacity > std::numeric_limits<std::size_t>::max() - pageSize * 2u) {
            setContractError("DMC virtual slot size overflow");
            return nullptr;
        }
        const std::size_t reservationLength = mappingCapacity + pageSize * 2u;
        const int descriptor = ::open(path, O_RDWR | O_CLOEXEC | O_NOFOLLOW);
        if (descriptor < 0) {
            setError("open DMC master");
            return nullptr;
        }
        struct stat fileStatus {};
        if (::fstat(descriptor, &fileStatus) != 0) {
            setError("fstat DMC master");
            (void)::close(descriptor);
            return nullptr;
        }
        if (!S_ISREG(fileStatus.st_mode) || fileStatus.st_size < reservedBytes) {
            setContractError("DMC master is not a physically sized regular file");
            (void)::close(descriptor);
            return nullptr;
        }

        const neoproc::npv10::RecordWriteLifetime<> lifetime(descriptor);
        __android_log_print(ANDROID_LOG_INFO, "Npv10WriterIo",
            "WRITE_LIFETIME_HINT previous=%llu requested=%llu observed=%llu attempted=%s "
            "applied=%s errno=%d scope=owned_master_inode durability=unchanged",
            static_cast<unsigned long long>(lifetime.previous),
            static_cast<unsigned long long>(lifetime.kLong),
            static_cast<unsigned long long>(lifetime.observed),
            lifetime.attempted ? "true" : "false", lifetime.applied ? "true" : "false",
            lifetime.error);

        std::shared_ptr<DirectMappedCommitEngine> engine;
        try {
            engine.reset(new DirectMappedCommitEngine(
                descriptor,
                dataStartOffset,
                frameHeaderBytes,
                inputPayloadBytes,
                payloadCapacityBytes,
                sampleCount,
                chunkedLosslessCompression,
                pageSize,
                mappingCapacity,
                reservationLength,
                reservedBytes,
                slotCount
            ));
        } catch (...) {
            setContractError("DMC engine allocation failed");
            (void)::close(descriptor);
            return nullptr;
        }
        if (!engine->initializeAlignedRecordWriter(path) || !engine->reserveSlots()) {
            const std::string initializationError = gLastDmcError;
            (void)engine->shutdown();
            engine.reset();
            gLastDmcError = initializationError;
            return nullptr;
        }
        if (!engine->startRecordWriterWorker()) {
            const std::string initializationError = gLastDmcError;
            (void)engine->shutdown();
            engine.reset();
            gLastDmcError = initializationError;
            return nullptr;
        }
        engine->startIncrementalWritebackWorker();
        clearError();
        return engine;
    }

    ~DirectMappedCommitEngine() {
        (void)shutdown();
    }

    bool beginFrame(std::int64_t frameIndex, std::int64_t reservedBytes) noexcept {
        std::unique_lock<std::mutex> lock(mutex_);
        if (closed_ || closing_) {
            setContractError("DMC begin rejected after close");
            return false;
        }
        if (!recordWriterFailure_.empty()) {
            setContractError(recordWriterFailure_.c_str());
            return false;
        }
        if (frameIndex < 0 || reservedBytes < dataStartOffset_) {
            setContractError("DMC begin received invalid frame index or reservation");
            return false;
        }
        Slot& slot = slots_[static_cast<std::size_t>(frameIndex % slots_.size())];
        if (slot.state != SlotState::Empty) {
            const bool retired = processorStateChanged_.wait_for(
                lock,
                kSlotReuseWaitDuration,
                [this, &slot] {
                    return slot.state == SlotState::Empty || closing_ ||
                        !recordWriterFailure_.empty();
                }
            );
            if (!retired || slot.state != SlotState::Empty) {
                if (!recordWriterFailure_.empty()) {
                    setContractError(recordWriterFailure_.c_str());
                } else {
                    setContractError("DMC bounded slot reuse attempted before retirement");
                }
                return false;
            }
        }
        std::int64_t frameOrdinal = 0;
        std::int64_t frameDelta = 0;
        std::int64_t conservativeEnd = 0;
        // Completed packets already occupy their actual compressed extent. Cover every outstanding
        // packet at its worst-case size, but never reserve raw-sized space again for past frames.
        // nextRecordStartOffset_ includes all queued writes; commit still validates the exact tail,
        // physical extent and CRC before the sole positional writer can consume a slot.
        // An explicitly aborted allocator slot may be reclaimed at a later generation without any
        // intervening commits. Keep that existing API contract when the physical reservation covers
        // the full ordinal; the production writer separately enforces its bounded in-flight window.
        if (frameIndex < nextCommitFrameIndex_ ||
            !checkedAdd(frameIndex - nextCommitFrameIndex_, 1, &frameOrdinal) ||
            !checkedMultiply(frameOrdinal, maximumFrameRecordBytes_, &frameDelta) ||
            !checkedAdd(nextRecordStartOffset_, frameDelta, &conservativeEnd) ||
            conservativeEnd > reservedBytes) {
            setContractError("DMC frame exceeds its conservative physical reservation");
            return false;
        }
        if (alignedRecordWriterEnabled_ &&
            (!alignedRecordWriter_->alignedEnd(conservativeEnd, &conservativeEnd) ||
                conservativeEnd > reservedBytes)) {
            setContractError("DMC aligned frame tail exceeds its physical reservation");
            return false;
        }
        struct stat fileStatus {};
        int extentResult = -1;
        int extentErrno = 0;
        {
            // A filesystem query must not prevent the independent writer from retiring slots.
            // Shutdown retains the descriptor until this counted operation reacquires the lock.
            neoproc::npv10::ScopedUnlockedIo extentProbe(
                lock, activeFileExtentProbes_, processorStateChanged_);
            extentResult = ::fstat(descriptor_, &fileStatus);
            if (extentResult != 0) extentErrno = errno;
        }
        // Another caller or shutdown may have progressed during I/O. Reject stale frame/lifecycle
        // admission before changing any slot. A smaller reservation snapshot is not itself stale:
        // a concurrent 10-GiB extension can increase the high-water mark while this fstat runs.
        // This call must still prove its entire conservative extent against BOTH its own snapshot
        // and the physical file. Never borrow unverified capacity from another caller's snapshot.
        if (closed_ || closing_ || !recordWriterFailure_.empty() ||
            slot.state != SlotState::Empty || frameIndex < nextCommitFrameIndex_) {
            setContractError("DMC reservation proof became stale during extent validation");
            return false;
        }
        if (extentResult != 0) {
            errno = extentErrno;
            setError("fstat DMC frame reservation");
            return false;
        }
        if (!S_ISREG(fileStatus.st_mode) || conservativeEnd > fileStatus.st_size) {
            setContractError("DMC frame is outside the physically sized master file");
            return false;
        }
        slot.frameIndex = frameIndex;
        slot.payloadFileOffset = -1;
        slot.crc32 = 0u;
        slot.storedBytes = 0;
        slot.compressedChunks = 0;
        slot.rawChunks = 0;
        slot.timestampNs = -1;
        slot.width = 0;
        slot.height = 0;
        slot.reservationLimit = 0;
        slot.state = SlotState::Claimed;
        reservedBytes_ = std::max(reservedBytes_, reservedBytes);
        clearError();
        return true;
    }

    bool acquirePayload(
        std::int64_t frameIndex,
        std::int64_t requiredBytes,
        neoproc::npv10::DirectMappedPayloadView* output,
        bool requestFusedLosslessPreparation = false
    ) noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        if (output == nullptr || closed_ || closing_ || frameIndex < 0 ||
            requiredBytes != inputPayloadBytes_) {
            setContractError("DMC processor claim parameters are invalid");
            return false;
        }
        Slot& slot = slots_[static_cast<std::size_t>(frameIndex % slots_.size())];
        if (slot.state != SlotState::Claimed || slot.frameIndex != frameIndex || slot.payload == nullptr) {
            setContractError("DMC processor claimed a stale or unowned frame");
            return false;
        }
        slot.state = SlotState::Processing;
        ++activeProcessors_;
        maximumProcessingOverlap_ = std::max(maximumProcessingOverlap_, activeProcessors_);
        slot.fusedLosslessPreparation = requestFusedLosslessPreparation && chunkedLosslessCompression_;
        // Only the immediate ordered frame has a proven final offset. Later parallel preparations
        // never wait for it or guess compressed sizes: their unchanged layout uses staged writes.
        slot.payload = slot.mappingBase;
        if (chunkedLosslessCompression_ && alignedRecordWriterEnabled_ &&
            frameIndex == nextCommitFrameIndex_) {
            const auto prefix = static_cast<std::size_t>(nextRecordStartOffset_ %
                static_cast<std::int64_t>(alignedRecordWriter_->alignment()));
            const auto head = prefix + static_cast<std::size_t>(frameHeaderBytes_);
            if (head <= slot.mappingLength &&
                static_cast<std::size_t>(payloadCapacityBytes_) <= slot.mappingLength - head) {
                slot.payload += head;
            }
        }
        slot.fusedCompressionDecision = slot.fusedLosslessPreparation
            ? losslessStoragePressure_.choose(recordWriteQueueCount_ + activeCommits_,
                slots_.size(), maximumProcessingOverlap_)
            : neoproc::npv10::LosslessStoragePressurePolicy::Decision{};
        output->data = slot.payload;
        output->capacity = payloadCapacityBytes_;
        output->fusedLosslessPreparation = slot.fusedLosslessPreparation;
        output->losslessAcceleration = slot.fusedCompressionDecision.acceleration;
        clearError();
        return true;
    }

    bool finishProcessing(
        std::int64_t frameIndex,
        bool successful,
        std::uint32_t packedCrc32,
        neoproc::npv10::DirectMappedPreparedPayload* output,
        const neoproc::npv10::lossless::EncodeResult* alreadyEncoded = nullptr,
        std::int64_t fusedPreparationElapsedNs = 0
    ) noexcept {
        if (output == nullptr || frameIndex < 0) {
            setContractError("DMC processor finish frame index is invalid");
            return false;
        }
        *output = {};
        std::unique_lock<std::mutex> lock(mutex_);
        Slot& slot = slots_[static_cast<std::size_t>(frameIndex % slots_.size())];
        if (slot.state != SlotState::Processing || slot.frameIndex != frameIndex ||
            activeProcessors_ == 0u) {
            setContractError("DMC processor finish did not match an active claim");
            return false;
        }
        if (!successful) {
            --activeProcessors_;
            const bool retired = retireSlot(slot, frameIndex);
            processorStateChanged_.notify_all();
            if (retired) clearError();
            return retired;
        }

        // Packet preparation runs outside the registry lock. Every slot has exclusive PROCESSING
        // ownership, and activeProcessors_ prevents shutdown from unmapping it. Adaptive Lossless
        // Commit keeps the pre-faulted ring and ordered asynchronous writer, but uses bounded
        // upstream LZ4 on worthwhile chunks before their write. Incompressible chunks retain every
        // source bit in the existing raw representation; no quantizer, pixel, FPS or CRC changes.
        // This worker-local CPU task introduces no GPU dispatch/readback or registry-lock wait.
        std::uint8_t* const payload = slot.payload;
        const bool fusedPreparation = slot.fusedLosslessPreparation;
        const auto compressionDecision = fusedPreparation ? slot.fusedCompressionDecision : chunkedLosslessCompression_
            ? losslessStoragePressure_.choose(recordWriteQueueCount_ + activeCommits_,
                slots_.size(), maximumProcessingOverlap_)
            : neoproc::npv10::LosslessStoragePressurePolicy::Decision{};
        lock.unlock();
        const auto encodeStarted = std::chrono::steady_clock::now();
        neoproc::npv10::DirectMappedPreparedPayload prepared{};
        bool encoded = true;
        if (alreadyEncoded != nullptr || fusedPreparation) {
            // The plane encoder owns the same PROCESSING slot and emits the same
            // packet/CRCs. Never feed an already encoded NLC2 packet to the packed
            // input compressor. Reject mismatched completion routes and bounds.
            const auto chunks = (sampleCount_ + std::int64_t{neoproc::npv10::lossless::kChunkSamples} - 1) /
                neoproc::npv10::lossless::kChunkSamples;
            encoded = fusedPreparation && chunkedLosslessCompression_ && alreadyEncoded != nullptr &&
                alreadyEncoded->successful && fusedPreparationElapsedNs >= 0 &&
                alreadyEncoded->packedCrc32 == packedCrc32 && alreadyEncoded->compressedChunks >= 0 &&
                alreadyEncoded->rawChunks >= 0 &&
                std::int64_t{alreadyEncoded->compressedChunks} + alreadyEncoded->rawChunks == chunks &&
                alreadyEncoded->storedBytes >= neoproc::npv10::lossless::kFrameHeaderBytes +
                    chunks * (neoproc::npv10::lossless::kChunkHeaderBytes + 1) &&
                alreadyEncoded->storedBytes <= payloadCapacityBytes_;
            if (encoded) {
                prepared.storedBytes = alreadyEncoded->storedBytes;
                prepared.storedCrc32 = alreadyEncoded->storedCrc32;
                prepared.compressedChunks = alreadyEncoded->compressedChunks;
                prepared.rawChunks = alreadyEncoded->rawChunks;
            }
        } else if (chunkedLosslessCompression_) {
            ScopedDmcTraceSection encodeTrace(compressionDecision.storagePressure
                ? "NPV10 lossless storage pressure encode" : "NPV10 lossless fast encode");
            const auto result = neoproc::npv10::lossless::compressPackedTenBitRealtimeRangeLz4InPlace(
                payload,
                inputPayloadBytes_,
                sampleCount_,
                payloadCapacityBytes_,
                packedCrc32,
                compressionDecision.acceleration
            );
            encoded = result.successful;
            prepared.storedBytes = result.storedBytes;
            prepared.storedCrc32 = result.storedCrc32;
            prepared.compressedChunks = result.compressedChunks;
            prepared.rawChunks = result.rawChunks;
        } else {
            prepared.storedBytes = inputPayloadBytes_;
            prepared.storedCrc32 = packedCrc32;
        }
        const auto encodeElapsedNs = fusedPreparation ? fusedPreparationElapsedNs :
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - encodeStarted).count();
        lock.lock();
        if (chunkedLosslessCompression_) {
            losslessStoragePressure_.completed(compressionDecision, encodeElapsedNs,
                inputPayloadBytes_, prepared.storedBytes, encoded);
        }
        if (slot.state != SlotState::Processing || slot.frameIndex != frameIndex ||
            activeProcessors_ == 0u) {
            if (activeProcessors_ > 0u) --activeProcessors_;
            processorStateChanged_.notify_all();
            setContractError("DMC processor ownership changed during lossless encoding");
            return false;
        }
        --activeProcessors_;
        bool stateUpdated = true;
        if (encoded && prepared.storedBytes > 0 &&
            prepared.storedBytes <= payloadCapacityBytes_) {
            slot.crc32 = prepared.storedCrc32;
            slot.storedBytes = prepared.storedBytes;
            slot.compressedChunks = prepared.compressedChunks;
            slot.rawChunks = prepared.rawChunks;
            slot.state = SlotState::Prepared;
            *output = prepared;
        } else {
            stateUpdated = retireSlot(slot, frameIndex);
            if (stateUpdated) setContractError("DMC bit-exact lossless encoding failed");
        }
        processorStateChanged_.notify_all();
        if (encoded && stateUpdated) clearError();
        return encoded && stateUpdated;
    }

    bool commitFrame(
        std::int64_t frameIndex,
        std::int64_t encodedBytes,
        std::int64_t payloadFileOffset,
        std::int64_t reservationLimit,
        std::uint32_t crc32,
        std::int64_t timestampNs,
        std::int32_t width,
        std::int32_t height
    ) noexcept {
        std::unique_lock<std::mutex> lock(mutex_);
        if (closed_ || closing_ || frameIndex < 0) {
            setContractError("DMC commit rejected by lifecycle state");
            return false;
        }
        if (!recordWriterFailure_.empty()) {
            setContractError(recordWriterFailure_.c_str());
            return false;
        }
        Slot& slot = slots_[static_cast<std::size_t>(frameIndex % slots_.size())];
        if (slot.state != SlotState::Prepared || slot.frameIndex != frameIndex ||
            slot.crc32 != crc32 || slot.storedBytes != encodedBytes || slot.payload == nullptr ||
            encodedBytes <= 0 || encodedBytes > payloadCapacityBytes_ ||
            slot.mappingLength < static_cast<std::size_t>(encodedBytes) ||
            frameIndex != nextCommitFrameIndex_ ||
            payloadFileOffset != nextRecordStartOffset_ + frameHeaderBytes_ ||
            timestampNs <= 0 || width <= 0 || height <= 0 ||
            frameHeaderBytes_ != static_cast<std::int64_t>(kNpv10FrameHeaderBytes)) {
            setContractError("DMC commit did not match the prepared frame and CRC");
            return false;
        }
        std::int64_t payloadEnd = 0;
        // Another packing lane may have observed a newer, physically proven allocation prefix
        // since this ordered commit took its snapshot. Growth cannot invalidate bytes covered by
        // the older snapshot. Validate this record against ITS OWN limit; never borrow the newer
        // high-water mark to admit an uncovered payload. The positional writer also rechecks the
        // actual file extent (and aligned tail) before persistence.
        if (!checkedAdd(payloadFileOffset, encodedBytes, &payloadEnd) ||
            reservationLimit < dataStartOffset_ || payloadEnd > reservationLimit) {
            setContractError("DMC commit exceeds its physical reservation");
            return false;
        }
        if (recordWriteQueueCount_ >= recordWriteQueue_.size()) {
            setContractError("DMC bounded record queue is full");
            return false;
        }
        reservedBytes_ = std::max(reservedBytes_, reservationLimit);
        std::atomic_thread_fence(std::memory_order_release);
        slot.payloadFileOffset = payloadFileOffset;
        slot.timestampNs = timestampNs;
        slot.width = width;
        slot.height = height;
        slot.reservationLimit = reservationLimit;
        losslessStoragePressure_.observeSourceTimestamp(timestampNs);
        slot.state = SlotState::Queued;
        recordWriteQueue_[recordWriteQueueTail_] = frameIndex;
        recordWriteQueueTail_ = (recordWriteQueueTail_ + 1u) % recordWriteQueue_.size();
        ++recordWriteQueueCount_;
        recordWriteMaxQueueDepth_ = std::max(
            recordWriteMaxQueueDepth_,
            static_cast<std::int64_t>(recordWriteQueueCount_)
        );
        nextRecordStartOffset_ = payloadEnd;
        ++nextCommitFrameIndex_;
        recordWriteWorkAvailable_.notify_one();
        clearError();
        return true;
    }

    bool awaitCommittedFrames(std::int64_t requiredFrames) noexcept {
        if (requiredFrames < 0) {
            setContractError("DMC drain frame count is invalid");
            return false;
        }
        std::unique_lock<std::mutex> lock(mutex_);
        if (requiredFrames > nextCommitFrameIndex_) {
            setContractError("DMC drain requested a frame boundary that was never submitted");
            return false;
        }
        processorStateChanged_.wait(lock, [this, requiredFrames] {
            return recordWriteCompletedFrames_ >= requiredFrames ||
                !recordWriterFailure_.empty() || closed_;
        });
        if (!recordWriterFailure_.empty()) {
            setContractError(recordWriterFailure_.c_str());
            return false;
        }
        if (recordWriteCompletedFrames_ < requiredFrames) {
            setContractError("DMC closed before the requested record boundary completed");
            return false;
        }
        clearError();
        return true;
    }

    bool allocatePhysicalRange(std::int64_t offset, std::int64_t byteCount) noexcept {
        constexpr std::int64_t kMaximumLiveSliceBytes = 32LL * 1024LL * 1024LL;
        std::int64_t end = 0;
        if (offset < 0 || byteCount <= 0 || byteCount > kMaximumLiveSliceBytes ||
            !checkedAdd(offset, byteCount, &end)) {
            setContractError("DMC physical allocation slice is invalid");
            return false;
        }
        std::unique_lock<std::mutex> lock(mutex_);
        if (closed_ || closing_ || descriptor_ < 0 || activeReservationAllocations_ != 0u ||
            offset != physicalAllocationEndOffset_) {
            setContractError("DMC physical allocation is closed, concurrent, stale or non-contiguous");
            return false;
        }
        int failure = EIO;
        try {
            // Holding a different FileDescriptor did not isolate fallocate from this inode's
            // O_DIRECT writes and fdatasync. All three now share the SAME I/O boundary. Keep
            // the state mutex available and retain the descriptor until this slice completes.
            neoproc::npv10::ScopedUnlockedIo retainedDescriptor(
                lock, activeReservationAllocations_, processorStateChanged_);
            ScopedDmcTraceSection waitTrace("NPV10 reservation record I/O barrier");
            auto exclusiveIo = recordIoBarrier_.acquire();
            ScopedDmcTraceSection allocationTrace("NPV10 coordinated physical allocation");
            do {
                failure = ::posix_fallocate64(descriptor_, static_cast<off64_t>(offset),
                    static_cast<off64_t>(byteCount));
            } while (failure == EINTR);
        } catch (...) {
            setContractError("DMC physical allocation ownership failed");
            return false;
        }
        if (failure != 0) {
            errno = failure;
            setError("DMC coordinated physical allocation");
            return false;
        }
        // Do not change a frame's admission snapshot or advertise an unallocated suffix.
        // Kotlin publishes precisely this completed prefix after the successful JNI return.
        physicalAllocationEndOffset_ = end;
        clearError();
        return true;
    }

    bool forceCommittedFrames(std::int64_t requiredFrames) noexcept {
        if (!awaitCommittedFrames(requiredFrames)) return false;
        std::unique_lock<std::mutex> lock(mutex_);
        if (closed_ || closing_ || descriptor_ < 0) {
            setContractError("DMC durability requested during or after close");
            return false;
        }
        if (!recordWriterFailure_.empty()) {
            setContractError(recordWriterFailure_.c_str());
            return false;
        }
        int result = -1;
        int failure = 0;
        try {
            // Retain the descriptor through both lock wait and syscall. Shutdown waits for this
            // lease, while the state lock remains available to packers and record completion.
            neoproc::npv10::ScopedUnlockedIo retainedDescriptor(
                lock, activeDurabilityForces_, processorStateChanged_);
            // The writer waits on this same barrier, so leaving its durability dependency at
            // default I/O priority can invert the writer's foreground storage admission. Scope
            // the identical best-effort hint to this force only and restore the calling thread
            // on every exit. Neither the durability interval nor the measured wait is changed.
            neoproc::npv10::ScopedRecordIoPriority<> durabilityPriority;
            __android_log_print(ANDROID_LOG_INFO, "Npv10WriterIo",
                "DURABILITY_IO_PRIORITY requested=%d previous=%d observed=%d applied=%s errno=%d "
                "scope=owned_force_call durability=unchanged",
                durabilityPriority.kRequested, durabilityPriority.previous(),
                durabilityPriority.observed(), durabilityPriority.applied() ? "true" : "false",
                durabilityPriority.error());
            ScopedDmcTraceSection barrierTrace("NPV10 durability record I/O barrier");
            auto exclusiveIo = recordIoBarrier_.acquire();
            ScopedDmcTraceSection forceTrace("NPV10 serialized fdatasync");
            do { result = ::fdatasync(descriptor_); } while (result < 0 && errno == EINTR);
            failure = result < 0 ? errno : 0;
        } catch (...) {
            setContractError("DMC durability I/O ownership failed");
            return false;
        }
        if (result != 0) {
            errno = failure == 0 ? EIO : failure;
            setError("DMC serialized durability checkpoint");
            return false;
        }
        clearError();
        return true;
    }

    void snapshotIncrementalWriteback(std::int64_t* output, std::size_t outputCount) noexcept {
        if (output == nullptr || outputCount < kWritebackTelemetryValues) return;
        std::lock_guard<std::mutex> lock(mutex_);
        output[0] = incrementalWritebackEnabled_ ? 1 : 0;
        output[1] = incrementalWritebackAttempts_;
        output[2] = incrementalWritebackSuccesses_;
        output[3] = incrementalWritebackFailures_;
        output[4] = incrementalWritebackBytes_;
        output[5] = incrementalWritebackTotalNs_;
        output[6] = incrementalWritebackMaxNs_;
        output[7] = incrementalWritebackCoveredThroughOffset_;
        output[8] = incrementalWritebackLastErrno_;
        output[9] = incrementalWritebackSuppressedForRealtime_ ? 1 : 0;
        output[10] = recordWriterWorkerStarted_ ? 1 : 0;
        output[11] = recordWriteCompletedFrames_;
        output[12] = recordWriteFailures_;
        output[13] = recordWriteTotalNs_;
        output[14] = recordWriteMaxNs_;
        output[15] = recordWriteMaxQueueDepth_;
        output[16] = static_cast<std::int64_t>(recordWriteQueueCount_);
        output[17] = nextCommitFrameIndex_;
        output[18] = incrementalWritebackMaxRequestBytes_;
        output[19] = incrementalWritebackMaxPendingBytes_;
        output[20] = recordWriteCompletedBytes_;
        output[21] = neoproc::npv10::IncrementalWritebackPolicy::targetBytes(
            chunkedLosslessCompression_
        );
        output[22] = alignedRecordWriterAttempted_ ? 1 : 0;
        output[23] = alignedRecordWriterEnabled_ ? 1 : 0;
        output[24] = alignedRecordWriter_ ? alignedRecordWriter_->admissionErrno() : 0;
        output[25] = alignedRecordWriter_ ?
            static_cast<std::int64_t>(alignedRecordWriter_->alignment()) : 0;
        const auto telemetryCount = [](std::uint64_t value) noexcept {
            return static_cast<std::int64_t>(std::min<std::uint64_t>(
                value, static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())));
        };
        output[26] = alignedRecordWriter_ ? telemetryCount(alignedRecordWriter_->writeCalls()) : 0;
        output[27] = alignedRecordWriter_ ? telemetryCount(alignedRecordWriter_->physicalBytes()) : 0;
        output[28] = alignedRecordWriter_ ? telemetryCount(alignedRecordWriter_->paddingBytes()) : 0;
        output[29] = alignedRecordWriter_ ? telemetryCount(alignedRecordWriter_->failures()) : 0;
        output[30] = alignedRecordWriterEnabled_ ?
            static_cast<std::int64_t>(alignedRecordWriter_->stagingBytes()) : 0;
        output[31] = alignedRecordWriter_ && alignedRecordWriter_->probeVerified() ? 1 : 0;
        const auto& pressure = losslessStoragePressure_.counters();
        output[32] = chunkedLosslessCompression_ ? 1 : 0;
        output[33] = losslessStoragePressure_.active() ? 1 : 0;
        output[34] = pressure.entries;
        output[35] = pressure.fastFrames;
        output[36] = pressure.pressureFrames;
        output[37] = pressure.cpuBackoffs;
        output[38] = pressure.fastEncodeNs;
        output[39] = pressure.pressureEncodeNs;
        output[40] = pressure.pressureMaxEncodeNs;
        output[41] = pressure.fastInputBytes;
        output[42] = pressure.fastStoredBytes;
        output[43] = pressure.pressureInputBytes;
        output[44] = pressure.pressureStoredBytes;
        output[45] = losslessStoragePressure_.sourceIntervalNs();
        output[46] = pressure.failures;
        output[47] = losslessStoragePressure_.cooldownFrames();
        output[48] = alignedRecordWriter_ && alignedRecordWriter_->kernelAioEnabled() ? 1 : 0;
        output[49] = alignedRecordWriter_ ? alignedRecordWriter_->kernelAioAdmissionErrno() : 0;
        output[50] = alignedRecordWriter_ ? telemetryCount(alignedRecordWriter_->kernelAioSubmissions()) : 0;
        output[51] = alignedRecordWriter_ ? telemetryCount(alignedRecordWriter_->kernelAioCompletions()) : 0;
        output[52] = alignedRecordWriter_ ? telemetryCount(alignedRecordWriter_->ownedRecordWrites()) : 0;
        output[53] = alignedRecordWriter_ ? telemetryCount(alignedRecordWriter_->ownedPayloadBytes()) : 0;
        output[54] = alignedRecordWriter_ ? telemetryCount(alignedRecordWriter_->immutableStreamRecordWrites()) : 0;
    }

    bool abortFrame(std::int64_t frameIndex) noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        if (closed_ || closing_ || frameIndex < 0) {
            setContractError("DMC abort rejected by lifecycle state");
            return false;
        }
        Slot& slot = slots_[static_cast<std::size_t>(frameIndex % slots_.size())];
        if (slot.state == SlotState::Empty && slot.lastRetiredFrameIndex == frameIndex) {
            clearError();
            return true;
        }
        if (slot.frameIndex != frameIndex || slot.state == SlotState::Empty) {
            setContractError("DMC abort targeted a stale frame slot");
            return false;
        }
        if (slot.state == SlotState::Processing || slot.state == SlotState::Queued ||
            slot.state == SlotState::Committing) {
            setContractError("DMC abort targeted an active processor or commit");
            return false;
        }
        const bool retired = retireSlot(slot, frameIndex);
        if (retired) clearError();
        return retired;
    }

    bool shutdown() noexcept {
        std::unique_lock<std::mutex> lock(mutex_);
        if (closed_) {
            if (shutdownSuccessful_) {
                clearError();
            } else {
                setContractError("DMC shutdown previously failed");
            }
            return shutdownSuccessful_;
        }
        if (closing_) {
            processorStateChanged_.wait(lock, [this] { return closed_; });
            if (shutdownSuccessful_) {
                clearError();
            } else {
                setContractError("DMC concurrent shutdown failed");
            }
            return shutdownSuccessful_;
        }
        closing_ = true;
        processorStateChanged_.wait(lock, [this] {
            return activeProcessors_ == 0u && activeFileExtentProbes_ == 0u &&
                activeDurabilityForces_ == 0u && activeReservationAllocations_ == 0u;
        });
        recordWriterStopping_ = true;
        recordWriteWorkAvailable_.notify_all();
        std::thread recordWriterThread;
        if (recordWriterWorkerStarted_) {
            recordWriterThread = std::move(recordWriterThread_);
            recordWriterWorkerStarted_ = false;
        }
        lock.unlock();
        if (recordWriterThread.joinable()) recordWriterThread.join();
        lock.lock();
        incrementalWritebackStopping_ = true;
        incrementalWritebackWorkAvailable_.notify_all();
        std::thread incrementalWritebackThread;
        if (incrementalWritebackWorkerStarted_) {
            incrementalWritebackThread = std::move(incrementalWritebackThread_);
            incrementalWritebackWorkerStarted_ = false;
        }
        lock.unlock();
        if (incrementalWritebackThread.joinable()) incrementalWritebackThread.join();
        lock.lock();
        bool successful = recordWriterFailure_.empty() && recordWriteQueueCount_ == 0u &&
            activeCommits_ == 0u;
        // Retire the kernel context BEFORE releasing slots now used as direct-I/O sources.
        // On the exceptional io_destroy-failure path retain only the bounded slot still read by
        // the kernel; it remains inaccessible and unpublished until process death.
        if (alignedRecordWriter_ && !alignedRecordWriter_->close()) {
            errno = alignedRecordWriter_->failureErrno();
            if (successful) setError("close DMC aligned descriptor");
            successful = false;
        }
        for (Slot& slot : slots_) {
            if (slot.unretiredKernelReader && alignedRecordWriter_ &&
                !alignedRecordWriter_->sourceBuffersRetired()) {
                successful = false;
                continue;
            }
            if (slot.reservationBase != MAP_FAILED &&
                ::munmap(slot.reservationBase, reservationLength_) != 0) {
                if (successful) setError("munmap DMC slot reservation");
                successful = false;
            }
            slot.reservationBase = MAP_FAILED;
            slot.mappingBase = nullptr;
            slot.payload = nullptr;
            slot.mappingLength = 0u;
            slot.frameIndex = -1;
            slot.payloadFileOffset = -1;
            slot.state = SlotState::Empty;
        }
        if (descriptor_ >= 0 && ::close(descriptor_) != 0) {
            if (successful) setError("close DMC descriptor");
            successful = false;
        }
        descriptor_ = -1;
        shutdownSuccessful_ = successful;
        closed_ = true;
        closing_ = false;
        processorStateChanged_.notify_all();
        if (successful) {
            clearError();
        } else if (!recordWriterFailure_.empty()) {
            setContractError(recordWriterFailure_.c_str());
        }
        return successful;
    }

private:
    DirectMappedCommitEngine(
        int descriptor,
        std::int64_t dataStartOffset,
        std::int32_t frameHeaderBytes,
        std::int32_t inputPayloadBytes,
        std::int32_t payloadCapacityBytes,
        std::int32_t sampleCount,
        bool chunkedLosslessCompression,
        std::size_t pageSize,
        std::size_t mappingCapacity,
        std::size_t reservationLength,
        std::int64_t reservedBytes,
        std::int32_t slotCount
    ) : descriptor_(descriptor),
        dataStartOffset_(dataStartOffset),
        frameHeaderBytes_(frameHeaderBytes),
        inputPayloadBytes_(inputPayloadBytes),
        payloadCapacityBytes_(payloadCapacityBytes),
        sampleCount_(sampleCount),
        chunkedLosslessCompression_(chunkedLosslessCompression),
        maximumFrameRecordBytes_(
            static_cast<std::int64_t>(frameHeaderBytes) + payloadCapacityBytes
        ),
        pageSize_(pageSize),
        mappingCapacity_(mappingCapacity),
        reservationLength_(reservationLength),
        reservedBytes_(reservedBytes),
        physicalAllocationEndOffset_(reservedBytes),
        nextRecordStartOffset_(dataStartOffset),
        incrementalWritebackPendingEndOffset_(dataStartOffset),
        incrementalWritebackCoveredThroughOffset_(dataStartOffset),
        slots_(static_cast<std::size_t>(slotCount)),
        recordWriteQueue_(static_cast<std::size_t>(slotCount), -1) {
        // Keep the measured uncompressed large-frame policy. A compressed slot's worst-case
        // capacity must not suppress hints for its much smaller actual writes. The separate
        // worker now bounds every hint, even after a stalled syscall accumulates many frames.
        // Periodic force(false), final force(true), CRC, and hash durability remain unchanged.
        incrementalWritebackSuppressedForRealtime_ =
            neoproc::npv10::IncrementalWritebackPolicy::suppressForRealtime(
                chunkedLosslessCompression_, payloadCapacityBytes_
            );
        incrementalWritebackEnabled_ = !incrementalWritebackSuppressedForRealtime_;
    }

    bool initializeAlignedRecordWriter(const char* path) noexcept {
        // The measured benefit concerns multi-MiB lossless Cinema packets. Keep the existing
        // small/legacy packet backend unchanged instead of introducing sector-copy overhead there.
        using Writer = neoproc::npv10::RealtimeAlignedRecordWriter;
        if (!chunkedLosslessCompression_ ||
            payloadCapacityBytes_ < static_cast<std::int64_t>(Writer::kMinimumPayloadCapacity)) return true;
        alignedRecordWriterAttempted_ = true;
        try {
            alignedRecordWriter_ = std::make_unique<Writer>(
                descriptor_, path, dataStartOffset_, reservedBytes_);
        } catch (...) {
            setContractError("DMC aligned writer allocation failed");
            return false;
        }
        if (alignedRecordWriter_->admission() == Writer::Admission::Fatal) {
            errno = alignedRecordWriter_->admissionErrno();
            setError("DMC aligned writer admission");
            return false;
        }
        alignedRecordWriterEnabled_ =
            alignedRecordWriter_->admission() == Writer::Admission::Active;
        // Direct writes do not build a dirty-page backlog. This disables only the optional hint,
        // never the first-frame, 120-frame or final durability barriers in Npv10FileWriter.
        if (alignedRecordWriterEnabled_) incrementalWritebackEnabled_ = false;
        return true;
    }

    /**
     * Starts the mandatory bounded record writer used by the live DMC route. Packing publishes an
     * immutable slot to this worker and immediately returns to the ordered Kotlin lane. The worker
     * owns all file I/O, preserves record order, and retires a slot only after its complete header
     * and payload have reached the kernel. This is the same producer/consumer separation used by
     * the RAW recorder, without copying the packed pixels into a second queue buffer.
     */
    bool startRecordWriterWorker() noexcept {
        try {
            recordWriterThread_ = std::thread([this] { recordWriterWorkerLoop(); });
            recordWriterWorkerStarted_ = true;
            return true;
        } catch (...) {
            setContractError("DMC record writer thread creation failed");
            return false;
        }
    }

    void recordWriterWorkerLoop() noexcept {
        // Best effort only: Android may cap the requested nice value. Correctness never depends on
        // scheduler privilege, while a successful request reduces UFS tail latency under load.
        (void)::setpriority(PRIO_PROCESS, 0, -4);
        // CPU nice alone leaves storage at its default priority. This owned-thread, best-effort
        // hint is sandbox-admitted and read back before any frame I/O. No realtime class, global
        // scheduler change, thermal override, buffer increase or early completion is permitted.
        neoproc::npv10::ScopedRecordIoPriority<> storagePriority;
        __android_log_print(ANDROID_LOG_INFO, "Npv10WriterIo",
            "WRITER_IO_PRIORITY requested=%d previous=%d observed=%d applied=%s errno=%d "
            "scope=owned_writer_thread durability=unchanged",
            storagePriority.kRequested, storagePriority.previous(), storagePriority.observed(),
            storagePriority.applied() ? "true" : "false", storagePriority.error());
        std::unique_lock<std::mutex> lock(mutex_);
        for (;;) {
            recordWriteWorkAvailable_.wait(lock, [this] {
                return recordWriterStopping_ || recordWriteQueueCount_ > 0u;
            });
            if (recordWriteQueueCount_ == 0u) {
                if (recordWriterStopping_) return;
                continue;
            }

            const std::int64_t frameIndex = recordWriteQueue_[recordWriteQueueHead_];
            recordWriteQueue_[recordWriteQueueHead_] = -1;
            recordWriteQueueHead_ = (recordWriteQueueHead_ + 1u) % recordWriteQueue_.size();
            --recordWriteQueueCount_;
            Slot& slot = slots_[static_cast<std::size_t>(frameIndex % slots_.size())];
            if (slot.state != SlotState::Queued || slot.frameIndex != frameIndex ||
                slot.payload == nullptr) {
                recordWriterFailure_ = "DMC record writer dequeued an invalid slot";
                ++recordWriteFailures_;
                shutdownSuccessful_ = false;
                processorStateChanged_.notify_all();
                return;
            }

            slot.state = SlotState::Committing;
            ++activeCommits_;
            const std::uint8_t* const payload = slot.payload;
            std::uint8_t* const ownedStorage = slot.mappingBase;
            const std::size_t ownedStorageBytes = slot.mappingLength;
            const std::int64_t storedBytes = slot.storedBytes;
            const std::int64_t payloadFileOffset = slot.payloadFileOffset;
            const std::int64_t reservationLimit = slot.reservationLimit;
            const std::int64_t timestampNs = slot.timestampNs;
            const std::int32_t width = slot.width;
            const std::int32_t height = slot.height;
            const std::uint32_t crc32 = slot.crc32;
            lock.unlock();

            const auto started = std::chrono::steady_clock::now();
            bool written = false;
            {
                ScopedDmcTraceSection writeTrace("NPV10 DMC async record write");
                written = writeRecordAt(
                    payload,
                    storedBytes,
                    payloadFileOffset,
                    reservationLimit,
                    timestampNs,
                    width,
                    height,
                    crc32,
                    ownedStorage,
                    ownedStorageBytes
                );
            }
            const std::int64_t elapsedNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - started
            ).count();
            const std::string writeError = written ? std::string() : gLastDmcError;

            lock.lock();
            --activeCommits_;
            if (recordWriteTotalNs_ <= std::numeric_limits<std::int64_t>::max() - elapsedNs) {
                recordWriteTotalNs_ += elapsedNs;
            } else {
                recordWriteTotalNs_ = std::numeric_limits<std::int64_t>::max();
            }
            recordWriteMaxNs_ = std::max(recordWriteMaxNs_, elapsedNs);
            if (!written) {
                slot.unretiredKernelReader = alignedRecordWriter_ &&
                    !alignedRecordWriter_->sourceBuffersRetired();
                ++recordWriteFailures_;
                recordWriterFailure_ = writeError.empty()
                    ? "DMC asynchronous record write failed"
                    : writeError;
                shutdownSuccessful_ = false;
                (void)retireSlot(slot, frameIndex);
                while (recordWriteQueueCount_ > 0u) {
                    const std::int64_t queuedFrame = recordWriteQueue_[recordWriteQueueHead_];
                    recordWriteQueue_[recordWriteQueueHead_] = -1;
                    recordWriteQueueHead_ =
                        (recordWriteQueueHead_ + 1u) % recordWriteQueue_.size();
                    --recordWriteQueueCount_;
                    Slot& queuedSlot = slots_[
                        static_cast<std::size_t>(queuedFrame % slots_.size())
                    ];
                    if (queuedSlot.state != SlotState::Empty) {
                        (void)retireSlot(queuedSlot, queuedFrame);
                    }
                }
                recordWriterStopping_ = true;
                processorStateChanged_.notify_all();
                return;
            }

            ++recordWriteCompletedFrames_;
            recordWriteCompletedBytes_ += frameHeaderBytes_ + storedBytes;
            noteCommittedRangeForIncrementalWriteback(
                payloadFileOffset + storedBytes
            );
            if (!retireSlot(slot, frameIndex)) {
                ++recordWriteFailures_;
                recordWriterFailure_ = "DMC record writer could not retire its completed slot";
                shutdownSuccessful_ = false;
                recordWriterStopping_ = true;
                processorStateChanged_.notify_all();
                return;
            }
            processorStateChanged_.notify_all();
        }
    }

    /**
     * The range-writeback syscall is only a latency hint, so failure to create its worker must not
     * disable the lossless DMC recorder or weaken its mandatory FileChannel durability barriers.
     * Record the unavailable hint explicitly and continue on the proven force(false)/force(true)
     * path. The worker owns no pixel buffers and is joined before the descriptor is closed.
     */
    void startIncrementalWritebackWorker() noexcept {
        if (!incrementalWritebackEnabled_) return;
        try {
            incrementalWritebackThread_ = std::thread([this] {
                incrementalWritebackWorkerLoop();
            });
            incrementalWritebackWorkerStarted_ = true;
        } catch (...) {
            incrementalWritebackEnabled_ = false;
            incrementalWritebackLastErrno_ = EAGAIN;
        }
    }

    bool reserveSlots() noexcept {
        for (Slot& slot : slots_) {
            void* reservation = ::mmap(
                nullptr,
                reservationLength_,
                PROT_NONE,
                MAP_PRIVATE | MAP_ANONYMOUS,
                -1,
                0
            );
            if (reservation == MAP_FAILED) {
                setError("reserve DMC virtual slot");
                return false;
            }
            slot.reservationBase = reservation;
            slot.mappingBase = static_cast<std::uint8_t*>(reservation) + pageSize_;
            void* const staging = ::mmap(
                slot.mappingBase,
                mappingCapacity_,
                PROT_READ | PROT_WRITE,
                MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED,
                -1,
                0
            );
            if (staging == MAP_FAILED || staging != slot.mappingBase) {
                setError("map DMC staging slot");
                return false;
            }
            slot.payload = slot.mappingBase;
            slot.mappingLength = mappingCapacity_;

            // Materialize every anonymous page during session setup. The hot path then reuses a
            // fixed bounded working set and cannot inherit a first-write page-fault tail from the
            // filesystem or the anonymous zero page. Volatile stores make this guarantee explicit
            // to the compiler; guard pages remain PROT_NONE on both sides of the writable range.
            volatile std::uint8_t* const pageTouch = slot.mappingBase;
            for (std::size_t offset = 0u; offset < mappingCapacity_; offset += pageSize_) {
                pageTouch[offset] = 0u;
            }
            pageTouch[mappingCapacity_ - 1u] = 0u;
        }
        return true;
    }

    bool writeRecordAt(
        const std::uint8_t* payload,
        std::int64_t byteCount,
        std::int64_t fileOffset,
        std::int64_t reservationLimit,
        std::int64_t timestampNs,
        std::int32_t width,
        std::int32_t height,
        std::uint32_t crc32,
        std::uint8_t* ownedStorage,
        std::size_t ownedStorageBytes
    ) noexcept {
        std::int64_t payloadEnd = 0;
        if (payload == nullptr || byteCount <= 0 || fileOffset < frameHeaderBytes_ ||
            timestampNs <= 0 || width <= 0 || height <= 0 ||
            frameHeaderBytes_ != static_cast<std::int64_t>(kNpv10FrameHeaderBytes) ||
            !checkedAdd(fileOffset, byteCount, &payloadEnd) ||
            fileOffset > static_cast<std::int64_t>(std::numeric_limits<off_t>::max()) ||
            payloadEnd > reservationLimit) {
            setContractError("DMC positional record write parameters are invalid");
            return false;
        }

        std::array<std::uint8_t, kNpv10FrameHeaderBytes> header{};
        header[0] = 'F';
        header[1] = 'R';
        header[2] = 'M';
        header[3] = '0';
        putBe64(header.data() + 4u, static_cast<std::uint64_t>(timestampNs));
        putBe32(header.data() + 12u, static_cast<std::uint32_t>(width));
        putBe32(header.data() + 16u, static_cast<std::uint32_t>(height));
        putBe32(header.data() + 20u, static_cast<std::uint32_t>(byteCount));
        putBe32(header.data() + 24u, crc32);

        // The enclosing async-record timer includes this wait. Keeping the force outside an
        // active direct-I/O record avoids filesystem flush/write contention without hiding it.
        ScopedDmcTraceSection barrierTrace("NPV10 record I/O including durability wait");
        auto exclusiveIo = recordIoBarrier_.acquire();
        if (alignedRecordWriterEnabled_) {
            ScopedDmcTraceSection alignedTrace("NPV10 aligned direct record write");
            if (!alignedRecordWriter_->writeRecord(
                    header.data(), header.size(), payload, static_cast<std::size_t>(byteCount),
                    fileOffset - frameHeaderBytes_, reservationLimit, ownedStorage, ownedStorageBytes)) {
                errno = alignedRecordWriter_->failureErrno();
                setError("DMC aligned frame record");
                return false; // No fallback after admission: a failed master remains failed.
            }
            return true;
        }

        std::size_t headerWritten = 0u;
        std::size_t payloadWritten = 0u;
        const std::size_t requiredBytes = static_cast<std::size_t>(byteCount);
        const off_t recordOffset = static_cast<off_t>(
            fileOffset - static_cast<std::int64_t>(header.size())
        );
        while (headerWritten < header.size() || payloadWritten < requiredBytes) {
            std::array<iovec, 2u> vectors{};
            int vectorCount = 0;
            if (headerWritten < header.size()) {
                vectors[vectorCount].iov_base = header.data() + headerWritten;
                vectors[vectorCount].iov_len = header.size() - headerWritten;
                ++vectorCount;
            }
            if (payloadWritten < requiredBytes) {
                vectors[vectorCount].iov_base = const_cast<std::uint8_t*>(
                    payload + payloadWritten
                );
                vectors[vectorCount].iov_len = std::min<std::size_t>(
                    requiredBytes - payloadWritten,
                    kAsynchronousWriteChunkBytes
                );
                ++vectorCount;
            }
            const off_t writeOffset = static_cast<off_t>(
                recordOffset + static_cast<off_t>(headerWritten + payloadWritten)
            );
            const ssize_t result = ::pwritev(
                descriptor_,
                vectors.data(),
                vectorCount,
                writeOffset
            );
            if (result < 0) {
                if (errno == EINTR) continue;
                setError("pwritev DMC frame record");
                return false;
            }
            if (result == 0) {
                errno = EIO;
                setError("pwritev DMC frame record made no progress");
                return false;
            }
            std::size_t consumed = static_cast<std::size_t>(result);
            const std::size_t headerRemaining = header.size() - headerWritten;
            const std::size_t consumedHeader = std::min(consumed, headerRemaining);
            headerWritten += consumedHeader;
            consumed -= consumedHeader;
            payloadWritten += consumed;
        }
        return true;
    }

    /**
     * Starts bounded asynchronous kernel writeback before the periodic durability boundary.
     *
     * SYNC_FILE_RANGE_WRITE deliberately does not wait: it moves dirty payload/header pages toward
     * storage while capture continues. The existing FileChannel.force(false) checkpoints and final
     * force(true) remain the only durability acknowledgements, so this latency optimization cannot
     * weaken crash consistency. A filesystem that rejects range writeback disables only this hint;
     * the mandatory durability path remains active and records its own failure independently.
     *
     * The caller holds mutex_. It only coalesces a monotonic range and wakes the dedicated worker;
     * the syscall never runs on the ordered commit lane. Production commits are monotonic, but
     * taking the maximum keeps the range safe if a future caller completes disjoint slots
     * concurrently. Compressed output uses actual committed bytes with a 16 MiB wake threshold;
     * the legacy small uncompressed route retains 64 MiB. The eight-frame ceiling bounds small
     * packets' dirty-page residence. Every kernel request is independently capped at 16 MiB.
     */
    void noteCommittedRangeForIncrementalWriteback(std::int64_t recordEndOffset) noexcept {
        if (recordEndOffset <= incrementalWritebackPendingEndOffset_) return;
        incrementalWritebackPendingEndOffset_ = recordEndOffset;
        if (!incrementalWritebackEnabled_) return;
        ++incrementalWritebackPendingFrames_;

        const std::int64_t pendingBytes =
            incrementalWritebackPendingEndOffset_ - incrementalWritebackCoveredThroughOffset_;
        incrementalWritebackMaxPendingBytes_ = std::max(
            incrementalWritebackMaxPendingBytes_, pendingBytes
        );
        const auto targetBytes = chunkedLosslessCompression_
            ? neoproc::npv10::IncrementalWritebackPolicy::kLosslessTargetBytes
            : kIncrementalWritebackTargetBytes;
        if (pendingBytes < targetBytes &&
            incrementalWritebackPendingFrames_ < kIncrementalWritebackMaxFrames) {
            return;
        }
        if (pendingBytes <= 0) {
            incrementalWritebackPendingFrames_ = 0;
            return;
        }

        incrementalWritebackRequestedEndOffset_ = std::max(
            incrementalWritebackRequestedEndOffset_,
            incrementalWritebackPendingEndOffset_
        );
        incrementalWritebackPendingFrames_ = 0;
        incrementalWritebackWorkAvailable_.notify_one();
    }

    /**
     * Executes optional range-writeback hints away from packing and ordered persistence. Multiple
     * commit notifications collapse into one forward-only range while a syscall is active. Close
     * stops and joins this worker before closing the descriptor; the subsequent force(true) remains
     * the authoritative all-data-and-metadata durability boundary.
     */
    void incrementalWritebackWorkerLoop() noexcept {
        std::unique_lock<std::mutex> lock(mutex_);
        for (;;) {
            incrementalWritebackWorkAvailable_.wait(lock, [this] {
                return incrementalWritebackStopping_ ||
                    (incrementalWritebackEnabled_ &&
                        incrementalWritebackRequestedEndOffset_ >
                            incrementalWritebackCoveredThroughOffset_);
            });
            if (incrementalWritebackStopping_) return;

            const std::int64_t rangeStart = incrementalWritebackCoveredThroughOffset_;
            const std::int64_t rangeEnd =
                neoproc::npv10::IncrementalWritebackPolicy::boundedEnd(
                    rangeStart, incrementalWritebackRequestedEndOffset_
                );
            const std::int64_t rangeBytes = rangeEnd - rangeStart;
            if (rangeBytes <= 0) continue;
            incrementalWritebackMaxRequestBytes_ = std::max(
                incrementalWritebackMaxRequestBytes_, rangeBytes
            );

            lock.unlock();
            ScopedDmcTraceSection writebackTrace("NPV10 bounded incremental writeback");
            const auto started = std::chrono::steady_clock::now();
            int result = 0;
            int failureErrno = 0;
            do {
                result = ::sync_file_range(
                    descriptor_,
                    static_cast<off64_t>(rangeStart),
                    static_cast<off64_t>(rangeBytes),
                    SYNC_FILE_RANGE_WRITE
                );
            } while (result != 0 && errno == EINTR);
            if (result != 0) failureErrno = errno;
            const std::int64_t elapsedNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - started
            ).count();
            lock.lock();

            ++incrementalWritebackAttempts_;
            incrementalWritebackTotalNs_ += elapsedNs;
            if (elapsedNs > incrementalWritebackMaxNs_) incrementalWritebackMaxNs_ = elapsedNs;

            if (result == 0) {
                ++incrementalWritebackSuccesses_;
                incrementalWritebackBytes_ += rangeBytes;
                incrementalWritebackCoveredThroughOffset_ = std::max(
                    incrementalWritebackCoveredThroughOffset_,
                    rangeEnd
                );
                incrementalWritebackLastErrno_ = 0;
                continue;
            }

            ++incrementalWritebackFailures_;
            incrementalWritebackLastErrno_ = failureErrno;
            incrementalWritebackEnabled_ = false;
        }
    }

    bool retireSlot(Slot& slot, std::int64_t frameIndex) noexcept {
        if (slot.frameIndex != frameIndex || slot.state == SlotState::Empty) {
            setContractError("DMC retirement did not match the live slot identity");
            return false;
        }
        slot.frameIndex = -1;
        slot.payloadFileOffset = -1;
        slot.lastRetiredFrameIndex = frameIndex;
        slot.crc32 = 0u;
        slot.storedBytes = 0;
        slot.compressedChunks = 0;
        slot.rawChunks = 0;
        slot.timestampNs = -1;
        slot.width = 0;
        slot.height = 0;
        slot.reservationLimit = 0;
        slot.state = SlotState::Empty;
        return true;
    }

    int descriptor_ = -1;
    const std::int64_t dataStartOffset_;
    const std::int64_t frameHeaderBytes_;
    const std::int64_t inputPayloadBytes_;
    const std::int64_t payloadCapacityBytes_;
    const std::int64_t sampleCount_;
    const bool chunkedLosslessCompression_;
    const std::int64_t maximumFrameRecordBytes_;
    const std::size_t pageSize_;
    const std::size_t mappingCapacity_;
    const std::size_t reservationLength_;
    std::int64_t reservedBytes_;
    std::int64_t physicalAllocationEndOffset_;
    std::int64_t nextRecordStartOffset_;
    std::int64_t nextCommitFrameIndex_ = 0;
    std::int64_t incrementalWritebackPendingEndOffset_;
    std::int64_t incrementalWritebackCoveredThroughOffset_;
    std::int64_t incrementalWritebackRequestedEndOffset_ = 0;
    std::int64_t incrementalWritebackAttempts_ = 0;
    std::int64_t incrementalWritebackSuccesses_ = 0;
    std::int64_t incrementalWritebackFailures_ = 0;
    std::int64_t incrementalWritebackBytes_ = 0;
    std::int64_t incrementalWritebackTotalNs_ = 0;
    std::int64_t incrementalWritebackMaxNs_ = 0;
    std::int64_t incrementalWritebackLastErrno_ = 0;
    std::int64_t incrementalWritebackMaxRequestBytes_ = 0;
    std::int64_t incrementalWritebackMaxPendingBytes_ = 0;
    std::int32_t incrementalWritebackPendingFrames_ = 0;
    bool incrementalWritebackEnabled_ = true;
    bool incrementalWritebackSuppressedForRealtime_ = false;
    bool incrementalWritebackWorkerStarted_ = false;
    bool incrementalWritebackStopping_ = false;
    std::vector<Slot> slots_;
    std::vector<std::int64_t> recordWriteQueue_;
    std::size_t recordWriteQueueHead_ = 0u;
    std::size_t recordWriteQueueTail_ = 0u;
    std::size_t recordWriteQueueCount_ = 0u;
    std::int64_t recordWriteCompletedFrames_ = 0;
    std::int64_t recordWriteCompletedBytes_ = 0;
    std::int64_t recordWriteFailures_ = 0;
    std::int64_t recordWriteTotalNs_ = 0;
    std::int64_t recordWriteMaxNs_ = 0;
    std::int64_t recordWriteMaxQueueDepth_ = 0;
    neoproc::npv10::LosslessStoragePressurePolicy losslessStoragePressure_;
    std::size_t activeFileExtentProbes_ = 0u;
    std::size_t activeDurabilityForces_ = 0u;
    std::size_t activeReservationAllocations_ = 0u;
    neoproc::npv10::RecordIoBarrier recordIoBarrier_;
    std::string recordWriterFailure_;
    std::unique_ptr<neoproc::npv10::RealtimeAlignedRecordWriter> alignedRecordWriter_;
    bool alignedRecordWriterAttempted_ = false;
    bool alignedRecordWriterEnabled_ = false;
    bool recordWriterWorkerStarted_ = false;
    bool recordWriterStopping_ = false;
    std::thread recordWriterThread_;
    std::thread incrementalWritebackThread_;
    std::mutex mutex_;
    std::condition_variable processorStateChanged_;
    std::condition_variable recordWriteWorkAvailable_;
    std::condition_variable incrementalWritebackWorkAvailable_;
    std::size_t activeProcessors_ = 0u;
    std::size_t maximumProcessingOverlap_ = 1u;
    std::size_t activeCommits_ = 0u;
    bool shutdownSuccessful_ = true;
    bool closing_ = false;
    bool closed_ = false;

    static constexpr std::int64_t kIncrementalWritebackTargetBytes =
        neoproc::npv10::IncrementalWritebackPolicy::kLegacyTargetBytes;
    static constexpr std::int32_t kIncrementalWritebackMaxFrames =
        neoproc::npv10::IncrementalWritebackPolicy::kMaximumPendingFrames;
    static constexpr std::size_t kAsynchronousWriteChunkBytes = 2u * 1024u * 1024u;
    static constexpr auto kSlotReuseWaitDuration = std::chrono::seconds(2);
    static constexpr std::size_t kWritebackTelemetryValues = 55u;
};

std::mutex gRegistryMutex;
std::unordered_map<std::int64_t, std::shared_ptr<DirectMappedCommitEngine>> gRegistry;
std::atomic<std::int64_t> gNextHandle{1};

std::shared_ptr<DirectMappedCommitEngine> resolveEngine(std::int64_t handle) noexcept {
    if (handle <= 0) {
        setContractError("DMC handle is invalid");
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    const auto found = gRegistry.find(handle);
    if (found == gRegistry.end()) {
        setContractError("DMC handle is stale");
        return nullptr;
    }
    return found->second;
}

std::int64_t registerEngine(const std::shared_ptr<DirectMappedCommitEngine>& engine) {
    for (;;) {
        const std::int64_t candidate = gNextHandle.fetch_add(1, std::memory_order_relaxed);
        if (candidate <= 0) continue;
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        if (gRegistry.emplace(candidate, engine).second) return candidate;
    }
}

bool closeRegisteredEngine(std::int64_t handle) noexcept {
    const std::shared_ptr<DirectMappedCommitEngine> engine = resolveEngine(handle);
    if (engine == nullptr) return false;
    const bool successful = engine->shutdown();
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        const auto found = gRegistry.find(handle);
        if (found != gRegistry.end() && found->second == engine) gRegistry.erase(found);
    }
    return successful;
}

}  // namespace

namespace neoproc::npv10 {

bool acquireDirectMappedPayload(
    std::int64_t handle,
    std::int64_t frameIndex,
    std::int64_t requiredBytes,
    DirectMappedPayloadView* output,
    bool requestFusedLosslessPreparation
) noexcept {
    const std::shared_ptr<DirectMappedCommitEngine> engine = resolveEngine(handle);
    return engine != nullptr && engine->acquirePayload(frameIndex, requiredBytes, output,
        requestFusedLosslessPreparation);
}

bool finishDirectMappedPayloadProcessing(
    std::int64_t handle,
    std::int64_t frameIndex,
    bool successful,
    std::uint32_t packedCrc32,
    DirectMappedPreparedPayload* output
) noexcept {
    const std::shared_ptr<DirectMappedCommitEngine> engine = resolveEngine(handle);
    return engine != nullptr && engine->finishProcessing(
        frameIndex,
        successful,
        packedCrc32,
        output
    );
}

bool finishDirectMappedEncodedPayloadProcessing(
    std::int64_t handle, std::int64_t frameIndex, bool sourceReleasedSuccessfully,
    const lossless::EncodeResult& encoded, std::int64_t preparationElapsedNs,
    DirectMappedPreparedPayload* output
) noexcept {
    const std::shared_ptr<DirectMappedCommitEngine> engine = resolveEngine(handle);
    return engine != nullptr && engine->finishProcessing(frameIndex,
        sourceReleasedSuccessfully && encoded.successful, encoded.packedCrc32,
        output, &encoded, preparationElapsedNs);
}

}  // namespace neoproc::npv10

extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10DirectMappedCommitNative_nativeCreate(
    JNIEnv* env,
    jclass,
    jstring path,
    jlong dataStartOffset,
    jint frameHeaderBytes,
    jint inputPayloadBytes,
    jint payloadCapacityBytes,
    jint sampleCount,
    jboolean chunkedLosslessCompression,
    jint slotCount,
    jlong reservedBytes
) {
    clearError();
    if (path == nullptr) {
        setContractError("DMC path is null");
        return 0;
    }
    const char* pathChars = env->GetStringUTFChars(path, nullptr);
    if (pathChars == nullptr) {
        setContractError("DMC path UTF-8 conversion failed");
        return 0;
    }
    std::shared_ptr<DirectMappedCommitEngine> engine = DirectMappedCommitEngine::create(
        pathChars,
        dataStartOffset,
        frameHeaderBytes,
        inputPayloadBytes,
        payloadCapacityBytes,
        sampleCount,
        chunkedLosslessCompression == JNI_TRUE,
        slotCount,
        reservedBytes
    );
    env->ReleaseStringUTFChars(path, pathChars);
    if (engine == nullptr) return 0;
    try {
        return static_cast<jlong>(registerEngine(engine));
    } catch (...) {
        (void)engine->shutdown();
        setContractError("DMC registry allocation failed");
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10DirectMappedCommitNative_nativeBeginFrame(
    JNIEnv*,
    jclass,
    jlong handle,
    jlong frameIndex,
    jlong reservedBytes
) {
    const std::shared_ptr<DirectMappedCommitEngine> engine = resolveEngine(handle);
    return engine != nullptr && engine->beginFrame(frameIndex, reservedBytes) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10DirectMappedCommitNative_nativeCommitFrame(
    JNIEnv*,
    jclass,
    jlong handle,
    jlong frameIndex,
    jint encodedBytes,
    jlong payloadFileOffset,
    jlong reservedBytes,
    jlong crc32,
    jlong timestampNs,
    jint width,
    jint height
) {
    if (encodedBytes <= 0 || payloadFileOffset < 0 || reservedBytes < 0 || crc32 < 0 ||
        crc32 > std::numeric_limits<std::uint32_t>::max() || timestampNs <= 0 ||
        width <= 0 || height <= 0) {
        setContractError("DMC commit JNI parameters are outside their valid domain");
        return JNI_FALSE;
    }
    const std::shared_ptr<DirectMappedCommitEngine> engine = resolveEngine(handle);
    return engine != nullptr && engine->commitFrame(
        frameIndex,
        encodedBytes,
        payloadFileOffset,
        reservedBytes,
        static_cast<std::uint32_t>(crc32),
        timestampNs,
        width,
        height
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10DirectMappedCommitNative_nativeAbortFrame(
    JNIEnv*,
    jclass,
    jlong handle,
    jlong frameIndex
) {
    const std::shared_ptr<DirectMappedCommitEngine> engine = resolveEngine(handle);
    return engine != nullptr && engine->abortFrame(frameIndex) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10DirectMappedCommitNative_nativeAwaitCommittedFrames(
    JNIEnv*,
    jclass,
    jlong handle,
    jlong requiredFrames
) {
    const std::shared_ptr<DirectMappedCommitEngine> engine = resolveEngine(handle);
    return engine != nullptr && engine->awaitCommittedFrames(requiredFrames)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10DirectMappedCommitNative_nativeAllocatePhysicalRange(
    JNIEnv*,
    jclass,
    jlong handle,
    jlong offset,
    jlong byteCount
) {
    const std::shared_ptr<DirectMappedCommitEngine> engine = resolveEngine(handle);
    return engine != nullptr && engine->allocatePhysicalRange(offset, byteCount)
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10DirectMappedCommitNative_nativeForceCommittedFrames(
    JNIEnv*,
    jclass,
    jlong handle,
    jlong requiredFrames
) {
    const std::shared_ptr<DirectMappedCommitEngine> engine = resolveEngine(handle);
    return engine != nullptr && engine->forceCommittedFrames(requiredFrames) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10DirectMappedCommitNative_nativeIncrementalWritebackTelemetry(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    constexpr jsize kValueCount = 55;
    const std::shared_ptr<DirectMappedCommitEngine> engine = resolveEngine(handle);
    if (engine == nullptr) return nullptr;
    std::int64_t nativeValues[kValueCount] = {};
    engine->snapshotIncrementalWriteback(
        nativeValues,
        static_cast<std::size_t>(kValueCount)
    );
    jlong values[kValueCount] = {};
    for (jsize index = 0; index < kValueCount; ++index) {
        values[index] = static_cast<jlong>(nativeValues[index]);
    }
    jlongArray output = env->NewLongArray(kValueCount);
    if (output == nullptr) return nullptr;
    env->SetLongArrayRegion(output, 0, kValueCount, values);
    return env->ExceptionCheck() ? nullptr : output;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10DirectMappedCommitNative_nativeClose(
    JNIEnv*,
    jclass,
    jlong handle
) {
    return closeRegisteredEngine(handle) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10DirectMappedCommitNative_nativeLastError(
    JNIEnv* env,
    jclass
) {
    return env->NewStringUTF(gLastDmcError.c_str());
}
