package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * s7-f05 / CP95 — GOLDEN LOCK. Proves the flag-OFF FocusEngine BODY contribution is bit-for-bit
 * unchanged (the flag is default-ON since 2026-07-03; each case pins it OFF explicitly to lock the
 * legacy path). Under the mockable android.jar (RectF.centerX()/width()=0), the pose
 * Kalman state seeds from (0,0) so the predicted body center collapses to (0,0); these values
 * are CALIBRATED to real output, not hand-derived.
 */
class PoseFaceHandoffGoldenLockTest {

    @After
    fun reset() { // restore the default-ON state for other test classes
        PoseHandoffFlag.set(true)
        DeadSubjectTypeFlag.set(true)
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float) =
        RectF().apply { left = l; top = t; right = r; bottom = b }

    @Test
    fun flagOffPoseOnlyBodyCandidateIsBitForBit() {
        PoseHandoffFlag.set(false)
        // DeadSubjectType (F09) is default-ON and would re-type the pose BODY candidate to
        // UPPER_BODY/FULL_BODY; pin it OFF to lock the legacy BODY subjectType here (F09's own
        // classification is covered by DeadSubjectTypeGoldenLockTest).
        DeadSubjectTypeFlag.set(false)
        val r = FocusEngine().selectSubjectAwareness(
            faces = emptyList(),
            pose = TrackedPose(
                trackingId = 7,
                boundingBox = rect(0.3f, 0.2f, 0.7f, 0.9f),
                landmarks = emptyList(),
                confidence = 0.9f,
                velocityX = 0f,
                velocityY = 0f,
                timestampMs = 1_000L
            ),
            objects = emptyList(),
            segmentation = null,
            depthData = null,
            sceneType = SceneType.GENERAL,
            timestampMs = 1_000L
        )
        assertNotNull(r.primarySubject)
        assertEquals(SubjectType.BODY, r.primarySubject!!.subjectType)
        assertEquals(7, r.primarySubject.trackingId)
        assertNotNull(r.focusPoint)
        // RectF mock => predicted body center collapses to (0,0).
        assertEquals(0f, r.focusPoint!!.focusX, 1e-4f)
        assertEquals(0f, r.focusPoint.focusY, 1e-4f)
    }
}
