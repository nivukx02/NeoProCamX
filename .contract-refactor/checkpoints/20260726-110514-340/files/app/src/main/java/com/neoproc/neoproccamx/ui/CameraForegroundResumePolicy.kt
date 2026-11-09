package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraState

internal data class CameraForegroundResumeDecision(
    val shouldOpenNow: Boolean = false,
    val shouldWaitForClose: Boolean = false,
    val shouldRecycleActiveSession: Boolean = false
)

internal object CameraForegroundResumePolicy {
    fun resolve(
        hasPreviewSurface: Boolean,
        cameraState: CameraState,
        closeInFlight: Boolean,
        recordingOrStopping: Boolean
    ): CameraForegroundResumeDecision {
        if (!hasPreviewSurface) return CameraForegroundResumeDecision()
        if (recordingOrStopping || cameraState == CameraState.Recording) {
            return CameraForegroundResumeDecision()
        }
        if (closeInFlight) {
            return CameraForegroundResumeDecision(shouldWaitForClose = true)
        }
        return when (cameraState) {
            CameraState.Open,
            CameraState.Streaming -> CameraForegroundResumeDecision(shouldRecycleActiveSession = true)
            CameraState.Opening,
            CameraState.Configuring,
            CameraState.Reconfiguring,
            CameraState.Closing -> CameraForegroundResumeDecision()
            else -> CameraForegroundResumeDecision(shouldOpenNow = true)
        }
    }
}
