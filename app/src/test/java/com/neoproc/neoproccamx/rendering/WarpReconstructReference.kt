package com.neoproc.neoproccamx.rendering

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * s3-f02 / CP52b — host-side faithful reference of the Chronos backward warp + the gated pyramid
 * (coarse-to-fine Lucas-Kanade) correspondence refinement in `warp_reconstruct.comp`.
 *
 * This is a JVM oracle used by [WarpReconstructGoldenTest] to (a) lock the single-level backward warp
 * (`out(p) = previous(p − flow)`, bilinear) and (b) prove the pyramid refinement recovers a large
 * displacement that the single-level warp — fed an under-ranged flow — cannot, all without a device.
 * It ports the shader's luminance-domain refinement line-for-line (3 levels {4,2,1}, two LK steps each,
 * per-step clamp ±2·radius, final hard clamp ±16 px). It does NOT port the colour/Kalman fusion — that
 * is locked by [WarpReconstructShaderContractTest]; this oracle is only about the *correspondence*.
 */
object WarpReconstructReference {

    /** A w×h single-channel (luma) image, row-major. */
    class Plane(val width: Int, val height: Int, val data: FloatArray) {
        init { require(data.size == width * height) }
        fun at(x: Int, y: Int): Float =
            data[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

        /** Bilinear sample with edge clamp (mirrors the shader bilinearSample over clamped loads). */
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

    /** Single-level backward warp by a constant flow: out(p) = previous(p − flow). This is the CP52 path. */
    fun warpSingleLevel(previous: Plane, flowX: Float, flowY: Float): Plane {
        val out = FloatArray(previous.width * previous.height)
        for (y in 0 until previous.height) for (x in 0 until previous.width) {
            out[y * previous.width + x] = previous.bilinear(x - flowX, y - flowY)
        }
        return Plane(previous.width, previous.height, out)
    }

    // 3x3 box-blurred luminance at stride `radius` — an on-the-fly Gaussian-pyramid level (no extra mips).
    private fun currentBox(current: Plane, x: Int, y: Int, radius: Float): Float {
        val r = max(radius.roundToInt(), 1)
        var sum = 0f
        for (j in -1..1) for (i in -1..1) sum += current.at(x + i * r, y + j * r)
        return sum / 9f
    }

    private fun previousBox(previous: Plane, cx: Float, cy: Float, radius: Float): Float {
        var sum = 0f
        for (j in -1..1) for (i in -1..1) sum += previous.bilinear(cx + i * radius, cy + j * radius)
        return sum / 9f
    }

    /**
     * Coarse-to-fine LK refinement of the backward source for one pixel, mirroring
     * `pyramidalCoarseToFineRefine` in the shader. Returns the refined source coord, hard-clamped to ±16 px
     * of the input source (the no-runaway invariant).
     */
    fun refineSource(
        current: Plane,
        previous: Plane,
        coordX: Int,
        coordY: Int,
        srcX0: Float,
        srcY0: Float,
    ): Pair<Float, Float> {
        var sx = srcX0
        var sy = srcY0
        val levels = 3
        for (level in 0 until levels) {
            val radius = (1 shl (levels - 1 - level)).toFloat() // 4, 2, 1
            val curLum = currentBox(current, coordX, coordY, radius)
            repeat(2) {
                val warpLum = previousBox(previous, sx, sy, radius)
                val residual = warpLum - curLum
                val gx = 0.5f * (previousBox(previous, sx + radius, sy, radius) - previousBox(previous, sx - radius, sy, radius))
                val gy = 0.5f * (previousBox(previous, sx, sy + radius, radius) - previousBox(previous, sx, sy - radius, radius))
                val g2 = gx * gx + gy * gy + 1e-4f
                sx += (-residual * gx / g2).coerceIn(-2f * radius, 2f * radius)
                sy += (-residual * gy / g2).coerceIn(-2f * radius, 2f * radius)
            }
        }
        val dx = (sx - srcX0).coerceIn(-16f, 16f)
        val dy = (sy - srcY0).coerceIn(-16f, 16f)
        return (srcX0 + dx) to (srcY0 + dy)
    }

    /** Pyramid warp from a (possibly under-ranged) constant initial flow guess: refine each pixel then sample. */
    fun warpPyramid(current: Plane, previous: Plane, initialFlowX: Float, initialFlowY: Float): Plane {
        val out = FloatArray(current.width * current.height)
        for (y in 0 until current.height) for (x in 0 until current.width) {
            val (sx, sy) = refineSource(current, previous, x, y, x - initialFlowX, y - initialFlowY)
            out[y * current.width + x] = previous.bilinear(sx, sy)
        }
        return Plane(current.width, current.height, out)
    }
}
