package com.neoproc.neoproccamx.color

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OklabGamutCuspTest {

    @Test
    fun oklabRoundTripsThroughLinearSrgb() {
        for (c in listOf(
            floatArrayOf(0.8f, 0.2f, 0.1f),
            floatArrayOf(0.1f, 0.5f, 0.9f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
        )) {
            val lab = OklabGamutCusp.linearSrgbToOklab(c[0], c[1], c[2])
            val rgb = OklabGamutCusp.oklabToLinearSrgb(lab[0], lab[1], lab[2])
            for (i in 0..2) assertEquals(c[i], rgb[i], 1e-3f)
        }
    }

    @Test
    fun cuspSitsExactlyOnTheGamutBoundaryForEveryHue() {
        // THE correctness property: at the cusp, the linear sRGB colour has its max channel == 1.0 and
        // sits inside the [0,1]^3 cube. Self-validating — no hand-picked reference numbers needed.
        var worstMax = 0f
        for (i in 0 until 256) {
            val hue = (i.toFloat() / 256f) * (2.0 * Math.PI).toFloat()
            val a = kotlin.math.cos(hue)
            val b = kotlin.math.sin(hue)
            val lc = OklabGamutCusp.findCusp(a, b)
            val l = lc[0]
            val c = lc[1]
            val rgb = OklabGamutCusp.oklabToLinearSrgb(l, c * a, c * b)
            val maxCh = maxOf(rgb[0], rgb[1], rgb[2])
            val minCh = minOf(rgb[0], rgb[1], rgb[2])
            assertEquals("hue bin $i: max channel must touch the gamut boundary", 1.0f, maxCh, 3e-3f)
            assertTrue("hue bin $i: no channel may fall meaningfully below 0 (was $minCh)", minCh > -3e-3f)
            assertTrue("hue bin $i: cusp lightness in (0,1)", l > 0f && l < 1f)
            assertTrue("hue bin $i: cusp chroma positive", c > 0f)
            worstMax = maxOf(worstMax, kotlin.math.abs(maxCh - 1f))
        }
        assertTrue("worst boundary error across all hues: $worstMax", worstMax < 3e-3f)
    }

    @Test
    fun cuspAtAPrimaryHueMatchesThatPrimary() {
        // Linear sRGB red (1,0,0) is a gamut-corner; the cusp at red's hue must reach red's own chroma.
        val redLab = OklabGamutCusp.linearSrgbToOklab(1f, 0f, 0f)
        val redChroma = hypot(redLab[1].toDouble(), redLab[2].toDouble()).toFloat()
        val redHue = atan2(redLab[2], redLab[1])
        val lc = OklabGamutCusp.findCuspForHue(redHue)
        // The cusp chroma should be >= the primary's chroma (the corner lies on the boundary) and close.
        assertTrue("cusp chroma ${lc[1]} should reach red's chroma $redChroma", lc[1] >= redChroma - 5e-3f)
    }

    @Test
    fun cuspLutIsWellFormedAndBounded() {
        val bins = 64
        val lut = OklabGamutCusp.cuspLut(bins)
        assertEquals(bins * 2, lut.size)
        for (i in 0 until bins) {
            val l = lut[i * 2]
            val c = lut[i * 2 + 1]
            assertTrue("L in (0,1)", l > 0f && l < 1f)
            assertTrue("C in a sane range", c > 0f && c < 0.5f)
        }
    }

    @Test
    fun exactCuspChromaVariesWithHueMoreSharplyThanItsMean() {
        // Documents WHY a fixed/low-order analytic boundary is wrong: the real per-hue cusp chroma has
        // strong, non-sinusoidal variation (sRGB primaries vs secondaries), so a smooth low-order guess
        // cannot track it. Assert the spread is a large fraction of the mean.
        val bins = 180
        val lut = OklabGamutCusp.cuspLut(bins)
        val chromas = (0 until bins).map { lut[it * 2 + 1] }
        val mean = chromas.average().toFloat()
        val spread = chromas.max() - chromas.min()
        assertTrue("cusp chroma must vary substantially with hue (spread=$spread mean=$mean)", spread > 0.25f * mean)
    }
}
