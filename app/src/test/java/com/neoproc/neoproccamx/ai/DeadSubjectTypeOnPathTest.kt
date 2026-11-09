package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * AF-SD (F09) — ON-path. With DeadSubjectTypeFlag ON: a cluster of comparable faces becomes a single
 * GROUP primary (member faces suppressed); a dominant single face still wins as FACE (dominance
 * guard); a pose is classified UPPER_BODY vs FULL_BODY from lower-body landmark visibility.
 */
class DeadSubjectTypeOnPathTest {

    @After
    fun reset() = DeadSubjectTypeFlag.set(true) // restore the default-ON state for other test classes

    private fun rect(l: Float, t: Float, r: Float, b: Float) =
        RectF().apply { left = l; top = t; right = r; bottom = b }

    private fun face(id: Int, l: Float, t: Float, r: Float, b: Float, conf: Float) = TrackedFace(
        trackingId = id, boundingBox = rect(l, t, r, b),
        leftEye = null, rightEye = null, confidence = conf,
        velocityX = 0f, velocityY = 0f, timestampMs = 1_000L
    )

    private fun pose(landmarks: List<PoseLandmarkPoint>) = TrackedPose(
        trackingId = 7, boundingBox = rect(0.3f, 0.2f, 0.7f, 0.9f),
        landmarks = landmarks, confidence = 0.9f,
        velocityX = 0f, velocityY = 0f, timestampMs = 1_000L
    )

    private fun lower(indices: Set<Int>) = (0 until 33).map {
        PoseLandmarkPoint(0.5f, 0.5f, 0f, if (it in indices) 0.9f else 0f)
    }

    private fun select(faces: List<TrackedFace>, pose: TrackedPose?) =
        FocusEngine().selectSubjectAwareness(
            faces = faces, pose = pose, objects = emptyList(),
            segmentation = null, depthData = null,
            sceneType = SceneType.GENERAL, timestampMs = 1_000L
        )

    @Test
    fun flagOn_clusteredComparableFaces_primaryIsGroup() {
        DeadSubjectTypeFlag.set(true)
        val r = select(
            listOf(
                face(1, 0.225f, 0.3f, 0.375f, 0.5f, 0.85f),
                face(2, 0.425f, 0.3f, 0.575f, 0.5f, 0.85f),
                face(3, 0.625f, 0.3f, 0.775f, 0.5f, 0.85f)
            ),
            null
        )
        assertNotNull(r.primarySubject)
        assertEquals(SubjectType.GROUP, r.primarySubject!!.subjectType)
        assertEquals(-900, r.primarySubject.trackingId)
    }

    @Test
    fun flagOn_dominantFace_staysFaceNotGroup() {
        DeadSubjectTypeFlag.set(true)
        val r = select(
            listOf(
                face(1, 0.30f, 0.30f, 0.50f, 0.50f, 0.95f),
                face(2, 0.50f, 0.30f, 0.70f, 0.50f, 0.45f)
            ),
            null
        )
        assertNotNull(r.primarySubject)
        assertEquals(SubjectType.FACE, r.primarySubject!!.subjectType)
        assertEquals(1, r.primarySubject.trackingId)
    }

    @Test
    fun flagOn_poseVisibleLowerBody_isFullBody() {
        DeadSubjectTypeFlag.set(true)
        val r = select(emptyList(), pose(lower(setOf(23, 24, 25, 26))))
        assertNotNull(r.primarySubject)
        assertEquals(SubjectType.FULL_BODY, r.primarySubject!!.subjectType)
    }

    @Test
    fun flagOn_poseNoLowerBody_isUpperBody() {
        DeadSubjectTypeFlag.set(true)
        val r = select(emptyList(), pose(emptyList()))
        assertNotNull(r.primarySubject)
        assertEquals(SubjectType.UPPER_BODY, r.primarySubject!!.subjectType)
    }
}
