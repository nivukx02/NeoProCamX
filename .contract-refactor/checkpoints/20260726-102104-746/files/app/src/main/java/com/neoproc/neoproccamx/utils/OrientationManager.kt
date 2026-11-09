package com.neoproc.neoproccamx.utils

import android.hardware.camera2.CameraCharacteristics
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

object OrientationManager {
    private const val RIGHT_ANGLE_DEGREES = 90
    private const val STANDARD_QUANTIZE_BIAS = 44
    private const val STRICT_QUANTIZE_BIAS = 45

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun normalizeDegrees(degrees: Int): Int = ((degrees % 360) + 360) % 360

    fun isRightAngleRotation(degrees: Int): Boolean =
        normalizeDegrees(degrees) % RIGHT_ANGLE_DEGREES == 0

    fun quantizePhysicalDeviceRotation(
        orientationDegrees: Int,
        fallbackRotationDegrees: Int = 0
    ): Int {
        if (orientationDegrees !in 0..359) {
            return normalizeDegrees(fallbackRotationDegrees)
        }
        return quantizeToRightAngle(orientationDegrees)
    }

    fun getSensorToOutputRotation(
        sensorOrientation: Int,
        deviceRotationDegrees: Int,
        lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    ): Int {
        val normalizedSensor = quantizeToRightAngle(sensorOrientation)
        val normalizedDevice = quantizeToRightAngle(deviceRotationDegrees)
        return when (lensFacing) {
            CameraCharacteristics.LENS_FACING_FRONT ->
                normalizeDegrees(normalizedSensor + normalizedDevice)
            else ->
                normalizeDegrees(normalizedSensor - normalizedDevice)
        }
    }

    /** Camera2 JPEG orientation uses the same sensor-to-output rotation as video metadata. */
    fun getJpegOrientation(
        sensorOrientation: Int,
        deviceRotationDegrees: Int,
        lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
    ): Int =
        getSensorToOutputRotation(
            sensorOrientation = sensorOrientation,
            deviceRotationDegrees = deviceRotationDegrees,
            lensFacing = lensFacing
        )

    /** Maps a clockwise output rotation to the TIFF/EXIF orientation tag used by DNG/JPEG. */
    fun exifOrientationForRotation(rotationDegrees: Int): Int =
        when (normalizeDegrees(rotationDegrees)) {
            90 -> 6
            180 -> 3
            270 -> 8
            else -> 1
        }

    private fun quantizeToRightAngle(degrees: Int): Int {
        val normalized = normalizeDegrees(degrees)
        val bias = if (strictHighEndEnabled()) STRICT_QUANTIZE_BIAS else STANDARD_QUANTIZE_BIAS
        val bucketed = ((normalized + bias) / RIGHT_ANGLE_DEGREES) * RIGHT_ANGLE_DEGREES
        return normalizeDegrees(bucketed)
    }
}
