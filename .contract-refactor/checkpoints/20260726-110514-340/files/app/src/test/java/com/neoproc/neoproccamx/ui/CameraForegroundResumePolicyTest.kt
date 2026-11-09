package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraForegroundResumePolicyTest {

    @Test
    fun streamingSession_recyclesOnForegroundResume() {
        val decision = CameraForegroundResumePolicy.resolve(
            hasPreviewSurface = true,
            cameraState = CameraState.Streaming,
            closeInFlight = false,
            recordingOrStopping = false
        )

        assertTrue(decision.shouldRecycleActiveSession)
        assertFalse(decision.shouldOpenNow)
        assertFalse(decision.shouldWaitForClose)
    }

    @Test
    fun closeInFlight_waitsBeforeOpening() {
        val decision = CameraForegroundResumePolicy.resolve(
            hasPreviewSurface = true,
            cameraState = CameraState.Closing,
            closeInFlight = true,
            recordingOrStopping = false
        )

        assertTrue(decision.shouldWaitForClose)
        assertFalse(decision.shouldOpenNow)
        assertFalse(decision.shouldRecycleActiveSession)
    }

    @Test
    fun closedSession_opensOnForegroundResume() {
        val decision = CameraForegroundResumePolicy.resolve(
            hasPreviewSurface = true,
            cameraState = CameraState.Closed,
            closeInFlight = false,
            recordingOrStopping = false
        )

        assertTrue(decision.shouldOpenNow)
        assertFalse(decision.shouldRecycleActiveSession)
    }

    @Test
    fun recordingSession_doesNotRecycleWhileStopCanStillBeRunning() {
        val decision = CameraForegroundResumePolicy.resolve(
            hasPreviewSurface = true,
            cameraState = CameraState.Recording,
            closeInFlight = false,
            recordingOrStopping = true
        )

        assertFalse(decision.shouldOpenNow)
        assertFalse(decision.shouldWaitForClose)
        assertFalse(decision.shouldRecycleActiveSession)
    }
}
