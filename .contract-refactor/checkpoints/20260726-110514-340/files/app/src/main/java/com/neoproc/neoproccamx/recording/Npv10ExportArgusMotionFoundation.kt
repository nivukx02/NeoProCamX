package com.neoproc.neoproccamx.recording

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * s6-f05 / CP80 — real-motion foundation for the NPV10→MOV export, at the JVM boundary.
 *
 * Mirrors the GPU shader's `rivenBlockMatchFlow` (SAD block-match optical flow) and the
 * `rivenP010LumaDeblur` early-out (no deblur when motion magnitude is below threshold) over a P010
 * luma plane, so the real-motion path is unit-testable without a device. DORMANT: invoked only when
 * `Npv10ExportRealMotionFlag` is enabled (default OFF, device-gated) — it replaces the synthetic
 * analytic `neuralOpticalFlowVector` temporal flow. No live-path caller today.
 */
internal object Npv10ExportArgusMotionFoundation {

    /** Below this motion magnitude the deblur is a no-op (mirrors the GPU speed<1e-5 early return). */
    const val MOTION_EPSILON: Double = 1e-5

    data class MotionVector(val dx: Int, val dy: Int, val sad: Long) {
        val magnitude: Double get() = sqrt((dx * dx + dy * dy).toDouble())
    }

    /**
     * Global translational SAD block-match flow between two luma planes (10-bit codes, row-major,
     * width*height). Returns the integer (dx,dy) in [-searchRadius, searchRadius] minimizing the
     * sum-of-absolute-differences over the overlapping region (current[x,y] vs previous[x-dx,y-dy]).
     */
    fun blockMatchFlow(
        current: IntArray,
        previous: IntArray,
        width: Int,
        height: Int,
        searchRadius: Int = 4
    ): MotionVector {
        require(current.size == width * height && previous.size == width * height) {
            "luma plane size must equal width*height"
        }
        var best = MotionVector(0, 0, Long.MAX_VALUE)
        for (dy in -searchRadius..searchRadius) {
            for (dx in -searchRadius..searchRadius) {
                var sad = 0L
                var overlap = 0L
                for (y in 0 until height) {
                    val py = y - dy
                    if (py < 0 || py >= height) continue
                    for (x in 0 until width) {
                        val px = x - dx
                        if (px < 0 || px >= width) continue
                        sad += abs(current[y * width + x] - previous[py * width + px]).toLong()
                        overlap++
                    }
                }
                if (overlap == 0L) continue
                // Prefer the smaller-magnitude vector on ties for deterministic, stable flow.
                if (sad < best.sad || (sad == best.sad && (dx * dx + dy * dy) < (best.dx * best.dx + best.dy * best.dy))) {
                    best = MotionVector(dx, dy, sad)
                }
            }
        }
        return best
    }

    /**
     * Directional 1-2-1 luma deblur along the motion vector. Foundation only. Returns the input
     * unchanged when motion is below [MOTION_EPSILON] (static frame), matching the GPU early-out.
     */
    fun directionalDeblur(luma: IntArray, motion: MotionVector, width: Int, height: Int): IntArray {
        if (motion.magnitude < MOTION_EPSILON) return luma
        val sx = motion.dx.coerceIn(-1, 1)
        val sy = motion.dy.coerceIn(-1, 1)
        if (sx == 0 && sy == 0) return luma
        val out = IntArray(luma.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = luma[y * width + x]
                val bx = x - sx
                val by = y - sy
                val fx = x + sx
                val fy = y + sy
                val back = if (bx in 0 until width && by in 0 until height) luma[by * width + bx] else c
                val fwd = if (fx in 0 until width && fy in 0 until height) luma[fy * width + fx] else c
                out[y * width + x] = ((back + 2 * c + fwd) / 4).coerceIn(0, 1023)
            }
        }
        return out
    }
}
