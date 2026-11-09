package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.utils.OrientationManager

internal object CameraRecordingOrientationPolicy {
    fun resolve(
        frameGeometry: FrameGeometry?,
        sensorOrientationDegrees: Int,
        deviceRotationDegrees: Int,
        lensFacing: Int
    ): Int =
        frameGeometry?.videoOrientationHintDegrees
            ?: OrientationManager.getSensorToOutputRotation(
                sensorOrientation = sensorOrientationDegrees,
                deviceRotationDegrees = deviceRotationDegrees,
                lensFacing = lensFacing
            )
}
