package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry

/**
 * s1-f04 / CP60 hand-off — reconciles the gyro clock domain with the camera SENSOR_TIMESTAMP exposure
 * window and interpolates device orientation at the exposure start / mid / end.
 *
 * The gyro stream (`TYPE_GYROSCOPE` `SensorEvent.timestamp`) and the camera `SENSOR_TIMESTAMP` are, in
 * general, DIFFERENT clock domains. On devices whose `SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME` the two
 * share `elapsedRealtimeNanos` and the offset is 0; otherwise a per-device offset must be MEASURED on
 * hardware (DEVICE-GATED). This policy takes that offset as an explicit parameter so the math is fully
 * unit-testable with synthetic clocks and applies NOTHING to any pixel — it is the timestamp-reconciled
 * hand-off the (still-dormant) gyro-EIS warp (s3-f06) and MFSR (s1-f06) consume.
 *
 * Convention: `gyroNs = cameraNs + gyroToCameraOffsetNs`. Orientations are expressed relative to the
 * exposure-window START (so [ReconciledOrientation.atStart] is identity); [ReconciledOrientation.acrossExposure]
 * is the rotation over the whole window (`start⁻¹·end`) — the term a global EIS warp / motion-blur model
 * needs. Endpoint interpolation is delegated to [GyroOrientationIntegrator.rotationBetween], which
 * linearly interpolates the angular velocity between bracketing gyro samples, so an endpoint that falls
 * BETWEEN two samples still resolves to sub-sample — hence sub-millisecond — accuracy. Pure,
 * deterministic, JVM-tested, dormant.
 */
object GyroExposureWindowReconciler {

    /** A frame's exposure window expressed in the GYRO clock domain (nanoseconds). */
    data class ExposureWindow(val startNs: Long, val midNs: Long, val endNs: Long) {
        val durationNs: Long get() = endNs - startNs
    }

    /** Interpolated orientation at the exposure-window endpoints, all relative to the window start. */
    data class ReconciledOrientation(
        val window: ExposureWindow,
        /** Orientation at exposure start — identity by definition (it is the reference instant). */
        val atStart: Quaternion,
        /** Orientation at mid-exposure relative to start — the canonical instant a global warp samples. */
        val atMid: Quaternion,
        /** Orientation at exposure end relative to start. */
        val atEnd: Quaternion,
        /** Rotation across the whole exposure window: `start⁻¹·end` (== [atEnd], start being identity). */
        val acrossExposure: Quaternion,
    )

    /**
     * Map a camera `SENSOR_TIMESTAMP` exposure window into the gyro clock domain. [sensorTimestampNs] is
     * the camera exposure-start (Camera2: time at start of exposure of the first row); [exposureDurationNs]
     * is `SENSOR_EXPOSURE_TIME`. A negative duration clamps to 0.
     */
    fun exposureWindow(
        sensorTimestampNs: Long,
        exposureDurationNs: Long,
        gyroToCameraOffsetNs: Long = 0L,
    ): ExposureWindow {
        val dur = exposureDurationNs.coerceAtLeast(0L)
        val startGyro = sensorTimestampNs + gyroToCameraOffsetNs
        val endGyro = startGyro + dur
        val midGyro = startGyro + dur / 2L
        return ExposureWindow(startGyro, midGyro, endGyro)
    }

    /**
     * Interpolated orientation at the [window]'s start / mid / end over the (oldest-first, GYRO-domain)
     * [samples]. The samples MUST be a single clock domain — feed the gyro-only ring view, never the
     * raw mixed ring (see [com.neoproc.neoproccamx.camera.CameraMotionTelemetryRingBuffer.snapshotGyroOldestFirst]).
     */
    fun reconcile(
        samples: List<CameraMotionTelemetry>,
        window: ExposureWindow,
    ): ReconciledOrientation {
        val atMid = orientationAt(samples, refNs = window.startNs, tNs = window.midNs)
        val atEnd = orientationAt(samples, refNs = window.startNs, tNs = window.endNs)
        return ReconciledOrientation(
            window = window,
            atStart = Quaternion.IDENTITY,
            atMid = atMid,
            atEnd = atEnd,
            acrossExposure = atEnd,
        )
    }

    /** Convenience: build the gyro-domain window from camera timing and reconcile in one call. */
    fun reconcile(
        samples: List<CameraMotionTelemetry>,
        sensorTimestampNs: Long,
        exposureDurationNs: Long,
        gyroToCameraOffsetNs: Long = 0L,
    ): ReconciledOrientation =
        reconcile(samples, exposureWindow(sensorTimestampNs, exposureDurationNs, gyroToCameraOffsetNs))

    /** Orientation at [tNs] relative to [refNs] (handles tNs before/after refNs); identity when equal. */
    private fun orientationAt(samples: List<CameraMotionTelemetry>, refNs: Long, tNs: Long): Quaternion = when {
        tNs > refNs -> GyroOrientationIntegrator.rotationBetween(samples, refNs, tNs)
        tNs < refNs -> GyroOrientationIntegrator.rotationBetween(samples, tNs, refNs).conjugate()
        else -> Quaternion.IDENTITY
    }
}
