#pragma once

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <sys/stat.h>
#include <sys/resource.h>
#include <unistd.h>
#include "npv10_aligned_write_lane.h"
#include "npv10_kernel_aio.h"

namespace neoproc::npv10 {

// This seam is compile-time only: production always uses the real syscalls. Native failure tests
// instantiate the same algorithm with fault-injecting syscalls, without adding runtime test flags.
struct AlignedRecordPosixIo {
    static long pageSize() noexcept { return ::sysconf(_SC_PAGESIZE); }
    static int openDirect(const char* path) noexcept {
        return ::open64(path, O_RDWR | O_CLOEXEC | O_NOFOLLOW | O_DIRECT);
    }
    static int stat(int fd, struct stat64* output) noexcept { return ::fstat64(fd, output); }
    static ssize_t read(int fd, void* buffer, std::size_t bytes, std::int64_t offset) noexcept {
        return ::pread64(fd, buffer, bytes, static_cast<off64_t>(offset));
    }
    static ssize_t write(int fd, const void* buffer, std::size_t bytes,
                         std::int64_t offset) noexcept {
        return ::pwrite64(fd, buffer, bytes, static_cast<off64_t>(offset));
    }
    static int sync(int fd) noexcept { return ::fdatasync(fd); }
    static int close(int fd) noexcept { return ::close(fd); }
};

/**
 * One ordered, bounded, byte-exact record writer for an exclusively owned, preallocated master.
 *
 * A separate O_DIRECT file description avoids dirty-page throttling without changing the original
 * descriptor flags, container, LZ4 packets, checksums or recovery barriers. Variable-length records
 * share an aligned staging range. Padding exists ONLY past the current logical tail, inside the
 * reservation; the next record replaces it and the existing final truncate removes it.
 *
 * Successful writeRecord means the entire record has completed its writes, not that it is durable.
 * The caller must still drain before each existing fdatasync/fsync/force and before truncation.
 * Only unsupported capability at initial admission permits the existing buffered backend. An I/O
 * error, identity mismatch, partial failed write or later capability loss is fatal, never a retry
 * through another backend. No per-record allocation and no unpersisted cross-record tail.
 */
template <class Io = AlignedRecordPosixIo,
          std::size_t StagingCapacity = 2u * 1024u * 1024u,
          bool OverlapChunkPreparation = false,
          std::size_t ConcurrentChunkWrites = 1u,
          bool EnableKernelAio = false,
          class AioIo = KernelAioPosix,
          std::size_t AioGroupCapacity = 8u * 1024u * 1024u,
          bool StreamRecordChunks = false,
          unsigned AioRequestDepth = 4u,
          bool CoalesceGroupCompletions = false>
class AlignedRecordWriter final {
    using KernelAio = BoundedKernelAio<AioIo, AioGroupCapacity, AioRequestDepth,
        CoalesceGroupCompletions>;
public:
    enum class Admission { Active, Unsupported, Fatal };
    // A compile-time bound lets physical-device tests compare submission sizes through this exact
    // writer, including all identity, reservation, short-write and durability checks. It never
    // changes packet bytes or selects another backend after recording admission.
    static constexpr std::size_t kCapacity = StagingCapacity;
    static constexpr std::size_t kResidentStagingBytes =
        kCapacity * (OverlapChunkPreparation ? 2u : 1u);
    static_assert(kCapacity >= 32768u && kCapacity <= 32u * 1024u * 1024u &&
        (kCapacity & (kCapacity - 1u)) == 0u, "DMC staging capacity must be bounded and aligned");
    // The producer fills the other pre-faulted buffer while ONE I/O worker owns the AIO
    // context. Four kernel requests still belong to one immutable staging buffer. Multiple
    // I/O workers must never concurrently submit into the same completion/generation state.
    static_assert(!EnableKernelAio || !OverlapChunkPreparation || ConcurrentChunkWrites == 1u,
        "Pipelined kernel AIO requires exactly one context-owning I/O worker");
    static_assert(!StreamRecordChunks || !OverlapChunkPreparation,
        "Use either streamed-slot filling or double-buffer preparation, never both");
    static_assert(!StreamRecordChunks || (EnableKernelAio && kCapacity == AioGroupCapacity),
        "Record streaming requires one fixed four-slot kernel-AIO staging range");

    AlignedRecordWriter(int originalFd, const char* path, std::int64_t startOffset,
                        std::int64_t reservedBytes) noexcept {
        if (originalFd < 0 || path == nullptr || path[0] == '\0' || startOffset < 0 ||
            reservedBytes < startOffset) {
            reject(EINVAL, Admission::Fatal);
            return;
        }
        const long page = Io::pageSize();
        if (page <= 0) {
            reject(EINVAL, Admission::Fatal);
            return;
        }
        alignment_ = std::max<std::size_t>(4096u, static_cast<std::size_t>(page));
        if ((alignment_ & (alignment_ - 1u)) != 0u || alignment_ > kCapacity / 2u ||
            kCapacity % alignment_ != 0u) {
            reject(EOPNOTSUPP, Admission::Unsupported);
            return;
        }
        // Prove capability in an already reserved sector after the logical prefix, not in a new
        // temporary file on a possibly different filesystem. Write exactly the bytes read there.
        std::int64_t probeOffset = 0;
        if (!alignedEnd(startOffset, &probeOffset) || probeOffset > reservedBytes ||
            alignment_ > static_cast<std::uint64_t>(reservedBytes - probeOffset)) {
            reject(ENOSPC, Admission::Fatal);
            return;
        }
        fd_ = Io::openDirect(path);
        if (fd_ < 0) {
            const int failure = errno;
            reject(failure, unsupported(failure) ? Admission::Unsupported : Admission::Fatal);
            return;
        }
        struct stat64 original{};
        struct stat64 direct{};
        if (Io::stat(originalFd, &original) != 0 || Io::stat(fd_, &direct) != 0) {
            reject(errno, Admission::Fatal);
            return;
        }
        if (!S_ISREG(original.st_mode) || !S_ISREG(direct.st_mode) ||
            original.st_dev != direct.st_dev || original.st_ino != direct.st_ino) {
            reject(ESTALE, Admission::Fatal);
            return;
        }
        if (direct.st_size < reservedBytes || original.st_size < reservedBytes) {
            reject(ENOSPC, Admission::Fatal);
            return;
        }
        device_ = direct.st_dev;
        inode_ = direct.st_ino;
        void* allocated = nullptr;
        const int allocationError = ::posix_memalign(&allocated, alignment_, kCapacity);
        if (allocationError != 0) {
            reject(allocationError, Admission::Fatal);
            return;
        }
        buffer_ = static_cast<std::uint8_t*>(allocated);
        initialBuffer_ = buffer_;
        // Pre-fault once before capture and retire dirty prefix data before mixed-I/O admission.
        std::memset(buffer_, 0, kCapacity);
        int synced = 0;
        do { synced = Io::sync(originalFd); } while (synced != 0 && errno == EINTR);
        if (synced != 0 || !readFully(originalFd, buffer_, alignment_, probeOffset)) {
            reject(errno, Admission::Fatal);
            return;
        }
        ssize_t probed = 0;
        do { probed = Io::write(fd_, buffer_, alignment_, probeOffset); }
        while (probed < 0 && errno == EINTR);
        if (probed != static_cast<ssize_t>(alignment_)) {
            const int failure = probed < 0 ? errno : EIO;
            reject(failure, probed < 0 && unsupported(failure)
                ? Admission::Unsupported : Admission::Fatal);
            return;
        }
        if (!readFully(fd_, buffer_ + alignment_, alignment_, probeOffset) ||
            std::memcmp(buffer_, buffer_ + alignment_, alignment_) != 0) {
            reject(EIO, Admission::Fatal);
            return;
        }
        baseOffset_ = startOffset - startOffset % static_cast<std::int64_t>(alignment_);
        used_ = static_cast<std::size_t>(startOffset - baseOffset_);
        if (used_ != 0u && !readFully(originalFd, buffer_, used_, baseOffset_)) {
            reject(errno, Admission::Fatal);
            return;
        }
        cursor_ = startOffset;
        if constexpr (OverlapChunkPreparation) {
            void* second = nullptr;
            const int secondError = ::posix_memalign(&second, alignment_, kCapacity);
            if (secondError != 0) {
                reject(secondError, Admission::Fatal);
                return;
            }
            secondBuffer_ = static_cast<std::uint8_t*>(second);
            std::memset(secondBuffer_, 0, kCapacity);
            if (!writeLane_.start(initialBuffer_, secondBuffer_, kCapacity, this,
                    [](void* owner, const std::uint8_t* bytes, std::size_t count,
                       std::int64_t offset) noexcept {
                        return static_cast<AlignedRecordWriter*>(owner)->writeAlignedBytes(
                            bytes, count, offset);
                    }, []() noexcept {
                        if constexpr (EnableKernelAio) {
                            // Match the existing native record writer's best-effort nice value.
                            // Android remains authoritative; no privileged scheduler override.
                            (void)::setpriority(PRIO_PROCESS, 0, -4);
                        }
                    })) {
                reject(writeLane_.failureErrno(), Admission::Fatal);
                return;
            }
        }
        if constexpr (EnableKernelAio) {
            const auto aioAdmission = alignment_ <= kCapacity / 3u
                ? kernelAio_.open() : KernelAio::Admission::Unsupported;
            if (aioAdmission == KernelAio::Admission::Fatal) {
                reject(kernelAio_.admissionErrno(), Admission::Fatal);
                return;
            }
            kernelAioEnabled_ = aioAdmission == KernelAio::Admission::Active;
            if (kernelAioEnabled_) {
                // Prove the actual app/file backend on an already-reserved unchanged sector.
                // No fallback is permitted after any AIO write has been admitted.
                if (!readFully(fd_, buffer_ + alignment_, alignment_, probeOffset) ||
                    !kernelAio_.writeAll(fd_, buffer_ + alignment_, alignment_, probeOffset, alignment_) ||
                    !readFully(fd_, buffer_ + 2u * alignment_, alignment_, probeOffset) ||
                    std::memcmp(buffer_ + alignment_, buffer_ + 2u * alignment_, alignment_) != 0) {
                    reject(kernelAio_.failureErrno() != 0 ? kernelAio_.failureErrno() : EIO, Admission::Fatal);
                    return;
                }
            }
        }
        probeVerified_ = true;
        admission_ = Admission::Active;
    }

    ~AlignedRecordWriter() {
        (void)close();
        if constexpr (EnableKernelAio) {
            // Never release bytes while an exceptional, unretired kernel request may read them.
            if (!kernelAio_.buffersRetired()) return;
        }
        std::free(initialBuffer_);
        std::free(secondBuffer_);
    }
    AlignedRecordWriter(const AlignedRecordWriter&) = delete;
    AlignedRecordWriter& operator=(const AlignedRecordWriter&) = delete;

    Admission admission() const noexcept { return admission_; }
    int admissionErrno() const noexcept { return admissionErrno_; }
    int failureErrno() const noexcept { return failureErrno_.load(std::memory_order_relaxed); }
    bool probeVerified() const noexcept { return probeVerified_; }
    std::size_t alignment() const noexcept { return alignment_; }
    std::uint64_t writeCalls() const noexcept { return writeCalls_.load(std::memory_order_relaxed); }
    std::uint64_t physicalBytes() const noexcept { return physicalBytes_.load(std::memory_order_relaxed); }
    std::uint64_t paddingBytes() const noexcept { return paddingBytes_.load(std::memory_order_relaxed); }
    std::uint64_t failures() const noexcept { return failures_.load(std::memory_order_relaxed); }
    bool kernelAioEnabled() const noexcept { return kernelAioEnabled_; }
    int kernelAioAdmissionErrno() const noexcept { return kernelAio_.admissionErrno(); }
    std::uint64_t kernelAioSubmissions() const noexcept { return kernelAio_.submissions(); }
    std::uint64_t kernelAioCompletions() const noexcept { return kernelAio_.completions(); }
    std::uint64_t kernelAioPartialSubmissions() const noexcept { return kernelAio_.partialSubmissions(); }
    std::uint64_t kernelAioBackpressureEvents() const noexcept {
        return kernelAio_.submissionBackpressureEvents();
    }
    bool sourceBuffersRetired() const noexcept { return kernelAio_.buffersRetired(); }
    std::uint64_t ownedRecordWrites() const noexcept {
        return ownedRecordWrites_.load(std::memory_order_relaxed);
    }
    std::uint64_t ownedPayloadBytes() const noexcept {
        return ownedPayloadBytes_.load(std::memory_order_relaxed);
    }
    std::uint64_t immutableStreamRecordWrites() const noexcept {
        return immutableStreamRecordWrites_.load(std::memory_order_relaxed);
    }

    bool alignedEnd(std::int64_t logicalEnd, std::int64_t* output) const noexcept {
        if (output == nullptr || logicalEnd < 0 || alignment_ == 0u) return false;
        const auto remainder = logicalEnd % static_cast<std::int64_t>(alignment_);
        const auto extra = remainder == 0 ? 0 : static_cast<std::int64_t>(alignment_) - remainder;
        if (logicalEnd > std::numeric_limits<std::int64_t>::max() - extra) return false;
        *output = logicalEnd + extra;
        return true;
    }

    bool writeRecord(const std::uint8_t* header, std::size_t headerBytes,
                     const std::uint8_t* payload, std::size_t payloadBytes,
                     std::int64_t recordOffset, std::int64_t reservationLimit) noexcept {
        if (admission_ != Admission::Active || fd_ < 0 || failureErrno() != 0) return false;
        // A failed submission can coexist with another in-flight positional write. Even a false
        // return must retire both readers before the caller can truncate/audit/release a record.
        RecordBoundaryCompletion completion(this);
        if (header == nullptr || payload == nullptr || headerBytes == 0u || payloadBytes == 0u ||
            recordOffset != cursor_ || cursor_ < 0 ||
            headerBytes > static_cast<std::uint64_t>(INT64_MAX - cursor_) ||
            payloadBytes > static_cast<std::uint64_t>(INT64_MAX - cursor_) - headerBytes) {
            return fail(EINVAL);
        }
        const auto end = cursor_ + static_cast<std::int64_t>(headerBytes) +
            static_cast<std::int64_t>(payloadBytes);
        std::int64_t physicalEnd = 0;
        if (!alignedEnd(end, &physicalEnd) || physicalEnd > reservationLimit) return fail(ENOSPC);
        struct stat64 file{};
        if (Io::stat(fd_, &file) != 0) return fail(errno);
        if (!S_ISREG(file.st_mode) || file.st_dev != device_ || file.st_ino != inode_) {
            return fail(ESTALE);
        }
        if (file.st_size < physicalEnd) return fail(ENOSPC);
        if constexpr (StreamRecordChunks) {
            if (kernelAioEnabled_ && payloadBytes >= alignment_) {
                return streamRecord(header, headerBytes, payload, payloadBytes, end, physicalEnd);
            }
        }
        if (!append(header, headerBytes) || !append(payload, payloadBytes)) return false;
        if (used_ != 0u) {
            const auto rounded = ((used_ + alignment_ - 1u) / alignment_) * alignment_;
            std::memset(buffer_ + used_, 0, rounded - used_);
            const auto* submitted = buffer_;
            if (!writeAligned(rounded)) return false;
            paddingBytes_.fetch_add(rounded - used_, std::memory_order_relaxed);
            const auto fragment = used_ % alignment_;
            const auto complete = used_ - fragment;
            if constexpr (OverlapChunkPreparation) {
                buffer_ = writeLane_.acquire();
                if (buffer_ == nullptr) return fail(writeLane_.failureErrno());
            }
            // Only read a submitted tail while its write reads it too. Its buffer is never
            // modified/reused until the worker retires it. The next record starts after drain.
            if (fragment != 0u) std::memmove(buffer_, submitted + complete, fragment);
            baseOffset_ += static_cast<std::int64_t>(complete);
            used_ = fragment;
        }
        if constexpr (OverlapChunkPreparation) {
            if (!writeLane_.drain()) return fail(writeLane_.failureErrno());
        }
        if (cursor_ != end || baseOffset_ + static_cast<std::int64_t>(used_) != end) {
            return fail(EIO);
        }
        return true;
    }

    /**
     * The recorder may prepare a packet at its final file-alignment residue inside its existing
     * guarded slot. Reuse that storage for the complete aligned record instead of copying every
     * payload byte through the staging buffer. Only the previous sector prefix, frame header and
     * out-of-file tail padding are written here; the encoded payload is never changed.
     *
     * Ownership covers ALL I/O, including failure retirement. A caller MUST keep ownedStorage
     * alive until sourceBuffersRetired(), even after a failed close/io_destroy. A speculative
     * preparation whose final offset was not yet known uses the existing staging route. This is
     * a layout choice before any write, not an I/O-error fallback or a container format change.
     */
    bool writeOwnedRecord(const std::uint8_t* header, std::size_t headerBytes,
                          const std::uint8_t* payload, std::size_t payloadBytes,
                          std::int64_t recordOffset, std::int64_t reservationLimit,
                          std::uint8_t* ownedStorage, std::size_t ownedStorageBytes,
                          bool streamImmutableRecord = false) noexcept {
        if (admission_ != Admission::Active || fd_ < 0 || failureErrno() != 0) return false;
        const auto allocation = reinterpret_cast<std::uintptr_t>(ownedStorage);
        const auto data = reinterpret_cast<std::uintptr_t>(payload);
        if (header == nullptr || payload == nullptr || ownedStorage == nullptr ||
            headerBytes == 0u || payloadBytes == 0u || recordOffset != cursor_ || cursor_ < 0 ||
            data < allocation || data - allocation > ownedStorageBytes ||
            payloadBytes > ownedStorageBytes - (data - allocation) ||
            headerBytes > static_cast<std::uint64_t>(INT64_MAX - cursor_) ||
            payloadBytes > static_cast<std::uint64_t>(INT64_MAX - cursor_) - headerBytes) {
            return fail(EINVAL);
        }
        const auto end = cursor_ + static_cast<std::int64_t>(headerBytes) +
            static_cast<std::int64_t>(payloadBytes);
        std::int64_t physicalEnd = 0;
        if (!alignedEnd(end, &physicalEnd) || physicalEnd > reservationLimit) return fail(ENOSPC);
        const auto head = headerBytes + used_;
        const auto tail = static_cast<std::size_t>(physicalEnd - end);
        // The original API also supports arbitrary larger headers. Keep those on its established
        // staging route, and never subtract a speculative prefix from an unproven pointer.
        if constexpr (OverlapChunkPreparation || StreamRecordChunks) {
            return writeRecord(header, headerBytes, payload, payloadBytes, recordOffset, reservationLimit);
        }
        if (headerBytes > alignment_ || head < headerBytes || data - allocation < head ||
            (data - head) % alignment_ != 0u ||
            tail > ownedStorageBytes - (data - allocation) - payloadBytes ||
            static_cast<std::uint64_t>(physicalEnd - baseOffset_) > SIZE_MAX) {
            return writeRecord(header, headerBytes, payload, payloadBytes, recordOffset, reservationLimit);
        }
        struct stat64 file{};
        if (Io::stat(fd_, &file) != 0) return fail(errno);
        if (!S_ISREG(file.st_mode) || file.st_dev != device_ || file.st_ino != inode_) return fail(ESTALE);
        if (file.st_size < physicalEnd) return fail(ENOSPC);
        auto* const record = ownedStorage + (data - allocation - head);
        const auto physical = static_cast<std::size_t>(physicalEnd - baseOffset_);
        if (used_ != 0u) std::memcpy(record, buffer_, used_);
        std::memcpy(record + used_, header, headerBytes);
        if (tail != 0u) std::memset(record + head + payloadBytes, 0, tail);
        // writeAlignedBytes still checks every accepted kernel completion before returning.
        // The same bounded four-request/8-MiB windows and external durability barrier are used.
        if (!writeAlignedBytes(record, physical, baseOffset_, streamImmutableRecord)) return false;
        const auto fragment = static_cast<std::size_t>(end % static_cast<std::int64_t>(alignment_));
        if (fragment != 0u) std::memcpy(buffer_, record + physical - alignment_, fragment);
        cursor_ = end;
        baseOffset_ = end - static_cast<std::int64_t>(fragment);
        used_ = fragment;
        paddingBytes_.fetch_add(tail, std::memory_order_relaxed);
        ownedPayloadBytes_.fetch_add(payloadBytes, std::memory_order_relaxed);
        ownedRecordWrites_.fetch_add(1u, std::memory_order_relaxed);
        if (streamImmutableRecord && kernelAioEnabled_)
            immutableStreamRecordWrites_.fetch_add(1u, std::memory_order_relaxed);
        return true;
    }

    // The engine joins its writer first. Never retry close(EINTR): the descriptor may be reused.
    bool close() noexcept {
        bool drained = true;
        if constexpr (OverlapChunkPreparation) {
            // In the pipelined route the worker owns io_submit/io_getevents. Join it before
            // destroying its context, closing the descriptor, or releasing either buffer.
            drained = writeLane_.stop();
            if (!drained) (void)fail(writeLane_.failureErrno());
        }
        if constexpr (EnableKernelAio) {
            if (!kernelAio_.close()) return fail(kernelAio_.failureErrno());
        }
        const int closingFd = fd_;
        fd_ = -1;
        if (closingFd >= 0 && Io::close(closingFd) != 0) return fail(errno);
        return drained;
    }

private:
    bool streamRecord(const std::uint8_t* header, std::size_t headerBytes,
                      const std::uint8_t* payload, std::size_t payloadBytes,
                      std::int64_t end, std::int64_t physicalEnd) noexcept {
        // The previous tail already occupies buffer_[0..used_). The first fill preserves it in
        // place before that slot is submitted. All subsequent chunks read only immutable caller
        // header/payload; an in-flight source slot is never overwritten by the filler.
        const auto prefix = used_;
        if (headerBytes > std::numeric_limits<std::size_t>::max() - prefix ||
            payloadBytes > std::numeric_limits<std::size_t>::max() - prefix - headerBytes ||
            static_cast<std::uint64_t>(physicalEnd - baseOffset_) >
                std::numeric_limits<std::size_t>::max()) return fail(EOVERFLOW);
        const auto headerEnd = prefix + headerBytes;
        const auto payloadEnd = headerEnd + payloadBytes;
        const auto physical = static_cast<std::size_t>(physicalEnd - baseOffset_);
        const auto before = kernelAio_.submissions();
        const bool written = kernelAio_.writeStream(fd_, buffer_, kCapacity, physical, baseOffset_,
            alignment_, [&](std::uint8_t* destination, std::size_t position, std::size_t count) noexcept {
                if (position == 0 && prefix != 0) {
                    if (destination != buffer_ || count < prefix) return false;
                    destination += prefix;
                    position += prefix;
                    count -= prefix;
                }
                while (count != 0) {
                    if (position < prefix) return false;
                    if (position < headerEnd) {
                        const auto take = std::min(count, headerEnd - position);
                        std::memcpy(destination, header + position - prefix, take);
                        destination += take; position += take; count -= take;
                    } else if (position < payloadEnd) {
                        const auto take = std::min(count, payloadEnd - position);
                        std::memcpy(destination, payload + position - headerEnd, take);
                        destination += take; position += take; count -= take;
                    } else {
                        std::memset(destination, 0, count);
                        count = 0;
                    }
                }
                return true;
            });
        writeCalls_.fetch_add(kernelAio_.submissions() - before, std::memory_order_relaxed);
        if (!written) return fail(kernelAio_.failureErrno());
        physicalBytes_.fetch_add(physical, std::memory_order_relaxed);
        paddingBytes_.fetch_add(static_cast<std::uint64_t>(physicalEnd - end), std::memory_order_relaxed);
        const auto fragment = static_cast<std::size_t>(end % static_cast<std::int64_t>(alignment_));
        // Large records guarantee that the complete final sector fragment belongs to payload,
        // even when the original header crossed a page. Small records retain the existing route.
        if (fragment != 0) std::memcpy(buffer_, payload + payloadBytes - fragment, fragment);
        cursor_ = end;
        baseOffset_ = end - static_cast<std::int64_t>(fragment);
        used_ = fragment;
        return true;
    }

    class RecordBoundaryCompletion final {
    public:
        explicit RecordBoundaryCompletion(AlignedRecordWriter* owner) noexcept : owner_(owner) {}
        ~RecordBoundaryCompletion() {
            if constexpr (OverlapChunkPreparation) {
                if (!owner_->writeLane_.drain()) (void)owner_->fail(owner_->writeLane_.failureErrno());
            }
        }
        RecordBoundaryCompletion(const RecordBoundaryCompletion&) = delete;
        RecordBoundaryCompletion& operator=(const RecordBoundaryCompletion&) = delete;
    private:
        AlignedRecordWriter* owner_;
    };

    static bool unsupported(int failure) noexcept {
        return failure == EINVAL || failure == EOPNOTSUPP || failure == ENOSYS;
    }
    void reject(int failure, Admission decision) noexcept {
        admissionErrno_ = failure == 0 ? EIO : failure;
        admission_ = decision;
        // Release a rejected descriptor before the buffered backend can start. A close failure
        // changes an otherwise optional rejection into a fatal admission error.
        if (!close()) {
            admission_ = Admission::Fatal;
            admissionErrno_ = failureErrno();
        }
    }
    bool fail(int failure) noexcept {
        int expected = 0;
        if (failureErrno_.compare_exchange_strong(expected, failure == 0 ? EIO : failure,
                std::memory_order_relaxed)) failures_.fetch_add(1u, std::memory_order_relaxed);
        errno = failureErrno();
        return false;
    }
    static bool readFully(int fd, std::uint8_t* buffer, std::size_t count,
                          std::int64_t offset) noexcept {
        std::size_t done = 0u;
        while (done < count) {
            const auto result = Io::read(fd, buffer + done, count - done,
                offset + static_cast<std::int64_t>(done));
            if (result < 0 && errno == EINTR) continue;
            if (result <= 0 || static_cast<std::size_t>(result) > count - done) {
                if (result >= 0) errno = EIO;
                return false;
            }
            done += static_cast<std::size_t>(result);
        }
        return true;
    }
    bool append(const std::uint8_t* bytes, std::size_t count) noexcept {
        while (count != 0u) {
            const auto take = std::min(count, kCapacity - used_);
            std::memcpy(buffer_ + used_, bytes, take);
            used_ += take;
            cursor_ += static_cast<std::int64_t>(take);
            bytes += take;
            count -= take;
            if (used_ == kCapacity) {
                if (!writeAligned(kCapacity)) return false;
                if constexpr (OverlapChunkPreparation) {
                    buffer_ = writeLane_.acquire();
                    if (buffer_ == nullptr) return fail(writeLane_.failureErrno());
                }
                baseOffset_ += static_cast<std::int64_t>(kCapacity);
                used_ = 0u;
            }
        }
        return true;
    }
    bool writeAligned(std::size_t count) noexcept {
        if constexpr (OverlapChunkPreparation) {
            if (!writeLane_.submit(buffer_, count, baseOffset_)) {
                return fail(writeLane_.failureErrno());
            }
            return true;
        }
        return writeAlignedBytes(buffer_, count, baseOffset_);
    }
    bool writeAlignedBytes(const std::uint8_t* bytes, std::size_t count,
                           std::int64_t offset, bool streamImmutableRecord = false) noexcept {
        if constexpr (EnableKernelAio) {
            if (kernelAioEnabled_) {
                writeCalls_.fetch_add((count - 1u) / KernelAio::kMaximumGroupBytes + 1u,
                    std::memory_order_relaxed);
                const bool written = streamImmutableRecord
                    ? kernelAio_.writeImmutableStream(fd_, bytes, count, offset, alignment_)
                    : kernelAio_.writeAll(fd_, bytes, count, offset, alignment_);
                if (!written)
                    return fail(kernelAio_.failureErrno());
                physicalBytes_.fetch_add(count, std::memory_order_relaxed);
                return true;
            }
        }
        std::size_t done = 0u;
        while (done < count) {
            const auto request = std::min(count - done, kCapacity);
            const auto result = Io::write(fd_, bytes + done, request,
                offset + static_cast<std::int64_t>(done));
            writeCalls_.fetch_add(1u, std::memory_order_relaxed);
            if (result < 0 && errno == EINTR) continue;
            if (result <= 0 || static_cast<std::size_t>(result) > request ||
                static_cast<std::size_t>(result) % alignment_ != 0u) {
                return fail(result < 0 ? errno : EIO);
            }
            physicalBytes_.fetch_add(static_cast<std::size_t>(result), std::memory_order_relaxed);
            done += static_cast<std::size_t>(result);
        }
        return true;
    }

    int fd_ = -1;
    std::uint8_t* buffer_ = nullptr;
    std::uint8_t* initialBuffer_ = nullptr;
    std::uint8_t* secondBuffer_ = nullptr;
    BoundedAlignedWriteLane<ConcurrentChunkWrites> writeLane_;
    KernelAio kernelAio_;
    bool kernelAioEnabled_ = false;
    std::size_t alignment_ = 0u;
    std::size_t used_ = 0u;
    std::int64_t baseOffset_ = 0;
    std::int64_t cursor_ = 0;
    dev_t device_ = 0;
    ino64_t inode_ = 0;
    Admission admission_ = Admission::Fatal;
    int admissionErrno_ = 0;
    bool probeVerified_ = false;
    std::atomic<int> failureErrno_{0};
    std::atomic<std::uint64_t> writeCalls_{0u};
    std::atomic<std::uint64_t> physicalBytes_{0u};
    std::atomic<std::uint64_t> paddingBytes_{0u};
    std::atomic<std::uint64_t> failures_{0u};
    std::atomic<std::uint64_t> ownedRecordWrites_{0u};
    std::atomic<std::uint64_t> ownedPayloadBytes_{0u};
    std::atomic<std::uint64_t> immutableStreamRecordWrites_{0u};
};

}  // namespace neoproc::npv10
