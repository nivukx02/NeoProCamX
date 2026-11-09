package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * AF-SD (F09) — no-downgrade golden. With DeadSubjectTypeFlag OFF (default): clustered faces still
 * each yield a FACE candidate (no GROUP is formed / no face suppressed) and a pose stays
 * SubjectType.BODY even when its lower-body landmarks are visible, so FocusEngine output is
 * bit-for-bit the legacy path.
 */
class DeadSubjectTypeGoldenLockTest {

    @After
    fun reset() = DeadSubjectTypeFlag.set(true) // restore the default-ON state for other test classes

    private fun rect(l: Float, t: Float, r: Float, b: Float) =
        RectF().apply { left = l; top = t; right = r; bottom = b }

    private fun face(id: Int, l: Float, t: Float, r: Float, b: Float, conf: Float) = TrackedFace(
        trackingId = id, boundingBox = rect(l, t, r, b),
        leftEye = null, rightEye = null, confidence = conf,
        velocityX = 0f, velocityY = 0f, timestampMs = 1_000L
    )

    private fun clusteredFaces() = listOf(
        face(1, 0.225f, 0.3f, 0.375f, 0.5f, 0.85f),
        face(2, 0.425f, 0.3f, 0.575f, 0.5f, 0.85f),
        face(3, 0.625f, 0.3f, 0.775f, 0.5f, 0.85f)
    )

    private fun poseWithLowerBody() = TrackedPose(
        trackingId = 7, boundingBox = rect(0.3f, 0.2f, 0.7f, 0.9f),
        landmarks = (0 until 33).map {
            PoseLandmarkPoint(0.5f, 0.5f, 0f, if (it in setOf(23, 24, 25, 26)) 0.9f else 0f)
        },
        confidence = 0.9f, velocityX = 0f, velocityY = 0f, timestampMs = 1_000L
    )

    private fun select(faces: List<TrackedFace>, pose: TrackedPose?) =
        FocusEngine().selectSubjectAwareness(
            faces = faces, pose = pose, objects = emptyList(),
            segmentation = null, depthData = null,
            sceneType = SceneType.GENERAL, timestampMs = 1_000L
        )

    @Test
    fun flagOff_clusteredFacesStillPickAFace_noGroup() {
        DeadSubjectTypeFlag.set(false)
        val r = select(clusteredFaces(), null)
        assertNotNull(r.primarySubject)
        assertEquals(SubjectType.FACE, r.primarySubject!!.subjectType)
    }

    @Test
    fun flagOff_poseWithLowerBodyStaysBody() {
        DeadSubjectTypeFlag.set(false)
        val r = select(emptyList(), poseWithLowerBody())
        assertNotNull(r.primarySubject)
        assertEquals(SubjectType.BODY, r.primarySubject!!.subjectType)
    }
}
