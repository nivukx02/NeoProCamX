package com.neoproc.neoproccamx.rendering

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * s3-f03 / CP53 — host-side faithful reference of the Riven directional motion-deconvolution in
 * `riven_motion_deblur.comp`: the 16-tap line-blur tap statistics, the clamp-bounded van Cittert
 * iteration (Path A, today's default) and the multiplicative Richardson-Lucy iteration (Path B, gated).
 *
 * Used by [RivenDeblurGoldenTest] to lock Path A's single step, prove the [lineMin,lineMax] clamp holds at
 * any iteration count (no ringing/halo), prove the Wiener noise floor suppresses grain (noise-aware), and
 * prove Path B sharpens like Path A in the low-noise limit without amplifying grain beyond it — all on the
 * JVM, no device. Single-channel luma (the shader is per-channel identical).
 */
object RivenDeblurReference {

    const val TAPS = 16

    class Plane(val width: Int, val height: Int, val data: FloatArray) {
        init { require(data.size == width * height) }
        fun at(x: Int, y: Int): Float =
            data[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

        fun bilinear(fx: Float, fy: Float): Float {
            val bx = floor(fx).toInt()
            val by = floor(fy).toInt()
            val tx = fx - bx
            val ty = fy - by
            val a = at(bx, by)
            val b = at(bx + 1, by)
            val c = at(bx, by + 1)
            val d = at(bx + 1, by + 1)
            return lerp(lerp(a, b, tx), lerp(c, d, tx), ty)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** The line-blur of the INPUT along ±dir over [kernelLength] + the input line envelope, seeded at center. */
    data class Tap(val blurEstimate: Float, val lineMin: Float, val lineMax: Float)

    fun tapStats(p: Plane, x: Int, y: Int, dirX: Float, dirY: Float, kernelLength: Float): Tap {
        val center = p.at(x, y)
        var acc = 0f
        var lo = center
        var hi = center
        for (i in 0 until TAPS) {
            val t = (i.toFloat() / (TAPS - 1).toFloat() - 0.5f) * kernelLength
            val tap = p.bilinear(x + dirX * t, y + dirY * t)
            acc += tap
            lo = min(lo, tap)
            hi = max(hi, tap)
        }
        return Tap(acc / TAPS, lo, hi)
    }

    /** Path A — clamp-bounded van Cittert iterate (mirrors the shader's default branch). */
    fun deblurPixelPathA(center: Float, blurEstimate: Float, lineMin: Float, lineMax: Float, gain: Float, iterations: Int): Float {
        var est = center
        repeat(max(iterations, 1)) {
            val residual = est - blurEstimate
            est = (est + gain * residual).coerceIn(lineMin, lineMax)
        }
        return est
    }

    /** Path B — clamp-bounded multiplicative Richardson-Lucy iterate (mirrors the shader's gated branch). */
    fun deblurPixelPathB(center: Float, blurEstimate: Float, lineMin: Float, lineMax: Float, gain: Float, iterations: Int): Float {
        var est = center
        repeat(max(iterations, 1)) {
            val reblur = blurEstimate * (est / max(center, 1e-4f))
            val ratio = center / max(reblur, 1e-4f)
            est = (est * lerp(1f, ratio, gain)).coerceIn(lineMin, lineMax)
        }
        return est
    }

    /** Full directional deblur of a plane (constant motion dir + length). [multiplicative] selects Path B. */
    fun deblurPlane(
        p: Plane,
        dirX: Float,
        dirY: Float,
        kernelLength: Float,
        gain: Float,
        iterations: Int,
        multiplicative: Boolean,
    ): Plane {
        val out = FloatArray(p.width * p.height)
        for (y in 0 until p.height) for (x in 0 until p.width) {
            val center = p.at(x, y)
            val stats = tapStats(p, x, y, dirX, dirY, kernelLength)
            out[y * p.width + x] = if (multiplicative) {
                deblurPixelPathB(center, stats.blurEstimate, stats.lineMin, stats.lineMax, gain, iterations)
            } else {
                deblurPixelPathA(center, stats.blurEstimate, stats.lineMin, stats.lineMax, gain, iterations)
            }
        }
        return Plane(p.width, p.height, out)
    }
}
