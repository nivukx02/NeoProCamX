package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s7-f10 / CP9A — hardened path (flag ON, test-only). Coast-through-occlusion with monotonic
 * confidence decay, then re-lock on a returning measurement. Positions stay 0 under RectF
 * mocking; we assert presence + decayed confidence, not absolute position.
 */
class PredictiveSubjectTrackerHardenedPathTest {

    @Test
    fun `hardened flag on coasts through gap with decaying confidence then relocks`() {
        val tracker = PredictiveSubjectTracker().apply { hardenedTrackingEnabled = true }

        tracker.updateTracking(listOf(face(1, 0.9f, 0L)), null, emptyList(), 0L)
        tracker.updateTracking(listOf(face(1, 0.9f, 16L)), null, emptyList(), 16L)

        // Gap frame 1: no observation -> coasted, present, confidence decayed below last observed.
        val g1 = tracker.updateTracking(emptyList(), null, emptyList(), 32L)
        assertTrue("coasted track present", g1.any { it.subjectId == "face:1" })
        val c1 = g1.first { it.subjectId == "face:1" }.confidence
        assertTrue("confidence decays below last observed (0.9)", c1 < 0.9f)

        // Gap frame 2: still present, strictly lower confidence.
        val g2 = tracker.updateTracking(emptyList(), null, emptyList(), 48L)
        assertTrue("still coasting", g2.any { it.subjectId == "face:1" })
        val c2 = g2.first { it.subjectId == "face:1" }.confidence
        assertTrue("strictly decaying while coasting", c2 < c1)

        // Re-supply a measurement: track retained and confidence recovers to the observation.
        val relock = tracker.updateTracking(listOf(face(1, 0.9f, 64L)), null, emptyList(), 64L)
        val c3 = relock.first { it.subjectId == "face:1" }.confidence
        assertEquals(0.9f, c3, 1e-4f)
    }

    private fun rect() = RectF().apply { left = 0.3f; top = 0.2f; right = 0.7f; bottom = 0.8f }

    private fun face(id: Int, conf: Float, ts: Long) = TrackedFace(
        trackingId = id, boundingBox = rect(), leftEye = null, rightEye = null,
        confidence = conf, velocityX = 0f, velocityY = 0f, timestampMs = ts
    )
}
