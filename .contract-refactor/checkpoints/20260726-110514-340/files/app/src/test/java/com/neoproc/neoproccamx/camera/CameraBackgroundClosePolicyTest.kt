package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraBackgroundClosePolicyTest {

    @Test
    fun recordingSessionContinuesAndDefersCloseUntilExplicitStopFinishes() {
        val decision = CameraBackgroundClosePolicy.resolve(
            isRecordingActive = true,
            isRecordingStopping = false
        )

        assertFalse(decision.requestRecordingStop)
        assertFalse(decision.closeCameraImmediately)
        assertTrue(decision.deferCameraCloseUntilRecordingStops)
    }

    @Test
    fun inFlightStopStillDefersCameraClose() {
        val decision = CameraBackgroundClosePolicy.resolve(
            isRecordingActive = false,
            isRecordingStopping = true
        )

        assertFalse(decision.requestRecordingStop)
        assertFalse(decision.closeCameraImmediately)
        assertTrue(decision.deferCameraCloseUntilRecordingStops)
    }

    @Test
    fun idleSessionClosesCameraImmediately() {
        val decision = CameraBackgroundClosePolicy.resolve(
            isRecordingActive = false,
            isRecordingStopping = false
        )

        assertFalse(decision.requestRecordingStop)
        assertTrue(decision.closeCameraImmediately)
        assertFalse(decision.deferCameraCloseUntilRecordingStops)
    }
}
