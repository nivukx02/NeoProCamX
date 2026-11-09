package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks [HdrHighlightProtectExposurePolicy]: the HDR highlight-protect bias must move exposure
 * compensation DOWN (protect highlights), respect the device range, and be a no-op when disabled.
 */
class HdrHighlightProtectExposurePolicyTest {

    @Test
    fun `applies a negative bias in whole index steps for a third-stop device`() {
        // step = 1/3 stop; -0.67 stop -> round(-0.67 / 0.333) = -2 steps.
        val index = HdrHighlightProtectExposurePolicy.resolveExposureCompensationIndex(
            baseEvIndex = 0,
            evStepStops = 1f / 3f,
            evLowerIndex = -24,
            evUpperIndex = 24
        )
        assertEquals(-2, index)
    }

    @Test
    fun `bias rides on top of the user EV dial`() {
        // User dialed +1 stop == +3 index; highlight protect subtracts 2 -> +1 index.
        val index = HdrHighlightProtectExposurePolicy.resolveExposureCompensationIndex(
            baseEvIndex = 3,
            evStepStops = 1f / 3f,
            evLowerIndex = -24,
            evUpperIndex = 24
        )
        assertEquals(1, index)
    }

    @Test
    fun `never exceeds the device compensation range`() {
        val index = HdrHighlightProtectExposurePolicy.resolveExposureCompensationIndex(
            baseEvIndex = -11,
            evStepStops = 1f / 3f,
            evLowerIndex = -12,
            evUpperIndex = 12
        )
        assertEquals(-12, index) // -11 - 2 = -13, clamped to -12
    }

    @Test
    fun `disabled bias returns the clamped base index`() {
        val index = HdrHighlightProtectExposurePolicy.resolveExposureCompensationIndex(
            baseEvIndex = 5,
            evStepStops = 1f / 3f,
            evLowerIndex = -24,
            evUpperIndex = 24,
            highlightProtectStops = 0f
        )
        assertEquals(5, index)
    }

    @Test
    fun `zero step size cannot throw and returns the clamped base`() {
        val index = HdrHighlightProtectExposurePolicy.resolveExposureCompensationIndex(
            baseEvIndex = 30,
            evStepStops = 0f,
            evLowerIndex = -24,
            evUpperIndex = 24
        )
        assertEquals(24, index)
    }

    @Test
    fun `default bias is a downward protection`() {
        assertTrue(HdrHighlightProtectExposurePolicy.DEFAULT_HIGHLIGHT_PROTECT_STOPS < 0f)
    }
}
