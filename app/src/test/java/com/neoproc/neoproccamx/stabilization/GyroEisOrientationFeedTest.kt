package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GyroEisOrientationFeedTest {

    private fun sample(tNs: Long, gz: Float = 0f) =
        CameraMotionTelemetry(
            timestampNs = tNs,
            oisShiftXPixels = 0f,
            oisShiftYPixels = 0f,
            gyroXRadPerSec = 0f,
            gyroYRadPerSec = 0f,
            gyroZRadPerSec = gz,
        )

    /** Constant ω about Z, samples every 10 ms over [0, totalSeconds]. */
    private fun stream(omega: Float, totalSeconds: Double): List<CameraMotionTelemetry> {
        val steps = (totalSeconds * 100).toInt()
        return (0..steps).map { i -> sample((i * 10_000_000L), gz = omega) }
    }

    @Test
    fun underSampledOrNoTimingYieldsNull() {
        assertNull(GyroEisOrientationFeed.frameOrientation(emptyList(), 1_000_000L, 20_000_000L))
        assertNull(GyroEisOrientationFeed.frameOrientation(listOf(sample(0L, 0.5f)), 1_000_000L, 20_000_000L))
        // No frame timing -> null (no warp can fire).
        assertNull(GyroEisOrientationFeed.frameOrientation(stream(0.5f, 1.0), sensorTimestampNs = 0L, exposureDurationNs = 20_000_000L))
    }

    @Test
    fun publishesMidOrientationAndAcrossExposureRotation() {
        val omega = 0.5f // rad/s about Z
        val samples = stream(omega, totalSeconds = 1.0)
        val frameStart = 400_000_000L // 0.4 s
        val exposure = 20_000_000L    // 20 ms
        val feed = GyroEisOrientationFeed.frameOrientation(samples, frameStart, exposure, gyroToCameraOffsetNs = 0L)
        assertNotNull(feed)
        feed!!
        // Window mid is start + exposure/2 in the gyro domain (offset 0).
        assertEquals(frameStart + exposure / 2, feed.frameMidTimestampNs)
        // Absolute orientation at the exposure mid (relative to ring start at t=0): ω * 0.41 s = 0.205 rad.
        assertEquals(0.205f, feed.orientationAtFrameMid.angleRad(), 3e-3f)
        assertEquals(0.205f, feed.orientationAtFrameMid.rotationVector()[2], 3e-3f)
        // Rotation across the 20 ms exposure window: ω * 0.02 s = 0.01 rad.
        assertEquals(0.01f, feed.acrossExposureRotation.angleRad(), 1e-3f)
    }
}
