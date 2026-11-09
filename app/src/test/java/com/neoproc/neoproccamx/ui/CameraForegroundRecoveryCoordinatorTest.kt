package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraForegroundRecoveryCoordinatorTest {
    @Test
    fun previewFreshnessUsesTheBoundedFrameWindow() {
        val coordinator = CameraForegroundRecoveryCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            cameraState = MutableStateFlow<CameraState>(CameraState.Streaming),
            hasPreviewSurface = { true },
            closeInFlight = { false },
            recordingOrStopping = { false },
            recoverStalePreview = {}
        )

        coordinator.onPreviewFrame(nowElapsedMs = 1_000L)

        assertTrue(coordinator.isPreviewFresh(nowElapsedMs = 1_650L))
        assertFalse(coordinator.isPreviewFresh(nowElapsedMs = 1_651L))
        coordinator.cancel()
    }
}
