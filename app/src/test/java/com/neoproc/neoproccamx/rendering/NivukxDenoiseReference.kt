package com.neoproc.neoproccamx.rendering

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * s4-f01 / CP20-21-22 — host-side faithful reference of the elite stages added to
 * `nivukx_denoise.comp`. Ports the new shader math line-for-line so [NivukxDenoiseEliteTest] can lock
 * the behaviour on the JVM with NO device:
 *   - CP20 motion-compensated temporal: bilinear history warp along the per-pixel flow,
 *   - CP21 Generalized-Anscombe VST: forward / Mäkitalo–Foi exact unbiased inverse / naive inverse,
 *   - CP22 perceptual opponent-channel chroma low-pass that leaves luma untouched.
 *
 * The default (gates-off) path is unchanged in the live shader; the no-downgrade guarantee is the
 * spec-constant default (the driver uses the GLSL defaults unless VkSpecializationInfo is set), so
 * this oracle exercises only the NEW math each gate switches on. Keep in lockstep with the shader.
 */
object NivukxDenoiseReference {

    // jzazbz luma weights — these sum to exactly 1.0, which is what makes the opponent residual
    // c = rgb - L·1 provably luma-free (see [perceptualChromaLowPass]).
    val LUMA_W = floatArrayOf(0.2627f, 0.6780f, 0.0593f)

    fun jzazbzLuma(r: Float, g: Float, b: Float): Float = r * LUMA_W[0] + g * LUMA_W[1] + b * LUMA_W[2]

    // ── CP21 Generalized Anscombe VST ────────────────────────────────────────────────
    // f(x) = (2/α)·√(αx + 3/8·α² + σ²) ≡ 2·√(u + 3/8) with u = x/α + σ²/α².
    class GatProfile(val sigmaReadSq: Float, val sigmaShot: Float) {
        init { require(sigmaShot > 0f) { "GAT shot gain must be > 0" } }
    }

    /** Spec-constant defaults that reproduce the shader's current implicit Poisson-Gaussian model. */
    val DEFAULT_GAT = GatProfile(sigmaReadSq = 0.00022f, sigmaShot = 0.0012f)

    /**
     * Maps a measured SENSOR_NOISE_PROFILE (read-noise variance, shot-noise gain, both in the working
     * [0,1] linear scale) to bounded GAT spec-constant coefficients. Coefficients come from the real
     * profile — never free knobs — and are clamped to physically safe ranges so a degenerate profile
     * cannot produce a NaN or a divide-by-zero in the forward/inverse.
     */
    fun gatCoefficientsFromProfile(readNoiseVariance: Float, shotNoiseGain: Float): GatProfile =
        GatProfile(
            sigmaReadSq = readNoiseVariance.coerceIn(1e-7f, 0.05f),
            sigmaShot = shotNoiseGain.coerceIn(1e-5f, 0.5f)
        )

    fun gatForward(x: Float, p: GatProfile = DEFAULT_GAT): Float {
        val u = x / p.sigmaShot + p.sigmaReadSq / (p.sigmaShot * p.sigmaShot)
        return 2f * sqrt(max(u + 0.375f, 0f))
    }

    /** Exact algebraic inverse of [gatForward] — round-trips to the identity (no DC/bias shift). */
    fun gatInverseNaive(d: Float, p: GatProfile = DEFAULT_GAT): Float {
        val u = 0.25f * d * d - 0.375f
        return p.sigmaShot * u - p.sigmaReadSq / p.sigmaShot
    }

    /** Mäkitalo–Foi exact unbiased inverse of the standard Anscombe, rescaled to the generalized form. */
    fun gatInverseExact(d: Float, p: GatProfile = DEFAULT_GAT): Float {
        val dc = max(d, 1e-4f)
        val d2 = dc * dc
        val s = sqrt(1.5f)
        val u = 0.25f * d2 + 0.25f * s / dc - 1.375f / d2 + 0.625f * s / (d2 * dc) - 0.125f
        return p.sigmaShot * u - p.sigmaReadSq / p.sigmaShot
    }

    // ── CP22 perceptual opponent chroma low-pass ─────────────────────────────────────
    /**
     * Returns the recomposed RGB for one pixel: the supplied (crisp, already-denoised) [lumaKeep]
     * with the input chroma residual low-passed over [radius]. Luma is preserved exactly because the
     * residual c = rgb − L·1 satisfies dot(c, w) = 0 (w sums to 1), and averaging luma-free vectors
     * stays luma-free. [rgb] is row-major RGB-interleaved, [w]×[h].
     */
    fun perceptualChromaLowPass(
        rgb: FloatArray, w: Int, h: Int, px: Int, py: Int, lumaKeep: Float, radius: Int = 3
    ): FloatArray {
        var sumR = 0f; var sumG = 0f; var sumB = 0f; var sumW = 0f
        val denom = (2 * radius * radius + 1).toFloat()
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val sx = (px + dx).coerceIn(0, w - 1)
                val sy = (py + dy).coerceIn(0, h - 1)
                val i = (sy * w + sx) * 3
                val r = rgb[i]; val g = rgb[i + 1]; val b = rgb[i + 2]
                val l = jzazbzLuma(r, g, b)
                val weight = exp(-(dx * dx + dy * dy).toFloat() / denom)
                sumR += (r - l) * weight
                sumG += (g - l) * weight
                sumB += (b - l) * weight
                sumW += weight
            }
        }
        val inv = 1f / max(sumW, 1e-6f)
        return floatArrayOf(lumaKeep + sumR * inv, lumaKeep + sumG * inv, lumaKeep + sumB * inv)
    }

    // ── CP20 motion-compensated history bilinear sample ──────────────────────────────
    /** Bilinear sample of the [prevRgb] history image at fractional ([u],[v]); clamped at borders. */
    fun sampleHistoryBilinear(prevRgb: FloatArray, w: Int, h: Int, u: Float, v: Float): FloatArray {
        val px = u - 0.5f
        val py = v - 0.5f
        val x0 = floor(px).toInt()
        val y0 = floor(py).toInt()
        val fx = px - x0
        val fy = py - y0
        fun at(x: Int, y: Int): FloatArray {
            val cx = x.coerceIn(0, w - 1); val cy = y.coerceIn(0, h - 1)
            val i = (cy * w + cx) * 3
            return floatArrayOf(prevRgb[i], prevRgb[i + 1], prevRgb[i + 2])
        }
        val c00 = at(x0, y0); val c10 = at(x0 + 1, y0)
        val c01 = at(x0, y0 + 1); val c11 = at(x0 + 1, y0 + 1)
        return FloatArray(3) { k ->
            val top = c00[k] + (c10[k] - c00[k]) * fx
            val bot = c01[k] + (c11[k] - c01[k]) * fx
            top + (bot - top) * fy
        }
    }
}
