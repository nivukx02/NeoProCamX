package com.neoproc.neoproccamx.monitor

/** Debounce tuning for [HealthStabilizer]. */
data class HealthStabilizerConfig(
    /** Consecutive confirming ticks before a WORSENING transition commits (e.g. HEALTHY -> WARNING). */
    val worsenTicks: Int = 2,
    /** Consecutive confirming ticks before an IMPROVING transition commits (e.g. ERROR -> HEALTHY). */
    val improveTicks: Int = 3
)

/**
 * Per-module transition smoother. Turns a stream of raw [ModuleHealthReading]s (one per ~150 ms tick)
 * into a stable [ModuleHealthStatus], preventing chip flicker while guaranteeing a real fault is never
 * hidden:
 *
 *  - The very first reading is adopted immediately (no cold-start lag).
 *  - A [ModuleHealthReading.hardFault] (surfaced exception / Error state) is adopted immediately.
 *  - An INACTIVE reading is adopted immediately (mode changes like "recording stopped" are definite).
 *  - Improving transitions require more confirming ticks than worsening ones, so the UI never flaps
 *    back to HEALTHY on a single lucky sample.
 *
 * Deterministic and clock-free (the caller supplies `nowMs`), so every rule is unit-testable by
 * feeding a fixed sequence of readings. Not thread-safe — the monitor calls it from one sampler.
 */
class HealthStabilizer(
    private val module: MonitoredModule,
    private val config: HealthStabilizerConfig = HealthStabilizerConfig()
) {
    private var current: ModuleHealthStatus? = null
    // Two INDEPENDENT accumulators — one for readings worse than the committed state, one for readings
    // better than it — each tracking the WORST reading it has seen and its observation count. Kept
    // separate (not a single direction-keyed streak) so a single opposite-direction reading no longer
    // wipes an in-progress transition: a genuinely-intermittent degradation (e.g. WARNING/ACTIVE
    // alternating every tick) still accumulates worsening observations and commits, instead of holding
    // a stale good/neutral state forever. Both reset only on a genuine return to the committed state
    // (or on any commit) — so an isolated single blip that recovers to committed is still absorbed.
    private var worsePending: ModuleHealthReading? = null
    private var worseStreak = 0
    private var betterPending: ModuleHealthReading? = null
    private var betterStreak = 0

    fun current(): ModuleHealthStatus? = current

    fun accept(reading: ModuleHealthReading, nowMs: Long): ModuleHealthStatus {
        require(reading.module == module) {
            "Reading for ${reading.module} fed to stabilizer for $module"
        }
        val prev = current
        if (prev == null) {
            return commit(reading, nowMs, sinceMs = nowMs)
        }
        if (reading.state == prev.state) {
            // Same state (a genuine return to the committed baseline): refresh live reason/diagnostics,
            // keep the original entry time, and absorb any in-flight pending transition (blip recovery).
            clearPending()
            return commit(reading, nowMs, sinceMs = prev.stateSinceElapsedMs)
        }
        // State differs. Immediate-adopt cases: hard faults and transitions into a DEFINITE mode/
        // capability fact (INACTIVE = mode change like "recording stopped"; UNSUPPORTED = a positively
        // established hardware-capability verdict). Both are unambiguous, so they show at once.
        if (reading.hardFault ||
            reading.state == HealthState.INACTIVE ||
            reading.state == HealthState.UNSUPPORTED
        ) {
            clearPending()
            return commit(reading, nowMs, sinceMs = nowMs)
        }
        // Debounce relative to the committed state, requiring more confirmations to improve than to
        // worsen. Each side tracks the WORST reading it has seen so a commit is never more optimistic
        // than the readings that produced it (guards the "never a false HEALTHY" rule under oscillation).
        val worsening = reading.state.severityRank > prev.state.severityRank
        if (worsening) {
            worseStreak++
            if (worsePending == null || reading.state.severityRank >= worsePending!!.state.severityRank) {
                worsePending = reading
            }
            if (worseStreak >= config.worsenTicks) {
                val commitReading = worsePending!!
                clearPending()
                return commit(commitReading, nowMs, sinceMs = nowMs)
            }
        } else {
            betterStreak++
            if (betterPending == null || reading.state.severityRank >= betterPending!!.state.severityRank) {
                betterPending = reading
            }
            if (betterStreak >= config.improveTicks) {
                val commitReading = betterPending!!
                clearPending()
                return commit(commitReading, nowMs, sinceMs = nowMs)
            }
        }
        // Not yet confirmed — hold the previous state, but keep the panel/diagnostics timestamp fresh.
        return prev.copy(updatedElapsedMs = nowMs).also { current = it }
    }

    private fun clearPending() {
        worsePending = null
        worseStreak = 0
        betterPending = null
        betterStreak = 0
    }

    private fun commit(reading: ModuleHealthReading, nowMs: Long, sinceMs: Long): ModuleHealthStatus {
        val status = ModuleHealthStatus(
            module = reading.module,
            state = reading.state,
            reason = reading.reason,
            diagnostics = reading.diagnostics,
            stateSinceElapsedMs = sinceMs,
            updatedElapsedMs = nowMs
        )
        current = status
        return status
    }
}
