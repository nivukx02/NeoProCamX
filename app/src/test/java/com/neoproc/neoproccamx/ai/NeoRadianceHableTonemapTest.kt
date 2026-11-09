package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression lock for [NeoRadianceMath.hableTonemap].
 *
 * The previous transcription saturated at 0.70, so bright HDR input could never map to display
 * white. The fixed (white-point normalized) curve must reach ~1.0. These also pin the curve shape.
 */
class NeoRadianceHableTonemapTest {

    @Test
    fun `maps black to black and reaches display white at the white point`() {
        assertEquals(0f, NeoRadianceMath.hableTonemap(0f), 1e-6f)
        assertEquals(1f, NeoRadianceMath.hableTonemap(11.2f), 1e-3f)
    }

    @Test
    fun `bright input reaches near display white (regression for the 0_70 cap)`() {
        // The old curve capped at ~0.70 here; the fix must reach ~1.0.
        assertTrue("bright input must reach white", NeoRadianceMath.hableTonemap(20f) >= 0.999f)
        assertTrue(NeoRadianceMath.hableTonemap(100f) >= 0.999f)
    }

    @Test
    fun `golden curve values`() {
        assertEquals(0.091642f, NeoRadianceMath.hableTonemap(0.25f), 1e-3f)
        assertEquals(0.171970f, NeoRadianceMath.hableTonemap(0.5f), 1e-3f)
        assertEquals(0.304301f, NeoRadianceMath.hableTonemap(1.0f), 1e-3f)
        assertEquals(0.492919f, NeoRadianceMath.hableTonemap(2.0f), 1e-3f)
        assertEquals(0.713238f, NeoRadianceMath.hableTonemap(4.0f), 1e-3f)
    }

    @Test
    fun `is monotonic non-decreasing and bounded to 0_1`() {
        var prev = -1f
        var x = 0f
        while (x <= 50f) {
            val y = NeoRadianceMath.hableTonemap(x)
            assertTrue(y in 0f..1f)
            assertTrue("monotonic at x=$x", y >= prev - 1e-6f)
            prev = y
            x += 0.05f
        }
    }
}
