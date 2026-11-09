package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Niv99MathTest {
    @Test
    fun `identity multiplication preserves matrix`() {
        val identity = FloatArray(9)
        val matrix = floatArrayOf(1.2f, 0.1f, -0.1f, 0.2f, 0.9f, -0.1f, 0.0f, 0.2f, 0.8f)
        val output = FloatArray(9)
        Niv99Math.setIdentity3(identity)
        Niv99Math.multiply3(identity, matrix, output)
        assertArrayEquals(matrix, output, 1e-7f)
    }

    @Test
    fun `matrix inverse returns identity product`() {
        val matrix = floatArrayOf(0.72f, 0.18f, 0.10f, 0.08f, 0.86f, 0.06f, 0.02f, 0.12f, 0.86f)
        val inverse = FloatArray(9)
        val product = FloatArray(9)
        assertTrue(Niv99Math.invert3(matrix, inverse))
        Niv99Math.multiply3(matrix, inverse, product)
        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            product,
            2e-5f
        )
    }

    @Test
    fun `singular matrix is rejected`() {
        val singular = floatArrayOf(1f, 2f, 3f, 2f, 4f, 6f, 0f, 0f, 0f)
        assertFalse(Niv99Math.invert3(singular, FloatArray(9)))
    }

    @Test
    fun `median handles odd and even sample counts`() {
        assertEquals(3f, Niv99Math.medianInPlace(floatArrayOf(5f, 1f, 3f), 3), 0f)
        assertEquals(2.5f, Niv99Math.medianInPlace(floatArrayOf(4f, 1f, 3f, 2f), 4), 0f)
    }

    @Test
    fun `robust weights reject distant outliers`() {
        assertEquals(1f, Niv99Math.huberWeight(0.1f, 0.2f), 0f)
        assertTrue(Niv99Math.huberWeight(2f, 0.2f) < 0.11f)
        assertEquals(0f, Niv99Math.tukeyWeight(1f, 0.5f), 0f)
        assertTrue(Niv99Math.tukeyWeight(0.1f, 0.5f) > 0.8f)
    }

    @Test
    fun `blackbody chromaticity stays physical over supported range`() {
        val xy = FloatArray(2)
        for (kelvin in 1_500..25_000 step 250) {
            Niv99Math.blackbodyXy(kelvin.toFloat(), xy)
            assertTrue("x at $kelvin = ${xy[0]}", xy[0] in 0.20f..0.60f)
            assertTrue("y at $kelvin = ${xy[1]}", xy[1] in 0.20f..0.50f)
            assertTrue(xy[0] + xy[1] < 1f)
        }
    }

    @Test
    fun `planckian search recovers representative CCTs`() {
        val xy = FloatArray(2)
        val uv = FloatArray(2)
        val cctDuv = FloatArray(2)
        for (kelvin in intArrayOf(2_850, 3_200, 5_000, 6_500, 10_000)) {
            Niv99Math.blackbodyXy(kelvin.toFloat(), xy)
            Niv99Math.xyToUvPrime(xy[0], xy[1], uv)
            Niv99Math.estimateCctAndDuv(uv[0], uv[1], cctDuv)
            assertTrue("expected=$kelvin actual=${cctDuv[0]}", abs(cctDuv[0] - kelvin) <= 25f)
            assertTrue(abs(cctDuv[1]) < 1e-4f)
        }
    }

    @Test
    fun `covariance propagation reflects red and blue WB amplification`() {
        val identity = FloatArray(9).also(Niv99Math::setIdentity3)
        val output = FloatArray(9)
        Niv99Math.propagateDiagonalCovariance3(
            covarianceR = 0.01f,
            covarianceG = 0.01f,
            covarianceB = 0.01f,
            redGain = 2f,
            greenGain = 1f,
            blueGain = 3f,
            colorMatrix = identity,
            output = output
        )
        assertEquals(0.04f, output[0], 1e-7f)
        assertEquals(0.01f, output[4], 1e-7f)
        assertEquals(0.09f, output[8], 1e-7f)
    }

    @Test
    fun `safe finite helpers reject nan and infinity`() {
        assertEquals(0f, Niv99Math.saturateFinite(Float.NaN), 0f)
        assertEquals(0f, Niv99Math.saturateFinite(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, Niv99Math.saturateFinite(-1f), 0f)
        assertEquals(1f, Niv99Math.saturateFinite(2f), 0f)
    }
}

