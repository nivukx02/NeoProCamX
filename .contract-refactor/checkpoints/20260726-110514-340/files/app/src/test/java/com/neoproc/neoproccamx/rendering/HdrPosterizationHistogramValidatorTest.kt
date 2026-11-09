package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrPosterizationHistogramValidatorTest {
    @Test
    fun smoothEightBitGradientPassesHistogramPosterizationGate() {
        val gradient = IntArray(256 * 8) { it % 256 }
        val result = HdrPosterizationHistogramValidator.validateNoPosterization8Bit(gradient)

        assertTrue(result.passes)
        assertTrue(result.occupiedBins >= 240)
        assertTrue(result.maxConsecutiveEmptyBins <= 1)
    }

    @Test
    fun PosterizedEightBitRampFailsHistogramPosterizationGate() {
        val posterized = IntArray(256 * 8) { ((it % 256) / 16) * 16 }
        val result = HdrPosterizationHistogramValidator.validateNoPosterization8Bit(posterized)

        assertFalse(result.passes)
        assertTrue(result.maxConsecutiveEmptyBins >= 8)
    }
}
