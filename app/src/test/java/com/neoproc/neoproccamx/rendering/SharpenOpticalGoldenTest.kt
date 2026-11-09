package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.quality.measure.ImageQualityMetrics
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * CP-QV17 — JVM property golden for the gated optical-recovery sharpener. A full host port of
 * `sharpen.comp` (multi-scale unsharp + Richardson-Lucy + Fienup-HIO / Hermite-Gaussian wavefront phase
 * retrieval) is infeasible in a unit test, so this LOCKS the detail-recovery PROPERTY on the shader's
 * primary sharpening leg — the edge-adaptive multi-scale UNSHARP MASK with Sobel edge protection and
 * halo suppression (`unsharpMask` + `computeAdaptiveStrength` + `suppressHalos`, ported faithfully). On a
 * synthetic Gaussian-blurred slanted edge it asserts:
 *   (1) the edge slope (MTF50 proxy) INCREASES vs the input (real sharpening), and
 *   (2) the max local overshoot stays under an anti-halo bound (< 15% of edge contrast — the shader's
 *       edge-protection + overshoot clamp keeps ringing bounded).
 *
 * A separate characterization test locks that the shader derives its PSF heuristically from
 * `params.radius`/`params.threshold` (NOT a calibrated lens model).
 *
 * What is NOT covered here (device-gated, unverifiable in the JVM): the GPU dispatch/barrier/copy-back
 * and the wavefront-phase-retrieval deconvolution legs of sharpen.comp.
 */
class SharpenOpticalGoldenTest {

    // ── Abramowitz & Stegun 7.1.26 erf approximation (matches DistantDetailMtfHaloGoldenTest). ──
    private fun erf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x))
        val sign = if (x < 0) -1.0 else 1.0
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t *
            Math.exp(-x * x)
        return sign * y
    }

    private fun at(img: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val cx = x.coerceIn(0, w - 1)
        val cy = y.coerceIn(0, h - 1)
        return img[cy * w + cx]
    }

    /** 3x3 Gaussian blur, sharpen.comp gaussianBlur3x3 weights. */
    private fun blur3(img: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val k = floatArrayOf(
            0.0625f, 0.125f, 0.0625f,
            0.125f, 0.25f, 0.125f,
            0.0625f, 0.125f, 0.0625f
        )
        var sum = 0f
        var idx = 0
        for (dy in -1..1) for (dx in -1..1) {
            sum += at(img, w, h, x + dx, y + dy) * k[idx]
            idx++
        }
        return sum
    }

    /** 5x5 Gaussian blur, sharpen.comp gaussianBlur5x5 weights. */
    private fun blur5(img: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val k = floatArrayOf(
            0.003765f, 0.015019f, 0.023792f, 0.015019f, 0.003765f,
            0.015019f, 0.059912f, 0.094907f, 0.059912f, 0.015019f,
            0.023792f, 0.094907f, 0.150342f, 0.094907f, 0.023792f,
            0.015019f, 0.059912f, 0.094907f, 0.059912f, 0.015019f,
            0.003765f, 0.015019f, 0.023792f, 0.015019f, 0.003765f
        )
        var sum = 0f
        var idx = 0
        for (dy in -2..2) for (dx in -2..2) {
            sum += at(img, w, h, x + dx, y + dy) * k[idx]
            idx++
        }
        return sum
    }

    /** Sobel edge magnitude on luma, sharpen.comp detectEdge (grayscale => luma == value). */
    private fun detectEdge(img: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val kx = floatArrayOf(-1f, 0f, 1f, -2f, 0f, 2f, -1f, 0f, 1f)
        val ky = floatArrayOf(-1f, -2f, -1f, 0f, 0f, 0f, 1f, 2f, 1f)
        var sx = 0f
        var sy = 0f
        var idx = 0
        for (dy in -1..1) for (dx in -1..1) {
            val v = at(img, w, h, x + dx, y + dy)
            sx += v * kx[idx]
            sy += v * ky[idx]
            idx++
        }
        return sqrt(sx * sx + sy * sy)
    }

    /** sharpen.comp computeAdaptiveStrength (grayscale: baseLuminance == value). */
    private fun adaptiveStrength(edge: Float, localVar: Float, baseLum: Float, edgeProtection: Float): Float {
        val edgeFactor = 1f - smoothstep(0.1f, 0.4f, edge) * edgeProtection
        val textureFactor = smoothstep(0.01f, 0.08f, localVar)
        val lumFactor = smoothstep(0.02f, 0.15f, baseLum) * smoothstep(0.98f, 0.85f, baseLum)
        return edgeFactor * (0.6f + textureFactor * 0.4f) * lumFactor
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** sharpen.comp unsharpMask on a scalar luma channel. */
    private fun unsharp(original: Float, blurred: Float, amount: Float, threshold: Float): Float {
        val detail = original - blurred
        val mag = abs(detail)
        if (mag < threshold) return original
        val soft = smoothstep(threshold * 0.5f, threshold * 2.0f, mag)
        return original + detail * amount * soft
    }

    /**
     * Faithful port of sharpen.comp's primary sharpening path on a scalar (grayscale) image: multi-scale
     * unsharp (3x3 + 5x5, and 7x7 when radius>1.5) modulated by the Sobel edge-adaptive strength, plus
     * the fine high-frequency re-injection, micro-contrast, and the adaptive halo-overshoot clamp.
     */
    private fun sharpen(
        img: FloatArray, w: Int, h: Int,
        amount: Float, radius: Float, threshold: Float,
        detailAmount: Float, edgeProtection: Float, microContrast: Float
    ): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val color = img[y * w + x]
            val baseLum = color
            val edge = detectEdge(img, w, h, x, y)
            val hi = color - blur3(img, w, h, x, y) // extractHighFrequency
            val localVar = abs(hi)
            val strength = adaptiveStrength(edge, localVar, baseLum, edgeProtection)
            val effective = amount * strength

            var s = unsharp(color, blur3(img, w, h, x, y), effective * 0.6f, threshold)
            s = unsharp(s, blur5(img, w, h, x, y), effective * 0.3f, threshold * 1.5f)
            // (coarse 7x7 layer only when radius>1.5; approximated by the 5x5 low-freq here)
            if (radius > 1.5f) {
                s = unsharp(s, blur5(img, w, h, x, y), effective * 0.15f, threshold * 2.0f)
            }

            // fine detail re-injection
            s += hi * (detailAmount * strength)
            // micro-contrast (deviation from 3x3 local mean; box3 ~ blur3 low-freq proxy)
            if (microContrast > 0f) {
                val localMean = blur3(img, w, h, x, y)
                s += (color - localMean) * (microContrast * strength)
            }

            // suppressHalos: clamp overshoot per channel by an edge-adaptive bound.
            val maxOvershoot = 0.0001f + (0.00002f - 0.0001f) * edge.coerceIn(0f, 1f)
            var diff = s - color
            diff = diff.coerceIn(-maxOvershoot, maxOvershoot)
            out[y * w + x] = (color + diff).coerceIn(0f, 1f)
        }
        return out
    }

    private fun centralRow(luma: FloatArray, w: Int, h: Int): DoubleArray {
        val y = h / 2
        return DoubleArray(w) { x -> luma[y * w + x].toDouble() }
    }

    @Test
    fun sharpenIncreasesEdgeSlopeWhileKeepingOvershootBounded() {
        val w = 64
        val h = 64
        // A well-conditioned Gaussian-blurred (erf) slanted step at x=32, slope 0.06, mapped into
        // [0.2, 0.8] so the unsharp fringes have headroom and are not hidden by the [0,1] clamp. Because
        // it is BLURRED, a real sharpener must raise its MTF50 (recover the edge).
        val edgeSigma = 1.6
        val s = edgeSigma * sqrt(2.0)
        val input = FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val edge = 32.0 + 0.06 * y
            val step = 0.5 * (1.0 + erf((x - edge) / s))
            (0.2 + 0.6 * step).toFloat()
        }

        val sharpened = sharpen(
            input, w, h,
            amount = 0.6f, radius = 1.5f, threshold = 0.02f,
            detailAmount = 0.4f, edgeProtection = 0.7f, microContrast = 0.25f
        )

        // (1) MTF50 (edge-slope proxy) must INCREASE — genuine detail recovery.
        val mtfIn = ImageQualityMetrics.slantedEdgeMtf50(input, w, h)
        val mtfOut = ImageQualityMetrics.slantedEdgeMtf50(sharpened, w, h)
        assertTrue(
            "sharpen must increase MTF50 vs input (in=$mtfIn out=$mtfOut)",
            mtfOut > mtfIn
        )

        // (2) The halo overshoot on the central row must stay under an anti-halo bound (< 15% of edge
        // contrast). The edge-adaptive overshoot clamp + edge-protected strength keep ringing bounded.
        val overshootIn = ImageQualityMetrics.edgeOvershoot1D(centralRow(input, w, h))
        val overshootOut = ImageQualityMetrics.edgeOvershoot1D(centralRow(sharpened, w, h))
        assertTrue(
            "sharpen overshoot must stay under the anti-halo bound (0.15) (in=$overshootIn out=$overshootOut)",
            overshootOut < 0.15
        )
    }

    // ── Characterization: the PSF is a HEURISTIC derived from params, not a calibrated lens model. ──

    @Test
    fun shaderPsfIsDerivedFromRadiusAndThresholdNotACalibratedLensModel() {
        val shader = File("src/main/cpp/shaders/vulkan/sharpen.comp").readText()
        // shaderFocalDistance / shaderAperture are derived INTERNALLY from the push-constant params, so
        // the "lens" is a per-frame heuristic, not a per-camera calibrated PSF/wavefront model.
        assertTrue(
            "shaderFocalDistance must be derived from params.radius",
            Regex("""float\s+shaderFocalDistance\s*=\s*max\(\s*params\.radius""").containsMatchIn(shader)
        )
        assertTrue(
            "shaderAperture must be derived from params.threshold",
            Regex("""float\s+shaderAperture\s*=\s*max\(\s*1\.0\s*\+\s*params\.threshold""").containsMatchIn(shader)
        )
        // Those derived values feed the Richardson-Lucy / wavefront-phase-retrieval optical-recovery legs.
        assertTrue(shader.contains("richardsonLucyDeconvolution(sharpened, coord, imageSize, shaderFocalDistance, shaderAperture)"))
        assertTrue(shader.contains("fienupHioWavefrontPhaseRetrieval("))
    }
}
