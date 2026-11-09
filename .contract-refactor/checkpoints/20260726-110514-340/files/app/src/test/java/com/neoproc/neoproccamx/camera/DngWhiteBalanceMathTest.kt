package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization of [DngWhiteBalanceMath] — the DNG-SDK device-true white-balance port.
 *
 * The temperature ↔ xy conversions are device-independent, so they are asserted to high precision
 * via round-trip. The device-true solve is checked for the two invariants that matter physically:
 * gain monotonicity (bluer light ⇒ more red gain, less blue gain) and the neutral-preserving CCM
 * property (`CCM · diag(gains)` reproduces the render, so gray in ⇒ gray out ⇔ equal CCM row sums).
 * Fail-closed behaviour is asserted so callers can safely fall back to the legacy approximation.
 */
class DngWhiteBalanceMathTest {

    // XYZ(D65) -> linear sRGB, used as a plausible invertible "camera = sRGB" color matrix for the
    // device-true tests. (Any invertible XYZ->camera matrix exercises the same code paths.)
    private val xyzToSrgb = floatArrayOf(
        3.2404542f, -1.5371385f, -0.4985314f,
        -0.9692660f, 1.8760108f, 0.0415560f,
        0.0556434f, -0.2040259f, 1.0572252f
    )

    @Test
    fun `kelvin-tint round trips through xy across the working range`() {
        val kelvins = listOf(2500.0, 3200.0, 4000.0, 5000.0, 5600.0, 6500.0, 8000.0, 9500.0)
        val tints = listOf(-80.0, -20.0, 0.0, 25.0, 60.0)
        for (k in kelvins) for (t in tints) {
            val xy = DngWhiteBalanceMath.kelvinTintToXy(k, t)
            assertTrue("xy usable for K=$k tint=$t", xy.isUsable())
            val (k2, t2) = DngWhiteBalanceMath.xyToKelvinTint(xy.x, xy.y)
            // Reciprocal-CCT round trip is accurate to a fraction of a percent; tint to ~1 unit.
            assertEquals("K round trip ($k,$t)", k, k2, k * 0.01)
            assertEquals("tint round trip ($k,$t)", t, t2, 2.0)
        }
    }

    @Test
    fun `daylight neutral sits on the Planckian locus near the expected chromaticity`() {
        // ~6500K blackbody is close to (but not identical to) D65; assert it lands in the daylight box.
        val xy = DngWhiteBalanceMath.kelvinTintToXy(6500.0, 0.0)
        assertTrue(xy.isUsable())
        assertTrue("x in daylight range", xy.x in 0.305..0.325)
        assertTrue("y in daylight range", xy.y in 0.320..0.345)
    }

    @Test
    fun `gains move in the physically correct direction as color temperature rises`() {
        val warm = DngWhiteBalanceMath.solveManualWhiteBalance(
            kelvin = 3000.0, tint = 0.0,
            colorMatrix1 = xyzToSrgb, colorMatrix2 = xyzToSrgb, cct1 = 2850.0, cct2 = 6500.0
        )
        val cool = DngWhiteBalanceMath.solveManualWhiteBalance(
            kelvin = 6500.0, tint = 0.0,
            colorMatrix1 = xyzToSrgb, colorMatrix2 = xyzToSrgb, cct1 = 2850.0, cct2 = 6500.0
        )
        assertNotNull(warm); assertNotNull(cool)
        warm!!; cool!!
        assertTrue("bluer light needs MORE red gain", cool.rGain > warm.rGain)
        assertTrue("bluer light needs LESS blue gain", cool.bGain < warm.bGain)
        assertTrue(warm.rGain > 0f && warm.bGain > 0f && cool.rGain > 0f && cool.bGain > 0f)
    }

    @Test
    fun `derived CCM is neutral-preserving (gray in maps to gray out)`() {
        // Color-matrix-only path: neutral preservation holds for any invertible color matrix.
        val wb = DngWhiteBalanceMath.solveManualWhiteBalance(
            kelvin = 5000.0, tint = 0.0,
            colorMatrix1 = xyzToSrgb, colorMatrix2 = xyzToSrgb, cct1 = 2850.0, cct2 = 6500.0,
            forwardMatrix1 = null, forwardMatrix2 = null
        )
        assertNotNull(wb)
        val ccm = wb!!.colorCorrectionTransform
        assertNotNull("color-matrix path should still derive a CCM", ccm)
        ccm!!
        assertEquals(9, ccm.size)
        assertTrue("CCM finite", ccm.all { it.isFinite() })
        // CCM · (1,1,1) = row sums. Equal row sums ⇔ achromatic input maps to achromatic output.
        val row0 = ccm[0] + ccm[1] + ccm[2]
        val row1 = ccm[3] + ccm[4] + ccm[5]
        val row2 = ccm[6] + ccm[7] + ccm[8]
        assertEquals("row0 == row1 (gray preserved)", row0, row1, 1e-2f)
        assertEquals("row1 == row2 (gray preserved)", row1, row2, 1e-2f)
    }

    @Test
    fun `neutral chosen at a reference illuminant yields near-unity gains for an sRGB camera`() {
        // With camera == sRGB (D65), a D65-ish neutral should need ~no correction.
        val wb = DngWhiteBalanceMath.solveManualWhiteBalance(
            kelvin = 6500.0, tint = 0.0,
            colorMatrix1 = xyzToSrgb, colorMatrix2 = xyzToSrgb, cct1 = 2850.0, cct2 = 6500.0
        )
        assertNotNull(wb)
        wb!!
        assertEquals("rGain ~1 at D65 for sRGB camera", 1.0f, wb.rGain, 0.12f)
        assertEquals("bGain ~1 at D65 for sRGB camera", 1.0f, wb.bGain, 0.12f)
    }

    @Test
    fun `interpolation weight respects reference illuminant ordering (swap-invariant)`() {
        // Same physical calibration, references given in both orders: identical result.
        val forward = DngWhiteBalanceMath.solveManualWhiteBalance(
            kelvin = 4500.0, tint = 10.0,
            colorMatrix1 = xyzToSrgb, colorMatrix2 = scaled(xyzToSrgb, 0.9f),
            cct1 = 2850.0, cct2 = 6500.0
        )
        val reversed = DngWhiteBalanceMath.solveManualWhiteBalance(
            kelvin = 4500.0, tint = 10.0,
            colorMatrix1 = scaled(xyzToSrgb, 0.9f), colorMatrix2 = xyzToSrgb,
            cct1 = 6500.0, cct2 = 2850.0
        )
        assertNotNull(forward); assertNotNull(reversed)
        assertEquals(forward!!.rGain, reversed!!.rGain, 1e-4f)
        assertEquals(forward.bGain, reversed.bGain, 1e-4f)
    }

    @Test
    fun `fails closed on unusable calibration so callers fall back to the legacy path`() {
        // Wrong matrix size.
        assertNull(
            DngWhiteBalanceMath.solveManualWhiteBalance(
                5000.0, 0.0, floatArrayOf(1f, 0f, 0f), xyzToSrgb, 2850.0, 6500.0
            )
        )
        // Non-positive / non-finite Kelvin.
        assertNull(
            DngWhiteBalanceMath.solveManualWhiteBalance(
                0.0, 0.0, xyzToSrgb, xyzToSrgb, 2850.0, 6500.0
            )
        )
        // Degenerate reference CCTs.
        assertNull(
            DngWhiteBalanceMath.solveManualWhiteBalance(
                5000.0, 0.0, xyzToSrgb, xyzToSrgb, 0.0, 6500.0
            )
        )
        // Singular color matrix (all zeros) -> no usable camera white.
        assertNull(
            DngWhiteBalanceMath.solveManualWhiteBalance(
                5000.0, 0.0, FloatArray(9), FloatArray(9), 2850.0, 6500.0
            )
        )
    }

    private fun scaled(m: FloatArray, k: Float): FloatArray = FloatArray(9) { m[it] * k }
}
