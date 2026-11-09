package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression lock for [NeoRadianceMath.uncharted2Tonemap] — the Hable sibling that previously
 * omitted white-point normalization and so saturated at 0.9667 (dim/washed). The fixed curve must
 * reach display white at the white point.
 */
class NeoRadianceUncharted2TonemapTest {

    @Test
    fun `maps black to black and reaches display white at the white point`() {
        assertEquals(0f, NeoRadianceMath.uncharted2Tonemap(0f), 1e-6f)
        assertEquals(1f, NeoRadianceMath.uncharted2Tonemap(11.2f), 1e-3f)
    }

    @Test
    fun `bright input reaches near display white (regression for the missing white normalization)`() {
        assertTrue(NeoRadianceMath.uncharted2Tonemap(20f) >= 0.999f)
        assertTrue(NeoRadianceMath.uncharted2Tonemap(100f) >= 0.999f)
    }

    @Test
    fun `golden curve values`() {
        assertEquals(0.274835f, NeoRadianceMath.uncharted2Tonemap(0.5f), 1e-3f)
        assertEquals(0.462526f, NeoRadianceMath.uncharted2Tonemap(1.0f), 1e-3f)
        assertEquals(0.666844f, NeoRadianceMath.uncharted2Tonemap(2.0f), 1e-3f)
        assertEquals(0.840067f, NeoRadianceMath.uncharted2Tonemap(4.0f), 1e-3f)
    }

    @Test
    fun `is monotonic non-decreasing and bounded to 0_1`() {
        var prev = -1f
        var x = 0f
        while (x <= 50f) {
            val y = NeoRadianceMath.uncharted2Tonemap(x)
            assertTrue(y in 0f..1f)
            assertTrue("monotonic at x=$x", y >= prev - 1e-6f)
            prev = y
            x += 0.05f
        }
    }
}
