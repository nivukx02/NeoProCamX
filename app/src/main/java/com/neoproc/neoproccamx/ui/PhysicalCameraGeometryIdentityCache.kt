package com.neoproc.neoproccamx.ui

import android.hardware.camera2.CameraCharacteristics

/**
 * Resolves immutable sensor geometry and colour calibration once per physical metadata snapshot.
 * The owning ViewModel retains only this bounded cache; live scene/control values stay uncached.
 * Failed HAL reads are retried and switching cameras cannot reuse another sensor's identity.
 */
internal class PhysicalCameraGeometryIdentityCache {
    private val snapshots = IdentitySnapshotCache<String, String>()

    fun resolve(characteristics: CameraCharacteristics?, cameraId: String): String =
        snapshots.resolve(characteristics, cameraId) {
            var completeRead = true
            val lensFacing = runCatching {
                characteristics?.get(CameraCharacteristics.LENS_FACING)
            }.onFailure { completeRead = false }.getOrNull() ?: -1
            val sensorOrientation = runCatching {
                characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION)
            }.onFailure { completeRead = false }.getOrNull() ?: -1
            val activeArray = runCatching {
                characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            }.onFailure { completeRead = false }.getOrNull()
            val pixelArray = runCatching {
                characteristics?.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            }.onFailure { completeRead = false }.getOrNull()
            val colorCalibrationFingerprint = listOf(
                CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1,
                CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2,
                CameraCharacteristics.SENSOR_COLOR_TRANSFORM1,
                CameraCharacteristics.SENSOR_COLOR_TRANSFORM2,
                CameraCharacteristics.SENSOR_FORWARD_MATRIX1,
                CameraCharacteristics.SENSOR_FORWARD_MATRIX2
            ).joinToString(separator = ":") { key ->
                runCatching { characteristics?.get(key)?.hashCode() ?: 0 }
                    .onFailure { completeRead = false }.getOrDefault(0).toString()
            }
            val identity = buildString {
                append(cameraId.ifBlank { "NO_CAMERA" })
                append("|facing=").append(lensFacing)
                append("|orientation=").append(sensorOrientation)
                append("|active=")
                if (activeArray == null) {
                    append("UNKNOWN")
                } else {
                    append(activeArray.left).append(',')
                        .append(activeArray.top).append(',')
                        .append(activeArray.right).append(',')
                        .append(activeArray.bottom)
                }
                append("|pixels=")
                if (pixelArray == null) {
                    append("UNKNOWN")
                } else {
                    append(pixelArray.width).append('x').append(pixelArray.height)
                }
                append("|calibration=").append(colorCalibrationFingerprint)
            }
            IdentitySnapshotCache.Computed(identity, cacheable = completeRead)
        }
}
