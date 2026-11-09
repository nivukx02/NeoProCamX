package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Goldens for the corrected AIHdrMath "operator zoo" (audit rank 32). These are
 * dead duplicates today (ColorScience routes to NeoRadianceMath), but were latent
 * traps: hable raised the black floor and never reached white; bt2390 mixed
 * nits/normalized domains and inverted highlights; agx had an infinite-slope kink.
 */
class AIHdrMathOperatorZooTest {

    @Test
    fun hableBlackIsZeroWhiteIsOneAndMonotone() {
        assertEquals("black must be 0", 0f, AIHdrMath.hableTonemap(0f), 1e-4f)
        assertEquals("white point maps to 1", 1f, AIHdrMath.hableTonemap(11.2f), 2e-3f)
        var prev = -1f
        var x = 0f
        while (x <= 20f) {
            val y = AIHdrMath.hableTonemap(x)
            assertTrue("hable non-monotone at $x", y >= prev - 1e-6f)
            assertTrue("hable out of range at $x: $y", y in 0f..1f)
            prev = y
            x += 0.05f
        }
    }

    @Test
    fun bt2390IsMonotoneAndNeverInvertsHighlights() {
        // The bug: bright input came out darker than mid (highlight inversion).
        var prev = -1f
        var x = 0f
        while (x <= 1f) {
            val y = AIHdrMath.bt2390Eetf(x)
            assertTrue("bt2390 non-monotone / inverted at $x: $y < $prev", y >= prev - 1e-6f)
            assertTrue("bt2390 out of range at $x: $y", y in 0f..1f)
            prev = y
            x += 0.01f
        }
        assertEquals(0f, AIHdrMath.bt2390Eetf(0f), 1e-4f)
        assertTrue("brightest must be >= mid", AIHdrMath.bt2390Eetf(1f) >= AIHdrMath.bt2390Eetf(0.9f))
    }

    @Test
    fun agxIsMonotoneWithBoundedSlopeNoKink() {
        // The sqrt(|t|) form had an infinite slope at the pivot input (~x=0.0014).
        // |t|^1.5 is C1: the discrete slope stays bounded through that region.
        var prev = -1f
        var prevY = AIHdrMath.agxTonemap(0.0005f)
        var x = 0.0006f
        while (x <= 0.01f) {
            val y = AIHdrMath.agxTonemap(x)
            assertTrue("agx non-monotone at $x", y >= prev - 1e-6f)
            val slope = (y - prevY) / 0.0001f
            assertTrue("agx slope spike (kink) at $x: $slope", slope < 500f)
            prev = y
            prevY = y
            x += 0.0001f
        }
        assertTrue(AIHdrMath.agxTonemap(0.18f) in 0f..1f)
    }
}
