package com.neoproc.neoproccamx.rendering

/**
 * s3-f04 / CP91 — the consolidated OmniClarity motion-stack tuning brain.
 *
 * Per-frame the adaptive thermal/quality governor hands this resolver its signals (thermal headroom,
 * frame-time budget, scene-cut state, measured noise) and it combines the three pure stage policies —
 * [ArgusFlowStagePolicy] (refine-iteration lever), [ChronosTemporalPolicy] (temporal-alpha lever +
 * scene-cut gating), [RivenDeblurPolicy] (noise-aware deblur lever) — into one [OmniClarityMotionTuning]
 * that the live native dispatch consumes via `VulkanBridge.applyOmniClarityMotionTuning`.
 *
 * NO-DOWNGRADE CONTRACT: with neutral inputs (cool device, full budget, no cut, baseline/zero noise, the
 * default iteration ceiling) the output is exactly [OmniClarityMotionTuning.NATIVE_DEFAULT] — the
 * CP-locked native defaults (refine 8, alphas 0.0/0.9, rlIterations 1, snrInverse 0.0) — so the live flow
 * + warp + deblur are bit-for-bit unchanged until the governor actually moves a lever device-side (CP91).
 */
internal data class OmniClarityMotionTuning(
    val refineIterations: Int,
    val sceneCutActive: Boolean,
    val baseTemporalAlpha: Float,
    val maxTemporalAlpha: Float,
    val rlIterations: Int,
    val snrInverse: Float,
) {
    companion object {
        /** The CP-locked native defaults. The resolver's neutral output MUST equal this (no-downgrade lock). */
        val NATIVE_DEFAULT = OmniClarityMotionTuning(
            refineIterations = ArgusFlowStagePolicy.LIVE_REFINE_ITERATIONS_DEFAULT,
            sceneCutActive = false,
            baseTemporalAlpha = ChronosTemporalPolicy.LIVE_BASE_TEMPORAL_ALPHA,
            maxTemporalAlpha = ChronosTemporalPolicy.LIVE_MAX_TEMPORAL_ALPHA,
            rlIterations = RivenDeblurPolicy.LIVE_RL_ITERATIONS_DEFAULT,
            snrInverse = 0.0f,
        )
    }
}

internal object OmniClarityMotionTuningResolver {

    /**
     * CP-QV15 — the thermal severity at/above which the live motion stack backs off. Chosen at SEVERE (3),
     * matching [com.neoproc.neoproccamx.ui.ThermalRuntimePolicy.ThermalDecision.requiresUserWarning], so the
     * stack only degrades under genuine sustained heat, never on a mild/moderate warm-up.
     */
    const val THERMAL_BACKOFF_SEVERITY = 3

    /**
     * CP-QV15 — pure mapping from an Android thermal severity (0..5) to the `thermalThrottling` lever the
     * adaptive guard feeds [resolve]. Extracted so the severity→tuning decision is JVM-testable without the
     * (non-instantiable) `CameraViewModel`. Below the threshold this is `false`, so [resolve] returns the
     * CP-locked native defaults and the live stack is unchanged (self-restoring on cooldown).
     */
    fun shouldThrottleForThermal(thermalSeverity: Int): Boolean =
        thermalSeverity >= THERMAL_BACKOFF_SEVERITY

    /**
     * Resolve the live motion-stack tuning for one frame from the governor signals. All parameters default
     * to the neutral (cool/idle/clean) state, so `resolve()` returns [OmniClarityMotionTuning.NATIVE_DEFAULT].
     *
     * @param thermalThrottling true when the thermal governor is shedding load.
     * @param frameBudgetMs the per-frame time budget (e.g. 16.6 at 60fps); <=0 disables the budget check.
     * @param recentFrameMs the recently measured frame time; > budget triggers a graceful backoff.
     * @param sceneCut true on a detected hard scene cut (from the shared residual-spike / frame-delta signal).
     * @param noiseSigma the measured heteroscedastic source noise in [0,1].
     * @param maxRlIterations the owner-set, device-validated deblur iteration ceiling (default 1 == today).
     */
    fun resolve(
        thermalThrottling: Boolean = false,
        frameBudgetMs: Double = 16.6,
        recentFrameMs: Double = 0.0,
        sceneCut: Boolean = false,
        noiseSigma: Float = 0f,
        maxRlIterations: Int = RivenDeblurPolicy.LIVE_RL_ITERATIONS_DEFAULT,
    ): OmniClarityMotionTuning {
        val refineIterations = ArgusFlowStagePolicy.liveRefineIterations(thermalThrottling, frameBudgetMs, recentFrameMs)
        val (base, max) = ChronosTemporalPolicy.liveWarpAlphas(thermalThrottling)
        // On a scene cut, drop the temporal blend to 0 (no cross-cut ghosting) AND raise the Argus flow gate
        // (force previousValid=0) — the same cut, gating both stages from one shared signal.
        val baseTemporalAlpha = if (sceneCut) 0f else base
        val maxTemporalAlpha = if (sceneCut) 0f else max
        val (rlIterations, snrInverse) = RivenDeblurPolicy.liveDeblurTuning(noiseSigma, maxRlIterations)
        return OmniClarityMotionTuning(
            refineIterations = refineIterations,
            sceneCutActive = sceneCut,
            baseTemporalAlpha = baseTemporalAlpha,
            maxTemporalAlpha = maxTemporalAlpha,
            rlIterations = rlIterations,
            snrInverse = snrInverse,
        )
    }
}
