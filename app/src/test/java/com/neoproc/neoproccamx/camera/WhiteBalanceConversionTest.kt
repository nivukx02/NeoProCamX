package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization lock for the Kelvin→RGGB gain math ([WhiteBalanceConversion.kelvinToRggbGains],
 * the pure core of `kelvinToRggb`). Pins the Tanner-Helland Planckian gains, tint compensation,
 * Kelvin clamping, and warm/cool monotonicity so the white-balance path cannot silently drift.
 */
class WhiteBalanceConversionTest {

    private fun g(kelvin: Int, tint: Float = 1.0f) =
        WhiteBalanceConversion.kelvinToRggbGains(kelvin, tint, null)

    @Test
    fun `golden gains at representative color temperatures`() {
        assertGains(g(6500), 0.99651f, 1f, 1f, 1.01627f)   // near-neutral daylight
        assertGains(g(3200), 0.72008f, 1f, 1f, 1.49140f)   // warm tungsten: R<1, B>1
        assertGains(g(5000), 0.89417f, 1f, 1f, 1.10723f)
        assertGains(g(2000), 0.53674f, 1f, 1f, 9.84376f)
        assertGains(g(12000), 1.10673f, 1f, 1f, 0.82939f)  // cool: R>1, B<1
    }

    @Test
    fun `kelvin is clamped to the supported 2000-12000 range`() {
        assertArrayEquals(g(2000), g(1000), 1e-6f)
        assertArrayEquals(g(12000), g(20000), 1e-6f)
    }

    @Test
    fun `green-magenta tint above 1 reduces green and lifts red-blue`() {
        assertGains(g(3200, 1.2f), 0.75008f, 0.90000f, 0.90000f, 1.52140f)
    }

    @Test
    fun `green-magenta tint below 1 raises green and trims red-blue`() {
        assertGains(g(3200, 0.8f), 0.70008f, 1.10000f, 1.10000f, 1.47140f)
    }

    @Test
    fun `warm illuminants get less red gain and more blue gain than cool ones`() {
        // NOTE: the Tanner-Helland curve is NOT strictly monotonic — it has a small discontinuity
        // at the temp=66 (6600K) branch boundary where rGain dips ~0.02. So pin the genuine
        // end-to-end trend (warm 2000K vs cool 12000K) rather than per-step monotonicity.
        val warm = g(2000)
        val cool = g(12000)
        assertTrue("warm rGain < cool rGain", warm[0] < cool[0])
        assertTrue("warm bGain > cool bGain", warm[3] > cool[3])
    }

    @Test
    fun `even and odd green gains are always equal`() {
        for (k in intArrayOf(2000, 3200, 5000, 6500, 12000)) {
            val gains = g(k)
            assertEquals(gains[1], gains[2], 0f)
        }
    }

    private fun assertGains(actual: FloatArray, r: Float, gEven: Float, gOdd: Float, b: Float) {
        assertEquals("r", r, actual[0], 1e-3f)
        assertEquals("gEven", gEven, actual[1], 1e-3f)
        assertEquals("gOdd", gOdd, actual[2], 1e-3f)
        assertEquals("b", b, actual[3], 1e-3f)
    }
}
