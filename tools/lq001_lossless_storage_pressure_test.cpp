// Executes the production controller on-device. Not a camera/FPS/thermal simulation PASS.
#include "npv10_lossless_storage_pressure.h"

#include <cstdio>
#include <limits>
#include <stdexcept>
#include <vector>

namespace {
using Policy = neoproc::npv10::LosslessStoragePressurePolicy;
unsigned checks = 0;
void require(bool condition, const char* why) {
    ++checks;
    if (!condition) throw std::runtime_error(why);
}
Policy primed() {
    Policy p;
    p.observeSourceTimestamp(1'000'000'000LL);
    p.observeSourceTimestamp(1'040'000'000LL);
    return p;
}
Policy::Decision enter(Policy& p) {
    require(!p.choose(3, 12).storagePressure, "pressure entered on one observation");
    require(!p.choose(3, 12).storagePressure, "pressure entered on two observations");
    const auto decision = p.choose(3, 12);
    require(decision.storagePressure && decision.acceleration == 64, "sustained pressure not admitted");
    return decision;
}
void run() {
    {
        Policy p;
        for (int i = 0; i < 1000; ++i) {
            require(p.choose(12, 12).acceleration == 1024, "unknown cadence changed default codec");
        }
        require(p.counters().entries == 0, "unmeasured cadence entered pressure");
    }
    {
        auto p = primed();
        for (int i = 0; i < 1000; ++i) {
            require(!p.choose(2, 12).storagePressure, "small queue triggered stronger compression");
            require(!p.choose(3, 2).storagePressure, "tiny capacity triggered compression");
        }
    }
    {
        auto p = primed();
        for (int i = 0; i < 1000; ++i) {
            require(!p.choose(5, 12).storagePressure, "single transient entered pressure");
            require(!p.choose(0, 12).storagePressure, "relieved queue entered pressure");
        }
        require(enter(p).wallBudgetNs == 20'000'000LL, "25 FPS source budget incorrect");
        for (unsigned i = 1; i < Policy::kMinimumPressureFrames; ++i) {
            require(p.choose(0, 12).storagePressure, "hysteresis released too early");
        }
        require(!p.choose(0, 12).storagePressure, "drained storage did not release pressure");
        require(p.counters().entries == 1, "transitions counted incorrectly");
    }
    {
        auto p = primed();
        p.observeSourceTimestamp(1'320'000'000LL); // seven-frame gap, never a bigger budget
        require(enter(p).wallBudgetNs == 20'000'000LL, "dropped frame enlarged work budget");
        p.observeSourceTimestamp(1'330'000'000LL);
        require(p.choose(3, 12).wallBudgetNs == 5'000'000LL, "faster source did not tighten budget");
        p.observeSourceTimestamp(-1);
        p.observeSourceTimestamp(0);
        p.observeSourceTimestamp(1'329'000'000LL);
        require(p.sourceIntervalNs() == 10'000'000LL, "stale timestamp changed cadence");
    }
    {
        auto p = primed();
        const auto d = enter(p);
        p.completed(d, d.wallBudgetNs, 1000, 800, true);
        require(p.active(), "exact work budget triggered backoff");
        p.completed(d, d.wallBudgetNs + 1, 1000, 700, true);
        require(!p.active() && p.cooldownFrames() == 50, "slow compression did not back off");
        for (unsigned i = 0; i < Policy::kCpuCooldownFrames; ++i) {
            require(!p.choose(12, 12).storagePressure, "cooldown bypassed under high pressure");
        }
        (void)enter(p);
        require(p.counters().pressureFrames == 2 && p.counters().cpuBackoffs == 1,
            "completion counters incorrect");
        require(p.counters().pressureStoredBytes == 1500 && p.counters().pressureInputBytes == 2000,
            "byte counters incorrect");
    }
    {
        auto p = primed();
        const auto olderFast = p.choose(0, 12);
        const auto stronger = enter(p);
        p.completed(stronger, 12'000'000LL, 1000, 700, true);
        p.completed(olderFast, 40'000'000LL, 1000, 900, true);
        require(p.active(), "late fast completion changed newer pressure state");
        p.completed(stronger, 100'000'000LL, 1000, 700, true);
        require(!p.active(), "late expensive worker did not protect capture");
        p.completed(stronger, 1, 1000, 700, true);
        require(!p.active(), "late cheap worker bypassed cooldown");
        require(p.counters().fastFrames == 1 && p.counters().pressureFrames == 3,
            "out-of-order completions attributed to wrong effort");
    }
    {
        auto p = primed();
        const auto d = enter(p);
        p.completed(d, 1, 1000, 0, false);
        p.completed({}, 1, 0, 1000, true);
        require(p.counters().failures == 2 && p.counters().pressureFrames == 0 &&
            p.counters().fastFrames == 0, "failed packet counted as completed");
        const auto limit = std::numeric_limits<std::int64_t>::max();
        p.completed({}, limit, limit, limit, true);
        p.completed({}, 100, 100, 100, true);
        require(p.counters().fastEncodeNs == limit && p.counters().fastStoredBytes == limit &&
            p.counters().fastInputBytes == limit, "telemetry overflowed");
    }
    {
        auto p = primed();
        (void)enter(p);
        const auto single = p.choose(3, 12, 1);
        const auto paired = p.choose(3, 12, 2);
        require(single.wallBudgetNs == 20'000'000LL, "single-lane budget changed");
        require(paired.wallBudgetNs == 40'000'000LL, "real packing overlap ignored");
        require(p.choose(3, 12, 0).wallBudgetNs == single.wallBudgetNs,
            "missing overlap changed conservative default");
        require(p.choose(3, 12, 999).wallBudgetNs == paired.wallBudgetNs,
            "excess lane count granted unbounded work");
        p.completed(paired, 31'017'240LL, 1000, 750, true);
        require(p.active() && p.counters().cpuBackoffs == 0,
            "measured affordable dual-lane work was wrongly rejected");
        p.completed(paired, 40'000'001LL, 1000, 750, true);
        require(!p.active() && p.cooldownFrames() == 50,
            "over-budget dual-lane work bypassed backoff");
    }
    {
        auto p = primed();
        std::vector<Policy::Decision> pending;
        for (int i = 0; i < 100'000; ++i) {
            const auto d = p.choose(static_cast<unsigned>(i % 17), 12);
            require(d.acceleration == 64 || d.acceleration == 1024, "unbounded codec effort");
            pending.push_back(d);
            if (pending.size() >= 2) {
                p.completed(pending.back(), (i % 131) == 0 ? 50'000'000LL : 8'000'000LL,
                    1000, 800, true);
                pending.pop_back();
                p.completed(pending.back(), 9'000'000LL, 1000, 800, true);
                pending.pop_back();
            }
        }
        require(p.counters().fastFrames + p.counters().pressureFrames == 100'000,
            "parallel-worker schedule lost telemetry completions");
        require(p.counters().failures == 0 && p.counters().cpuBackoffs > 0,
            "stress schedule did not exercise backoff");
    }
}
}  // namespace

int main() {
    try {
        run();
        std::printf("{\"result\":\"PASS\",\"assertions\":%u,\"scope\":\"production_pressure_policy_only_not_camera_fps\"}\n", checks);
        return 0;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "PRESSURE_POLICY_FAILED: %s\n", e.what());
        return 1;
    }
}
