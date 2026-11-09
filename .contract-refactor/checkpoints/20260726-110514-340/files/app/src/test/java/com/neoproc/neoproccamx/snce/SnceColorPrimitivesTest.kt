package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnceColorPrimitivesTest {

    private fun assertVecNear(expected: FloatArray, actual: FloatArray, tol: Float, msg: String) {
        expected.indices.forEach { i -> assertEquals(msg, expected[i], actual[i], tol) }
    }

    @Test
    fun `invert3 produces a true inverse for the cone matrices`() {
        listOf(
            SnceColorPrimitives.XYZ_TO_LMS_HPE,
            SnceColorPrimitives.XYZ_TO_LMS_SS,
            SnceColorPrimitives.XYZ_TO_LMS_IPT,
            SnceColorPrimitives.LMSP_TO_IPT
        ).forEach { m ->
            val product = SnceColorPrimitives.mul3(m, SnceColorPrimitives.invert3(m))
            val identity = SnceColorPrimitives.identity3()
            identity.indices.forEach { i ->
                assertEquals("inverse round-trip", identity[i], product[i], 1e-4f)
            }
        }
    }

    @Test
    fun `XYZ to IPT round trips`() {
        listOf(
            SnceColorPrimitives.D65_XYZ,
            floatArrayOf(0.19f, 0.20f, 0.22f),
            floatArrayOf(0.30f, 0.18f, 0.10f),
            floatArrayOf(0.15f, 0.25f, 0.12f),
            floatArrayOf(0.18f, 0.18f, 0.38f)
        ).forEach { xyz ->
            val ipt = SnceColorPrimitives.xyzToIpt(xyz)
            val back = SnceColorPrimitives.iptToXyz(ipt)
            assertVecNear(xyz, back, 1e-3f, "IPT round-trip")
        }
    }

    @Test
    fun `IPT achromatic channel is near 1 for the reference white`() {
        val ipt = SnceColorPrimitives.xyzToIpt(SnceColorPrimitives.D65_XYZ)
        // For a normalised white the protan/tritan opponents are near zero.
        assertEquals("P near 0 for white", 0f, ipt[1], 0.02f)
        assertEquals("T near 0 for white", 0f, ipt[2], 0.02f)
    }

    @Test
    fun `deltaE2000 is zero for identical colours and positive otherwise`() {
        val gray = SnceColorPrimitives.xyzToLab(floatArrayOf(0.19f, 0.20f, 0.22f))
        val red = SnceColorPrimitives.xyzToLab(floatArrayOf(0.30f, 0.18f, 0.10f))
        assertEquals(0f, SnceColorPrimitives.deltaE2000(gray, gray), 1e-4f)
        assertTrue(SnceColorPrimitives.deltaE2000(gray, red) > 1f)
    }

    @Test
    fun `CCT of D65 is close to 6500K`() {
        val cct = SnceColorPrimitives.correlatedColorTemperature(SnceColorPrimitives.D65_XYZ)
        assertEquals(6500f, cct, 120f)
    }

    @Test
    fun `CCT increases as the white turns bluer`() {
        val warm = SnceColorPrimitives.xyToXyz(0.4476f, 0.4074f) // illuminant A ~2856K
        val cool = SnceColorPrimitives.xyToXyz(0.2990f, 0.3149f) // D75 ~7500K
        assertTrue(
            SnceColorPrimitives.correlatedColorTemperature(warm) <
                SnceColorPrimitives.correlatedColorTemperature(cool)
        )
    }

    @Test
    fun `kelvin to xy lands near the canonical daylight chromaticities`() {
        val d65 = SnceColorPrimitives.kelvinToXy(6500f)
        assertEquals(0.3127f, d65.first, 0.006f)
        assertEquals(0.3290f, d65.second, 0.006f)
    }

    @Test
    fun `hue difference wraps to the shortest signed arc`() {
        assertEquals(20f, SnceColorPrimitives.hueDifferenceDegrees(350f, 10f), 1e-4f)
        assertEquals(-20f, SnceColorPrimitives.hueDifferenceDegrees(10f, 350f), 1e-4f)
        assertEquals(0f, SnceColorPrimitives.wrapHueDegrees(360f), 1e-4f)
        assertEquals(350f, SnceColorPrimitives.wrapHueDegrees(-10f), 1e-4f)
    }
}
