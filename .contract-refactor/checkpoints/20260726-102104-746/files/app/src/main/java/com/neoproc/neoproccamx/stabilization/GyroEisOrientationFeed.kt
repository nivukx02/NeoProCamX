package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry

/**
 * s3-f05 / CP60 — the per-frame orientation keystone the (still-dormant) gyro-EIS warp (s3-f06) consumes.
 *
 * For one camera frame it publishes (a) the device's absolute orientation at the exposure midpoint —
 * integrated from the GYRO-domain ring relative to the ring's oldest sample, the common reference an
 * [OrientationTrajectorySmoother] needs to build a smooth virtual-camera path across frames — and (b) the
 * rotation across the exposure window (for rolling-shutter / motion-blur). It is a pure, deterministic,
 * JVM-tested function that APPLIES NOTHING to any pixel; the production consumer in CameraController calls
 * it per frame only while the gyro-EIS consumer is enabled (DEVICE-GATED), and routes the result solely to
 * s3-f06's warp input, which stays OFF until the owner enables EIS device-side. Zero pixel change.
 */
data class EisFrameOrientation(
    /** Exposure-window midpoint in the GYRO clock domain (ns) — the timestamp for the trajectory smoother. */
    val frameMidTimestampNs: Long,
    /** Absolute orientation at the exposure mid, relative to the ring's oldest sample (the smoother's path point). */
    val orientationAtFrameMid: Quaternion,
    /** Rotation across the whole exposure window (`start⁻¹·end`) — the rolling-shutter / motion-blur term. */
    val acrossExposureRotation: Quaternion,
)

object GyroEisOrientationFeed {

    /**
     * Resolve the per-frame EIS orientation from the GYRO-domain ring view and the camera frame timing.
     * Returns null when the frame timing is unknown or the gyro window is under-sampled (< 2 samples) — in
     * which case the consumer publishes nothing and no warp can fire.
     *
     * [samples] MUST be the single-clock-domain gyro view (snapshotGyroOldestFirst), oldest first.
     */
    fun frameOrientation(
        samples: List<CameraMotionTelemetry>,
        sensorTimestampNs: Long,
        exposureDurationNs: Long,
        gyroToCameraOffsetNs: Long = 0L,
    ): EisFrameOrientation? {
        if (sensorTimestampNs <= 0L || samples.size < 2) return null
        val window = GyroExposureWindowReconciler.exposureWindow(sensorTimestampNs, exposureDurationNs, gyroToCameraOffsetNs)
        val reconciled = GyroExposureWindowReconciler.reconcile(samples, window)
        val refNs = samples.first().timestampNs
        val orientationAtMid = when {
            window.midNs > refNs -> GyroOrientationIntegrator.rotationBetween(samples, refNs, window.midNs)
            window.midNs < refNs -> GyroOrientationIntegrator.rotationBetween(samples, window.midNs, refNs).conjugate()
            else -> Quaternion.IDENTITY
        }
        return EisFrameOrientation(
            frameMidTimestampNs = window.midNs,
            orientationAtFrameMid = orientationAtMid,
            acrossExposureRotation = reconciled.acrossExposure,
        )
    }
}
