package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FaceEyeFocusTargetPolicyTest {

    @Test
    fun `open eye wins over closed eye with higher raw confidence`() {
        val target = FaceEyeFocusTargetPolicy.resolve(
            face = trackedFace(
                leftEye = EyePoint(x = 0.38f, y = 0.36f, confidence = 0.98f, isOpen = false),
                rightEye = EyePoint(x = 0.62f, y = 0.37f, confidence = 0.72f, isOpen = true)
            ),
            eyePriorityEnabled = true
        )

        assertNotNull(target)
        assertEquals(SubjectType.EYE, target!!.subjectType)
        assertEquals(0.62f, target.focusX, 0.0001f)
        assertEquals(0.37f, target.focusY, 0.0001f)
    }

    @Test
    fun `eye priority falls back to upper face anchor when eyes are unusable`() {
        val target = FaceEyeFocusTargetPolicy.resolve(
            face = trackedFace(
                leftEye = EyePoint(x = 0.39f, y = 0.36f, confidence = 0.18f, isOpen = false),
                rightEye = EyePoint(x = 0.61f, y = 0.37f, confidence = 0.16f, isOpen = false)
            ),
            eyePriorityEnabled = true
        )

        assertNotNull(target)
        assertEquals(SubjectType.FACE, target!!.subjectType)
        assertEquals(0.52f, target.focusX, 0.0001f)
        assertEquals(0.416f, target.focusY, 0.0001f)
    }

    @Test
    fun `disabled eye priority uses stable face focus anchor`() {
        val target = FaceEyeFocusTargetPolicy.resolve(
            face = trackedFace(
                leftEye = EyePoint(x = 0.39f, y = 0.36f, confidence = 0.92f, isOpen = true),
                rightEye = EyePoint(x = 0.61f, y = 0.37f, confidence = 0.88f, isOpen = true)
            ),
            eyePriorityEnabled = false
        )

        assertNotNull(target)
        assertEquals(SubjectType.FACE, target!!.subjectType)
        assertEquals(0.52f, target.focusX, 0.0001f)
        assertEquals(0.416f, target.focusY, 0.0001f)
    }

    @Test
    fun `heavy occlusion rejects eye lock and keeps face anchor`() {
        val target = FaceEyeFocusTargetPolicy.resolve(
            face = trackedFace(
                leftEye = EyePoint(x = 0.39f, y = 0.36f, confidence = 0.94f, isOpen = true),
                rightEye = EyePoint(x = 0.61f, y = 0.37f, confidence = 0.93f, isOpen = true),
                occlusionLevel = 0.86f
            ),
            eyePriorityEnabled = true
        )

        assertNotNull(target)
        assertEquals(SubjectType.FACE, target!!.subjectType)
        assertEquals(0.52f, target.focusX, 0.0001f)
        assertEquals(0.416f, target.focusY, 0.0001f)
    }

    private fun trackedFace(
        leftEye: EyePoint?,
        rightEye: EyePoint?,
        occlusionLevel: Float = 0.10f
    ): TrackedFace =
        TrackedFace(
            trackingId = 7,
            boundingBox = rectF(0.27f, 0.20f, 0.77f, 0.80f),
            leftEye = leftEye,
            rightEye = rightEye,
            confidence = 0.9f,
            velocityX = 0f,
            velocityY = 0f,
            timestampMs = 1_000L,
            predictedX = 0.52f,
            predictedY = 0.50f,
            occlusionLevel = occlusionLevel
        )

    private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
}
