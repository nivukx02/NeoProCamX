package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

/**
 * Locks the companding curve that replaces the CP16 linear truncation: monotone,
 * invertible (no skipped codes), identity at native depth, and bounded reconstruction
 * error including the explicit "< 0.5 stop highlight code-loss" target.
 */
class RawCompandingCurveTest {

    private fun stops(a: Int, b: Int): Double =
        abs(ln((a + 1.0) / (b + 1.0)) / ln(2.0))

    @Test
    fun `identity when the 12 bit container already holds a 12 bit sensor`() {
        val curve = RawCompandingCurve.of(sourceWhiteLevel = 4095, targetBitDepth = 12)
        assertTrue(curve.isIdentity)
        assertEquals(RawCompandingCurve.COMPANDING_NONE, curve.type)
        for (s in intArrayOf(0, 1, 1024, 2048, 4095)) {
            assertEquals(s, curve.encode(s))
            assertEquals(s, curve.decode(curve.encode(s)))
        }
    }

    @Test
    fun `identity when a 16 bit container holds the sensor word`() {
        val curve = RawCompandingCurve.of(sourceWhiteLevel = 16383, targetBitDepth = 16)
        assertTrue(curve.isIdentity)
        assertEquals(16383, curve.encode(16383))
    }

    @Test
    fun `companding activates only when bits would otherwise be discarded`() {
        assertEquals(
            RawCompandingCurve.COMPANDING_PERCEPTUAL_LOG,
            RawCompandingCurve.of(16383, 12).type
        )
        assertEquals(RawCompandingCurve.COMPANDING_NONE, RawCompandingCurve.of(1023, 12).type)
    }

    @Test
    fun `14 to 12 bit companding is monotone with pinned endpoints and no skipped codes`() {
        val white = 16383
        val curve = RawCompandingCurve.of(white, 12)
        val targetMax = 4095

        assertEquals(0, curve.encode(0))
        assertEquals(targetMax, curve.encode(white))

        var previous = -1
        val seen = BooleanArray(targetMax + 1)
        for (s in 0..white) {
            val c = curve.encode(s)
            assertTrue("code in range", c in 0..targetMax)
            assertTrue("monotone non-decreasing at s=$s", c >= previous)
            previous = c
            seen[c] = true
        }
        // Slope-at-black == 1 guarantees a true bijection onto the code space: no gaps.
        for (c in 0..targetMax) assertTrue("code $c has a preimage", seen[c])
    }

    @Test
    fun `14 to 12 bit round trip stays under half a stop everywhere and is near lossless in shadow`() {
        val white = 16383
        val curve = RawCompandingCurve.of(white, 12)

        var worstStops = 0.0
        for (s in 1..white) {
            val recon = curve.decode(curve.encode(s))
            assertTrue("reconstruction in sensor domain", recon in 0..white)
            val err = stops(recon, s)
            if (err > worstStops) worstStops = err
        }
        assertTrue("worst-case reconstruction $worstStops stops should be < 0.5", worstStops < 0.5)

        // Deep shadows are essentially 1:1 (the curve's defining property).
        for (s in 1..16) {
            val recon = curve.decode(curve.encode(s))
            assertTrue("deep shadow s=$s recon=$recon within +/-4", abs(recon - s) <= 4)
        }
    }

    @Test
    fun `highlight code loss is far under half a stop`() {
        val white = 16383
        val curve = RawCompandingCurve.of(white, 12)
        // Top stop: sensor codes white/2 .. white.
        for (s in (white / 2)..white) {
            val recon = curve.decode(curve.encode(s))
            assertTrue("highlight s=$s loss ${stops(recon, s)} < 0.5 stop", stops(recon, s) < 0.5)
        }
    }

    @Test
    fun `16 to 12 bit companding also bijects and bounds error`() {
        val white = 65535
        val curve = RawCompandingCurve.of(white, 12)
        assertEquals(RawCompandingCurve.COMPANDING_PERCEPTUAL_LOG, curve.type)
        assertEquals(0, curve.encode(0))
        assertEquals(4095, curve.encode(white))
        var worstStops = 0.0
        for (s in 1..white step 7) {
            val recon = curve.decode(curve.encode(s))
            worstStops = maxOf(worstStops, stops(recon, s))
        }
        assertTrue("worst $worstStops < 0.5 stop", worstStops < 0.5)
    }
}
