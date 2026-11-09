package com.neoproc.neoproccamx.monitor

/**
 * Non-destructive recovery actions. Per product decision, recovery NEVER tears down or restarts the
 * camera session or encoder mid-recording — it only re-probes / refreshes / re-arms already-safe
 * affordances. A failure that has no safe automated remedy is reported honestly ([RecoveryAction.NONE]
 * with a rationale) rather than pretended-fixed.
 */
enum class RecoveryAction {
    /** No safe automated recovery applies; the fault is surfaced and recording continues untouched. */
    NONE,

    /** Re-probe Vulkan capabilities / encoder-surface status (refreshCapabilitySummary). */
    REFRESH_GPU_CAPABILITIES,

    /** Re-arm the CP17 ISP metadata read-back so the next frames re-verify the requested ISP mode. */
    REARM_ISP_READBACK,

    /** Reset transient GPU-import failure accounting so a recovered pipeline can re-prove itself. */
    RESET_IMPORT_HEALTH
}

data class RecoveryDecision(
    val action: RecoveryAction,
    val rationale: String
)

/** Backoff/limit tuning for [HealthRecoveryPolicy]. */
data class HealthRecoveryConfig(
    /** Minimum ms between recovery attempts for a module (avoids hammering a live pipeline). */
    val backoffMs: Long = 1_500,
    /** Attempts before the policy gives up and asks for manual intervention. */
    val maxAttempts: Int = 5
)

/**
 * Pure decision layer: given an ERROR reading and the recovery history, decide whether — and what —
 * to attempt. Executing the action is the Android layer's job (see `HealthRecoveryExecutor`), keeping
 * this fully unit-testable.
 */
object HealthRecoveryPolicy {

    fun decide(
        reading: ModuleHealthReading,
        attemptsSoFar: Int,
        lastAttemptElapsedMs: Long?,
        nowMs: Long,
        config: HealthRecoveryConfig = HealthRecoveryConfig()
    ): RecoveryDecision {
        if (reading.state != HealthState.ERROR || !reading.recoverable) {
            return RecoveryDecision(RecoveryAction.NONE, "No recovery needed")
        }
        if (attemptsSoFar >= config.maxAttempts) {
            return RecoveryDecision(
                RecoveryAction.NONE,
                "Automated recovery exhausted after $attemptsSoFar attempts — manual intervention required"
            )
        }
        if (lastAttemptElapsedMs != null && nowMs - lastAttemptElapsedMs < config.backoffMs) {
            return RecoveryDecision(RecoveryAction.NONE, "Backing off before the next attempt")
        }
        val action = when (reading.module) {
            MonitoredModule.PROV_CORE -> RecoveryAction.RESET_IMPORT_HEALTH
            MonitoredModule.LAB -> RecoveryAction.REFRESH_GPU_CAPABILITIES
            MonitoredModule.NATIVE -> RecoveryAction.REARM_ISP_READBACK
            // A rejected/failed 10-bit transport cannot be safely recovered without a restart (excluded).
            MonitoredModule.P010 -> RecoveryAction.NONE
        }
        val rationale = if (action == RecoveryAction.NONE) {
            "No non-destructive recovery for ${reading.module.displayLabel}; recording continues"
        } else {
            "Attempt ${attemptsSoFar + 1}: $action"
        }
        return RecoveryDecision(action, rationale)
    }
}

/**
 * Executes a [RecoveryAction] against the live pipeline. Implemented on the Android side with real,
 * safe calls; returns true if the action had a genuine effect (so the ledger can report honestly).
 */
interface HealthRecoveryExecutor {
    fun execute(action: RecoveryAction): Boolean
}
