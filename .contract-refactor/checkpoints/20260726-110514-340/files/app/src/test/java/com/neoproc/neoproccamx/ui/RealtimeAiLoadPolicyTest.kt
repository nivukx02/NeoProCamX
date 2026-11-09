package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeAiLoadPolicyTest {

    @Test
    fun `recording disables realtime ai even when user enabled it for idle preview`() {
        val state = RealtimeAiLoadPolicy.resolve(
            startupBlocked = false,
            aiAssistEnabled = true,
            recordingOrHighLoad = true,
            faceTrackingEnabled = true,
            eyeTrackingEnabled = true,
            subjectMaskEnabled = false,
            cinemaAutoLockActive = false
        )

        assertFalse(state.faceDetectionEnabled)
        assertFalse(state.eyeTrackingEnabled)
        assertFalse(state.aiAutoFocusEnabled)
        assertFalse(state.poseTrackingEnabled)
        assertFalse(state.enhancementEnabled)
        assertFalse(state.depthEnabled)
        assertFalse(state.sceneDetectionEnabled)
    }

    @Test
    fun `idle preview honors explicit face and eye settings`() {
        val state = RealtimeAiLoadPolicy.resolve(
            startupBlocked = false,
            aiAssistEnabled = false,
            recordingOrHighLoad = false,
            faceTrackingEnabled = true,
            eyeTrackingEnabled = true,
            subjectMaskEnabled = false,
            cinemaAutoLockActive = false
        )

        assertTrue(state.faceDetectionEnabled)
        assertTrue(state.eyeTrackingEnabled)
        assertTrue(state.aiAutoFocusEnabled)
        assertTrue(state.poseTrackingEnabled)
        assertFalse(state.enhancementEnabled)
    }

    @Test
    fun `startup blocks all realtime ai stages`() {
        val state = RealtimeAiLoadPolicy.resolve(
            startupBlocked = true,
            aiAssistEnabled = true,
            recordingOrHighLoad = false,
            faceTrackingEnabled = true,
            eyeTrackingEnabled = true,
            subjectMaskEnabled = true,
            cinemaAutoLockActive = false
        )

        assertFalse(state.faceDetectionEnabled)
        assertFalse(state.eyeTrackingEnabled)
        assertFalse(state.aiAutoFocusEnabled)
        assertFalse(state.enhancementEnabled)
    }
}
