package com.neoproc.neoproccamx.ui

import android.os.Build
import android.os.PowerManager
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

import com.neoproc.neoproccamx.pipeline.PredictiveThermalKalmanFilter

/**
 * ThermalRuntimePolicyResolver — Thermal decision coordination for the camera pipeline.
 *
 * Combines signals from multiple thermal sources (CPU, battery, GPU, SoC skin temp)
 * and produces a single authoritative [ThermalRuntimePolicy.ThermalDecision] for
 * the active recording session.
 *
 * UPGRADE: Now includes a Predictive Thermal Kalman Filter that tracks the
 * rate-of-change of SoC temperature and proactively adjusts encoder complexity
 * BEFORE the OS triggers a throttle. Resolution and bitrate remain locked.
 *
 * Sources integrated:
 *  1. Android PowerManager `currentThermalStatus` (API 29+)
 *  2. Custom thermal label string from the UI state flow
 *  3. Pipeline-level performance stability hints
 *  4. Strict high-end mode policy overrides
 *  5. Predictive Kalman Filter (slope-based thermal prediction)
 *
 * The resolver follows a conservative merge strategy: when multiple sources
 * disagree, the most restrictive decision wins. This prevents thermal overshoot
 * during high-sustained-load recording (e.g., 8K RAW + NDR10+ sidecar processing).
 */
class ThermalRuntimePolicyResolver {

    // ── State ─────────────────────────────────────────────────────────────────

    /** Last resolved decision (cached to avoid re-computation on identical inputs). */
    @Volatile
    private var lastDecision: ThermalRuntimePolicy.ThermalDecision? = null

    @Volatile
    private var lastThermalLabel: String = ""

    @Volatile
    private var lastRecordingActive: Boolean = false

    @Volatile
    private var lastStrictHighEnd: Boolean? = null

    @Volatile
    private var lastPlatformThermalStatus: Int? = null

    // ── Predictive Thermal Kalman Filter ──────────────────────────────────────
    /** Trend-based thermal prediction engine. */
    internal val kalmanFilter = PredictiveThermalKalmanFilter()

    /** Latest Kalman filter decision (encoder complexity recommendation). */
    internal val kalmanDecision: PredictiveThermalKalmanFilter.ThermalDecision
        get() = kalmanFilter.decision

    // ── Primary Resolution ────────────────────────────────────────────────────

    /**
     * Resolves the authoritative [ThermalRuntimePolicy.ThermalDecision] for the pipeline.
     *
     * @param thermalLabel     Current thermal label from the system.
     * @param recordingActive  Whether a recording session is in progress.
     * @param powerManager     Optional PowerManager for direct API-level thermal status.
     * @param strictHighEnd    Whether strict high-end mode is active.
     */
    fun resolve(
        thermalLabel: String = "",
        recordingActive: Boolean = false,
        powerManager: PowerManager? = null,
        strictHighEnd: Boolean = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
    ): ThermalRuntimePolicy.ThermalDecision {
        return resolveWithPlatformThermalStatus(
            thermalLabel = thermalLabel,
            recordingActive = recordingActive,
            platformThermalStatus = samplePlatformThermalStatus(powerManager),
            strictHighEnd = strictHighEnd
        )
    }

    /**
     * Pure decision seam for an already sampled platform status. Cache lookup,
     * policy merge, and predictive tracking therefore share one observation.
     */
    @Synchronized
    internal fun resolveWithPlatformThermalStatus(
        thermalLabel: String,
        recordingActive: Boolean,
        platformThermalStatus: Int?,
        strictHighEnd: Boolean
    ): ThermalRuntimePolicy.ThermalDecision {
        // Cache hit — avoid recomputation on identical inputs
        val cached = lastDecision
        if (thermalLabel == lastThermalLabel &&
            recordingActive == lastRecordingActive &&
            strictHighEnd == lastStrictHighEnd &&
            platformThermalStatus == lastPlatformThermalStatus &&
            cached != null
        ) {
            return cached
        }

        // Primary decision from label string
        val labelDecision = ThermalRuntimePolicy.resolve(
            thermalLabel = thermalLabel,
            recordingActive = recordingActive,
            strictHighEnd = strictHighEnd
        )

        // Secondary decision from PowerManager API (Android 10+)
        val apiDecision: ThermalRuntimePolicy.ThermalDecision? = platformThermalStatus?.let { status ->
            val apiLabel = thermalStatusToLabel(status)
            if (apiLabel != thermalLabel) {
                ThermalRuntimePolicy.resolve(
                    thermalLabel = apiLabel,
                    recordingActive = recordingActive,
                    strictHighEnd = strictHighEnd
                )
            } else null
        }

        // Merge: most conservative wins
        val merged = if (apiDecision != null) {
            ThermalRuntimePolicy.merge(labelDecision, apiDecision)
        } else {
            labelDecision
        }

        // ── Predictive Thermal Kalman Filter update ──────────────────────
        // Feed the Kalman filter with every thermal measurement so it
        // continuously tracks the temperature slope for proactive mitigation.
        kalmanFilter.updateFromLabel(thermalLabel)
        // Also feed from PowerManager API if available (higher fidelity)
        platformThermalStatus?.let(kalmanFilter::update)

        lastThermalLabel = thermalLabel
        lastRecordingActive = recordingActive
        lastStrictHighEnd = strictHighEnd
        lastPlatformThermalStatus = platformThermalStatus
        lastDecision = merged
        return merged
    }

    /**
     * Convenience overload: resolves using only a PowerManager instance.
     * Used when no custom thermal label is available.
     */
    fun resolveFromSystem(
        powerManager: PowerManager,
        recordingActive: Boolean = false
    ): ThermalRuntimePolicy.ThermalDecision {
        val platformThermalStatus = samplePlatformThermalStatus(powerManager)
        val statusLabel = platformThermalStatus?.let(::thermalStatusToLabel).orEmpty()
        return resolveWithPlatformThermalStatus(
            thermalLabel = statusLabel,
            recordingActive = recordingActive,
            platformThermalStatus = platformThermalStatus,
            strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        )
    }

    /**
     * Resolves a thermal decision from a [ThermalStatus] enum value.
     */
    fun resolveFromStatus(
        status: ThermalStatus,
        recordingActive: Boolean = false
    ): ThermalRuntimePolicy.ThermalDecision =
        resolve(
            thermalLabel = status.label,
            recordingActive = recordingActive
        )

    /**
     * Forces a specific thermal label without cache — used for testing
     * and when the pipeline explicitly overrides the thermal state.
     */
    @Synchronized
    fun forceResolve(
        thermalLabel: String,
        recordingActive: Boolean = false
    ): ThermalRuntimePolicy.ThermalDecision {
        invalidateCache()
        return resolve(thermalLabel, recordingActive)
    }

    /**
     * Returns the current cached decision without recomputation.
     * Returns a safe default if no decision has been made yet.
     */
    @Synchronized
    fun currentDecision(): ThermalRuntimePolicy.ThermalDecision =
        lastDecision ?: ThermalRuntimePolicy.resolve("", false)

    /**
     * Invalidates the cached decision, forcing recomputation on next call.
     */
    @Synchronized
    fun invalidateCache() {
        lastDecision = null
        lastThermalLabel = ""
        lastRecordingActive = false
        lastStrictHighEnd = null
        lastPlatformThermalStatus = null
    }

    // ── Bitrate Mitigation ────────────────────────────────────────────────────

    /**
     * Resolves bitrate mitigation feedback for the current thermal state.
     *
     * @param bitrateReduced Whether the encoder has already reduced bitrate.
     */
    fun resolveBitrateMitigationFeedback(
        bitrateReduced: Boolean
    ): ThermalRuntimePolicy.ThermalMitigationFeedback {
        val severity = lastDecision?.thermalSeverity ?: 0
        return ThermalRuntimePolicy.resolveBitrateMitigationFeedback(bitrateReduced, severity)
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private fun samplePlatformThermalStatus(powerManager: PowerManager?): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            powerManager.currentThermalStatus
        } else {
            null
        }

    private fun thermalStatusToLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE      -> ""
        PowerManager.THERMAL_STATUS_LIGHT     -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE  -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE    -> "THROTTLING"
        PowerManager.THERMAL_STATUS_CRITICAL  -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN  -> "EMERGENCY"
        else -> ""
    }
}
