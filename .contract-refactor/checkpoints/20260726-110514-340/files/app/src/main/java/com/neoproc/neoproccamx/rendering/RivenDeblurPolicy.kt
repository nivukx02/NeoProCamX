package com.neoproc.neoproccamx.rendering

import kotlin.math.roundToInt

/**
 * OmniClarity Engine — §4 Riven Motion Deblur: gating + noise-aware parameterization.
 *
 * Decides whether motion deblur runs and with what regularization. The deconvolution amplifies noise,
 * so the regularization (1/SNR) must track the measured source noise or the result rings instead of
 * sharpening. Deblur is only meaningful when the Argus motion field exists and the exposure occupies a
 * real fraction of the frame interval. Per-pixel kernel-length gating happens in
 * `riven_motion_deblur.comp`; this object computes no pixels.
 */
internal data class RivenDeblurParams(
    val exposureFraction: Float,
    val minKernelPx: Float,
    val snrInverse: Float,
    val maxKernelPx: Float,
    val deblurStrength: Float
) {
    /** Push-constant layout for riven_motion_deblur.comp's RivenDeblurParams block. */
    fun toPushConstants(): FloatArray = floatArrayOf(
        exposureFraction,
        minKernelPx,
        snrInverse,
        maxKernelPx,
        deblurStrength
    )
}

internal data class RivenDeblurPlan(
    val active: Boolean,
    val params: RivenDeblurParams,
    val reason: String
)

internal object RivenDeblurPolicy {
    private const val MIN_KERNEL_PX = 2f       // below ~2px of blur, deblur does more harm than good
    private const val MAX_KERNEL_CAP_PX = 48f
    private const val NOISE_REGULARIZATION_GAIN = 4f
    private const val BASE_STRENGTH = 0.75f
    private const val STRICT_STRENGTH = 1.0f

    // ───────────────────── s3-f03 / CP53 — live noise-aware deblur lever ─────────────────────
    // Drives the *live* native Riven dispatch (vulkan_bridge.cpp) via VulkanBridge.setOmniClarityDeblurTuning,
    // wired by s3-f04. rlIterations defaults to the CP53 native value (1) and is only raised — toward an
    // owner-set device-validated ceiling — on CLEAN frames (more deconvolution where it's safe), backing off
    // to 1 as noise rises. snrInverse tracks the measured heteroscedastic noise (0 at zero noise == native
    // default). At neutral (baseline/zero noise, default ceiling) the lever is (1, 0.0): today's exact output.

    /** Today's native CP53 RivenDeblurPushConstants.rlIterations. MUST equal the C++ default. */
    const val LIVE_RL_ITERATIONS_DEFAULT = 1

    /**
     * Live Riven deblur lever (rlIterations, snrInverse) for the native dispatch. [maxRlIterations] is the
     * owner-set, device-validated iteration ceiling (defaults to 1 == today, so the lever is bit-stable
     * until the owner raises it). On a clean frame the count rises toward that ceiling; as the measured
     * [noiseSigma] rises it backs off to 1 and the Wiener noise floor [snrInverse] rises — stronger deblur
     * on a clean frame, conservative on a noisy one, so grain is never amplified.
     */
    fun liveDeblurTuning(
        noiseSigma: Float,
        maxRlIterations: Int = LIVE_RL_ITERATIONS_DEFAULT,
    ): Pair<Int, Float> {
        val noise = noiseSigma.coerceIn(0f, 1f)
        val snrInverse = noise * NOISE_REGULARIZATION_GAIN // 0 at zero noise == native default
        val ceiling = maxRlIterations.coerceAtLeast(LIVE_RL_ITERATIONS_DEFAULT)
        // Linearly fade the iteration count from the ceiling (clean) down to 1 (noisy).
        val iters = (ceiling - (ceiling - 1) * noise).roundToInt().coerceIn(1, ceiling)
        return iters to snrInverse
    }

    fun resolve(
        motionFieldAvailable: Boolean,
        exposureFraction: Float,
        noiseSigma: Float,
        maxFlow: Float,
        highEndStrict: Boolean
    ): RivenDeblurPlan {
        val exposure = exposureFraction.coerceIn(0f, 1f)
        if (!motionFieldAvailable || exposure <= 0f) {
            return RivenDeblurPlan(
                active = false,
                params = RivenDeblurParams(exposure, MIN_KERNEL_PX, 0f, MIN_KERNEL_PX + 1f, 0f),
                reason = if (!motionFieldAvailable) "NO_FLOW" else "ZERO_EXPOSURE_FRACTION"
            )
        }

        val noise = noiseSigma.coerceIn(0f, 1f)
        val maxKernel = (maxFlow * exposure).coerceIn(MIN_KERNEL_PX + 1f, MAX_KERNEL_CAP_PX)
        // Noisier source -> lower deconvolution gain so it tracks noise instead of ringing.
        val baseStrength = if (highEndStrict) STRICT_STRENGTH else BASE_STRENGTH
        val strength = (baseStrength * (1f - 0.5f * noise)).coerceIn(0f, 1f)

        return RivenDeblurPlan(
            active = true,
            params = RivenDeblurParams(
                exposureFraction = exposure,
                minKernelPx = MIN_KERNEL_PX,
                snrInverse = noise * NOISE_REGULARIZATION_GAIN,
                maxKernelPx = maxKernel,
                deblurStrength = strength
            ),
            reason = "CONFIDENCE_GATED_DEBLUR"
        )
    }
}
