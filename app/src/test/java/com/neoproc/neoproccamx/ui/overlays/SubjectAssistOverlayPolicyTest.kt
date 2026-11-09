package com.neoproc.neoproccamx.ui.overlays

import android.graphics.PointF
import android.graphics.RectF
import com.neoproc.neoproccamx.ai.DetectedFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectAssistOverlayPolicyTest {

    @Test
    fun eyeAutofocusPlacesCueBetweenDetectedEyes() {
        val cues = SubjectAssistOverlayPolicy.resolve(
            faces = listOf(testFace()),
            faceDetectionEnabled = true,
            eyeAutofocusEnabled = true
        )

        val eyeCue = cues.single { it.type == SubjectAssistCueType.EYE }
        assertEquals(2, cues.size)
        assertEquals(0.50f, eyeCue.normalizedX, 0.001f)
        assertEquals(0.32f, eyeCue.normalizedY, 0.001f)
    }

    @Test
    fun eyeAutofocusDoesNotSuppressFaceCueWhenFaceDetectionIsEnabled() {
        val cues = SubjectAssistOverlayPolicy.resolve(
            faces = listOf(testFace()),
            faceDetectionEnabled = true,
            eyeAutofocusEnabled = true
        )

        val faceCue = cues.single { it.type == SubjectAssistCueType.FACE }
        assertEquals(0.50f, faceCue.normalizedX, 0.001f)
        assertEquals(0.45f, faceCue.normalizedY, 0.001f)
    }

    @Test
    fun faceDetectionPlacesCueAtFaceCenterWhenEyeAfIsOff() {
        val cues = SubjectAssistOverlayPolicy.resolve(
            faces = listOf(testFace()),
            faceDetectionEnabled = true,
            eyeAutofocusEnabled = false
        )

        assertEquals(1, cues.size)
        assertEquals(SubjectAssistCueType.FACE, cues.single().type)
        assertEquals(0.50f, cues.single().normalizedX, 0.001f)
        assertEquals(0.45f, cues.single().normalizedY, 0.001f)
    }

    @Test
    fun disabledDetectionProducesNoCues() {
        val cues = SubjectAssistOverlayPolicy.resolve(
            faces = listOf(testFace()),
            faceDetectionEnabled = false,
            eyeAutofocusEnabled = false
        )

        assertTrue(cues.isEmpty())
    }

    private fun testFace(): DetectedFace =
        DetectedFace(
            id = 7,
            bounds = RectF().apply {
                left = 0.30f
                top = 0.20f
                right = 0.70f
                bottom = 0.70f
            },
            leftEye = PointF().apply {
                x = 0.42f
                y = 0.32f
            },
            rightEye = PointF().apply {
                x = 0.58f
                y = 0.32f
            }
        )
}
