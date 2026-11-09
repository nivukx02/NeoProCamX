package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroOrientationIntegratorTest {

    private fun sample(tNs: Long, gx: Float = 0f, gy: Float = 0f, gz: Float = 0f) =
        CameraMotionTelemetry(
            timestampNs = tNs,
            oisShiftXPixels = 0f,
            oisShiftYPixels = 0f,
            gyroXRadPerSec = gx,
            gyroYRadPerSec = gy,
            gyroZRadPerSec = gz,
        )

    /** N+1 samples uniformly spanning [0, totalSeconds] with a constant angular velocity. */
    private fun constantStream(gx: Float, gy: Float, gz: Float, totalSeconds: Double, steps: Int): List<CameraMotionTelemetry> {
        val list = ArrayList<CameraMotionTelemetry>(steps + 1)
        for (i in 0..steps) {
            val tNs = (i.toDouble() / steps * totalSeconds * 1_000_000_000.0).toLong()
            list.add(sample(tNs, gx, gy, gz))
        }
        return list
    }

    @Test
    fun fewerThanTwoSamplesIsIdentity() {
        assertEquals(0f, GyroOrientationIntegrator.integrate(emptyList()).angleRad(), 0f)
        assertEquals(0f, GyroOrientationIntegrator.integrate(listOf(sample(0L, gz = 1f))).angleRad(), 0f)
    }

    @Test
    fun constantAngularVelocityIntegratesToOmegaTimesTime() {
        val omega = 0.5f // rad/s about Z
        val q = GyroOrientationIntegrator.integrate(constantStream(0f, 0f, omega, totalSeconds = 1.0, steps = 10))
        // Single-axis, constant ω, trapezoidal integration is exact: angle == ω * T == 0.5 rad.
        assertEquals(0.5f, q.angleRad(), 1e-3f)
        assertEquals(0.5f, q.rotationVector()[2], 1e-3f)
    }

    @Test
    fun zeroGyroProducesNoRotation() {
        val q = GyroOrientationIntegrator.integrate(constantStream(0f, 0f, 0f, totalSeconds = 1.0, steps = 8))
        assertEquals(0f, q.angleRad(), 1e-5f)
    }

    @Test
    fun rotationBetweenIntegratesOnlyTheRequestedWindow() {
        val omega = 0.8f
        val stream = constantStream(0f, 0f, omega, totalSeconds = 1.0, steps = 100)
        val t0 = 200_000_000L // 0.2 s
        val t1 = 700_000_000L // 0.7 s
        val q = GyroOrientationIntegrator.rotationBetween(stream, t0, t1)
        // 0.5 s of 0.8 rad/s = 0.4 rad.
        assertEquals(0.4f, q.angleRad(), 2e-3f)
    }

    @Test
    fun longDurationConstantRotationStaysWithinDriftBound() {
        // s3-f05 — drift bound: integrating a constant ω over a long window with MANY intervals must not
        // accumulate error. 1000 trapezoidal steps over 10 s of 0.1 rad/s must still land on 1.0 rad.
        val omega = 0.1f // rad/s about Z
        val q = GyroOrientationIntegrator.integrate(constantStream(0f, 0f, omega, totalSeconds = 10.0, steps = 1000))
        assertEquals("long-duration integrated angle drifted", 1.0f, q.angleRad(), 2e-3f)
        assertEquals(1.0f, q.rotationVector()[2], 2e-3f)
    }

    @Test
    fun rotationBetweenInterpolatesSubSampleEndpoints() {
        // s3-f05 — exposure-window interpolation: with COARSE 100 ms gyro sampling, a window whose
        // endpoints fall BETWEEN samples (50 ms .. 150 ms) must still resolve to the analytic mid-exposure
        // rotation via linear ω interpolation at the clipped endpoints.
        val omega = 0.4f
        val stream = constantStream(0f, 0f, omega, totalSeconds = 1.0, steps = 10) // samples every 100 ms
        val q = GyroOrientationIntegrator.rotationBetween(stream, t0Ns = 50_000_000L, t1Ns = 150_000_000L)
        // 0.1 s of 0.4 rad/s = 0.04 rad, recovered across a sub-sample window.
        assertEquals(0.04f, q.angleRad(), 2e-3f)
        assertEquals(0.04f, q.rotationVector()[2], 2e-3f)
    }

    @Test
    fun forwardThenReverseRotationReturnsToStart() {
        val forward = constantStream(0f, 0f, 0.6f, totalSeconds = 0.5, steps = 25)
        // Continue from 0.5s..1.0s with the opposite angular velocity.
        val reverse = ArrayList<CameraMotionTelemetry>()
        for (i in 0..25) {
            val tNs = (500_000_000.0 + i.toDouble() / 25 * 500_000_000.0).toLong()
            reverse.add(sample(tNs, gz = -0.6f))
        }
        val q = GyroOrientationIntegrator.integrate(forward + reverse)
        assertTrue("net rotation should be ~0, was ${q.angleRad()}", q.angleRad() < 1e-2f)
    }
}
