package com.neoproc.neoproccamx.color

import kotlin.math.atan2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OklabGamutMappingTest {

    private fun hue(lab: FloatArray) = atan2(lab[2], lab[1])

    @Test
    fun inGamutColorsAreReturnedUnchanged() {
        for (c in listOf(
            floatArrayOf(0.5f, 0.3f, 0.2f),
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(1f, 1f, 1f),
            floatArrayOf(0.8f, 0.1f, 0.6f),
        )) {
            val out = OklabGamutMapping.gamutClipPreserveChroma(c[0], c[1], c[2])
            for (i in 0..2) assertEquals(c[i], out[i], 1e-4f)
        }
    }

    @Test
    fun outOfGamutColorsAreBroughtBackInsideTheCube() {
        // A spread of out-of-gamut linear sRGB colours (over-saturated / negative channels).
        for (c in listOf(
            floatArrayOf(1.6f, -0.2f, 0.1f),
            floatArrayOf(-0.3f, 1.4f, 0.2f),
            floatArrayOf(0.1f, 0.2f, 1.8f),
            floatArrayOf(1.3f, 1.2f, -0.4f),
            floatArrayOf(1.9f, -0.5f, 1.1f),
        )) {
            val out = OklabGamutMapping.gamutClipPreserveChroma(c[0], c[1], c[2])
            for (i in 0..2) {
                assertTrue("channel $i must be <= 1 after clip (was ${out[i]})", out[i] <= 1f + 3e-3f)
                assertTrue("channel $i must be >= 0 after clip (was ${out[i]})", out[i] >= -3e-3f)
            }
        }
    }

    @Test
    fun clippingPreservesOklabHue() {
        val c = floatArrayOf(1.7f, -0.1f, 0.4f) // out of gamut
        val before = OklabGamutCusp.linearSrgbToOklab(c[0], c[1], c[2])
        val out = OklabGamutMapping.gamutClipPreserveChroma(c[0], c[1], c[2])
        val after = OklabGamutCusp.linearSrgbToOklab(out[0], out[1], out[2])
        // Hue (atan2 of the a,b plane) must be preserved by the chroma-reducing projection.
        var dh = hue(after) - hue(before)
        while (dh > Math.PI) dh -= 2 * Math.PI.toFloat()
        while (dh < -Math.PI) dh += 2 * Math.PI.toFloat()
        assertEquals("clip must preserve hue", 0f, dh, 5e-3f)
    }

    @Test
    fun clippedColorLandsOnTheBoundaryNotDeepInside() {
        // A strongly out-of-gamut colour should clip to ~the boundary (one channel near 1 or 0),
        // not collapse toward grey — that's the point of preserving chroma.
        val out = OklabGamutMapping.gamutClipPreserveChroma(2.0f, -0.6f, 0.2f)
        val maxCh = maxOf(out[0], out[1], out[2])
        val minCh = minOf(out[0], out[1], out[2])
        assertTrue("clipped colour should sit on the gamut surface (max≈1 or min≈0): max=$maxCh min=$minCh",
            kotlin.math.abs(maxCh - 1f) < 5e-3f || kotlin.math.abs(minCh) < 5e-3f)
    }
}
