package com.neoproc.neoproccamx.rendering

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * NeoProCamX NDR — dual-conversion-gain fusion control (CP31 / s1-f10).
 *
 * Owns the host side of the dual_gain_fusion.comp GPU merge: the push-constant ABI mirror and the
 * SNR-optimal (inverse-variance) blend policy derived from the s1-f03 heteroscedastic sensor noise
 * model. This is NeoProCamX's own dynamic-range technology — classical inverse-variance weighting and
 * a per-read photon+read noise model, NOT a vendor HDR formula. The GPU pixel math itself lives in
 * dual_gain_fusion.comp (the user's shader); this object computes the parameters that feed it and the
 * reference blend weight the golden test proves tracks σ²(signal).
 *
 * Dual-conversion-gain physics: one sensor read at a HIGH conversion gain (HCG — more DN per electron,
 * so it saturates early but is photon-clean in the shadows) and one at a LOW conversion gain (LCG —
 * holds the highlights, noisier in the shadows). The LCG read is equalized to common scene radiance by
 * the gain ratio kGain; the merge takes shadows from the cleaner HCG read and highlights from the LCG
 * read, weighted per pixel by which read has the lower variance.
 */
internal data class DualGainFusionParams(
    /** Image width — a GLSL `uint` slot (packed as int-bits). */
    val width: Int,
    /** Image height — a GLSL `uint` slot (packed as int-bits). */
    val height: Int,
    /** LCG→HCG conversion-gain ratio used to equalize the low-gain read to common radiance. */
    val kGain: Float,
    /** Read-noise floor (σ at zero signal) gating the motion mask, from the s1-f03 model. */
    val noiseFloor: Float,
    /** Normalized luma at which the HCG read is treated as saturating (highlights pass to LCG). */
    val saturationThreshold: Float,
    /** Motion-region shadow lift the shader applies to the equalized LCG. */
    val shadowStretch: Float
) {
    /**
     * Push-constant layout for dual_gain_fusion.comp's `DualGainFusionParams` block, field-for-field:
     * `uint width; uint height; float kGain; float noiseFloor; float saturationThreshold; float shadowStretch`.
     * width/height are `uint` slots → int-bit reinterpreted (floatBitsToInt on the GPU); the rest are
     * float. Locked against the GLSL block + the native struct by [DualGainFusionPushConstantLayoutTest].
     */
    fun toPushConstants(): FloatArray = floatArrayOf(
        Float.fromBits(width),
        Float.fromBits(height),
        kGain,
        noiseFloor,
        saturationThreshold,
        shadowStretch
    )
}

/** Per-read affine (heteroscedastic) sensor noise: variance σ²(signal) = noiseS·signal + noiseO. */
internal data class DualGainNoiseModel(
    val lcgNoiseS: Float,
    val lcgNoiseO: Float,
    val hcgNoiseS: Float,
    val hcgNoiseO: Float
) {
    init {
        require(lcgNoiseS >= 0f && lcgNoiseO >= 0f && hcgNoiseS >= 0f && hcgNoiseO >= 0f) {
            "noise coefficients must be non-negative (physical variance)"
        }
    }
}

internal object DualGainFusionPolicy {
    const val MIN_KGAIN = 1f
    const val MAX_KGAIN = 64f
    const val MIN_SATURATION = 0.72f
    const val MAX_SATURATION = 0.995f
    const val MAX_NOISE_FLOOR = 0.20f

    /** s1-f03 affine read variance σ²(signal) = noiseS·signal + noiseO, floored ≥ 0. */
    fun readVariance(signal: Float, noiseS: Float, noiseO: Float): Float =
        (noiseS * max(signal, 0f) + noiseO).coerceAtLeast(0f)

    /**
     * SNR-optimal (inverse-variance) HCG blend weight for a scene radiance [signal]. The HCG read sees
     * `signal` directly with variance σ²_hcg; the LCG read measures `signal/kGain` and is equalized
     * back by ×kGain, which amplifies its variance by kGain² → σ²_lcgEq = kGain·noiseS_lcg·signal +
     * kGain²·noiseO_lcg. Inverse-variance weighting puts weight on the lower-variance read, so the
     * result → HCG in the shadows (σ²_hcg ≪ σ²_lcgEq) and → LCG as HCG variance grows. Returns the
     * weight ON the HCG read in [0,1]; classical, vendor-free.
     */
    fun snrOptimalHcgWeight(signal: Float, kGain: Float, model: DualGainNoiseModel): Float {
        val k = kGain.coerceIn(MIN_KGAIN, MAX_KGAIN)
        val s = max(signal, 0f)
        val varHcg = readVariance(s, model.hcgNoiseS, model.hcgNoiseO)
        val varLcgEq = k * model.lcgNoiseS * s + k * k * model.lcgNoiseO
        val denom = (varHcg + varLcgEq).coerceAtLeast(1e-12f)
        return (varLcgEq / denom).coerceIn(0f, 1f)
    }

    /** Read-noise floor for the shader's motion mask: σ at zero signal of the cleaner HCG read. */
    fun noiseFloor(model: DualGainNoiseModel): Float =
        sqrt(model.hcgNoiseO.coerceAtLeast(0f)).coerceIn(0f, MAX_NOISE_FLOOR)

    /** Build the validated dual_gain_fusion push-constant params from the noise model + capture config. */
    fun params(
        width: Int,
        height: Int,
        kGain: Float,
        model: DualGainNoiseModel,
        saturationThreshold: Float = 0.90f,
        shadowStretch: Float = 0.10f
    ): DualGainFusionParams = DualGainFusionParams(
        width = max(width, 0),
        height = max(height, 0),
        kGain = kGain.coerceIn(MIN_KGAIN, MAX_KGAIN),
        noiseFloor = noiseFloor(model),
        saturationThreshold = saturationThreshold.coerceIn(MIN_SATURATION, MAX_SATURATION),
        shadowStretch = shadowStretch.coerceIn(0f, 1f)
    )

    /** True when the inverse-variance weighting strictly prefers the lower-variance read (sanity guard). */
    fun prefersLowerVariance(varianceA: Float, varianceB: Float): Boolean {
        val wA = varianceB / max(varianceA + varianceB, 1e-12f) // inverse-variance weight on read A
        return if (abs(varianceA - varianceB) < 1e-12f) abs(wA - 0.5f) < 1e-4f else (wA > 0.5f) == (varianceA < varianceB)
    }
}
