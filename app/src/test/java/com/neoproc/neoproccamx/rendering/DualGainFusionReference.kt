package com.neoproc.neoproccamx.rendering

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * CP31 / s1-f10 — host-side faithful reference of dual_gain_fusion.comp (NeoProCamX's own dual-
 * conversion-gain ACEScg merge). Ports the shader pixel math line-for-line so [DualGainFusionGoldenTest]
 * can lock the dynamic-range behaviour on the JVM with NO device:
 *   - LCG equalized to common radiance by kGain, HCG holds shadows / LCG holds highlights,
 *   - per-region luma-weighted, luminance-preserving exposure blend (acescgExposureBlend32f),
 *   - motion mask + shadow stretch.
 * Inputs are linear ACEScg RGB images (values may exceed 1). This is a copy of the shader, not a
 * binding of the SPIR-V, so it must be kept in lockstep with dual_gain_fusion.comp.
 */
object DualGainFusionReference {

    class Image(val width: Int, val height: Int, val rgb: FloatArray) {
        init { require(rgb.size == width * height * 3) { "image size mismatch" } }
    }

    class Params(
        val kGain: Float,
        val noiseFloor: Float,
        val saturationThreshold: Float,
        val shadowStretch: Float
    )

    class Result(val fused: FloatArray, val motionConfidence: FloatArray)

    private fun clampX(x: Int, w: Int) = x.coerceIn(0, w - 1)
    private fun clampY(y: Int, h: Int) = y.coerceIn(0, h - 1)

    private fun acesCgLuma(r: Float, g: Float, b: Float): Float =
        r * 0.272229f + g * 0.674082f + b * 0.053689f

    private fun acesCgLuma(c: FloatArray): Float = acesCgLuma(c[0], c[1], c[2])

    private fun load(img: Image, x: Int, y: Int): FloatArray {
        val cx = clampX(x, img.width)
        val cy = clampY(y, img.height)
        val o = (cy * img.width + cx) * 3
        return floatArrayOf(max(img.rgb[o], 0f), max(img.rgb[o + 1], 0f), max(img.rgb[o + 2], 0f))
    }

    private fun scale(c: FloatArray, k: Float) = floatArrayOf(c[0] * k, c[1] * k, c[2] * k)
    private fun maxv(c: FloatArray, m: Float) = floatArrayOf(max(c[0], m), max(c[1], m), max(c[2], m))

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun mix(a: Float, b: Float, t: Float): Float = a * (1f - t) + b * t
    private fun mix(a: FloatArray, b: FloatArray, t: Float): FloatArray =
        floatArrayOf(mix(a[0], b[0], t), mix(a[1], b[1], t), mix(a[2], b[2], t))

    // ── shader: subPixelLumaDifference ──
    private fun subPixelLumaDifference(lcg: Image, hcg: Image, p: Params, x: Int, y: Int, eqCenter: FloatArray, hcgCenter: FloatArray): Float {
        val centerDelta = abs(acesCgLuma(eqCenter) - acesCgLuma(hcgCenter))
        var regionalDelta = centerDelta
        for (dy in -1..1) for (dx in -1..1) {
            val lcgSample = scale(load(lcg, x + dx, y + dy), p.kGain)
            val hcgSample = load(hcg, x + dx, y + dy)
            val sampleDelta = abs(acesCgLuma(lcgSample) - acesCgLuma(hcgSample))
            val len = sqrt((dx + 0.5f) * (dx + 0.5f) + (dy + 0.5f) * (dy + 0.5f))
            val subPixelWeight = 1f / (1f + len * 0.42f)
            regionalDelta += sampleDelta * subPixelWeight
        }
        return regionalDelta / 7.34f
    }

    private fun hcgSaturation(p: Params, hcg: FloatArray): Float {
        val maxChannel = max(hcg[0], max(hcg[1], hcg[2]))
        return smoothstep(p.saturationThreshold * 0.82f, p.saturationThreshold, maxChannel)
    }

    private fun computeMotionMask(p: Params, lumaDifference: Float, localSignal: Float): Float {
        val safeNoiseFloor = max(p.noiseFloor, 1e-5f)
        val normalized = (lumaDifference - safeNoiseFloor) / max(localSignal + safeNoiseFloor, 1e-5f)
        return smoothstep(0.018f, 0.145f, normalized)
    }

    private fun coherentCaCorrection(lcg: Image, hcg: Image, p: Params, x: Int, y: Int, signal: FloatArray, hcgSource: Boolean): FloatArray {
        val k = max(p.kGain, 1f)
        val redAligned = if (hcgSource) load(hcg, x - 1, y) else scale(load(lcg, x - 1, y), k)
        val blueAligned = if (hcgSource) load(hcg, x + 1, y) else scale(load(lcg, x + 1, y), k)
        val edgeLuma = abs(acesCgLuma(redAligned) - acesCgLuma(blueAligned))
        val correctionWeight = smoothstep(0.025f, 0.18f, edgeLuma) * 0.18f
        return floatArrayOf(
            mix(signal[0], redAligned[0], correctionWeight),
            signal[1],
            mix(signal[2], blueAligned[2], correctionWeight)
        )
    }

    private fun localizedLuminanceWeight(p: Params, lcg: FloatArray, hcg: FloatArray, saturationAmount: Float): Float {
        val lcgLuma = acesCgLuma(lcg)
        val hcgLuma = acesCgLuma(hcg)
        val shadowSnrWeight = 1f - smoothstep(0.025f, 0.38f, hcgLuma)
        val highlightProtect = 1f - saturationAmount
        val exposureContinuity = exp(-abs(lcgLuma - hcgLuma) / max(p.noiseFloor + 0.035f, 1e-5f))
        return (mix(highlightProtect, shadowSnrWeight, 0.42f) * exposureContinuity).coerceIn(0f, 1f)
    }

    private fun acescgExposureBlend32f(p: Params, lcg: FloatArray, hcg: FloatArray, baseHcgWeight: Float, saturationAmount: Float): FloatArray {
        val localWeight = localizedLuminanceWeight(p, lcg, hcg, saturationAmount)
        val hcgWeight = (baseHcgWeight * (0.55f + localWeight * 0.45f)).coerceIn(0f, 1f)
        val blended = mix(lcg, hcg, hcgWeight)
        val blendedLuma = max(acesCgLuma(blended), 1e-5f)
        val targetLuma = mix(acesCgLuma(lcg), acesCgLuma(hcg), hcgWeight)
        return maxv(scale(blended, targetLuma / blendedLuma), 0f)
    }

    private fun subPixelWavefrontAlignment(lcg: Image, hcg: Image, p: Params, x: Int, y: Int, signal: FloatArray, hcgSource: Boolean): FloatArray {
        val k = max(p.kGain, 1f)
        val left = if (hcgSource) load(hcg, x - 1, y) else scale(load(lcg, x - 1, y), k)
        val right = if (hcgSource) load(hcg, x + 1, y) else scale(load(lcg, x + 1, y), k)
        val redBlueSkew = ((right[2] - left[0]) * 0.08f).coerceIn(-0.035f, 0.035f)
        val highContrastEdge = smoothstep(0.025f, 0.22f, abs(acesCgLuma(right) - acesCgLuma(left)))
        return floatArrayOf(
            mix(signal[0], left[0] + redBlueSkew, highContrastEdge * 0.12f),
            signal[1],
            mix(signal[2], right[2] - redBlueSkew, highContrastEdge * 0.12f)
        )
    }

    private fun linearAcesCgExposureFusionCosmic(p: Params, lcg: FloatArray, hcg: FloatArray, baseHcgWeight: Float, saturationAmount: Float): FloatArray {
        val baseBlend = acescgExposureBlend32f(p, lcg, hcg, baseHcgWeight, saturationAmount)
        val luminanceWeight = localizedLuminanceWeight(p, lcg, hcg, saturationAmount)
        val shadowContinuity = 1f - smoothstep(0.04f, 0.36f, acesCgLuma(baseBlend))
        val shadowSNRBlend = mix(lcg, hcg, (luminanceWeight + shadowContinuity * 0.18f).coerceIn(0f, 1f))
        return maxv(mix(baseBlend, shadowSNRBlend, shadowContinuity * 0.22f), 0f)
    }

    private fun applyShadowStretch(p: Params, equalizedLcg: FloatArray, motionAmount: Float): FloatArray {
        val y = max(acesCgLuma(equalizedLcg), 1e-5f)
        val shadowWeight = 1f - smoothstep(0.035f, 0.34f, y)
        val lift = p.shadowStretch * motionAmount * shadowWeight
        return scale(equalizedLcg, 1f + lift)
    }

    /** Full fuse for every pixel: returns the fused ACEScg image (w*h*3) + motion confidence (w*h). */
    fun fuse(lcg: Image, hcg: Image, p: Params): Result {
        require(lcg.width == hcg.width && lcg.height == hcg.height) { "LCG/HCG dimensions must match" }
        val w = lcg.width
        val h = lcg.height
        val fused = FloatArray(w * h * 3)
        val motion = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var equalizedLcg = scale(load(lcg, x, y), max(p.kGain, 1f))
            var hcgSignal = load(hcg, x, y)
            equalizedLcg = coherentCaCorrection(lcg, hcg, p, x, y, equalizedLcg, false)
            hcgSignal = coherentCaCorrection(lcg, hcg, p, x, y, hcgSignal, true)
            equalizedLcg = subPixelWavefrontAlignment(lcg, hcg, p, x, y, equalizedLcg, false)
            hcgSignal = subPixelWavefrontAlignment(lcg, hcg, p, x, y, hcgSignal, true)
            val difference = subPixelLumaDifference(lcg, hcg, p, x, y, equalizedLcg, hcgSignal)
            val localSignal = max(max(acesCgLuma(equalizedLcg), acesCgLuma(hcgSignal)), 1e-5f)
            val motionMask = computeMotionMask(p, difference, localSignal)
            val saturationAmount = hcgSaturation(p, hcgSignal)

            val staticHcgWeight = 1f - saturationAmount
            var staticBlend = acescgExposureBlend32f(p, equalizedLcg, hcgSignal, staticHcgWeight, saturationAmount)
            staticBlend = linearAcesCgExposureFusionCosmic(p, equalizedLcg, hcgSignal, staticHcgWeight, saturationAmount)
            val motionSafeLcg = applyShadowStretch(p, equalizedLcg, motionMask)
            var fusedPx = mix(staticBlend, mix(hcgSignal, equalizedLcg, motionMask), motionMask)
            fusedPx = mix(fusedPx, motionSafeLcg, motionMask * 0.62f)

            val o = (y * w + x) * 3
            fused[o] = max(fusedPx[0], 0f); fused[o + 1] = max(fusedPx[1], 0f); fused[o + 2] = max(fusedPx[2], 0f)
            motion[y * w + x] = motionMask
        }
        return Result(fused, motion)
    }
}
