package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraState

internal data class CameraForegroundOpenDecision(
    val shouldOpenNow: Boolean,
    val shouldWaitForClose: Boolean
)

internal object CameraForegroundOpenPolicy {
    fun resolve(
        hasPreviewSurface: Boolean,
        cameraState: CameraState,
        closeInFlight: Boolean
    ): CameraForegroundOpenDecision {
        if (!hasPreviewSurface) {
            return CameraForegroundOpenDecision(
                shouldOpenNow = false,
                shouldWaitForClose = false
            )
        }
        if (closeInFlight) {
            return CameraForegroundOpenDecision(
                shouldOpenNow = false,
                shouldWaitForClose = true
            )
        }
        if (CameraModeSwitchPolicy.shouldIgnoreOpenRequest(cameraState)) {
            return CameraForegroundOpenDecision(
                shouldOpenNow = false,
                shouldWaitForClose = false
            )
        }
        return CameraForegroundOpenDecision(
            shouldOpenNow = true,
            shouldWaitForClose = false
        )
    }
}
