package com.neoproc.neoproccamx.ui

/**
 * Keeps the Camera2 preview transport sensor-native while display aspect changes are handled by the
 * presentation layer. This preserves maximum source coverage and prevents cosmetic 1:1/4:3/16:9
 * changes from replacing live Camera2/EGL surfaces.
 */
internal object PreviewTransportAspectPolicy {
    private const val DEFAULT_SENSOR_ASPECT = 4f / 3f

    fun resolve(sensorAspect: Float?, currentStreamAspect: Float?): Float {
        val authoritative = sensorAspect?.takeIf { it.isFinite() && it > 0f }
            ?: currentStreamAspect?.takeIf { it.isFinite() && it > 0f }
            ?: DEFAULT_SENSOR_ASPECT
        return SensorCropEngine.normalizeLongShortRatio(authoritative)
    }
}
