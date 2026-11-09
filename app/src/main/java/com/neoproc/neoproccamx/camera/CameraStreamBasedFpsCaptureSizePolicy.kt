package com.neoproc.neoproccamx.camera

/** Keeps session-level vendor FPS parameters stable across prearmed packet-capture start/stop. */
internal object CameraStreamBasedFpsCaptureSizePolicy {
    fun resolve(
        npv10CaptureEnabled: Boolean,
        npv10CaptureSize: CameraStreamSize?,
        rawPrearmEnabled: Boolean,
        rawCaptureEnabled: Boolean,
        rawReadoutSize: CameraStreamSize?,
        encoderSize: CameraStreamSize?
    ): CameraStreamSize? = when {
        npv10CaptureEnabled && npv10CaptureSize != null -> npv10CaptureSize
        (rawPrearmEnabled || rawCaptureEnabled) && rawReadoutSize != null -> rawReadoutSize
        else -> encoderSize
    }
}
