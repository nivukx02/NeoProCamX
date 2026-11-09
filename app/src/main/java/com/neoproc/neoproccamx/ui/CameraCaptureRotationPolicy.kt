package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.utils.OrientationManager

internal object CameraCaptureRotationPolicy {
    fun resolveCameraDeviceRotationDegrees(
        physicalDeviceRotationDegrees: Int,
        displayRotationDegrees: Int
    ): Int {
        if (physicalDeviceRotationDegrees !in 0..359) {
            return OrientationManager.normalizeDegrees(displayRotationDegrees)
        }
        val physical = OrientationManager.quantizePhysicalDeviceRotation(
            orientationDegrees = physicalDeviceRotationDegrees,
            fallbackRotationDegrees = displayRotationDegrees
        )
        // OrientationEventListener reports the physical angle counter to Android display/Camera2
        // rotation in landscape. Passing it through unchanged swaps 90 and 270; on a 90-degree sensor
        // that becomes a false 180-degree MOV/JPEG orientation. Convert the physical angle into the
        // display-rotation convention before CameraRecordingOrientationPolicy applies sensor rotation.
        return OrientationManager.normalizeDegrees(360 - physical)
    }
}
