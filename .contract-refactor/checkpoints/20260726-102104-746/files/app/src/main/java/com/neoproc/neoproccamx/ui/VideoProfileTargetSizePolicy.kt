package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraStreamSize

internal object VideoProfileTargetSizePolicy {

    fun resolveTargetStreamSize(
        availableSizes: List<CameraStreamSize>,
        pixelArraySize: CameraStreamSize,
        maxRawSize: CameraStreamSize,
        fallbackSensorSize: CameraStreamSize,
        rawEnabled: Boolean,
        rawSupported: Boolean
    ): CameraStreamSize {
        val sensorMax = listOf(pixelArraySize, maxRawSize, fallbackSensorSize)
            .largestByArea()
            ?: CameraStreamSize(1920, 1080)

        if (rawEnabled && rawSupported) {
            return sensorMax
        }

        return availableSizes
            .distinctBy { "${it.width}x${it.height}" }
            .largestByArea()
            ?: CameraStreamSize(1920, 1080)
    }

    private fun List<CameraStreamSize>.largestByArea(): CameraStreamSize? =
        maxWithOrNull(compareBy<CameraStreamSize> { it.area }.thenBy { it.width }.thenBy { it.height })
}
