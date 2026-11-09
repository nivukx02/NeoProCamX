package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest

/**
 * Keeps optional Camera2 result metadata from competing with the realtime recording path.
 *
 * A lens-shading map is calibration/diagnostic metadata; requesting it does not perform lens-
 * shading correction. Processed recordings keep their independently selected SHADING_MODE (the
 * strict profile requests HIGH_QUALITY), so suppressing the map for processed preview/record streams
 * changes no pixels. RAW_SENSOR and still capture retain the map because their offline/DNG paths can
 * consume it.
 */
internal object CameraRealtimeMetadataPolicy {
    fun lensShadingMapMode(
        rawSensorActive: Boolean,
        stillCapture: Boolean
    ): Int = if (rawSensorActive || stillCapture) {
        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
    } else {
        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF
    }
}
