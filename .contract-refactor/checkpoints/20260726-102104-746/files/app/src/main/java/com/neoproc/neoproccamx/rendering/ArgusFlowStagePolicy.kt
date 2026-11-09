package com.neoproc.neoproccamx.rendering

import kotlin.math.roundToInt

/**
 * OmniClarity Engine — §2 Argus hybrid optical flow: cost-control + parameterization.
 *
 * 100% classical, zero learned weights. Two stages:
 *  - Stage A: hierarchical block matching (`argus_flow_blockmatch.comp`).
 *  - Stage B: smoothness-regularized variational refinement (separate iterated pass).
 *
 * Per the design, **Stage B is the first thing dropped under thermal/time pressure**; Stage A always
 * runs when a prior frame exists, because every motion-dependent stage below (Photon ghosting,
 * Riven deblur, Chronos temporal fusion) depends on at least the coarse field. This object decides
 * the active stage, the variational iteration count, the flow resolution, and the block-match
 * push-constant parameters. It computes no pixels — the native dispatcher consumes [ArgusFlowPlan].
 */
internal enum class ArgusFlowStage {
    /** No prior frame: emit static (zero) flow only. */
    NONE,

    /** Coarse-to-fine block matching only (Stage B dropped). */
    STAGE_A_BLOCK_MATCH,

    /** Block matching followed by variational refinement. */
    STAGE_A_AND_B_VARIATIONAL
}

internal data class ArgusBlockMatchParams(
    val searchRadius: Int,
    val coarseStep: Int,
    val patchRadius: Int,
    val maxFlow: Float,
    val previousValid: Boolean,
    val minTexture: Float
) {
    /**
     * Push-constant layout for `argus_flow_blockmatch.comp`'s `ArgusBlockMatchParams` block.
     * Integer fields are bit-reinterpreted into the float slots so the native side can upload one
     * contiguous block and the GLSL reads them back as `int` (reinterpret), matching the existing
     * mixed int/float push-constant convention (e.g. MfsrFusionPushConstants).
     */
    fun toPushConstants(): FloatArray = floatArrayOf(
        Float.fromBits(searchRadius),
        Float.fromBits(coarseStep),
        Float.fromBits(patchRadius),
        maxFlow,
        if (previousValid) 1f else 0f,
        minTexture
    )
}

internal data class ArgusRefineParams(
    val regularization: Float,
    val maxFlow: Float,
    val previousValid: Boolean,
    val edgeStopSigma: Float
) {
    /** Push-constant layout for argus_flow_refine.comp's ArgusRefineParams block (all float). */
    fun toPushConstants(): FloatArray = floatArrayOf(
        regularization,
        maxFlow,
        if (previousValid) 1f else 0f,
        edgeStopSigma
    )
}

internal data class ArgusSelectParams(
    val occlusionThreshold: Float,
    val minTexture: Float,
    val maxFlow: Float,
    val previousValid: Boolean
) {
    /** Push-constant layout for argus_flow_select.comp's ArgusSelectParams block (all float). */
    fun toPushConstants(): FloatArray = floatArrayOf(
        occlusionThreshold,
        minTexture,
        maxFlow,
        if (previousValid) 1f else 0f
    )
}

internal data class ArgusFlowPlan(
    val stage: ArgusFlowStage,
    val variationalIterations: Int,
    val blockMatch: ArgusBlockMatchParams,
    val refine: ArgusRefineParams?,
    val select: ArgusSelectParams?,
    val flowResolutionDivisor: Int,
    val reason: String
) {
    val runsBlockMatch: Boolean get() = stage != ArgusFlowStage.NONE
    val runsVariational: Boolean get() = stage == ArgusFlowStage.STAGE_A_AND_B_VARIATIONAL
}

internal object ArgusFlowStagePolicy {

    private const val MIN_TEXTURE_FLOOR = 0.02f // normalized-luma gradient floor for reliable matches
    private const val BASE_VARIATIONAL_ITERATIONS = 3
    private const val STRICT_VARIATIONAL_ITERATIONS = 6
    private const val REGULARIZATION = 0.5f // alpha^2 smoothness weight for the variational refine
    private const val EDGE_STOP_SIGMA = 0.01f // anisotropic smoothness luma-edge sensitivity
    private const val OCCLUSION_THRESHOLD = 1.0f // px fwd/bwd residual above which flow is occluded

    fun resolve(
        thermalThrottling: Boolean,
        frameBudgetMs: Double,
        recentFrameMs: Double,
        highEndStrict: Boolean,
        previousFrameAvailable: Boolean,
        frameWidth: Int,
        frameHeight: Int
    ): ArgusFlowPlan {
        if (!previousFrameAvailable || frameWidth <= 0 || frameHeight <= 0) {
            return ArgusFlowPlan(
                stage = ArgusFlowStage.NONE,
                variationalIterations = 0,
                blockMatch = blockMatchParams(divisor = 4, frameWidth = frameWidth, highEndStrict = false, previousValid = false, thermal = false),
                refine = null,
                select = null,
                flowResolutionDivisor = 4,
                reason = "NO_PRIOR_FRAME"
            )
        }

        val overBudget = frameBudgetMs > 0.0 && recentFrameMs > frameBudgetMs

        // Stage B (variational refinement) is the first thing dropped under pressure.
        val dropStageB = thermalThrottling || overBudget
        val divisor = when {
            thermalThrottling -> 8
            !dropStageB && highEndStrict -> 2
            else -> 4
        }
        val params = blockMatchParams(
            divisor = divisor,
            frameWidth = frameWidth,
            highEndStrict = highEndStrict && !dropStageB,
            previousValid = true,
            thermal = thermalThrottling
        )

        return if (dropStageB) {
            ArgusFlowPlan(
                stage = ArgusFlowStage.STAGE_A_BLOCK_MATCH,
                variationalIterations = 0,
                blockMatch = params,
                refine = null,
                select = null,
                flowResolutionDivisor = divisor,
                reason = if (thermalThrottling) "STAGE_B_DROPPED_THERMAL" else "STAGE_B_DROPPED_BUDGET"
            )
        } else {
            ArgusFlowPlan(
                stage = ArgusFlowStage.STAGE_A_AND_B_VARIATIONAL,
                variationalIterations = if (highEndStrict) STRICT_VARIATIONAL_ITERATIONS else BASE_VARIATIONAL_ITERATIONS,
                blockMatch = params,
                refine = ArgusRefineParams(
                    regularization = REGULARIZATION,
                    maxFlow = params.maxFlow,
                    previousValid = true,
                    edgeStopSigma = EDGE_STOP_SIGMA
                ),
                select = ArgusSelectParams(
                    occlusionThreshold = OCCLUSION_THRESHOLD,
                    minTexture = MIN_TEXTURE_FLOOR,
                    maxFlow = params.maxFlow,
                    previousValid = true
                ),
                flowResolutionDivisor = divisor,
                reason = "STAGE_A_AND_B"
            )
        }
    }

    // ───────────────────────── s3-f01 / CP54b — live motion-stack levers ─────────────────────────
    // The values below drive the *live* native OmniClarity dispatch (vulkan_bridge.cpp
    // dispatchOmniClarityMotionStack), NOT the older reduced-res `resolve()` cost model above. They are
    // pushed into the C++ tuning channel by s3-f04 via VulkanBridge.setOmniClarityMotionTuning(). Every
    // neutral value here equals today's hardcoded native default, so until the governor moves a lever
    // under real device load the live flow field is bit-for-bit unchanged (no-downgrade contract).

    /** Today's native Stage-B refine ping-pong count (`kOmniRefineIterations`). MUST equal the C++ default. */
    const val LIVE_REFINE_ITERATIONS_DEFAULT = 8

    /** Floor the governor may back the refine count down to under maximal thermal load. */
    const val LIVE_REFINE_ITERATIONS_MIN = 2

    /**
     * Default global brightness-constancy flow-residual above which the current frame is treated as a
     * hard scene cut. A well-tracked fast pan keeps the post-flow residual small (the flow explains the
     * motion); only a genuine cut — where no flow can reconcile the two frames — drives the residual
     * this high. Chosen above any normal-motion residual so the default never false-triggers (the
     * `sceneCutResidualStraddlesFastPanAndHardCut` golden proves the separation). Shares the cut signal
     * with [ChronosTemporalPolicy.isSceneCut] (s3-f04).
     */
    const val SCENE_CUT_RESIDUAL_THRESHOLD = 0.15f

    /**
     * Live Stage-B refine ping-pong count for the native dispatch. Defaults to
     * [LIVE_REFINE_ITERATIONS_DEFAULT] (8) at neutral; backs off **monotonically** under thermal /
     * over-budget pressure and recovers as headroom returns. The result is always **even and ≥2** so the
     * ping-pong always terminates with the refined field in `flowLowA` (the buffer the select pass reads)
     * — an odd count would leave the freshest field in `flowLowB` and corrupt the selection.
     */
    fun liveRefineIterations(
        thermalThrottling: Boolean,
        frameBudgetMs: Double,
        recentFrameMs: Double,
    ): Int {
        val overBudget = frameBudgetMs > 0.0 && recentFrameMs > frameBudgetMs
        var iters = LIVE_REFINE_ITERATIONS_DEFAULT
        if (overBudget) iters -= 2          // shed two refine passes when we miss the frame budget
        if (thermalThrottling) iters -= 4   // thermal is the heavier backoff
        iters = iters.coerceIn(LIVE_REFINE_ITERATIONS_MIN, LIVE_REFINE_ITERATIONS_DEFAULT)
        if (iters % 2 != 0) iters -= 1      // keep even so the ping-pong lands in flowLowA
        return iters.coerceAtLeast(LIVE_REFINE_ITERATIONS_MIN)
    }

    /**
     * True when the global post-flow brightness-constancy residual indicates a hard scene cut. The
     * native dispatch forces `previousValid → 0` (the CP51 first-frame mechanism) for that frame so the
     * flow/warp never propagate garbage across the cut. A normalized residual ≥ [threshold] is a cut.
     */
    fun isSceneCutSpike(
        normalizedFlowResidual: Float,
        threshold: Float = SCENE_CUT_RESIDUAL_THRESHOLD,
    ): Boolean = normalizedFlowResidual.coerceAtLeast(0f) >= threshold

    private fun blockMatchParams(
        divisor: Int,
        frameWidth: Int,
        highEndStrict: Boolean,
        previousValid: Boolean,
        thermal: Boolean
    ): ArgusBlockMatchParams {
        val flowResWidth = (frameWidth / divisor).coerceAtLeast(1)
        // Max searched displacement ~3% of the flow-resolution width (thermal trims it back).
        val rawRadius = (flowResWidth * 0.03f).roundToInt()
        val searchRadius = (if (thermal) (rawRadius * 0.6f).roundToInt() else rawRadius).coerceIn(4, 16)
        val coarseStep = (searchRadius / 4).coerceIn(1, 4)
        val patchRadius = if (highEndStrict) 3 else 2
        return ArgusBlockMatchParams(
            searchRadius = searchRadius,
            coarseStep = coarseStep,
            patchRadius = patchRadius,
            maxFlow = searchRadius.toFloat(),
            previousValid = previousValid,
            minTexture = MIN_TEXTURE_FLOOR
        )
    }
}
