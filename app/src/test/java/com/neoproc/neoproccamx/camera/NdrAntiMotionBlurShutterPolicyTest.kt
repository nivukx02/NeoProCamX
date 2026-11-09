package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the closed-loop anti-motion-blur shutter cap ([NdrAntiMotionBlurShutterPolicy]): engage only
 * in dim (over-cap) scenes, release when a pinned bright scene falls to floor ISO, never flap, and
 * never pin bright scenes (which would blow highlights).
 */
class NdrAntiMotionBlurShutterPolicyTest {

    private val exposureRange = CameraLongRange(1_000L, 100_000_000L)
    private val frame30fps = 33_333_333L // 1/30 s

    @Test
    fun `cap is a 90-degree shutter by default (frameDuration over 4)`() {
        val cap = NdrAntiMotionBlurShutterPolicy.resolveCapNs(frame30fps, exposureRange)
        assertEquals(8_333_333L, cap)
    }

    @Test
    fun `cap is clamped up to the sensor exposure floor`() {
        val cap = NdrAntiMotionBlurShutterPolicy.resolveCapNs(
            frameDurationNs = frame30fps,
            exposureRange = CameraLongRange(12_000_000L, 100_000_000L)
        )
        assertEquals(12_000_000L, cap)
    }

    @Test
    fun `bright scene stays in AUTO (never pins) so highlights are safe`() {
        val d = NdrAntiMotionBlurShutterPolicy.resolve(
            requested = true,
            currentlyEngaged = false,
            meteredExposureNs = 2_000_000L, // 1/500 s — AE already crisp
            meteredIso = 100,
            isoFloor = 50,
            frameDurationNs = frame30fps,
            exposureRange = exposureRange
        )
        assertFalse(d.engage)
    }

    @Test
    fun `dim scene over the cap engages the pin`() {
        val d = NdrAntiMotionBlurShutterPolicy.resolve(
            requested = true,
            currentlyEngaged = false,
            meteredExposureNs = 20_000_000L, // 1/50 s — would smear
            meteredIso = 400,
            isoFloor = 50,
            frameDurationNs = frame30fps,
            exposureRange = exposureRange
        )
        assertTrue(d.engage)
        assertEquals(8_333_333L, d.pinnedExposureNs)
    }

    @Test
    fun `pinned scene releases only once ISO falls to near the floor`() {
        val stillDim = NdrAntiMotionBlurShutterPolicy.resolve(
            requested = true,
            currentlyEngaged = true,
            meteredExposureNs = 8_333_333L, // pinned at cap
            meteredIso = 800, // still boosting gain -> dim -> hold
            isoFloor = 50,
            frameDurationNs = frame30fps,
            exposureRange = exposureRange
        )
        assertTrue(stillDim.engage)

        val brightened = NdrAntiMotionBlurShutterPolicy.resolve(
            requested = true,
            currentlyEngaged = true,
            meteredExposureNs = 8_333_333L,
            meteredIso = 60, // near floor (50 * 1.35 = 67) -> release
            isoFloor = 50,
            frameDurationNs = frame30fps,
            exposureRange = exposureRange
        )
        assertFalse(brightened.engage)
    }

    @Test
    fun `not requested never engages`() {
        val d = NdrAntiMotionBlurShutterPolicy.resolve(
            requested = false,
            currentlyEngaged = true,
            meteredExposureNs = 20_000_000L,
            meteredIso = 400,
            isoFloor = 50,
            frameDurationNs = frame30fps,
            exposureRange = exposureRange
        )
        assertFalse(d.engage)
    }

    @Test
    fun `no metered data holds the current state (no flap at cold start)`() {
        val heldEngaged = NdrAntiMotionBlurShutterPolicy.resolveWithCap(
            requested = true,
            currentlyEngaged = true,
            meteredExposureNs = 0L,
            meteredIso = 0,
            isoFloor = 50,
            capNs = 8_333_333L
        )
        assertTrue(heldEngaged.engage)
        val heldOff = NdrAntiMotionBlurShutterPolicy.resolveWithCap(
            requested = true,
            currentlyEngaged = false,
            meteredExposureNs = 0L,
            meteredIso = 0,
            isoFloor = 50,
            capNs = 8_333_333L
        )
        assertFalse(heldOff.engage)
    }
}
