package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

/**
 * ThermalRuntimePolicy — cinema-oriented thermal policy and mitigation engine.
 *
 * Translates device thermal state labels from the Android PowerManager
 * (THERMAL_STATUS_*) into actionable pipeline decisions for NeoProCamX.
 * Each decision affects:
 *
 *  - Preview analysis FPS cap
 *  - Preview resolution maximum long-edge
 *  - Encoding bitrate headroom
 *  - AI inference rate
 *  - GPU LUT complexity
 *  - RAW video admission throttling
 *  - User warning visibility
 *
 * Designed for multi-hour cinematic recording on mobile SoCs with
 * power envelopes of 5–12W continuous.
 */
object ThermalRuntimePolicy {

    // ── Constants ─────────────────────────────────────────────────────────────

    private const val ANALYSIS_FPS_OPTIMAL   = 30
    private const val ANALYSIS_FPS_NOMINAL   = 30
    private const val ANALYSIS_FPS_ELEVATED  = 24
    private const val ANALYSIS_FPS_SUSTAINED = 20
    private const val ANALYSIS_FPS_THROTTLED = 15
    private const val ANALYSIS_FPS_CRITICAL  = 10
    private const val ANALYSIS_FPS_EMERGENCY = 5

    private const val RECORDING_FPS_DIVISOR  = 2   // halve FPS if recording active

    private const val PREVIEW_EDGE_OPTIMAL   = 8192 // Sensor-native preview ceiling
    private const val PREVIEW_EDGE_NOMINAL   = 3840
    private const val PREVIEW_EDGE_ELEVATED  = 3840
    private const val PREVIEW_EDGE_SUSTAINED = 2560
    private const val PREVIEW_EDGE_THROTTLED = 1920
    private const val PREVIEW_EDGE_CRITICAL  = 1280
    private const val PREVIEW_EDGE_EMERGENCY = 854

    // ── Data Models ───────────────────────────────────────────────────────────

    /**
     * Actionable pipeline decision derived from thermal status.
     *
     * @param analysisFpsCap       Maximum AI/analysis FPS allowed.
     * @param previewMaxLongEdge   Maximum preview long-edge resolution.
     * @param shouldApplyPreviewGuard Whether to drop to low-res preview mode.
     * @param shouldThrottleBitrate   Whether encoder bitrate should be reduced.
     * @param shouldReduceAiLoad      Whether to reduce AI inference rate.
     * @param shouldThrottleRaw       Whether RAW video pipeline should throttle.
     * @param allowLut               Whether GPU LUT processing is permitted.
     * @param allowHighEndCodec      Whether HEVC/AV1 high-complexity modes are permitted.
     * @param thermalLabel           Human-readable label for UI/logging.
     * @param thermalSeverity        0=none, 1=mild, 2=moderate, 3=severe, 4=critical, 5=emergency.
     */
    data class ThermalDecision(
        val analysisFpsCap: Int,
        val previewMaxLongEdge: Int,
        val shouldApplyPreviewGuard: Boolean = false,
        val shouldThrottleBitrate: Boolean = false,
        val shouldReduceAiLoad: Boolean = false,
        val shouldThrottleRaw: Boolean = false,
        val allowLut: Boolean = true,
        val allowHighEndCodec: Boolean = true,
        val thermalLabel: String = "Nominal",
        val thermalSeverity: Int = 0
    ) {
        val requiresUserWarning: Boolean
            get() = thermalSeverity >= 3

        val isEmergency: Boolean
            get() = thermalSeverity >= 5

        val bitrateReductionFactor: Float
            get() = when (thermalSeverity) {
                0, 1 -> 1.00f
                2    -> 0.90f
                3    -> 0.75f
                4    -> 0.55f
                else -> 0.35f
            }
    }

    /**
     * Mitigation feedback for bitrate reduction events.
     *
     * @param warningMessage          Non-null if a user warning should be shown.
     * @param shouldRaiseCriticalError True if pipeline should raise a critical fault.
     * @param recommendedBitrateScale Suggested bitrate multiplier (0.0–1.0).
     */
    data class ThermalMitigationFeedback(
        val warningMessage: String? = null,
        val shouldRaiseCriticalError: Boolean = false,
        val recommendedBitrateScale: Float = 1.0f,
        val recommendedPreviewEdge: Int = PREVIEW_EDGE_NOMINAL
    )

    // ── Core Resolution API ───────────────────────────────────────────────────

    /**
     * Resolves a [ThermalDecision] from the Android thermal label string.
     *
     * @param thermalLabel    String from PowerManager.OnThermalStatusChangedListener
     *                        (e.g. "NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL",
     *                        "EMERGENCY", "SHUTDOWN").
     * @param recordingActive Whether a recording session is in progress.
     * @param strictHighEnd   Whether the strict high-end pipeline is enabled.
     */
    fun resolve(
        thermalLabel: String,
        recordingActive: Boolean,
        strictHighEnd: Boolean = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
    ): ThermalDecision {
        val severity = parseSeverity(thermalLabel)
        val baseFpsCap = baseFpsForSeverity(severity)
        val adjustedFpsCap = if (recordingActive) {
            maxOf(baseFpsCap / RECORDING_FPS_DIVISOR, ANALYSIS_FPS_EMERGENCY)
        } else {
            baseFpsCap
        }
        val basePreviewEdge = previewEdgeForSeverity(severity)
        val adjustedPreviewEdge = if (recordingActive && severity >= 2) {
            minOf(basePreviewEdge, PREVIEW_EDGE_THROTTLED)
        } else {
            basePreviewEdge
        }
        val thermalProtectionActive = severity >= 2
        val highEndMultiplier = if (strictHighEnd && !thermalProtectionActive) 1.15f else 1.0f
        val finalPreviewEdge = (adjustedPreviewEdge * highEndMultiplier)
            .toInt()
            .coerceAtMost(if (strictHighEnd) PREVIEW_EDGE_OPTIMAL else PREVIEW_EDGE_NOMINAL)

        return ThermalDecision(
            analysisFpsCap = if (strictHighEnd && !thermalProtectionActive) 60 else adjustedFpsCap,
            previewMaxLongEdge = finalPreviewEdge,
            shouldApplyPreviewGuard = severity >= 3,
            shouldThrottleBitrate = !strictHighEnd && (severity >= 2 || (recordingActive && severity >= 1)),
            shouldReduceAiLoad = severity >= 1,
            shouldThrottleRaw = severity >= 3,
            allowLut = strictHighEnd || severity < 2,
            allowHighEndCodec = strictHighEnd || severity < 4,
            thermalLabel = labelForSeverity(severity),
            thermalSeverity = severity
        )
    }

    /**
     * Resolves mitigation feedback for a bitrate reduction event.
     *
     * @param bitrateReduced Whether the encoder has already reduced bitrate.
     * @param currentSeverity Current thermal severity level.
     */
    fun resolveBitrateMitigationFeedback(
        bitrateReduced: Boolean,
        currentSeverity: Int = 2
    ): ThermalMitigationFeedback {
        if (!bitrateReduced) return ThermalMitigationFeedback()
        return when (currentSeverity) {
            0, 1 -> ThermalMitigationFeedback(
                warningMessage = null,
                recommendedBitrateScale = 0.90f,
                recommendedPreviewEdge = PREVIEW_EDGE_NOMINAL
            )
            2 -> ThermalMitigationFeedback(
                warningMessage = "Thermal Throttling: Bitrate Reduced",
                recommendedBitrateScale = 0.75f,
                recommendedPreviewEdge = PREVIEW_EDGE_SUSTAINED
            )
            3 -> ThermalMitigationFeedback(
                warningMessage = "⚠ Thermal Throttling — Bitrate Reduced",
                shouldRaiseCriticalError = false,
                recommendedBitrateScale = 0.55f,
                recommendedPreviewEdge = PREVIEW_EDGE_THROTTLED
            )
            4 -> ThermalMitigationFeedback(
                warningMessage = "🔴 Critical Thermal — Pipeline Degraded",
                shouldRaiseCriticalError = false,
                recommendedBitrateScale = 0.35f,
                recommendedPreviewEdge = PREVIEW_EDGE_CRITICAL
            )
            else -> ThermalMitigationFeedback(
                warningMessage = "🔴 Emergency — Stop Recording Immediately",
                shouldRaiseCriticalError = true,
                recommendedBitrateScale = 0.20f,
                recommendedPreviewEdge = PREVIEW_EDGE_EMERGENCY
            )
        }
    }

    /**
     * Merges two thermal decisions, taking the more conservative values.
     * Used when combining CPU and battery thermal signals.
     */
    fun merge(a: ThermalDecision, b: ThermalDecision): ThermalDecision {
        val higher = if (a.thermalSeverity >= b.thermalSeverity) a else b
        return higher.copy(
            analysisFpsCap = minOf(a.analysisFpsCap, b.analysisFpsCap),
            previewMaxLongEdge = minOf(a.previewMaxLongEdge, b.previewMaxLongEdge)
        )
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private fun parseSeverity(label: String): Int = when {
        label.contains("emergency", ignoreCase = true) ||
            label.contains("shutdown", ignoreCase = true)  -> 5
        label.contains("critical", ignoreCase = true)      -> 4
        label.contains("severe", ignoreCase = true) ||
            label.contains("throttling", ignoreCase = true) -> 3
        label.contains("moderate", ignoreCase = true) ||
            label.contains("elevated", ignoreCase = true)  -> 2
        label.contains("light", ignoreCase = true) ||
            label.contains("warning", ignoreCase = true)   -> 1
        else                                               -> 0
    }

    private fun baseFpsForSeverity(severity: Int): Int = when (severity) {
        0 -> ANALYSIS_FPS_OPTIMAL
        1 -> ANALYSIS_FPS_NOMINAL
        2 -> ANALYSIS_FPS_ELEVATED
        3 -> ANALYSIS_FPS_SUSTAINED
        4 -> ANALYSIS_FPS_CRITICAL
        else -> ANALYSIS_FPS_EMERGENCY
    }

    private fun previewEdgeForSeverity(severity: Int): Int = when (severity) {
        0 -> PREVIEW_EDGE_OPTIMAL
        1 -> PREVIEW_EDGE_NOMINAL
        2 -> PREVIEW_EDGE_ELEVATED
        3 -> PREVIEW_EDGE_SUSTAINED
        4 -> PREVIEW_EDGE_THROTTLED
        5 -> PREVIEW_EDGE_CRITICAL
        else -> PREVIEW_EDGE_EMERGENCY
    }

    private fun labelForSeverity(severity: Int): String = when (severity) {
        0 -> "Optimal"
        1 -> "Nominal"
        2 -> "Elevated"
        3 -> "Throttling"
        4 -> "Critical"
        5 -> "Emergency"
        else -> "Unknown"
    }
}

/**
 * Thermal status levels used by NeoProCamX's UI and pipeline coordination layers.
 */
enum class ThermalStatus(
    /** Human-readable label. */
    val label: String,
    /** Severity: 0=none, 5=emergency. */
    val severity: Int
) {
    NORMAL("Normal", 0),
    ELEVATED("Elevated", 1),
    THROTTLING("Throttling", 2),
    CRITICAL("Critical", 3),
    EMERGENCY("Emergency", 4);

    val requiresWarning: Boolean get() = severity >= 2
    val isEmergency: Boolean get() = severity >= 4

    companion object {
        fun fromSeverity(severity: Int): ThermalStatus =
            entries.firstOrNull { it.severity == severity } ?: NORMAL

        fun fromLabel(label: String): ThermalStatus =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: NORMAL
    }
}
