package com.neoproc.neoproccamx.monitor

/**
 * Runtime module-health monitoring model (pure Kotlin, zero Android dependency so the whole
 * verification core is JVM-unit-testable).
 *
 * The four HUD indicators (PROV CORE / P010 / Lab / Native) are backed by [RuntimeHealthMonitor],
 * which samples an immutable [RuntimeSignalSnapshot] of REAL runtime signals every ~150 ms, runs a
 * pure [HealthProbe] per module, debounces the verdict through a [HealthStabilizer], and drives
 * non-destructive recovery via [HealthRecoveryPolicy].
 *
 * Design contract (mirrors the app's existing "honest / PROVEN-vs-NOT_PROVEN" philosophy):
 *  - A module is NEVER reported [HealthState.HEALTHY]/[HealthState.ACTIVE] unless a live signal
 *    positively confirms it.
 *  - Absence of a signal (device did not report it) is NOT success — it degrades to WARNING / UNKNOWN /
 *    INACTIVE, never a fabricated healthy/active claim.
 *  - "recovering" remains an attribute of the status (the blue Error->Recovering->Healthy visual),
 *    surfaced as the RECOVERING display token, NOT a distinct enum value — so the [RuntimeHealthMonitor]
 *    recovery bookkeeping (which keys on state==ERROR + recovering:Boolean) is preserved.
 */
enum class HealthState(
    /**
     * Ordering used by the stabilizer to tell a worsening transition from an improving one
     * (higher == worse). The critical invariant is preserved: INACTIVE < HEALTHY < WARNING < ERROR, so
     * a real degradation always out-ranks any good/neutral state and the "never a false HEALTHY under
     * oscillation" rule keeps holding. Positive/live states (ACTIVE/READY) sit low so claiming them is
     * the conservative "improving" direction (needs more confirming ticks); WARNING/ERROR sit at the top.
     */
    val severityRank: Int,
    /** Human-facing label. ERROR renders as FAILED to match the spec's state-machine vocabulary. */
    val displayName: String
) {
    /** Capability genuinely absent on this hardware (e.g. no Vulkan runtime, HAL cannot bypass the ISP). */
    UNSUPPORTED(0, "UNSUPPORTED"),

    /** Module intentionally disabled or not applicable in the current mode. */
    INACTIVE(1, "INACTIVE"),

    /** Verified work is happening this instant — compute dispatching, recording 10-bit, ISP honored. */
    ACTIVE(2, "ACTIVE"),

    /** Capability present and armed, positively confirmed, but not currently exercising (idle-ready). */
    READY(3, "READY"),

    /** Everything the probe can verify is confirmed working. */
    HEALTHY(4, "HEALTHY"),

    /** State not yet determined — cold start, liveness unproven, verification pending. */
    UNKNOWN(5, "UNKNOWN"),

    /** Working but degraded — fallback mode, reduced performance, high latency, partial capability. */
    WARNING(6, "WARNING"),

    /** Module unavailable, runtime failure, exception, hardware rejection, init failure, timeout. */
    ERROR(7, "FAILED");

    /** True when the module is engaged/live (doing or ready to do work), false when off/unknown/failed. */
    val isActive: Boolean get() = this == ACTIVE || this == READY || this == HEALTHY || this == WARNING
}

/** The four monitored backend modules and their HUD chip labels. */
enum class MonitoredModule(
    val displayLabel: String,
    val shortLabel: String
) {
    PROV_CORE("Pipeline Core", "PROV"),
    P010("P010 10-bit", "P010"),
    LAB("GPU Processing", "LAB"),
    NATIVE("ISP Native", "NATIVE")
}

/**
 * Shared, tunable thresholds for the probes. Centralised so the probes and their tests agree on the
 * exact boundaries and so a device tuning pass has a single place to adjust.
 */
object HealthThresholds {
    /** GPU-import consecutive-failure counts that trip WARNING / ERROR (mirrors isPipelineHealthy()<10). */
    const val IMPORT_FAILURE_WARN: Long = 3
    const val IMPORT_FAILURE_ERROR: Long = 10

    /** Frame-delivery stall (ms since last delivered capture result) that trips WARNING / ERROR. */
    const val FRAME_STALL_WARN_MS: Long = 600
    const val FRAME_STALL_ERROR_MS: Long = 1_500

    /** GPU compute average per-dispatch latency (ms) that trips WARNING / ERROR. */
    const val COMPUTE_LATENCY_WARN_MS: Double = 24.0
    const val COMPUTE_LATENCY_ERROR_MS: Double = 45.0

    /** Preview FPS floor below which delivery is considered degraded (only while a frame source is live). */
    const val PREVIEW_FPS_WARN_FLOOR: Float = 12f

    /** Dropped-frame estimate (per current session) that trips a WARNING while recording. */
    const val DROP_ESTIMATE_WARN: Int = 1
}

/**
 * Everything the tap-to-open diagnostics panel needs, produced by the probe from the same snapshot
 * it judged. All fields are nullable so the panel can render "—" for anything not applicable rather
 * than inventing detail.
 */
data class HealthDiagnostics(
    val failureReason: String? = null,
    val runtimeException: String? = null,
    val pipelineStage: String,
    val currentFallback: String? = null,
    val performanceImpact: String? = null,
    val suggestedSolution: String? = null,
    /** Extra live telemetry (label -> value) surfaced verbatim in the panel. */
    val metrics: List<Pair<String, String>> = emptyList()
)

/**
 * The raw, per-tick verdict a [HealthProbe] returns for one module. Pre-hysteresis and pre-recovery.
 */
data class ModuleHealthReading(
    val module: MonitoredModule,
    val state: HealthState,
    val reason: String,
    val diagnostics: HealthDiagnostics,
    /**
     * True for an explicit, unambiguous fault (a surfaced exception / Error FSM state / hardware
     * rejection). Hard faults bypass the improving-side debounce so a real failure is shown at once.
     */
    val hardFault: Boolean = false,
    /** True if this ERROR is eligible for the non-destructive auto-recovery ladder. */
    val recoverable: Boolean = false
)

/**
 * The smoothed, monitor-maintained status the UI chip and the diagnostics panel render. Carries the
 * recovery bookkeeping so the panel can show attempts/result without a second source of truth.
 */
data class ModuleHealthStatus(
    val module: MonitoredModule,
    val state: HealthState,
    val reason: String,
    val diagnostics: HealthDiagnostics,
    val recovering: Boolean = false,
    val recoveryAttempts: Int = 0,
    val recoveryResult: String? = null,
    /** Monotonic ms (caller clock) at which the CURRENT state was first entered. */
    val stateSinceElapsedMs: Long = 0L,
    /** Monotonic ms of the most recent sample that produced this status. */
    val updatedElapsedMs: Long = 0L
) {
    /**
     * The state-machine token to DISPLAY: the canonical [state]'s [HealthState.displayName], except an
     * in-flight non-destructive recovery on an ERROR surfaces as RECOVERING (matching the blue-pulse
     * chip visual). RECOVERING is a display projection of (ERROR + recovering), never a stored state,
     * so the recovery ledger keeps its single source of truth.
     */
    val displayStateLabel: String
        get() = if (recovering && state == HealthState.ERROR) "RECOVERING" else state.displayName
}
