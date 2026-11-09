package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.rendering.WarpReconstructReference.Plane
import kotlin.math.abs
import kotlin.math.floor
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s3-f02 / CP52b — host golden for the Chronos backward warp + the gated pyramid refinement.
 *
 * Synthetic value-noise scene with KNOWN translation between frames (true forward flow == the shift).
 * Test 1 locks the single-level backward warp (CP52, default path) against ground truth. Tests 2/3 prove
 * the pyramid path recovers a large displacement the single-level warp — fed an under-ranged (zero) flow —
 * cannot, and that the refinement can never diverge (the ±16 px hard bound). Fully in-repo / no device.
 */
class WarpReconstructGoldenTest {

    private val w = 80
    private val h = 80
    private val cell = 5f // smoother than the Argus scene so the LK gradient is well-conditioned

    private fun hash(ix: Int, iy: Int): Float {
        var hsh = ix * -1640531527 + iy * 2654435761.toInt() + ix * iy * 1013904223
        hsh = hsh xor (hsh ushr 15)
        hsh *= -2048144789
        hsh = hsh xor (hsh ushr 13)
        return ((hsh ushr 8) and 0xFFFFFF).toFloat() / 0x1000000.toFloat()
    }

    private fun scene(x: Float, y: Float): Float {
        val gx = x / cell
        val gy = y / cell
        val ix = floor(gx).toInt()
        val iy = floor(gy).toInt()
        val tx = gx - ix
        val ty = gy - iy
        val sx = tx * tx * (3f - 2f * tx)
        val sy = ty * ty * (3f - 2f * ty)
        val a = hash(ix, iy)
        val b = hash(ix + 1, iy)
        val c = hash(ix, iy + 1)
        val d = hash(ix + 1, iy + 1)
        val top = a + (b - a) * sx
        val bot = c + (d - c) * sx
        return top + (bot - top) * sy
    }

    private fun currentFrame(): Plane {
        val data = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) data[y * w + x] = scene(x.toFloat(), y.toFloat())
        return Plane(w, h, data)
    }

    /** previous(q) = scene(q + d): the frame the camera translated INTO, so true forward flow == (dx,dy). */
    private fun previousFrame(dx: Float, dy: Float): Plane {
        val data = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) data[y * w + x] = scene(x + dx, y + dy)
        return Plane(w, h, data)
    }

    /** Mean absolute warp error |warped(p) − current(p)| over the interior (avoids edge-clamp artifacts). */
    private fun mae(warped: Plane, current: Plane, margin: Int): Float {
        var sum = 0.0
        var n = 0
        for (y in margin until h - margin) for (x in margin until w - margin) {
            sum += abs(warped.at(x, y) - current.at(x, y))
            n++
        }
        return (sum / n).toFloat()
    }

    @Test
    fun singleLevelWarpReproducesCurrentFromTrueFlow() {
        // CP52 lock: backward-warping the previous frame by the TRUE flow reconstructs the current frame.
        val dx = 3f
        val dy = -2f
        val current = currentFrame()
        val previous = previousFrame(dx, dy)
        val warped = WarpReconstructReference.warpSingleLevel(previous, dx, dy)
        assertTrue("single-level warp must reconstruct current from true flow", mae(warped, current, margin = 8) < 0.02f)
    }

    @Test
    fun pyramidRecoversLargeMotionWhereSingleLevelFails() {
        // A large displacement the input flow under-ranged to ZERO (block-match search exceeded). The
        // single-level warp then samples the unmoved previous frame -> large error. The pyramid refinement
        // recovers the motion from the zero seed -> small error.
        val dx = 7f
        val dy = 0f
        val current = currentFrame()
        val previous = previousFrame(dx, dy)

        val singleLevelZero = mae(WarpReconstructReference.warpSingleLevel(previous, 0f, 0f), current, margin = 18)
        val pyramid = mae(WarpReconstructReference.warpPyramid(current, previous, 0f, 0f), current, margin = 18)

        assertTrue("the under-ranged single-level warp must visibly fail: $singleLevelZero", singleLevelZero > 0.08f)
        assertTrue("pyramid must beat the failed single-level warp: single=$singleLevelZero pyramid=$pyramid", pyramid < 0.4f * singleLevelZero)
        assertTrue("pyramid residual must be small in absolute terms: $pyramid", pyramid < 0.05f)
    }

    @Test
    fun pyramidRefinementStaysWithinHardBound() {
        // No-runaway invariant: the refined source can never stray beyond ±16 px of the flow-given source,
        // for ANY pixel and ANY (even pathological zero-flow) seed — so a degenerate gradient cannot smear.
        val current = currentFrame()
        val previous = previousFrame(7f, 0f)
        for (y in 0 until h) for (x in 0 until w) {
            val (sx, sy) = WarpReconstructReference.refineSource(current, previous, x, y, x.toFloat(), y.toFloat())
            assertTrue("x refinement escaped the bound at ($x,$y): ${sx - x}", abs(sx - x) <= 16f + 1e-3f)
            assertTrue("y refinement escaped the bound at ($x,$y): ${sy - y}", abs(sy - y) <= 16f + 1e-3f)
        }
    }
}
