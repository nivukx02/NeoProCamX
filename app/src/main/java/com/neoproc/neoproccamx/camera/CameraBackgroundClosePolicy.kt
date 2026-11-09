package com.neoproc.neoproccamx.camera

internal data class CameraBackgroundCloseDecision(
    val requestRecordingStop: Boolean = false,
    val closeCameraImmediately: Boolean = false,
    val deferCameraCloseUntilRecordingStops: Boolean = false
)

internal object CameraBackgroundClosePolicy {
    fun resolve(
        isRecordingActive: Boolean,
        isRecordingStopping: Boolean
    ): CameraBackgroundCloseDecision {
        return when {
            isRecordingActive -> CameraBackgroundCloseDecision(
                requestRecordingStop = false,
                deferCameraCloseUntilRecordingStops = true
            )
            isRecordingStopping -> CameraBackgroundCloseDecision(
                deferCameraCloseUntilRecordingStops = true
            )
            else -> CameraBackgroundCloseDecision(
                closeCameraImmediately = true
            )
        }
    }
}
