#pragma once

// Rejected physical-performance prototype; deliberately outside application
// source sets. Exactness/concurrency tests passed, but measured native encoding
// on the iQOO target was slower than the current production serial encoder.

#include "npv10_lossless_chunk_codec.h"
#include <algorithm>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>
#include <sys/resource.h>
#include <zlib.h>

namespace neoproc::npv10::lossless {

/**
 * One reusable helper encodes a disjoint, chunk-aligned half of ONE owned frame.
 * The caller encodes the other half. Joining preserves the established NLC2
 * samples, format, header, order and CRC, with no cross-frame prediction or extra
 * frame queue. Copy + both encodes + join are inside the caller's preparation
 * timer. A busy helper declines BEFORE touching input; the caller retains the
 * existing serial encoder. No partially completed job is ever acknowledged.
 */
class ParallelRangeEncoder final {
public:
    static constexpr std::int64_t kMaximumScratchBytes = 32LL * 1024 * 1024;

    bool initialize(std::int64_t packedBytes, std::int64_t samples,
                    std::int64_t capacity) noexcept {
        std::lock_guard<std::mutex> admission(callerMutex_);
        if (worker_.joinable() || initialized_ || stopping_ ||
            samples < 4LL * kChunkSamples || samples > INT32_MAX / 2 ||
            maximumStoredBytes(packedBytes, samples) != capacity) return false;
        const auto chunks = (samples + kChunkSamples - 1) / kChunkSamples;
        firstSamples_ = (chunks / 2) * kChunkSamples;
        secondSamples_ = samples - firstSamples_;
        firstPacked_ = firstSamples_ * 10 / 8;
        secondPacked_ = packedBytes - firstPacked_;
        firstCapacity_ = maximumStoredBytes(firstPacked_, firstSamples_);
        secondCapacity_ = maximumStoredBytes(secondPacked_, secondSamples_);
        if (firstCapacity_ <= 0 || secondCapacity_ <= 0 ||
            secondCapacity_ > kMaximumScratchBytes ||
            firstCapacity_ + secondCapacity_ - kFrameHeaderBytes != capacity) return false;
        packedBytes_ = packedBytes;
        samples_ = samples;
        capacity_ = capacity;
        try {
            // Fixed per-session storage, zero-filled/prefaulted before the first
            // frame. No per-frame allocation or new thread in the capture path.
            scratch_.resize(static_cast<std::size_t>(secondCapacity_), 0u);
            worker_ = std::thread(&ParallelRangeEncoder::workerLoop, this);
        } catch (...) {
            return false;
        }
        std::unique_lock<std::mutex> state(stateMutex_);
        completed_.wait(state, [this] { return workerReady_; });
        initialized_ = workspaceReady_;
        return initialized_;
    }

    /** False means no input was modified. True means inspect result.successful. */
    bool tryEncode(std::uint8_t* packet, std::int64_t packedBytes,
                   std::int64_t samples, std::int64_t capacity,
                   std::uint32_t originalCrc, int acceleration,
                   EncodeResult& result) noexcept {
        std::unique_lock<std::mutex> admission(callerMutex_, std::try_to_lock);
        if (!admission.owns_lock() || !initialized_ || stopping_ || packet == nullptr ||
            packedBytes != packedBytes_ || samples != samples_ || capacity != capacity_ ||
            acceleration < 1 || acceleration > 65537) return false;
        result = {};
        // Save every second-half byte BEFORE the first half can expand over it.
        std::memcpy(scratch_.data(), packet + firstPacked_, static_cast<std::size_t>(secondPacked_));
        {
            std::lock_guard<std::mutex> state(stateMutex_);
            packet_ = packet;
            acceleration_ = acceleration;
            firstResult_ = {};
            jobComplete_ = false;
            jobReady_ = true;
        }
        workAvailable_.notify_one();
        const auto second = compressPackedTenBitRealtimeRangeLz4InPlace(
            scratch_.data(), secondPacked_, secondSamples_, secondCapacity_, 0u, acceleration);
        EncodeResult first;
        {
            std::unique_lock<std::mutex> state(stateMutex_);
            completed_.wait(state, [this] { return jobComplete_; });
            first = firstResult_;
        }
        // Both owners are retired even on error. Never retry modified input.
        if (!validFragment(first, firstCapacity_) || !validFragment(second, secondCapacity_))
            return true;
        const auto firstBodyBytes = first.storedBytes - kFrameHeaderBytes;
        const auto secondBodyBytes = second.storedBytes - kFrameHeaderBytes;
        const auto total = first.storedBytes + secondBodyBytes;
        if (total > capacity_) return true;
        const auto firstBodyCrc = bodyCrc(first, packet, firstBodyBytes);
        const auto secondBodyCrc = bodyCrc(second, scratch_.data(), secondBodyBytes);
        std::memcpy(packet + first.storedBytes, scratch_.data() + kFrameHeaderBytes,
            static_cast<std::size_t>(secondBodyBytes));
        // The temporary fragment headers are not public packets. Their zero
        // packed-source CRC is replaced with the ORIGINAL full-frame CRC here.
        putBe32(packet + 8, static_cast<std::uint32_t>(packedBytes_));
        putBe32(packet + 12, static_cast<std::uint32_t>(samples_ * 2));
        putBe32(packet + 16, static_cast<std::uint32_t>(samples_));
        putBe32(packet + 24, static_cast<std::uint32_t>(
            (samples_ + kChunkSamples - 1) / kChunkSamples));
        putBe32(packet + 28, originalCrc);
        const auto headerCrc = ::crc32(0, packet, kFrameHeaderBytes);
        const auto firstJoinedCrc = ::crc32_combine(headerCrc, firstBodyCrc,
            static_cast<z_off_t>(firstBodyBytes));
        result.successful = true;
        result.storedBytes = total;
        result.storedCrc32 = static_cast<std::uint32_t>(::crc32_combine(
            firstJoinedCrc, secondBodyCrc, static_cast<z_off_t>(secondBodyBytes)));
        result.packedCrc32 = originalCrc;
        result.compressedChunks = first.compressedChunks + second.compressedChunks;
        result.rawChunks = first.rawChunks + second.rawChunks;
        result.borrowedInputBytes = first.borrowedInputBytes + second.borrowedInputBytes;
        return true;
    }

    void close() noexcept {
        // An active caller includes both jobs and the final compaction. Wait for
        // it before releasing any pointer, workspace or native worker.
        std::lock_guard<std::mutex> admission(callerMutex_);
        {
            std::lock_guard<std::mutex> state(stateMutex_);
            stopping_ = true;
            initialized_ = false;
        }
        workAvailable_.notify_one();
        if (worker_.joinable()) worker_.join();
        std::vector<std::uint8_t>().swap(scratch_);
    }

    ~ParallelRangeEncoder() { close(); }
    ParallelRangeEncoder() = default;
    ParallelRangeEncoder(const ParallelRangeEncoder&) = delete;
    ParallelRangeEncoder& operator=(const ParallelRangeEncoder&) = delete;

private:
    static bool validFragment(const EncodeResult& result, std::int64_t capacity) noexcept {
        return result.successful && result.storedBytes > kFrameHeaderBytes &&
            result.storedBytes <= capacity;
    }

    static std::uint32_t bodyCrc(const EncodeResult& result, const std::uint8_t* packet,
                                 std::int64_t bodyBytes) noexcept {
        // CRC concatenation is linear: total = shifted(prefix) XOR suffix.
        // Remove just the temporary header's contribution; no payload rescan.
        const auto header = ::crc32(0, packet, kFrameHeaderBytes);
        return result.storedCrc32 ^ static_cast<std::uint32_t>(::crc32_combine(
            header, 0, static_cast<z_off_t>(bodyBytes)));
    }

    static void putBe32(std::uint8_t* output, std::uint32_t value) noexcept {
        output[0] = static_cast<std::uint8_t>(value >> 24u);
        output[1] = static_cast<std::uint8_t>(value >> 16u);
        output[2] = static_cast<std::uint8_t>(value >> 8u);
        output[3] = static_cast<std::uint8_t>(value);
    }

    void workerLoop() noexcept {
        // Ordinary advisory app-thread priority only; no affinity, real-time
        // privilege, thermal override or frequency/driver manipulation.
        (void)::setpriority(PRIO_PROCESS, 0, -8);
        std::uint8_t warmup[64]{};
        const auto warmed = compressPackedTenBitRealtimeRangeLz4InPlace(
            warmup, 5, 4, sizeof(warmup), static_cast<std::uint32_t>(::crc32(0, warmup, 5)), 1024);
        const bool ready = warmed.successful;
        std::unique_lock<std::mutex> state(stateMutex_);
        workspaceReady_ = ready;
        workerReady_ = true;
        completed_.notify_one();
        while (true) {
            workAvailable_.wait(state, [this] { return stopping_ || jobReady_; });
            if (stopping_) return;
            auto* const packet = packet_;
            const auto acceleration = acceleration_;
            jobReady_ = false;
            state.unlock();
            const auto result = compressPackedTenBitRealtimeRangeLz4InPlace(
                packet, firstPacked_, firstSamples_, firstCapacity_, 0u, acceleration);
            state.lock();
            packet_ = nullptr;
            firstResult_ = result;
            jobComplete_ = true;
            completed_.notify_one();
        }
    }

    std::mutex callerMutex_;
    std::mutex stateMutex_;
    std::condition_variable workAvailable_;
    std::condition_variable completed_;
    std::thread worker_;
    std::vector<std::uint8_t> scratch_;
    std::int64_t packedBytes_ = 0, samples_ = 0, capacity_ = 0;
    std::int64_t firstSamples_ = 0, secondSamples_ = 0;
    std::int64_t firstPacked_ = 0, secondPacked_ = 0;
    std::int64_t firstCapacity_ = 0, secondCapacity_ = 0;
    std::uint8_t* packet_ = nullptr;
    int acceleration_ = 1024;
    EncodeResult firstResult_{};
    bool initialized_ = false, stopping_ = false;
    bool workerReady_ = false, workspaceReady_ = false;
    bool jobReady_ = false, jobComplete_ = false;
};

} // namespace neoproc::npv10::lossless
