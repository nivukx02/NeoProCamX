package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraForegroundOpenPolicyTest {

    @Test
    fun `foreground waits for background close before reopening camera`() {
        val decision = CameraForegroundOpenPolicy.resolve(
            hasPreviewSurface = true,
            cameraState = CameraState.Closing,
            closeInFlight = true
        )

        assertFalse(decision.shouldOpenNow)
        assertTrue(decision.shouldWaitForClose)
    }

    @Test
    fun `foreground opens closed camera when surface is still available`() {
        val decision = CameraForegroundOpenPolicy.resolve(
            hasPreviewSurface = true,
            cameraState = CameraState.Closed,
            closeInFlight = false
        )

        assertTrue(decision.shouldOpenNow)
        assertFalse(decision.shouldWaitForClose)
    }

    @Test
    fun `foreground does not open until preview surface exists`() {
        val decision = CameraForegroundOpenPolicy.resolve(
            hasPreviewSurface = false,
            cameraState = CameraState.Closed,
            closeInFlight = false
        )

        assertFalse(decision.shouldOpenNow)
        assertFalse(decision.shouldWaitForClose)
    }
}
