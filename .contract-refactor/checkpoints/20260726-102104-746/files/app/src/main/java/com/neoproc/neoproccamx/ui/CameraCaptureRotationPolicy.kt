package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.utils.OrientationManager

internal object CameraCaptureRotationPolicy {
    fun resolveCameraDeviceRotationDegrees(
        physicalDeviceRotationDegrees: Int,
        displayRotationDegrees: Int
    ): Int =
        OrientationManager.quantizePhysicalDeviceRotation(
            orientationDegrees = physicalDeviceRotationDegrees,
            fallbackRotationDegrees = displayRotationDegrees
        )
}
