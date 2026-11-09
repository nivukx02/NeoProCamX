package com.neoproc.neoproccamx.niv99

/**
 * Selects the Camera2 metadata depth required by the pixel domain that owns the frame.
 *
 * Processed Video/Pro frames already contain ISP black-level, lens-shading, and noise correction.
 * Reading and copying the RAW-only dynamic payload again on every 40 ms callback wastes the camera
 * thread budget and, after a mode switch, can accidentally carry sensor-linear calibration into a
 * processed frame. Sensor-linear RAW and demosaiced RAW continue to receive the complete per-frame
 * payload without any reduction in RAW fidelity.
 */
internal enum class Niv99CaptureMetadataDepth {
    PROCESSED,
    SENSOR_LINEAR
}

internal object Niv99CaptureMetadataRoutingPolicy {
    fun resolve(signalDomain: Niv99SignalDomain): Niv99CaptureMetadataDepth = when (signalDomain) {
        Niv99SignalDomain.SENSOR_LINEAR_RAW,
        Niv99SignalDomain.SENSOR_LINEAR_DEMOSAICED -> Niv99CaptureMetadataDepth.SENSOR_LINEAR

        Niv99SignalDomain.LINEAR_RGB,
        Niv99SignalDomain.P010_YUV,
        Niv99SignalDomain.DISPLAY_RGB -> Niv99CaptureMetadataDepth.PROCESSED
    }
}
