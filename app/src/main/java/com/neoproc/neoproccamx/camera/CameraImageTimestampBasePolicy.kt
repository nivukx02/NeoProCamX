package com.neoproc.neoproccamx.camera

import android.hardware.camera2.params.OutputConfiguration
import android.os.Build

/**
 * Keeps app-owned ImageReader pixels and Camera2 metadata in the same clock domain.
 *
 * Android's DEFAULT timestamp base is MONOTONIC for an ImageReader carrying VIDEO_ENCODE usage,
 * but SENSOR for an ordinary analysis/RAW reader. Mixing those defaults made the same capture
 * appear as two frames and made callback-age based normalization depend on queue latency.
 * Selecting SENSOR at the producer removes that ambiguity without modifying any sensor timestamp,
 * pixel, frame rate, usage flag, or codec/audio presentation timestamp.
 *
 * Display surfaces and direct MediaCodec inputs retain their platform timing behavior. API < 33
 * retains the existing timebase reconciliation; it must not claim this explicit producer contract.
 */
internal object CameraImageTimestampBasePolicy {
    fun requiresSensorBase(sdkInt: Int, appOwnedImageReader: Boolean): Boolean =
        sdkInt >= 33 && appOwnedImageReader

    fun apply(output: OutputConfiguration, appOwnedImageReader: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            !requiresSensorBase(Build.VERSION.SDK_INT, appOwnedImageReader)
        ) {
            return false
        }
        output.timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR
        check(output.timestampBase == OutputConfiguration.TIMESTAMP_BASE_SENSOR) {
            "Camera ImageReader sensor timestamp base was not retained"
        }
        return true
    }
}
