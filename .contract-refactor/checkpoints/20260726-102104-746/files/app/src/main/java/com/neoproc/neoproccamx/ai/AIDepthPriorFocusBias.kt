package com.neoproc.neoproccamx.ai

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * CP-F6 (s5-f05) — DEPTH-PRIOR HYBRID AF BIAS (region-capable devices)
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * On phones that report `maxAfRegions > 0` the [AIFocusDistanceController] PID is intentionally
 * NOT allowed to take over the lens (that would fight the superior hardware PDAF/Dual-Pixel servo).
 * This policy is the *rangefinder-style* alternative used by professional focus pullers (cmotion
 * cPRO, ARRI Hi-5, Preston light-ranger): a depth PRIOR is used only to point hardware AF in the
 * right direction first, never to seize continuous servo.
 *
 * It is a **pure decision function** (no Android, no camera handle) so it is unit-testable in
 * isolation, exactly like [LogFocusResponsivenessPolicy].
 *
 * ACTIVATION: both gates default ON (user-directed 2026-07-04); it consumes the live MiDaS depth.
 * The path is bounded-safe by construction: with [enabled] == false the decision is a strict no-op
 * ([BiasDecision.NO_OP]) reproducing today's request bit-for-bit; when on it only biases the AF/AE
 * metering REGION (never seizes the servo) and only above MIN_BIAS_CONFIDENCE (0.55, > the controller's
 * 0.42, so strictly more conservative). The one-shot lens-distance hint ([oneShotLensHintEnabled]) may
 * fire AT MOST ONCE per acquisition (the [lensHintLatched] guard) clamped to the lens ceiling, so it can
 * never become a continuous lens drive that fights the hardware servo. Device caveat: focus-pull ACCURACY
 * (that the region bias improves, never worsens, acquisition) is device-observed — verify on a focus-pull.
 */
internal object AIDepthPriorFocusBias {

    /** Default state of the depth-prior hybrid: ON (user-directed 2026-07-04). OFF = bit-for-bit baseline. */
    const val ENABLED_DEFAULT = true

    /** Default state of the optional one-shot lens-distance direction hint: ON (user-directed 2026-07-04). */
    const val ONE_SHOT_LENS_HINT_DEFAULT = true

    /**
     * Minimum fused depth/edge/subject confidence (the value returned by
     * [AIFocusDistanceController.resolveDepthAwareFocusStep]'s `confidence`) required before the
     * depth prior is trusted enough to bias hardware AF. Below this the path degrades to the exact
     * hardware-AF baseline (no-op). Chosen above the controller's internal
     * MIN_DEPTH_FOCUS_CONFIDENCE (0.42) so the region bias is strictly more conservative than the
     * region-less PID would be — we only bias when we are clearly confident.
     */
    const val MIN_BIAS_CONFIDENCE = 0.55f

    /**
     * Device-side master flag. The owner flips this to true on the vivo I2405 / Adreno after
     * validating that the depth-derived metering region improves (never worsens) acquisition.
     */
    @JvmField
    var enabled = ENABLED_DEFAULT

    /**
     * Device-side sub-flag for the one-shot lens-distance direction hint. Independent of [enabled]
     * and OFF by default; the metering-region bias is the safe primary mechanism and works with
     * this off. Only enable after validating the one-shot clear on device (see device-gated notes).
     */
    @JvmField
    var oneShotLensHintEnabled = ONE_SHOT_LENS_HINT_DEFAULT

    /**
     * The decision for one frame on a region-capable device.
     *
     * @param applyRegionBias       place the depth-derived AF/AE metering region this frame.
     * @param fireOneShotLensHint    emit a single bounded lens-distance hint this frame (never on a
     *                               subsequent frame of the same acquisition).
     * @param directionDiopters      the depth-derived target lens diopters (the "which way" prior);
     *                               only meaningful when [fireOneShotLensHint] is true.
     * @param latchHint              true when the one-shot hint just fired and the caller must set
     *                               its latch so the hint is not re-emitted until re-acquisition.
     */
    data class BiasDecision(
        val applyRegionBias: Boolean,
        val fireOneShotLensHint: Boolean,
        val directionDiopters: Float,
        val latchHint: Boolean
    ) {
        companion object {
            /** Exact hardware-AF baseline: change nothing. */
            val NO_OP = BiasDecision(
                applyRegionBias = false,
                fireOneShotLensHint = false,
                directionDiopters = -1f,
                latchHint = false
            )
        }
    }

    /**
     * Resolve the per-frame depth-prior bias decision. PURE — no side effects.
     *
     * @param enabledFlag           master gate (caller passes [enabled]).
     * @param oneShotEnabledFlag     one-shot lens-hint sub-gate (caller passes [oneShotLensHintEnabled]).
     * @param hasDepthTarget         true when resolveDepthAwareFocusStep returned a non-null
     *                               targetDiopters (i.e. depth/edge evidence cleared its own gate).
     * @param depthConfidence        the fused confidence from the depth step (0..1).
     * @param targetDiopters         the depth-derived target lens diopters (>= 0 when [hasDepthTarget]).
     * @param maxDiopters            the lens diopter ceiling (camera.minFocusDist); <= 0 disables.
     * @param lensHintLatched        true if the one-shot hint has already fired this acquisition.
     */
    fun resolve(
        enabledFlag: Boolean,
        oneShotEnabledFlag: Boolean,
        hasDepthTarget: Boolean,
        depthConfidence: Float,
        targetDiopters: Float,
        maxDiopters: Float,
        lensHintLatched: Boolean
    ): BiasDecision {
        // Master gate OFF => exact baseline (today's no-op). No-downgrade default.
        if (!enabledFlag) return BiasDecision.NO_OP
        // Depth evidence absent (acquisition lost / low edge) => exact baseline.
        if (!hasDepthTarget) return BiasDecision.NO_OP
        // Low fused confidence => degrade to pure hardware AF (identical request to baseline).
        if (depthConfidence < MIN_BIAS_CONFIDENCE) return BiasDecision.NO_OP

        // High confidence: bias the hardware-AF metering region toward the depth-derived subject.
        val boundedDirection =
            if (maxDiopters > 0f) targetDiopters.coerceIn(0f, maxDiopters) else -1f

        // The one-shot lens-distance direction hint may fire at most once per acquisition, only when
        // its sub-gate is on and it has not already been latched. It NEVER drives continuously.
        val canHint =
            oneShotEnabledFlag && !lensHintLatched && maxDiopters > 0f && boundedDirection >= 0f

        return BiasDecision(
            applyRegionBias = true,
            fireOneShotLensHint = canHint,
            directionDiopters = boundedDirection,
            latchHint = canHint
        )
    }
}
