package com.neoproc.neoproccamx.pipeline

/**
 * ThermalState — Cinema-grade thermal management state machine.
 *
 * Maps Android PowerManager thermal status levels to NeoProCamX pipeline
 * states, providing fine-grained thermal headroom management for
 * sustained 4K/8K video recording and LOG/HDR processing.
 *
 * Pipeline decisions driven by this state affect:
 * - Preview resolution and analysis FPS
 * - GPU LUT complexity
 * - RAW video backpressure budget
 * - Encoding bitrate
 * - AI inference rate
 *
 * Transition model (from highest to lowest performance):
 *   OPTIMAL → NOMINAL → ELEVATED → SUSTAINED → THROTTLED → CRITICAL → EMERGENCY
 */
enum class ThermalState(
    /** Human-readable label for UI and logging. */
    val label: String,
    /** Maximum fraction of full resources to allocate (0.0–1.0). */
    val resourceCeiling: Float,
    /** Whether a UI warning should be displayed. */
    val requiresUserWarning: Boolean,
    /** Whether pipeline must enter emergency fallback mode. */
    val isEmergency: Boolean,
    /** Priority score (higher = better performance budget). */
    val priority: Int
) {

    /** Peak performance — cool device, full resources, unrestricted. */
    OPTIMAL(
        label = "Optimal",
        resourceCeiling = 1.00f,
        requiresUserWarning = false,
        isEmergency = false,
        priority = 7
    ),

    /** Normal operating temperature — full cinema features active. */
    NOMINAL(
        label = "Nominal",
        resourceCeiling = 0.95f,
        requiresUserWarning = false,
        isEmergency = false,
        priority = 6
    ),

    /** Slightly elevated — minor reductions begin (AI analysis FPS). */
    ELEVATED(
        label = "Elevated",
        resourceCeiling = 0.85f,
        requiresUserWarning = false,
        isEmergency = false,
        priority = 5
    ),

    /**
     * Sustained thermal load — Android PowerManager THERMAL_STATUS_MODERATE.
     * Reduce preview analysis FPS, defer non-critical operations.
     */
    SUSTAINED(
        label = "Sustained",
        resourceCeiling = 0.72f,
        requiresUserWarning = false,
        isEmergency = false,
        priority = 4
    ),

    /**
     * Active throttling — Android PowerManager THERMAL_STATUS_SEVERE.
     * Drop preview resolution, warn user, reduce encode bitrate.
     */
    THROTTLED(
        label = "Throttling",
        resourceCeiling = 0.55f,
        requiresUserWarning = true,
        isEmergency = false,
        priority = 3
    ),

    /**
     * Critical thermal state — Android PowerManager THERMAL_STATUS_CRITICAL.
     * Drop to minimum-viable pipeline, force user notification.
     */
    CRITICAL(
        label = "Critical",
        resourceCeiling = 0.35f,
        requiresUserWarning = true,
        isEmergency = false,
        priority = 2
    ),

    /**
     * Emergency state — imminent shutdown risk.
     * Stop recording, release GPU resources, notify user immediately.
     */
    EMERGENCY(
        label = "Emergency — Stop Recording",
        resourceCeiling = 0.10f,
        requiresUserWarning = true,
        isEmergency = true,
        priority = 1
    ),

    /**
     * Recovery — transitioning back from a hot state.
     * Resources restored gradually to prevent thermal oscillation.
     */
    RECOVERY(
        label = "Recovering",
        resourceCeiling = 0.78f,
        requiresUserWarning = false,
        isEmergency = false,
        priority = 4
    ),

    /**
     * Balanced — normal operation with conservative headroom.
     * Used as a safe default when thermal data is unavailable.
     */
    BALANCED(
        label = "Balanced",
        resourceCeiling = 0.88f,
        requiresUserWarning = false,
        isEmergency = false,
        priority = 5
    );

    // ── Derived Properties ────────────────────────────────────────────────────

    /** Whether the device is hot enough to constrain the pipeline. */
    val isConstrained: Boolean
        get() = resourceCeiling < 0.80f

    /** Whether the pipeline must shed load immediately. */
    val requiresImmediateAction: Boolean
        get() = resourceCeiling < 0.60f

    /** Max analysis FPS cap for this thermal state (normalized to 30fps baseline). */
    val maxAnalysisFps: Int
        get() = (30 * resourceCeiling).toInt().coerceAtLeast(5)

    /** Max preview resolution long-edge for this state. */
    val maxPreviewLongEdge: Int
        get() = when {
            resourceCeiling >= 0.90f -> 8192 // Sensor-native preview ceiling
            resourceCeiling >= 0.80f -> 3840
            resourceCeiling >= 0.65f -> 2560
            resourceCeiling >= 0.50f -> 1920
            else                     -> 1280
        }

    /** Whether 4K preview is allowed at this thermal state. */
    val allows4KPreview: Boolean
        get() = maxPreviewLongEdge >= 3840

    /** Whether RAW video capture should be throttled at this state. */
    val shouldThrottleRaw: Boolean
        get() = resourceCeiling < 0.65f

    /** Whether AI inference should be reduced at this state. */
    val shouldReduceAiInference: Boolean
        get() = resourceCeiling < 0.85f

    // ── State Transition Helpers ──────────────────────────────────────────────

    /** True if this state is more severe than [other]. */
    fun isHotterThan(other: ThermalState): Boolean = priority < other.priority

    /** True if this state is cooler (better performance) than [other]. */
    fun isCoolerThan(other: ThermalState): Boolean = priority > other.priority

    companion object {
        /**
         * Maps Android PowerManager thermal status integer to a [ThermalState].
         * Handles all THERMAL_STATUS_* constants defined in Android API 29+.
         */
        fun fromAndroidStatus(status: Int): ThermalState = when (status) {
            0 -> OPTIMAL       // THERMAL_STATUS_NONE
            1 -> NOMINAL       // THERMAL_STATUS_LIGHT
            2 -> ELEVATED      // THERMAL_STATUS_MODERATE
            3 -> THROTTLED     // THERMAL_STATUS_SEVERE
            4 -> CRITICAL      // THERMAL_STATUS_CRITICAL
            5 -> EMERGENCY     // THERMAL_STATUS_EMERGENCY
            6 -> EMERGENCY     // THERMAL_STATUS_SHUTDOWN
            else -> BALANCED
        }

        /**
         * Merges two thermal states, taking the more conservative (hotter) one.
         * Used when combining battery and CPU thermal signals.
         */
        fun merge(a: ThermalState, b: ThermalState): ThermalState =
            if (a.isHotterThan(b)) a else b

        /** Returns the most performant (coolest) state. */
        val best: ThermalState get() = OPTIMAL

        /** Returns the safest (most conservative) state. */
        val safest: ThermalState get() = BALANCED
    }
}
