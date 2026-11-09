package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s7-f10 / CP9A — GOLDEN LOCK. Pins TODAY's flag-OFF [PredictiveSubjectTracker.updateTracking]
 * output so the gated hardening path provably introduces zero default-path change.
 *
 * JVM caveat (mockable android.jar, returnDefaultValues=true): RectF.centerX()/centerY()/width()
 * return 0, so every observation collapses to position (0,0). The meaningful pins are the
 * subject-id contract, confidence pass-through, isMoving, determinism and the legacy stale-drop
 * semantics. Full position-driven lead behaviour is DEVICE-GATED.
 */
class PredictiveSubjectTrackerGoldenLockTest {

    @Test
    fun `flag off default - one prediction per subject with id confidence and no motion`() {
        val tracker = PredictiveSubjectTracker()
        assertTrue("hardened flag now defaults ON (activated 2026-07-03)", tracker.hardenedTrackingEnabled)
        tracker.hardenedTrackingEnabled = false // pin OFF to lock the legacy golden below

        val preds = tracker.updateTracking(
            faces = listOf(face(1, 0.9f)),
            pose = pose(50, 0.8f),
            objects = listOf(obj(80, 0.7f)),
            timestampMs = 1_000L
        )

        assertEquals(3, preds.size)
        val byId = preds.associateBy { it.subjectId }
        assertNotNull(byId["face:1"])
        assertNotNull(byId["pose:50"])
        assertNotNull(byId["object:80"])
        preds.forEach {
            assertEquals(0f, it.predictedCenterX, 0f)
            assertEquals(0f, it.predictedCenterY, 0f)
            assertFalse(it.isMoving)
        }
        assertEquals(0.9f, byId["face:1"]!!.confidence, 0f)
        assertEquals(0.8f, byId["pose:50"]!!.confidence, 0f)
        assertEquals(0.7f, byId["object:80"]!!.confidence, 0f)
    }

    @Test
    fun `flag off default - selection is deterministic across two trackers`() {
        fun run() = PredictiveSubjectTracker().apply { hardenedTrackingEnabled = false }.updateTracking(
            faces = listOf(face(3, 0.93f)), pose = null, objects = emptyList(), timestampMs = 2_000L
        )
        val a = run()
        val b = run()
        assertEquals(a.size, b.size)
        assertEquals(a[0].subjectId, b[0].subjectId)
        assertEquals(a[0].predictedCenterX, b[0].predictedCenterX, 0f)
        assertEquals(a[0].predictedCenterY, b[0].predictedCenterY, 0f)
        assertEquals(a[0].confidence, b[0].confidence, 0f)
    }

    @Test
    fun `flag off default - stale track hard-dropped after 500ms (legacy emit-then-drop)`() {
        val tracker = PredictiveSubjectTracker().apply { hardenedTrackingEnabled = false }
        tracker.updateTracking(listOf(face(1, 0.9f)), null, emptyList(), 0L)

        // +600ms with no observation: legacy emits the stale track THIS frame, then removeAll
        // drops it (cleanup runs after emission) — pinned as current behaviour.
        val gap = tracker.updateTracking(emptyList(), null, emptyList(), 600L)
        assertTrue("legacy emits stale track the frame it expires", gap.any { it.subjectId == "face:1" })

        // Following frame: the track has been removed, so it is absent.
        val after = tracker.updateTracking(emptyList(), null, emptyList(), 600L)
        assertTrue("stale track dropped on the following frame", after.none { it.subjectId == "face:1" })
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────
    private fun rect(l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f) =
        RectF().apply { left = l; top = t; right = r; bottom = b }

    private fun face(id: Int, conf: Float) = TrackedFace(
        trackingId = id, boundingBox = rect(0.3f, 0.2f, 0.7f, 0.8f), leftEye = null, rightEye = null,
        confidence = conf, velocityX = 0f, velocityY = 0f, timestampMs = 1_000L
    )

    private fun pose(id: Int, conf: Float) = TrackedPose(
        trackingId = id, boundingBox = rect(0.1f, 0.1f, 0.9f, 0.95f), landmarks = emptyList(),
        confidence = conf, velocityX = 0f, velocityY = 0f, timestampMs = 1_000L
    )

    private fun obj(id: Int, conf: Float) = TrackedObject(
        trackingId = id, boundingBox = rect(0.05f, 0.05f, 0.4f, 0.4f), confidence = conf,
        classId = 1, className = "car", velocityX = 0f, velocityY = 0f, timestampMs = 1_000L
    )
}
