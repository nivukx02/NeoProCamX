package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteBalanceChromaticityTest {

    @Test
    fun `equal gains map to the D65 white point`() {
        val uv = WhiteBalanceChromaticity.rggbGainsToUv(1f, 1f, 1f, 1f)
        assertNotNull(uv)
        uv!!
        // Matches VideoColorTemporalStabilityPolicy's neutral fallback (0.1978, 0.4683).
        assertEquals(0.1978f, uv.u, 1e-3f)
        assertEquals(0.4683f, uv.v, 1e-3f)
    }

    @Test
    fun `raising the red gain cools the white point and lowers u prime`() {
        val neutral = WhiteBalanceChromaticity.rggbGainsToUv(1f, 1f, 1f, 1f)!!
        val redder = WhiteBalanceChromaticity.rggbGainsToUv(2f, 1f, 1f, 1f)!!
        assertTrue("more red gain should lower u'", redder.u < neutral.u)
    }

    @Test
    fun `raising the blue gain warms the white point and raises u prime`() {
        val neutral = WhiteBalanceChromaticity.rggbGainsToUv(1f, 1f, 1f, 1f)!!
        val bluer = WhiteBalanceChromaticity.rggbGainsToUv(1f, 1f, 1f, 2f)!!
        assertTrue("more blue gain should raise u'", bluer.u > neutral.u)
    }

    @Test
    fun `gains survive a round trip through u prime v prime up to green normalization`() {
        val r = 1.7f
        val gEven = 1.05f
        val gOdd = 0.95f
        val b = 1.3f
        val uv = WhiteBalanceChromaticity.rggbGainsToUv(r, gEven, gOdd, b)!!
        val back = WhiteBalanceChromaticity.uvToRggbGains(uv)!!

        // Gains are scale-invariant; the inverse normalizes green to 1, so compare ratios.
        val gMean = (gEven + gOdd) / 2f
        assertEquals(r / gMean, back[0], 1e-2f)
        assertEquals(1f, back[1], 1e-6f)
        assertEquals(1f, back[2], 1e-6f)
        assertEquals(b / gMean, back[3], 1e-2f)
    }

    @Test
    fun `the round trip is stable for a neutral white`() {
        val uv = WhiteBalanceChromaticity.rggbGainsToUv(1f, 1f, 1f, 1f)!!
        val back = WhiteBalanceChromaticity.uvToRggbGains(uv)!!
        assertEquals(1f, back[0], 1e-2f)
        assertEquals(1f, back[3], 1e-2f)
    }

    @Test
    fun `non-positive gains are rejected`() {
        assertNull(WhiteBalanceChromaticity.rggbGainsToUv(0f, 1f, 1f, 1f))
        assertNull(WhiteBalanceChromaticity.rggbGainsToUv(1f, 0f, 0f, 1f))
        assertNull(WhiteBalanceChromaticity.rggbGainsToUv(1f, 1f, 1f, -2f))
    }

    @Test
    fun `non-finite gains are rejected`() {
        assertNull(WhiteBalanceChromaticity.rggbGainsToUv(Float.NaN, 1f, 1f, 1f))
        assertNull(WhiteBalanceChromaticity.rggbGainsToUv(1f, Float.POSITIVE_INFINITY, 1f, 1f))
    }

    @Test
    fun `conversion is deterministic`() {
        val a = WhiteBalanceChromaticity.rggbGainsToUv(1.4f, 1f, 1f, 1.6f)!!
        val b = WhiteBalanceChromaticity.rggbGainsToUv(1.4f, 1f, 1f, 1.6f)!!
        assertEquals(a.u, b.u, 0f)
        assertEquals(a.v, b.v, 0f)
    }

    @Test
    fun `distinct illuminants map to distinct chromaticities`() {
        val warm = WhiteBalanceChromaticity.rggbGainsToUv(1.2f, 1f, 1f, 2.2f)!!
        val cool = WhiteBalanceChromaticity.rggbGainsToUv(2.2f, 1f, 1f, 1.2f)!!
        assertTrue(warm.u != cool.u || warm.v != cool.v)
    }
}
