package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnceCam16Test {

    private fun vc(
        la: Float = 100f,
        yb: Float = 20f,
        surround: SnceCam16.Surround = SnceCam16.Surround.AVERAGE,
        dOverride: Float = Float.NaN
    ) = SnceCam16.SnceViewingConditions(
        whiteXyz = SnceColorPrimitives.D65_XYZ,
        adaptingLuminance = la,
        backgroundLuminance = yb,
        surround = surround,
        degreeOfAdaptationOverride = dOverride
    )

    @Test
    fun `white maps to J near 100 and chroma near 0 under full adaptation`() {
        val conditions = vc(dOverride = 1f)
        val app = SnceCam16.forward(SnceColorPrimitives.D65_XYZ, conditions)
        assertEquals("J for white", 100f, app.j, 0.5f)
        assertEquals("C for white", 0f, app.c, 0.3f)
    }

    @Test
    fun `forward then inverse from JCh round trips XYZ`() {
        val conditions = vc()
        listOf(
            floatArrayOf(0.19f, 0.20f, 0.22f),
            floatArrayOf(0.30f, 0.18f, 0.10f),
            floatArrayOf(0.15f, 0.25f, 0.12f),
            floatArrayOf(0.18f, 0.18f, 0.38f),
            floatArrayOf(0.50f, 0.52f, 0.55f)
        ).forEach { xyz ->
            val app = SnceCam16.forward(xyz, conditions)
            val back = SnceCam16.inverseFromJch(app.j, app.c, app.h, conditions)
            xyz.indices.forEach { i ->
                assertEquals("CAM16 round-trip channel $i", xyz[i], back[i], 0.004f)
            }
        }
    }

    @Test
    fun `inverse from JMh agrees with inverse from JCh`() {
        val conditions = vc()
        val xyz = floatArrayOf(0.28f, 0.20f, 0.12f)
        val app = SnceCam16.forward(xyz, conditions)
        val fromC = SnceCam16.inverseFromJch(app.j, app.c, app.h, conditions)
        val fromM = SnceCam16.inverseFromJmh(app.j, app.m, app.h, conditions)
        fromC.indices.forEach { i ->
            assertEquals("JMh vs JCh channel $i", fromC[i], fromM[i], 1e-3f)
        }
    }

    @Test
    fun `lightness increases with luminance`() {
        val conditions = vc()
        val dark = SnceCam16.forward(floatArrayOf(0.05f, 0.05f, 0.055f), conditions)
        val bright = SnceCam16.forward(floatArrayOf(0.45f, 0.47f, 0.51f), conditions)
        assertTrue("brighter sample must have higher J", bright.j > dark.j)
    }

    @Test
    fun `chroma increases for a more saturated stimulus`() {
        val conditions = vc()
        val nearGray = SnceCam16.forward(floatArrayOf(0.205f, 0.20f, 0.21f), conditions)
        val saturated = SnceCam16.forward(floatArrayOf(0.34f, 0.18f, 0.06f), conditions)
        assertTrue("saturated sample must have higher C", saturated.c > nearGray.c)
    }

    @Test
    fun `all correlates stay finite across viewing conditions`() {
        listOf(
            vc(la = 4f, surround = SnceCam16.Surround.DARK),
            vc(la = 20f, surround = SnceCam16.Surround.DIM),
            vc(la = 320f, surround = SnceCam16.Surround.AVERAGE)
        ).forEach { conditions ->
            val app = SnceCam16.forward(floatArrayOf(0.26f, 0.22f, 0.18f), conditions)
            listOf(app.j, app.c, app.h, app.q, app.m, app.s, app.a, app.b).forEach {
                assertTrue("correlate finite", it.isFinite())
            }
            val ucs = SnceCam16.toUcs(app)
            assertTrue(ucs.jp.isFinite() && ucs.ap.isFinite() && ucs.bp.isFinite())
        }
    }
}
