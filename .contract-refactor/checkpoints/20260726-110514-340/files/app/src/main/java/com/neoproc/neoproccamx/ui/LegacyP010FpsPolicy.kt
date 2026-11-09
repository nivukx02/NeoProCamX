package com.neoproc.neoproccamx.ui

import android.util.Size

internal object LegacyP010FpsPolicy {
    private const val HIGH_RESOLUTION_PIXEL_THRESHOLD = 7_000_000L
    private const val HIGH_RESOLUTION_SUSTAINABLE_FPS = 25

    // Record-then-develop LIGHT lane: full-sensor pixel gate + lifted cadence (mirrors ProcessedGpuRecordFpsPolicy).
    private const val LIGHT_RECORD_FULL_SENSOR_PIXELS = 12_000_000L
    private const val LIGHT_RECORD_FULL_SENSOR_FPS = 30

    fun resolve(requestedFps: Int, encoderSize: Size): Int {
        return resolve(
            requestedFps = requestedFps,
            width = encoderSize.width,
            height = encoderSize.height
        )
    }

    fun resolve(requestedFps: Int, width: Int, height: Int): Int {
        val safeRequestedFps = requestedFps.coerceAtLeast(1)
        val pixelCount = width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0).toLong()
        // Record-then-develop LIGHT lane (reversible, default-OFF): don't re-clamp full-sensor back to 25 on
        // the legacy ByteBuffer-P010 fallback route, so the lifted cadence stays coherent if it ever engages.
        if (com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag.enabled &&
            pixelCount >= LIGHT_RECORD_FULL_SENSOR_PIXELS
        ) {
            // Full-sensor constant-fps (reversible, default ON): pin full-sensor to a constant 25 on the
            // legacy ByteBuffer-P010 fallback too. Flag OFF ⇒ the 30 lift is byte-identical.
            return safeRequestedFps.coerceAtMost(
                com.neoproc.neoproccamx.recording.FullSensorConstantFpsPolicy
                    .lightLaneFullSensorFps(LIGHT_RECORD_FULL_SENSOR_FPS)
            )
        }
        val highResolutionP010 = pixelCount >= HIGH_RESOLUTION_PIXEL_THRESHOLD
        return if (
            highResolutionP010 &&
            safeRequestedFps > HIGH_RESOLUTION_SUSTAINABLE_FPS
        ) {
            HIGH_RESOLUTION_SUSTAINABLE_FPS
        } else {
            safeRequestedFps
        }
    }
}
