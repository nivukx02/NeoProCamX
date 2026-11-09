package com.neoproc.neoproccamx.ai

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * CP94 (s7-f03) — DEPTH TEMPORAL GATE + CONFIDENCE HONESTY POLICY
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Two purely-functional, Android-free decisions for the MiDaS depth path:
 *
 *  1. SCENE-CUT GATE — bounded temporal fusion. The existing fusion (DepthEstimator's
 *     applyTemporalFusion + motionAdaptiveTemporalDepthSmoothing) blends every frame toward the
 *     previous one. On a HARD CUT that lags: the new scene is dragged toward the old depth for K
 *     frames. This gate detects a cut from the per-frame mean depth-motion that DepthEstimator
 *     already computes (estimateDepthMotion) and tells the caller to DROP history for that frame
 *     (use the current depth verbatim, no blend) so the field snaps to the new scene.
 *
 *  2. CONFIDENCE HONESTY — the synthetic radial fallback (model unavailable / inference failed) is
 *     NOT MiDaS output and must not be trusted like one. This policy exposes an OPT-IN multiplier
 *     focus-scoring consumers may apply to depth confidence when a frame is flagged synthetic, so
 *     a relative-from-nothing field is down-weighted rather than silently driving focus.
 *
 * NO-DOWNGRADE CONTRACT: [enabled] defaults OFF. With it off, [resolveFusion] returns
 * [FusionDecision.PASSTHROUGH] (keep history, blend exactly as today) and [confidenceScaleFor]
 * returns 1f for every frame — so the live depth field and every consumer are reproduced
 * bit-for-bit. The thresholds below are only consulted when the owner flips [enabled] true
 * device-side after validating that a real cut is followed within K frames with no static-scene
 * false-trips.
 */
internal object DepthTemporalGatePolicy {

    /** Master gate: OFF == today's unbounded fusion + full trust of every frame (bit-for-bit). */
    const val ENABLED_DEFAULT = false

    /**
     * Mean per-frame depth motion (0..1, same metric as DepthEstimator.estimateDepthMotion) above
     * which the change is treated as a SCENE CUT rather than subject/camera motion. Calibrated
     * conservatively high so ordinary motion never trips it; only a hard cut clears it.
     * ENGINEERING PLACEHOLDER — DEVICE-CALIBRATED (only consulted when [enabled] is true).
     */
    const val SCENE_CUT_MOTION_THRESHOLD = 0.45f

    /**
     * Confidence multiplier applied by consumers to a SYNTHETIC-FALLBACK frame's depth confidence.
     * < 1 down-weights the fabricated radial field so it cannot dominate focus scoring; the real
     * MiDaS path is always scaled by 1f. Only consulted when [enabled] is true.
     */
    const val SYNTHETIC_CONFIDENCE_SCALE = 0.5f

    /**
     * Device-side master flag. Owner flips true on vivo I2405 / Adreno after validating bounded
     * fusion follows a real cut and synthetic frames are correctly de-emphasised.
     */
    @JvmField
    var enabled = ENABLED_DEFAULT

    /** Per-frame fusion decision. PASSTHROUGH == today's behavior (keep & blend history). */
    enum class FusionDecision {
        /** Blend with previous depth exactly as the current live path does. */
        PASSTHROUGH,

        /** Hard cut detected: drop history this frame, emit current depth verbatim (no lag-drag). */
        DROP_HISTORY
    }

    /**
     * Resolve the fusion decision for one frame. PURE.
     *
     * @param enabledFlag      caller passes [enabled].
     * @param hasPrevious      true when a fusable previous depth map exists.
     * @param meanDepthMotion  the per-frame mean abs depth delta (DepthEstimator.estimateDepthMotion).
     */
    fun resolveFusion(
        enabledFlag: Boolean,
        hasPrevious: Boolean,
        meanDepthMotion: Float
    ): FusionDecision {
        // Gate OFF or no history => exact baseline.
        if (!enabledFlag || !hasPrevious) return FusionDecision.PASSTHROUGH
        val motion = meanDepthMotion.coerceIn(0f, 1f)
        return if (motion >= SCENE_CUT_MOTION_THRESHOLD) {
            FusionDecision.DROP_HISTORY
        } else {
            FusionDecision.PASSTHROUGH
        }
    }

    /**
     * Confidence multiplier a consumer should apply for a frame. PURE.
     * Returns 1f (no change) unless the gate is on AND the frame is the synthetic fallback.
     *
     * @param enabledFlag       caller passes [enabled].
     * @param syntheticFallback true when the DepthData was produced by the radial fallback.
     */
    fun confidenceScaleFor(enabledFlag: Boolean, syntheticFallback: Boolean): Float {
        if (!enabledFlag || !syntheticFallback) return 1f
        return SYNTHETIC_CONFIDENCE_SCALE
    }

    /**
     * Convenience: is the supplied per-frame motion a scene cut under the active gate? PURE.
     * Mirrors [resolveFusion] for tests/consumers that only need the boolean.
     */
    fun isSceneCut(enabledFlag: Boolean, hasPrevious: Boolean, meanDepthMotion: Float): Boolean =
        resolveFusion(enabledFlag, hasPrevious, meanDepthMotion) == FusionDecision.DROP_HISTORY
}
