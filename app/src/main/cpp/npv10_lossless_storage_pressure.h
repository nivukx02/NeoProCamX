#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace neoproc::npv10 {

/**
 * Bounded lossless storage relief, not a quality or frame-rate controller.
 *
 * Called only while the DMC mutex is held. The actual encoder runs outside that lock;
 * its immutable decision returns with the completion, including out-of-order workers.
 * Neither representation drops samples or changes the NLC2 decoding contract. Queue
 * capacity, admission timeouts, physical reservations and thermal safety are untouched.
 */
class LosslessStoragePressurePolicy final {
public:
    static constexpr int kFastAcceleration = 1024;
    static constexpr int kStorageAcceleration = 64;
    static constexpr unsigned kEnterObservations = 3u;
    static constexpr unsigned kReleaseObservations = 16u;
    static constexpr unsigned kMinimumPressureFrames = 25u;
    static constexpr unsigned kCpuCooldownFrames = 50u;

    struct Decision {
        int acceleration = kFastAcceleration;
        std::int64_t wallBudgetNs = 0;
        bool storagePressure = false;
    };

    struct Counters {
        std::int64_t entries = 0;
        std::int64_t fastFrames = 0;
        std::int64_t pressureFrames = 0;
        std::int64_t cpuBackoffs = 0;
        std::int64_t fastEncodeNs = 0;
        std::int64_t pressureEncodeNs = 0;
        std::int64_t pressureMaxEncodeNs = 0;
        std::int64_t fastInputBytes = 0;
        std::int64_t fastStoredBytes = 0;
        std::int64_t pressureInputBytes = 0;
        std::int64_t pressureStoredBytes = 0;
        std::int64_t failures = 0;
    };

    void observeSourceTimestamp(std::int64_t timestampNs) noexcept {
        if (timestampNs <= 0 || timestampNs <= previousTimestampNs_) return;
        if (previousTimestampNs_ > 0) {
            const auto interval = timestampNs - previousTimestampNs_;
            // A dropped-frame gap must never grant the compressor a larger CPU budget.
            if (interval <= 1'000'000'000LL &&
                (sourceIntervalNs_ == 0 || interval < sourceIntervalNs_)) {
                sourceIntervalNs_ = interval;
            }
        }
        previousTimestampNs_ = timestampNs;
    }

    Decision choose(std::size_t pendingWrites, std::size_t slotCapacity,
                    std::size_t observedProcessingLanes = 1u) noexcept {
        if (cooldownFrames_ > 0u) {
            --cooldownFrames_;
            return {};
        }
        // Until real source cadence is known, keep the existing fast representation.
        if (sourceIntervalNs_ <= 0 || slotCapacity < 3u) return {};
        const auto highWater = std::max<std::size_t>(2u, slotCapacity / 4u);
        if (!active_) {
            highObservations_ = pendingWrites >= highWater
                ? std::min(highObservations_ + 1u, kEnterObservations) : 0u;
            if (highObservations_ >= kEnterObservations) {
                active_ = true;
                holdFrames_ = kMinimumPressureFrames;
                lowObservations_ = 0u;
                add(counters_.entries, 1);
            }
        } else {
            lowObservations_ = pendingWrites <= 1u
                ? std::min(lowObservations_ + 1u, kReleaseObservations) : 0u;
            if (holdFrames_ == 0u && lowObservations_ >= kReleaseObservations) {
                leavePressure();
            }
        }
        if (!active_) return {};
        if (holdFrames_ > 0u) --holdFrames_;
        // DMC measures overlapping PROCESSING ownership, rather than guessing from CPU
        // core count. With two real submitters, each receives every other source frame:
        // half of that worker's interval is one source interval. The first device retry
        // incorrectly imposed a single-lane half-frame limit on both independent lanes.
        // Keep single-lane behavior unchanged and never budget more than two lanes.
        const auto lanes = static_cast<std::int64_t>(
            std::clamp<std::size_t>(observedProcessingLanes, 1u, 2u));
        return {kStorageAcceleration, std::min<std::int64_t>(
            (sourceIntervalNs_ / 2) * lanes, 30'000'000LL * lanes), true};
    }

    void completed(const Decision& decision, std::int64_t elapsedNs,
                   std::int64_t inputBytes, std::int64_t storedBytes,
                   bool successful) noexcept {
        const auto elapsed = std::max<std::int64_t>(0, elapsedNs);
        // Wall time includes preemption: if capture cannot afford the extra work, back
        // off even when pure codec CPU was cheap. A late older completion may extend
        // cooldown, but can never re-enable pressure or alter another slot's decision.
        if (decision.storagePressure &&
            (decision.wallBudgetNs <= 0 || elapsed > decision.wallBudgetNs)) {
            leavePressure();
            cooldownFrames_ = kCpuCooldownFrames;
            add(counters_.cpuBackoffs, 1);
        }
        if (!successful || inputBytes <= 0 || storedBytes <= 0) {
            add(counters_.failures, 1);
            return;
        }
        if (decision.storagePressure) {
            add(counters_.pressureFrames, 1);
            add(counters_.pressureEncodeNs, elapsed);
            counters_.pressureMaxEncodeNs = std::max(counters_.pressureMaxEncodeNs, elapsed);
            add(counters_.pressureInputBytes, inputBytes);
            add(counters_.pressureStoredBytes, storedBytes);
        } else {
            add(counters_.fastFrames, 1);
            add(counters_.fastEncodeNs, elapsed);
            add(counters_.fastInputBytes, inputBytes);
            add(counters_.fastStoredBytes, storedBytes);
        }
    }

    bool active() const noexcept { return active_; }
    unsigned cooldownFrames() const noexcept { return cooldownFrames_; }
    std::int64_t sourceIntervalNs() const noexcept { return sourceIntervalNs_; }
    const Counters& counters() const noexcept { return counters_; }

private:
    static void add(std::int64_t& target, std::int64_t value) noexcept {
        if (value > 0) target += std::min(value, std::numeric_limits<std::int64_t>::max() - target);
    }
    void leavePressure() noexcept {
        active_ = false;
        highObservations_ = 0u;
        lowObservations_ = 0u;
        holdFrames_ = 0u;
    }
    Counters counters_{};
    std::int64_t previousTimestampNs_ = 0;
    std::int64_t sourceIntervalNs_ = 0;
    unsigned highObservations_ = 0u;
    unsigned lowObservations_ = 0u;
    unsigned holdFrames_ = 0u;
    unsigned cooldownFrames_ = 0u;
    bool active_ = false;
};

}  // namespace neoproc::npv10
