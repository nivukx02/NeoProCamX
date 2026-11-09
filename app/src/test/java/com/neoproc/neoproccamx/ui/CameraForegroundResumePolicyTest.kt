package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraForegroundResumePolicyTest {

    @Test
    fun freshStreamingSession_isKeptAndVerifiedOnForegroundResume() {
        val decision = CameraForegroundResumePolicy.resolve(
            hasPreviewSurface = true,
            cameraState = CameraState.Streaming,
            closeInFlight = false,
            recordingOrStopping = false,
            previewFresh = true
        )

        assertTrue(decision.shouldVerifyActiveSession)
        assertFalse(decision.shouldRecycleActiveSession)
        assertFalse(decision.shouldOpenNow)
        assertFalse(decision.shouldWaitForClose)
    }

    @Test
    fun staleStreamingSession_recyclesOnForegroundResume() {
        val decision = CameraForegroundResumePolicy.resolve(
            hasPreviewSurface = true,
            cameraState = CameraState.Streaming,
            closeInFlight = false,
            recordingOrStopping = false,
            previewFresh = false
        )

        assertTrue(decision.shouldRecycleActiveSession)
        assertFalse(decision.shouldVerifyActiveSession)
    }

    @Test
    fun closeInFlight_waitsBeforeOpening() {
        val decision = CameraForegroundResumePolicy.resolve(
            hasPreviewSurface = true,
            cameraState = CameraState.Closing,
            closeInFlight = true,
            recordingOrStopping = false,
            previewFresh = false
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
            recordingOrStopping = false,
            previewFresh = false
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
            recordingOrStopping = true,
            previewFresh = false
        )

        assertFalse(decision.shouldOpenNow)
        assertFalse(decision.shouldWaitForClose)
        assertFalse(decision.shouldRecycleActiveSession)
    }
}
