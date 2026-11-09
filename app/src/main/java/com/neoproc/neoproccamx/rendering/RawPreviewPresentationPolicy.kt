package com.neoproc.neoproccamx.rendering

/**
 * Selects the visible RAW monitor without changing the RAW_SENSOR master.
 *
 * A full sensor debayer is useful when it can finish inside one preview frame. On ultra-high-resolution
 * sensors it competes with Camera2 session delivery and makes a lower-quality monitor in practice:
 * stale frames, frozen orientation, and clipped highlights. Those sensors use the same high-quality,
 * orientation-correct camera monitor as the other video modes. The full-resolution RAW reader is then
 * attached only when recording starts, so monitor work can never steal bandwidth from the RAW master.
 */
internal object RawPreviewPresentationPolicy {
    const val MAX_REALTIME_DEBAYER_PIXELS = 16_000_000L

    enum class Path {
        CAMERA_MONITOR,
        SENSOR_DEBAYER
    }

    fun resolve(
        rawModeActive: Boolean,
        recordingActive: Boolean,
        sensorWidth: Int,
        sensorHeight: Int,
        maxRealtimeDebayerPixels: Long = MAX_REALTIME_DEBAYER_PIXELS
    ): Path {
        if (!rawModeActive || recordingActive) return Path.CAMERA_MONITOR
        val pixels = sensorWidth.coerceAtLeast(0).toLong() * sensorHeight.coerceAtLeast(0).toLong()
        return if (pixels in 1..maxRealtimeDebayerPixels.coerceAtLeast(1L)) {
            Path.SENSOR_DEBAYER
        } else {
            Path.CAMERA_MONITOR
        }
    }
}
