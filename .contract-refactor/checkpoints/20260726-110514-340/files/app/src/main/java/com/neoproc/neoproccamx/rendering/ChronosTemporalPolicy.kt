package com.neoproc.neoproccamx.rendering

/**
 * OmniClarity Engine — § Chronos motion-rejecting temporal fusion: drives `warp_reconstruct.comp`.
 *
 * Temporal accumulation must reject unstable motion. This policy disables temporal blending across
 * scene cuts and when no flow exists (both would ghost), and caps the maximum blend so accumulation
 * reduces noise/shimmer without smearing. The warp shader already does the motion-compensated,
 * occlusion-gated blend per pixel; this object only supplies its confidence/alpha parameters.
 */
internal data class ChronosWarpParams(
    val baseTemporalAlpha: Float,
    val maxTemporalAlpha: Float,
    val colorSigma: Float,
    val varianceSigma: Float,
    val flowConsistencySigma: Float,
    val boundaryFeatherPixels: Float
) {
    /** Push-constant layout for warp_reconstruct.comp's WarpParams block (field order matches GLSL). */
    fun toPushConstants(): FloatArray = floatArrayOf(
        baseTemporalAlpha,
        maxTemporalAlpha,
        colorSigma,
        varianceSigma,
        flowConsistencySigma,
        boundaryFeatherPixels
    )
}

internal data class ChronosTemporalPlan(
    val active: Boolean,
    val warpParams: ChronosWarpParams,
    val reason: String
)

internal object ChronosTemporalPolicy {
    private const val MAX_ALPHA_NORMAL = 0.85f
    private const val MAX_ALPHA_STRICT = 0.92f
    private const val MAX_ALPHA_THERMAL = 0.60f
    private const val SCENE_CUT_THRESHOLD = 0.35f
    private const val COLOR_SIGMA = 0.05f
    private const val VARIANCE_SIGMA = 0.02f
    private const val FLOW_CONSISTENCY_SIGMA = 4.0f
    private const val BOUNDARY_FEATHER_PX = 2.0f

    /** Scene-cut detection from a normalized inter-frame difference metric supplied by the dispatcher. */
    fun isSceneCut(normalizedFrameDelta: Float, threshold: Float = SCENE_CUT_THRESHOLD): Boolean =
        normalizedFrameDelta.coerceIn(0f, 1f) >= threshold

    // ───────────────────────── s3-f02 / CP52b — live warp temporal lever ─────────────────────────
    // These drive the *live* native warp (vulkan_bridge.cpp) via VulkanBridge.setOmniClarityWarpTuning,
    // wired by s3-f04. They MUST default to the CP52 native struct values (base 0.0 / max 0.9), NOT the
    // older resolve() value (0.85) — driving the live warp below 0.9 at neutral would weaken today's
    // temporal denoise (a downgrade). The governor only lowers the max under real thermal load device-side.

    /** Today's native CP52 OmniWarpPushConstants.baseTemporalAlpha. MUST equal the C++ default. */
    const val LIVE_BASE_TEMPORAL_ALPHA = 0.0f

    /** Today's native CP52 OmniWarpPushConstants.maxTemporalAlpha. MUST equal the C++ default. */
    const val LIVE_MAX_TEMPORAL_ALPHA = 0.9f

    /** Thermal backoff target for the live max temporal alpha (weaker accumulation when hot, never raised). */
    const val LIVE_MAX_TEMPORAL_ALPHA_THERMAL = 0.6f

    /**
     * Live Chronos temporal-blend alphas (base, max) for the native warp dispatch. Defaults to the CP52
     * values at neutral so the live warp is bit-for-bit unchanged; under thermal load the max backs off
     * (less accumulation) while never being raised above today's value. base stays 0.0 (CP52).
     */
    fun liveWarpAlphas(thermalThrottling: Boolean): Pair<Float, Float> =
        LIVE_BASE_TEMPORAL_ALPHA to (if (thermalThrottling) LIVE_MAX_TEMPORAL_ALPHA_THERMAL else LIVE_MAX_TEMPORAL_ALPHA)

    fun resolve(
        flowAvailable: Boolean,
        sceneCut: Boolean,
        highEndStrict: Boolean,
        thermalThrottling: Boolean
    ): ChronosTemporalPlan {
        val disabled = ChronosWarpParams(
            baseTemporalAlpha = 0f,
            maxTemporalAlpha = 0f,
            colorSigma = COLOR_SIGMA,
            varianceSigma = VARIANCE_SIGMA,
            flowConsistencySigma = FLOW_CONSISTENCY_SIGMA,
            boundaryFeatherPixels = BOUNDARY_FEATHER_PX
        )
        if (!flowAvailable) {
            return ChronosTemporalPlan(false, disabled, "NO_FLOW")
        }
        if (sceneCut) {
            // Reset temporal history across a cut to prevent cross-scene ghosting.
            return ChronosTemporalPlan(false, disabled, "SCENE_CUT")
        }

        val maxAlpha = when {
            thermalThrottling -> MAX_ALPHA_THERMAL
            highEndStrict -> MAX_ALPHA_STRICT
            else -> MAX_ALPHA_NORMAL
        }
        return ChronosTemporalPlan(
            active = true,
            warpParams = ChronosWarpParams(
                baseTemporalAlpha = 0f,
                maxTemporalAlpha = maxAlpha,
                colorSigma = COLOR_SIGMA,
                varianceSigma = VARIANCE_SIGMA,
                flowConsistencySigma = FLOW_CONSISTENCY_SIGMA,
                boundaryFeatherPixels = BOUNDARY_FEATHER_PX
            ),
            reason = "MOTION_REJECTING_TEMPORAL"
        )
    }
}
