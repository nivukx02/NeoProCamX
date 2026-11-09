package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

/**
 * HighEndRuntimeTier — Cinema pipeline runtime capability classification.
 *
 * Determines the processing power, algorithm complexity, and feature set
 * available to the active pipeline stage. Each tier corresponds to a
 * specific set of capabilities that scale based on device thermal state,
 * battery charge, hardware generation, and user-selected quality profile.
 *
 * Tier hierarchy (ascending capability):
 *   BASIC → STANDARD → PROFESSIONAL → CINEMA → ULTRA → FLAGSHIP
 *
 * The active tier is re-evaluated at each pipeline tick and may degrade
 * gracefully under thermal pressure or recover as conditions improve.
 */
enum class HighEndRuntimeTier(
    /** Human-readable label for UI and logging. */
    val label: String,
    /** Maximum allowed processing time per frame (ms). */
    val maxFrameBudgetMs: Long,
    /** GPU LUT size tier supported (n³ where n = this value). */
    val maxLutSize: Int,
    /** Maximum AI inference output resolution (long edge). */
    val maxAiOutputLongEdge: Int,
    /** Whether Vulkan acceleration is enabled at this tier. */
    val vulkanEnabled: Boolean,
    /** Whether 10-bit display pipeline is active. */
    val tenBitDisplay: Boolean,
    /** Maximum parallel codec streams allowed. */
    val maxParallelStreams: Int,
    /** Ordinal priority (higher = better). */
    val rank: Int
) {

    /**
     * BASIC — Emergency fallback tier.
     * Software-only preview, no LUTs, no AI, minimal bitrate.
     * Activated under CRITICAL/EMERGENCY thermal states.
     */
    BASIC(
        label = "Basic",
        maxFrameBudgetMs = 66L,     // ~15 fps
        maxLutSize = 0,
        maxAiOutputLongEdge = 0,
        vulkanEnabled = false,
        tenBitDisplay = false,
        maxParallelStreams = 1,
        rank = 1
    ),

    /**
     * STANDARD — Entry-level processing.
     * 8-bit preview pipeline, compact LUT, reduced AI analysis.
     * Used for older devices or heavy thermal throttling.
     */
    STANDARD(
        label = "Standard",
        maxFrameBudgetMs = 50L,     // ~20 fps
        maxLutSize = 17,            // 17³ LUT cube
        maxAiOutputLongEdge = 640,
        vulkanEnabled = false,
        tenBitDisplay = false,
        maxParallelStreams = 1,
        rank = 2
    ),

    /**
     * PROFESSIONAL — Mainstream cinema-grade processing.
     * Vulkan preview, 33-cube LUT, full AF and AE pipeline,
     * HEVC 10-bit encoding support.
     */
    PROFESSIONAL(
        label = "Professional",
        maxFrameBudgetMs = 33L,     // 30 fps
        maxLutSize = 33,            // 33³ LUT cube (industry standard)
        maxAiOutputLongEdge = 1080,
        vulkanEnabled = true,
        tenBitDisplay = false,
        maxParallelStreams = 2,
        rank = 3
    ),

    /**
     * CINEMA — High-end cinema-grade processing.
     * Full Vulkan pipeline, 64-cube LUT, 10-bit display,
     * LOG/NDR10+/NIVUKX Vision support, multi-stream encoding.
     */
    CINEMA(
        label = "Cinema",
        maxFrameBudgetMs = 22L,     // ~45 fps headroom
        maxLutSize = 64,            // 64³ LUT cube
        maxAiOutputLongEdge = 2160,
        vulkanEnabled = true,
        tenBitDisplay = true,
        maxParallelStreams = 3,
        rank = 4
    ),

    /**
     * ULTRA — Ultra-high-end processing tier.
     * Maximum LUT complexity, Neo dynamic-range support,
     * 4K RAW video pipeline, 12-bit processing.
     */
    ULTRA(
        label = "Ultra",
        maxFrameBudgetMs = 14L,     // ~70 fps headroom (FLAGSHIP UPGRADE)
        maxLutSize = 65,            // 65³ LUT cube (RGB 16-bit boundary)
        maxAiOutputLongEdge = 5120, // FLAGSHIP: 5K AI analysis
        vulkanEnabled = true,
        tenBitDisplay = true,
        maxParallelStreams = 5,     // FLAGSHIP: 5 parallel streams
        rank = 5
    ),

    /**
     * FLAGSHIP — Maximum capability tier.
     * Full sensor-native open-gate readout, 8K RAW pipelines, 128³ LUT chains,
     * multi-camera synchronized capture, zero-lag processing.
     */
    FLAGSHIP(
        label = "Flagship Cinema",
        maxFrameBudgetMs = 6L,      // 120+ fps headroom (FLAGSHIP UPGRADE)
        maxLutSize = 128,           // 128³ LUT for maximum precision
        maxAiOutputLongEdge = 8192, // FLAGSHIP: Sensor-native AI analysis ceiling
        vulkanEnabled = true,
        tenBitDisplay = true,
        maxParallelStreams = 8,     // FLAGSHIP: 8 parallel streams
        rank = 7                   // FLAGSHIP: Highest rank
    ),

    /**
     * RECOVERY — Transitional tier between throttled and normal.
     * Conservative resource budgets while device cools down.
     */
    RECOVERY(
        label = "Recovery",
        maxFrameBudgetMs = 40L,
        maxLutSize = 33,
        maxAiOutputLongEdge = 720,
        vulkanEnabled = true,
        tenBitDisplay = false,
        maxParallelStreams = 1,
        rank = 2
    ),

    /**
     * BALANCED — Safe mid-point tier for sustained recording.
     * Provides stable 30fps with moderate LUT and AI complexity.
     */
    BALANCED(
        label = "Balanced",
        maxFrameBudgetMs = 33L,
        maxLutSize = 33,
        maxAiOutputLongEdge = 1080,
        vulkanEnabled = true,
        tenBitDisplay = false,
        maxParallelStreams = 2,
        rank = 3
    ),

    /**
     * SUSTAINED — Long-duration recording optimized tier.
     * Designed for multi-hour recording sessions without thermal spikes.
     */
    SUSTAINED(
        label = "Sustained",
        maxFrameBudgetMs = 28L,
        maxLutSize = 33,
        maxAiOutputLongEdge = 1440,
        vulkanEnabled = true,
        tenBitDisplay = true,
        maxParallelStreams = 2,
        rank = 4
    );

    // ── Derived Properties ────────────────────────────────────────────────────

    /** Whether LUT processing is available at this tier. */
    val lutEnabled: Boolean get() = maxLutSize > 0

    /** Whether AI-driven analysis pipelines are active at this tier. */
    val aiEnabled: Boolean get() = maxAiOutputLongEdge > 0

    /** Whether HDR10 display pipeline is available. */
    val hdr10Capable: Boolean get() = tenBitDisplay && rank >= CINEMA.rank

    /** Whether advanced Neo dynamic-range processing is available. */
    val neoDynamicRangeCapable: Boolean get() = rank >= ULTRA.rank

    /** Whether this tier supports 4K output. */
    val supports4KOutput: Boolean get() = rank >= CINEMA.rank

    /** Whether LOG video recording is fully supported. */
    val supportsLogRecording: Boolean get() = rank >= PROFESSIONAL.rank

    /** Returns whether this tier is higher than [other]. */
    fun isHigherThan(other: HighEndRuntimeTier): Boolean = rank > other.rank

    /** Returns whether this tier is at least as capable as [other]. */
    fun isAtLeast(other: HighEndRuntimeTier): Boolean = rank >= other.rank

    // ── Static Helpers ────────────────────────────────────────────────────────

    companion object {

        /**
         * Resolves the appropriate tier from the global [HighEndQualityPolicy].
         */
        fun fromPolicy(): HighEndRuntimeTier {
            if (!HighEndQualityPolicy.enabled) return STANDARD
            // FLAGSHIP: Strict mode -> FLAGSHIP tier for maximum capability
            return if (HighEndQualityPolicy.strictHighEndOnly) FLAGSHIP else CINEMA
        }

        /**
         * Resolves the tier based on a [ThermalState], capping the pipeline
         * at a safe operating tier for the given thermal condition.
         */
        fun fromThermalState(thermal: ThermalState): HighEndRuntimeTier = when (thermal) {
            ThermalState.OPTIMAL   -> FLAGSHIP  // FLAGSHIP: Max tier at optimal thermal
            ThermalState.NOMINAL   -> ULTRA     // FLAGSHIP: Ultra at nominal
            ThermalState.BALANCED  -> CINEMA    // FLAGSHIP: Cinema at balanced
            ThermalState.ELEVATED  -> SUSTAINED
            ThermalState.SUSTAINED -> BALANCED
            ThermalState.RECOVERY  -> RECOVERY
            ThermalState.THROTTLED -> STANDARD
            ThermalState.CRITICAL  -> BASIC
            ThermalState.EMERGENCY -> BASIC
        }

        /**
         * Returns the minimum (most conservative) of two tiers.
         * Useful for combining policy-driven and thermal-driven tier decisions.
         */
        fun min(a: HighEndRuntimeTier, b: HighEndRuntimeTier): HighEndRuntimeTier =
            if (a.rank <= b.rank) a else b

        /**
         * Returns the maximum (most capable) of two tiers.
         */
        fun max(a: HighEndRuntimeTier, b: HighEndRuntimeTier): HighEndRuntimeTier =
            if (a.rank >= b.rank) a else b

        /** The default safe operating tier. */
        /** FLAGSHIP: Default to CINEMA tier for premium defaults. */
        val default: HighEndRuntimeTier get() = CINEMA
    }
}
