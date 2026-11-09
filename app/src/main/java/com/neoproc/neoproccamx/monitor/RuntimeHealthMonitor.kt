package com.neoproc.neoproccamx.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Structured sink for health transitions and recovery events (implemented over NeoLogger). */
interface HealthEventLogger {
    fun onStateTransition(module: MonitoredModule, previous: HealthState?, next: ModuleHealthStatus)
    fun onRecoveryAttempt(
        module: MonitoredModule,
        action: RecoveryAction,
        attempt: Int,
        success: Boolean,
        rationale: String
    )
}

/**
 * Coordinates the whole monitoring core. Each [sample] call:
 *  1. runs every [HealthProbe] against the immutable snapshot,
 *  2. drives non-destructive recovery on ERROR readings (per [HealthRecoveryPolicy], executed via the
 *     injected [HealthRecoveryExecutor]),
 *  3. smooths the verdict through a per-module [HealthStabilizer], and
 *  4. publishes the map on [status] and logs transitions/recoveries.
 *
 * `sample` is synchronous and side-effect-contained (only reads the snapshot, calls the executor, and
 * updates a StateFlow), so it is cheap enough to run every ~150 ms and fully unit-testable by feeding
 * crafted snapshots and asserting `status.value`. The polling loop and snapshot construction live in
 * the Android layer, keeping this class free of Android and lifecycle concerns.
 */
class RuntimeHealthMonitor(
    private val probes: List<HealthProbe> = DEFAULT_HEALTH_PROBES,
    private val recoveryExecutor: HealthRecoveryExecutor? = null,
    private val logger: HealthEventLogger? = null,
    stabilizerConfig: HealthStabilizerConfig = HealthStabilizerConfig(),
    private val recoveryConfig: HealthRecoveryConfig = HealthRecoveryConfig()
) {
    private val order: List<MonitoredModule> = probes.map { it.module }
    private val stabilizers = probes.associate { it.module to HealthStabilizer(it.module, stabilizerConfig) }
    private val ledgers = probes.associate { it.module to RecoveryLedger() }

    private val _status = MutableStateFlow<Map<MonitoredModule, ModuleHealthStatus>>(emptyMap())
    val status: StateFlow<Map<MonitoredModule, ModuleHealthStatus>> = _status.asStateFlow()

    fun currentStatuses(): Map<MonitoredModule, ModuleHealthStatus> = _status.value

    fun sample(snapshot: RuntimeSignalSnapshot): Map<MonitoredModule, ModuleHealthStatus> {
        val now = snapshot.sampleElapsedMs
        val result = LinkedHashMap<MonitoredModule, ModuleHealthStatus>(order.size)

        for (probe in probes) {
            val module = probe.module
            val stabilizer = stabilizers.getValue(module)
            val ledger = ledgers.getValue(module)
            val previousState = stabilizer.current()?.state

            val reading = probe.evaluate(snapshot)
            driveRecovery(reading, ledger, now)

            val base = stabilizer.accept(reading, now)
            val status = base.copy(
                recovering = ledger.recovering,
                recoveryAttempts = ledger.attempts,
                recoveryResult = ledger.lastResult
            )
            result[module] = status

            if (previousState != status.state) {
                logger?.onStateTransition(module, previousState, status)
            }
        }

        val published: Map<MonitoredModule, ModuleHealthStatus> = result.toMap()
        _status.value = published
        return published
    }

    /** Recovery reacts to the RAW reading (immediate), independent of the stabilizer's committed state. */
    private fun driveRecovery(reading: ModuleHealthReading, ledger: RecoveryLedger, now: Long) {
        val isError = reading.state == HealthState.ERROR
        if (isError && !ledger.inIncident) {
            // A fresh incident gets a fresh recovery budget and clean reporting.
            ledger.reset()
            ledger.inIncident = true
        }
        if (!isError) {
            if (ledger.inIncident) {
                ledger.inIncident = false
                ledger.recovering = false
                if (ledger.attempts > 0) {
                    ledger.lastResult = "Recovered after ${ledger.attempts} attempt(s)"
                }
            }
            return
        }

        val decision = HealthRecoveryPolicy.decide(
            reading = reading,
            attemptsSoFar = ledger.attempts,
            lastAttemptElapsedMs = ledger.lastAttemptMs,
            nowMs = now,
            config = recoveryConfig
        )
        when (decision.action) {
            RecoveryAction.NONE -> {
                // Distinguish a TERMINAL NONE (recovery exhausted, or no safe action exists for this
                // module) from a transient backoff gap between attempts. Terminal => nothing is or ever
                // will be in flight, so clear `recovering` (the chip must show the true red ERROR, not a
                // perpetual blue "recovering") and surface the rationale. Backoff => keep the in-progress
                // recovery cycle intact so the chip stays "recovering" between attempts.
                val exhausted = ledger.attempts >= recoveryConfig.maxAttempts
                val nothingToDo = ledger.attempts == 0
                if (exhausted || nothingToDo) {
                    ledger.recovering = false
                    ledger.lastResult = decision.rationale
                }
            }
            else -> {
                ledger.attempts++
                ledger.lastAttemptMs = now
                ledger.recovering = true
                val success = try {
                    recoveryExecutor?.execute(decision.action) ?: false
                } catch (t: Throwable) {
                    false
                }
                ledger.lastResult = if (success) {
                    "Attempt ${ledger.attempts}: ${decision.action} applied"
                } else {
                    "Attempt ${ledger.attempts}: ${decision.action} had no effect"
                }
                logger?.onRecoveryAttempt(reading.module, decision.action, ledger.attempts, success, decision.rationale)
            }
        }
    }

    private class RecoveryLedger {
        var attempts: Int = 0
        var lastAttemptMs: Long? = null
        var lastResult: String? = null
        var recovering: Boolean = false
        var inIncident: Boolean = false

        fun reset() {
            attempts = 0
            lastAttemptMs = null
            lastResult = null
            recovering = false
        }
    }
}
