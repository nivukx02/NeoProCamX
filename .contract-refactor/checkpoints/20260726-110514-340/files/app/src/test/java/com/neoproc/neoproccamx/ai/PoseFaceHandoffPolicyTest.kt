package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** s7-f05 / CP95 — pure handoff/gating/smoothing policy. Hand-verifiable, no Android types. */
class PoseFaceHandoffPolicyTest {

    private fun lm(x: Float, y: Float, vis: Float) = PoseLandmarkPoint(x = x, y = y, z = 0f, visibility = vis)

    private fun std(xs: List<Float>): Float {
        val m = xs.average().toFloat()
        return sqrt(xs.map { (it - m) * (it - m) }.average().toFloat())
    }

    @Test
    fun handoffNeverJumpsBeyondTheContinuityBoundAndCompletesToBody() {
        val body = List(4) { lm(0.8f, 0.8f, 0.9f) }
        val faceConfs = listOf(0.9f, 0.7f, 0.5f, 0.3f, 0.1f, 0.0f)
        var prev: PoseHandoffAnchor? = null
        var lastX = Float.NaN
        var lastY = Float.NaN
        for (fc in faceConfs) {
            val a = PoseFaceHandoffPolicy.resolveBodyAnchor(
                landmarks = body, bboxCenterX = 0.8f, bboxCenterY = 0.8f, bodyConfidence = 0.85f,
                faceAnchorX = 0.2f, faceAnchorY = 0.2f, faceConfidence = fc, previousAnchor = prev
            )
            if (!lastX.isNaN()) {
                val d = sqrt((a.focusX - lastX) * (a.focusX - lastX) + (a.focusY - lastY) * (a.focusY - lastY))
                assertTrue("handoff jump $d exceeds bound", d <= PoseFaceHandoffPolicy.MAX_HANDOFF_STEP + 1e-4f)
            }
            lastX = a.focusX
            lastY = a.focusY
            prev = a
        }
        // With the face gone, continued calls converge to the body centroid.
        repeat(40) {
            prev = PoseFaceHandoffPolicy.resolveBodyAnchor(
                body, 0.8f, 0.8f, 0.85f, 0.2f, 0.2f, 0f, prev
            )
        }
        val resolved = requireNotNull(prev)
        assertEquals(0.8f, resolved.focusX, 0.02f)
        assertEquals(0.8f, resolved.focusY, 0.02f)
    }

    @Test
    fun fewVisibleKeypointsFallBackToBboxCenter() {
        val sparse = listOf(lm(0.3f, 0.3f, 0.9f), lm(0.3f, 0.3f, 0.9f), lm(0.9f, 0.9f, 0.05f))
        val a = PoseFaceHandoffPolicy.resolveBodyAnchor(
            sparse, bboxCenterX = 0.5f, bboxCenterY = 0.5f, bodyConfidence = 0.8f,
            faceAnchorX = null, faceAnchorY = null, faceConfidence = 0f, previousAnchor = null
        )
        assertTrue(a.usedBodyFallback)
        assertEquals(0.5f, a.focusX, 1e-4f)
        assertEquals(0.5f, a.focusY, 1e-4f)
    }

    @Test
    fun confidenceGatedCentroidExcludesLowVisibilityOutliers() {
        val pts = listOf(
            lm(0.4f, 0.6f, 0.9f), lm(0.4f, 0.6f, 0.9f), lm(0.4f, 0.6f, 0.9f), lm(0.4f, 0.6f, 0.9f),
            lm(0.95f, 0.95f, 0.05f), lm(0.95f, 0.95f, 0.02f)
        )
        val a = PoseFaceHandoffPolicy.resolveBodyAnchor(
            pts, bboxCenterX = 0.1f, bboxCenterY = 0.1f, bodyConfidence = 0.8f,
            faceAnchorX = null, faceAnchorY = null, faceConfidence = 0f, previousAnchor = null
        )
        assertFalse(a.usedBodyFallback)
        // First frame (prev=null) => smoothed==target==cluster centroid; outliers excluded.
        assertEquals(0.4f, a.focusX, 1e-4f)
        assertEquals(0.6f, a.focusY, 1e-4f)
    }

    @Test
    fun smoothingReducesCentroidJitter() {
        val offsets = floatArrayOf(0.02f, -0.02f, 0.015f, -0.015f, 0.025f, -0.025f, 0.01f, -0.01f)
        val inputs = ArrayList<Float>()
        val outputs = ArrayList<Float>()
        var prev: PoseHandoffAnchor? = null
        for (i in 0 until 30) {
            val cx = 0.5f + offsets[i % offsets.size]
            inputs.add(cx)
            val a = PoseFaceHandoffPolicy.resolveBodyAnchor(
                List(4) { lm(cx, 0.5f, 0.9f) }, bboxCenterX = 0.5f, bboxCenterY = 0.5f,
                bodyConfidence = 0.8f, faceAnchorX = null, faceAnchorY = null,
                faceConfidence = 0f, previousAnchor = prev
            )
            outputs.add(a.focusX)
            prev = a
        }
        val inStd = std(inputs)
        val outStd = std(outputs)
        assertTrue("smoothing must reduce sigma (in=$inStd out=$outStd)", outStd < inStd)
        assertTrue("output sigma too high: $outStd", outStd < 0.012f)
    }
}
