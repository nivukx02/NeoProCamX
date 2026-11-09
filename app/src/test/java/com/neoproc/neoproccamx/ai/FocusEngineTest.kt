package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusEngineTest {

    @Test
    fun indoorScene_noLongerForcesLowLightSuggestion() {
        val result = FocusEngine().selectSubjectAwareness(
            faces = emptyList(),
            pose = null,
            segmentation = null,
            depthData = null,
            sceneType = SceneType.INDOOR,
            timestampMs = 1_000L
        )

        assertFalse(result.lowLightSuggested)
    }

    @Test
    fun brightBacklight_suggestsHdr() {
        val result = FocusEngine().selectSubjectAwareness(
            faces = emptyList(),
            pose = null,
            segmentation = null,
            depthData = null,
            sceneType = SceneType.BACKLIGHT,
            timestampMs = 1_000L
        )

        assertTrue(result.hdrSuggested)
    }

    @Test
    fun `face eye focus chooses open eye instead of closed higher confidence eye`() {
        val result = FocusEngine().selectSubjectAwareness(
            faces = listOf(
                TrackedFace(
                    trackingId = 9,
                    boundingBox = rectF(0.30f, 0.18f, 0.72f, 0.76f),
                    leftEye = EyePoint(x = 0.40f, y = 0.35f, confidence = 0.96f, isOpen = false),
                    rightEye = EyePoint(x = 0.61f, y = 0.36f, confidence = 0.70f, isOpen = true),
                    confidence = 0.91f,
                    velocityX = 0f,
                    velocityY = 0f,
                    timestampMs = 1_000L,
                    occlusionLevel = 0.05f
                )
            ),
            pose = null,
            segmentation = null,
            depthData = null,
            sceneType = SceneType.PORTRAIT,
            timestampMs = 1_000L
        )

        assertNotNull(result.focusPoint)
        assertEquals(SubjectType.EYE, result.focusPoint!!.subjectType)
        assertEquals(0.61f, result.focusPoint.focusX, 0.0001f)
        assertEquals(0.36f, result.focusPoint.focusY, 0.0001f)
    }

    private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
}
