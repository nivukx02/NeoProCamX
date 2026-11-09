package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * AF-SD (F10) — no-downgrade golden. With HeadSubjectFlag OFF (default): an averted/profile face
 * with unusable eyes still resolves to SubjectType.FACE (never HEAD), and a bird object keeps the
 * legacy inline-map category (OBJECT), so FocusEngine output is bit-for-bit the legacy path.
 */
class HeadSubjectGoldenLockTest {

    @After
    fun reset() = HeadSubjectFlag.set(true) // restore the default-ON state for other test classes

    private fun rect(l: Float, t: Float, r: Float, b: Float) =
        RectF().apply { left = l; top = t; right = r; bottom = b }

    private fun avertedFace() = TrackedFace(
        trackingId = 3,
        boundingBox = rect(0.3f, 0.2f, 0.7f, 0.8f),
        leftEye = null,
        rightEye = null,
        confidence = 0.9f,
        velocityX = 0f,
        velocityY = 0f,
        timestampMs = 1_000L,
        headPoseYaw = 60f, // strongly averted (>= AVERTED_YAW_DEG), eyes unusable
        occlusionLevel = 0f
    )

    private fun bird() = TrackedObject(
        trackingId = 9,
        boundingBox = rect(0.3f, 0.3f, 0.6f, 0.6f),
        confidence = 0.9f,
        classId = 16,
        className = "bird",
        velocityX = 0f,
        velocityY = 0f,
        timestampMs = 1_000L,
        category = SubjectCategory.ANIMAL // what ObjectTracker would emit; ignored while flag OFF
    )

    private fun select(faces: List<TrackedFace>, objects: List<TrackedObject>) =
        FocusEngine().selectSubjectAwareness(
            faces = faces, pose = null, objects = objects,
            segmentation = null, depthData = null,
            sceneType = SceneType.GENERAL, timestampMs = 1_000L
        )

    @Test
    fun flagOff_avertedFaceStaysFace_notHead() {
        HeadSubjectFlag.set(false)
        val r = select(listOf(avertedFace()), emptyList())
        assertNotNull(r.primarySubject)
        assertEquals(SubjectType.FACE, r.primarySubject!!.subjectType)
        assertEquals(SubjectType.FACE, r.focusPoint!!.subjectType)
    }

    @Test
    fun flagOff_birdCategoryStaysObject_bitForBit() {
        HeadSubjectFlag.set(false)
        val r = select(emptyList(), listOf(bird()))
        assertNotNull(r.primarySubject)
        assertEquals(SubjectCategory.OBJECT, r.primarySubject!!.category)
    }
}
