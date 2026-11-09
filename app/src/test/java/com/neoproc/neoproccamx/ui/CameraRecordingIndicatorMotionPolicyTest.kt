package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRecordingIndicatorMotionPolicyTest {

    @Test
    fun recordingIndicatorAcknowledgesStartThenSettlesWithoutAnEndlessRedrawLoop() {
        assertTrue(CameraRecordingIndicatorMotionPolicy.START_ACKNOWLEDGEMENT_DURATION_MS > 0L)
        assertTrue(
            CameraRecordingIndicatorMotionPolicy.SETTLED_ALPHA in 0.01f..1.0f
        )
        assertFalse(CameraRecordingIndicatorMotionPolicy.REPEATS_WHILE_RECORDING)
    }
}
