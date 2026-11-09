package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroExposureWindowReconcilerTest {

    private fun sample(tNs: Long, gx: Float = 0f, gy: Float = 0f, gz: Float = 0f) =
        CameraMotionTelemetry(
            timestampNs = tNs,
            oisShiftXPixels = 0f,
            oisShiftYPixels = 0f,
            gyroXRadPerSec = gx,
            gyroYRadPerSec = gy,
            gyroZRadPerSec = gz,
            source = CameraMotionTelemetry.Source.GYRO,
        )

    /** Constant-ω stream over [0, totalNs] with [steps] equal intervals (oldest-first). */
    private fun constantStream(gx: Float, gy: Float, gz: Float, totalNs: Long, steps: Int) =
        (0..steps).map { i -> sample(i.toLong() * totalNs / steps, gx, gy, gz) }

    private val oneMs = 1_000_000L

    @Test
    fun exposureWindowMapsCameraTimeIntoGyroDomain() {
        val w0 = GyroExposureWindowReconciler.exposureWindow(10 * oneMs, 8 * oneMs, gyroToCameraOffsetNs = 0L)
        assertEquals(10 * oneMs, w0.startNs)
        assertEquals(14 * oneMs, w0.midNs)
        assertEquals(18 * oneMs, w0.endNs)
        assertEquals(8 * oneMs, w0.durationNs)

        // A non-zero clock offset shifts the whole window by exactly the offset (gyroNs = cameraNs + off).
        val w1 = GyroExposureWindowReconciler.exposureWindow(10 * oneMs, 8 * oneMs, gyroToCameraOffsetNs = 2 * oneMs)
        assertEquals(12 * oneMs, w1.startNs)
        assertEquals(16 * oneMs, w1.midNs)
        assertEquals(20 * oneMs, w1.endNs)
    }

    @Test
    fun negativeExposureClampsToZeroDuration() {
        val w = GyroExposureWindowReconciler.exposureWindow(5 * oneMs, exposureDurationNs = -100L)
        assertEquals(5 * oneMs, w.startNs)
        assertEquals(5 * oneMs, w.midNs)
        assertEquals(5 * oneMs, w.endNs)
        assertEquals(0L, w.durationNs)
    }

    @Test
    fun subSampleEndpointsResolveToSubMillisecondAccuracy() {
        // 1 ms-spaced samples, but the exposure window endpoints fall BETWEEN samples (10.3 ms → 10.8 ms).
        val omega = 0.5f // rad/s about Z
        val stream = constantStream(0f, 0f, omega, totalNs = 100 * oneMs, steps = 100)
        val recon = GyroExposureWindowReconciler.reconcile(
            stream,
            sensorTimestampNs = 10_300_000L,
            exposureDurationNs = 500_000L, // 0.5 ms exposure
        )
        // Linear interpolation of ω at the clipped endpoints ⇒ exact 0.5 rad/s × 0.5 ms = 0.00025 rad.
        // A snap-to-nearest-sample reconciler would be off by up to ω×1 ms = 5e-4; 1e-6 proves interp.
        assertEquals(0.00025f, recon.atEnd.angleRad(), 1e-6f)
        assertEquals(0.00025f, recon.atEnd.rotationVector()[2], 1e-6f)
    }

    @Test
    fun orientationAtStartMidEndMatchAnalyticConstantRate() {
        val omega = 0.5f // rad/s about Z
        val stream = constantStream(0f, 0f, omega, totalNs = 100 * oneMs, steps = 100)
        val recon = GyroExposureWindowReconciler.reconcile(
            stream,
            sensorTimestampNs = 20 * oneMs,
            exposureDurationNs = 10 * oneMs, // mid = 25 ms, end = 30 ms
        )
        assertEquals(0f, recon.atStart.angleRad(), 0f)
        assertEquals(0.0025f, recon.atMid.angleRad(), 1e-5f) // 0.5 × 5 ms
        assertEquals(0.005f, recon.atEnd.angleRad(), 1e-5f) // 0.5 × 10 ms
        // acrossExposure is start⁻¹·end and start is identity, so it equals atEnd.
        assertEquals(recon.atEnd.angleRad(), recon.acrossExposure.angleRad(), 1e-7f)
    }

    @Test
    fun clockOffsetShiftsTheIntegratedWindow() {
        val omega = 0.5f
        val stream = constantStream(0f, 0f, omega, totalNs = 100 * oneMs, steps = 100)
        // Camera window [18 ms, 28 ms] + 2 ms offset ⇒ gyro window [20 ms, 30 ms] — same as the test above.
        val recon = GyroExposureWindowReconciler.reconcile(
            stream,
            sensorTimestampNs = 18 * oneMs,
            exposureDurationNs = 10 * oneMs,
            gyroToCameraOffsetNs = 2 * oneMs,
        )
        assertEquals(20 * oneMs, recon.window.startNs)
        assertEquals(30 * oneMs, recon.window.endNs)
        assertEquals(0.005f, recon.atEnd.angleRad(), 1e-5f)
    }

    @Test
    fun stillCameraYieldsIdentityOrientation() {
        val stream = constantStream(0f, 0f, 0f, totalNs = 100 * oneMs, steps = 100)
        val recon = GyroExposureWindowReconciler.reconcile(stream, sensorTimestampNs = 20 * oneMs, exposureDurationNs = 10 * oneMs)
        assertEquals(0f, recon.atMid.angleRad(), 1e-6f)
        assertEquals(0f, recon.atEnd.angleRad(), 1e-6f)
    }

    @Test
    fun yawWindowProducesRotationAboutYAxisOnly() {
        val omega = 0.3f // rad/s about Y (yaw)
        val stream = constantStream(0f, omega, 0f, totalNs = 100 * oneMs, steps = 100)
        val recon = GyroExposureWindowReconciler.reconcile(stream, sensorTimestampNs = 10 * oneMs, exposureDurationNs = 20 * oneMs)
        val rv = recon.atEnd.rotationVector()
        assertEquals(0f, rv[0], 1e-5f)
        assertEquals(0.006f, rv[1], 1e-5f) // 0.3 × 20 ms
        assertEquals(0f, rv[2], 1e-5f)
    }
}
