package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class VideoColorTemporalStabilityPolicyTest {

    private fun distance(a: ColorChromaticity, b: ColorChromaticity): Float {
        val du = a.u - b.u
        val dv = a.v - b.v
        return sqrt(du * du + dv * dv)
    }

    @Test
    fun `first frame adopts the measured white point verbatim`() {
        val measured = ColorChromaticity(0.21f, 0.47f)
        val result = VideoColorTemporalStabilityPolicy.stabilize(
            previous = null,
            measured = measured,
            maxFrameToFrameColorDriftUv = 0.0060f
        )

        assertEquals(measured, result.stabilized)
        assertEquals(0f, result.appliedDriftUv, 1e-7f)
        assertFalse(result.clamped)
        assertTrue(result.converged)
    }

    @Test
    fun `first frame with a non-finite measurement falls back to a finite neutral white`() {
        val result = VideoColorTemporalStabilityPolicy.stabilize(
            previous = null,
            measured = ColorChromaticity(Float.NaN, 0.47f),
            maxFrameToFrameColorDriftUv = 0.0060f
        )

        assertTrue(result.stabilized.u.isFinite())
        assertTrue(result.stabilized.v.isFinite())
    }

    @Test
    fun `a small in-budget move is exponentially smoothed and not clamped`() {
        val previous = ColorChromaticity(0.20f, 0.47f)
        val measured = ColorChromaticity(0.2010f, 0.4710f)
        val result = VideoColorTemporalStabilityPolicy.stabilize(
            previous = previous,
            measured = measured,
            maxFrameToFrameColorDriftUv = 0.0060f,
            smoothingFactor = 0.5f
        )

        assertFalse(result.clamped)
        // Half the requested step is taken (EMA factor 0.5).
        assertEquals(0.2005f, result.stabilized.u, 1e-5f)
        assertEquals(0.4705f, result.stabilized.v, 1e-5f)
        assertEquals(0.5f * distance(previous, measured), result.appliedDriftUv, 1e-6f)
    }

    @Test
    fun `a large jump is hard-clamped to the drift budget to suppress awb hunting`() {
        val previous = ColorChromaticity(0.20f, 0.47f)
        val measured = ColorChromaticity(0.30f, 0.47f) // a huge 0.10 u' swing
        val budget = 0.0060f
        val result = VideoColorTemporalStabilityPolicy.stabilize(
            previous = previous,
            measured = measured,
            maxFrameToFrameColorDriftUv = budget,
            smoothingFactor = 0.5f
        )

        assertTrue(result.clamped)
        assertFalse(result.converged)
        // Movement is capped exactly at the budget, in the direction of the measurement.
        assertEquals(budget, result.appliedDriftUv, 1e-6f)
        assertEquals(0.20f + budget, result.stabilized.u, 1e-6f)
        assertEquals(0.47f, result.stabilized.v, 1e-6f)
    }

    @Test
    fun `a zero budget hard-locks the white point at the previous value`() {
        val previous = ColorChromaticity(0.20f, 0.47f)
        val result = VideoColorTemporalStabilityPolicy.stabilize(
            previous = previous,
            measured = ColorChromaticity(0.25f, 0.46f),
            maxFrameToFrameColorDriftUv = 0f
        )

        assertEquals(previous, result.stabilized)
        assertEquals(0f, result.appliedDriftUv, 1e-7f)
        assertTrue(result.clamped)
    }

    @Test
    fun `a negative budget is treated as a hard lock`() {
        val previous = ColorChromaticity(0.20f, 0.47f)
        val result = VideoColorTemporalStabilityPolicy.stabilize(
            previous = previous,
            measured = ColorChromaticity(0.25f, 0.46f),
            maxFrameToFrameColorDriftUv = -1f
        )

        assertEquals(previous, result.stabilized)
    }

    @Test
    fun `an unchanged measurement produces no drift and reports converged`() {
        val previous = ColorChromaticity(0.20f, 0.47f)
        val result = VideoColorTemporalStabilityPolicy.stabilize(
            previous = previous,
            measured = previous,
            maxFrameToFrameColorDriftUv = 0.0060f
        )

        assertEquals(0f, result.appliedDriftUv, 1e-7f)
        assertFalse(result.clamped)
        assertTrue(result.converged)
        assertEquals(previous, result.stabilized)
    }

    @Test
    fun `a non-finite measurement after history holds the last good white point`() {
        val previous = ColorChromaticity(0.20f, 0.47f)
        val result = VideoColorTemporalStabilityPolicy.stabilize(
            previous = previous,
            measured = ColorChromaticity(Float.POSITIVE_INFINITY, 0.47f),
            maxFrameToFrameColorDriftUv = 0.0060f
        )

        assertEquals(previous, result.stabilized)
        assertFalse(result.converged)
    }

    @Test
    fun `a smoothing factor above one is coerced and never overshoots the measurement`() {
        val previous = ColorChromaticity(0.20f, 0.47f)
        val measured = ColorChromaticity(0.2005f, 0.47f)
        val result = VideoColorTemporalStabilityPolicy.stabilize(
            previous = previous,
            measured = measured,
            maxFrameToFrameColorDriftUv = 0.0060f,
            smoothingFactor = 5f // coerced to 1.0 -> take the full (in-budget) step
        )

        assertEquals(measured.u, result.stabilized.u, 1e-6f)
        assertTrue(result.converged)
    }

    @Test
    fun `repeated frames converge monotonically toward a distant target within budget`() {
        val measured = ColorChromaticity(0.26f, 0.47f)
        val budget = 0.0060f
        var current = ColorChromaticity(0.20f, 0.47f)
        var previousDistance = distance(current, measured)
        var converged = false
        var frames = 0

        repeat(200) {
            val result = VideoColorTemporalStabilityPolicy.stabilize(
                previous = current,
                measured = measured,
                maxFrameToFrameColorDriftUv = budget,
                smoothingFactor = 0.5f
            )
            // No single frame may ever exceed the drift budget.
            assertTrue(result.appliedDriftUv <= budget + 1e-6f)
            val newDistance = distance(result.stabilized, measured)
            // Distance to the target must never increase (monotonic approach).
            assertTrue(newDistance <= previousDistance + 1e-6f)
            previousDistance = newDistance
            current = result.stabilized
            frames++
            if (result.converged) {
                converged = true
                return@repeat
            }
        }

        assertTrue("temporal stabilizer should converge to the target", converged)
        assertEquals(measured.u, current.u, 1e-3f)
    }

    @Test
    fun `the plan overload enforces the tighter locked-shot budget`() {
        val lockedPlan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = true,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = false,
            existingAeAwbLock = false,
            normalSdrVideoPreviewActive = true,
            ndrOrLogModeActive = false
        )
        val adaptivePlan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = false,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = false,
            existingAeAwbLock = false,
            normalSdrVideoPreviewActive = false,
            ndrOrLogModeActive = true
        )
        val previous = ColorChromaticity(0.20f, 0.47f)
        val measured = ColorChromaticity(0.30f, 0.47f)

        val lockedResult = VideoColorTemporalStabilityPolicy.stabilize(lockedPlan, previous, measured)
        val adaptiveResult = VideoColorTemporalStabilityPolicy.stabilize(adaptivePlan, previous, measured)

        // Both clamp a huge jump, but the locked shot moves less per frame than the adaptive one.
        assertTrue(lockedResult.clamped)
        assertTrue(adaptiveResult.clamped)
        assertEquals(0.0015f, lockedResult.appliedDriftUv, 1e-6f)
        assertEquals(0.0060f, adaptiveResult.appliedDriftUv, 1e-6f)
        assertTrue(lockedResult.appliedDriftUv < adaptiveResult.appliedDriftUv)
    }
}
