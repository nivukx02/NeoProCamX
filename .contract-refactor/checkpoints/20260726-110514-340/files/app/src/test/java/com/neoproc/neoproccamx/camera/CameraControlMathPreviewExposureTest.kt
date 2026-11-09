package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression lock for [CameraControlMath.previewSafeExposure] (Long overload).
 *
 * Pins the fix for a real crash: the cinema shutter cap (frameBudget * shutterRatio) can fall below
 * the device exposure floor, which made the final `coerceIn(lower, min(cap, upper))` throw
 * `IllegalArgumentException: Cannot coerce value to an empty range` (max < min).
 */
class CameraControlMathPreviewExposureTest {

    private val ms = 1_000_000L

    @Test
    fun `does not throw when the cinema cap falls below the exposure floor`() {
        // 30fps frame budget = 33.3ms; 180-degree cap = 16.6ms < 20ms floor -> old code threw.
        val result = CameraControlMath.previewSafeExposure(
            requestedExposureNs = 33 * ms,
            previewFps = 30,
            exposureLowerNs = 20 * ms,
            exposureUpperNs = 100 * ms,
            maxShutterAngleDegrees = 180f
        )
        // Upper bound clamped up to the floor -> the floor is returned.
        assertEquals(20 * ms, result)
    }

    @Test
    fun `small shutter angle does not throw and clamps to the cap`() {
        // 90-degree shutter -> ratio 0.25 -> cap = 8.33ms (above the 5ms floor here).
        val result = CameraControlMath.previewSafeExposure(
            requestedExposureNs = 33 * ms,
            previewFps = 30,
            exposureLowerNs = 5 * ms,
            exposureUpperNs = 100 * ms,
            maxShutterAngleDegrees = 90f
        )
        assertTrue("result must be within [floor, cap]", result in (5 * ms)..(9 * ms))
    }

    @Test
    fun `normal case clamps the request to the 180-degree cinema cap`() {
        // floor 1ms, 30fps -> cap 16.6ms; request 30ms -> capped at ~16.6ms.
        val result = CameraControlMath.previewSafeExposure(
            requestedExposureNs = 30 * ms,
            previewFps = 30,
            exposureLowerNs = 1 * ms,
            exposureUpperNs = 100 * ms,
            maxShutterAngleDegrees = 180f
        )
        assertEquals(16_666_666L, result)
    }

    @Test
    fun `request within range is returned unchanged`() {
        val result = CameraControlMath.previewSafeExposure(
            requestedExposureNs = 8 * ms,
            previewFps = 30,
            exposureLowerNs = 1 * ms,
            exposureUpperNs = 100 * ms,
            maxShutterAngleDegrees = 180f
        )
        assertEquals(8 * ms, result)
    }

    @Test
    fun `result is always within the device exposure bounds`() {
        for (fps in intArrayOf(24, 30, 60, 120)) {
            for (lower in longArrayOf(1 * ms, 10 * ms, 25 * ms)) {
                val upper = 100 * ms
                val r = CameraControlMath.previewSafeExposure(50 * ms, fps, lower, upper, 180f)
                assertTrue("fps=$fps lower=$lower -> $r", r in lower..upper)
            }
        }
    }
}
