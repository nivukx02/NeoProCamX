package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s7-f06 / CP96 §4.2 — subordination GOLDEN LOCK. A confident, large, central OBJECT must never
 * outrank a present FACE in FocusEngine subject selection (face > body > object priority). Pins the
 * existing priority constants so any regression is visible.
 */
class ObjectSubordinationGoldenLockTest {

    private fun rect(l: Float, t: Float, r: Float, b: Float) =
        RectF().apply { left = l; top = t; right = r; bottom = b }

    @Test
    fun confidentCentralObjectNeverOutranksAFace() {
        val result = FocusEngine().selectSubjectAwareness(
            faces = listOf(
                TrackedFace(
                    trackingId = 1,
                    boundingBox = rect(0.35f, 0.25f, 0.65f, 0.75f),
                    leftEye = null,
                    rightEye = null,
                    confidence = 0.70f,
                    velocityX = 0f,
                    velocityY = 0f,
                    timestampMs = 1_000L
                )
            ),
            pose = null,
            objects = listOf(
                TrackedObject(
                    trackingId = 80,
                    boundingBox = rect(0.02f, 0.02f, 0.98f, 0.98f),
                    confidence = 0.99f,
                    classId = 3,
                    className = "car",
                    velocityX = 0f,
                    velocityY = 0f,
                    timestampMs = 1_000L
                )
            ),
            segmentation = null,
            depthData = null,
            sceneType = SceneType.GENERAL,
            timestampMs = 1_000L
        )
        assertNotNull(result.primarySubject)
        assertEquals("face must win over the object", 1, result.primarySubject!!.trackingId)
        assertTrue(
            "primary must be FACE/EYE, never OBJECT",
            result.primarySubject.subjectType == SubjectType.FACE ||
                result.primarySubject.subjectType == SubjectType.EYE
        )
    }
}
