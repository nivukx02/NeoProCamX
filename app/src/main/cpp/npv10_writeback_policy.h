#pragma once

#include <cstdint>

namespace neoproc::npv10 {

/**
 * Schedules optional page-cache writeback, never a durability acknowledgement.
 *
 * A compressed packet's slot capacity is its incompressible upper bound, not the number of dirty
 * bytes. Suppressing all hints from that capacity lets gigabytes accumulate before force(false).
 * The lossless route instead wakes from actual committed bytes and caps each kernel request.
 * Existing uncompressed large-frame suppression and every mandatory force/CRC/hash stay intact.
 */
struct IncrementalWritebackPolicy final {
    static constexpr std::int64_t kLegacyTargetBytes = 64LL * 1024LL * 1024LL;
    static constexpr std::int64_t kLosslessTargetBytes = 16LL * 1024LL * 1024LL;
    static constexpr std::int64_t kMaximumRequestBytes = 16LL * 1024LL * 1024LL;
    static constexpr std::int64_t kLegacySuppressionPayloadBytes = 8LL * 1024LL * 1024LL;
    static constexpr std::int32_t kMaximumPendingFrames = 8;

    static constexpr bool suppressForRealtime(
        bool chunkedLosslessCompression, std::int64_t payloadCapacityBytes
    ) noexcept {
        return !chunkedLosslessCompression &&
            payloadCapacityBytes >= kLegacySuppressionPayloadBytes;
    }

    static constexpr std::int64_t targetBytes(bool chunkedLosslessCompression) noexcept {
        return chunkedLosslessCompression ? kLosslessTargetBytes : kLegacyTargetBytes;
    }

    // Subtract before adding: even a valid INT64_MAX file offset must never wrap or extend the
    // request beyond the committed tail. Empty/reversed/invalid ranges perform no I/O.
    static constexpr std::int64_t boundedEnd(
        std::int64_t coveredThrough, std::int64_t requestedThrough
    ) noexcept {
        if (coveredThrough < 0 || requestedThrough <= coveredThrough) return coveredThrough;
        const auto pending = requestedThrough - coveredThrough;
        return coveredThrough + (pending < kMaximumRequestBytes ? pending : kMaximumRequestBytes);
    }
};

}  // namespace neoproc::npv10
