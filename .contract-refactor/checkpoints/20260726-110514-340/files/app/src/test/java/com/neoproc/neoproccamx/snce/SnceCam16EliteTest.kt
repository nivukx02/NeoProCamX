package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s2-f10 elite locks for [SnceCam16]: forward∘inverse invertibility over a wide gamut, and the
 * degree-of-adaptation `D` term's range + monotonicity (§4.3). These guard the perceptual core
 * that the live AWB/appearance chain rests on.
 */
class SnceCam16EliteTest {

    private fun vc(
        la: Float = 100f,
        surround: SnceCam16.Surround = SnceCam16.Surround.AVERAGE
    ) = SnceCam16.SnceViewingConditions(
        whiteXyz = SnceColorPrimitives.D65_XYZ,
        adaptingLuminance = la,
        backgroundLuminance = 20f,
        surround = surround
    )

    @Test
    fun `forward then inverse round-trips XYZ across a wide gamut`() {
        val vc = vc()
        var maxErr = 0f
        // Sweep well-conditioned Rec.709 samples (avoid near-black / fully-saturated, where the
        // float Naka-Rushton inverse is ill-conditioned) across a broad hue/chroma/lightness set.
        val steps = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f)
        for (r in steps) for (g in steps) for (b in steps) {
            val xyz = SnceColorPrimitives.mulV(
                SnceIlluminantEstimator.REC709_TO_XYZ, floatArrayOf(r, g, b)
            )
            val app = SnceCam16.forward(xyz, vc)
            val back = SnceCam16.inverseFromJch(app.j, app.c, app.h, vc)
            for (i in 0..2) {
                maxErr = maxOf(maxErr, kotlin.math.abs(back[i] - xyz[i]))
            }
        }
        // Float CAM16 is well-conditioned near the neutral axis (the existing SnceCam16Test
        // locks <0.004 there); on saturated corners the Naka-Rushton inverse loosens to ~1%.
        // This broad sweep proves invertibility across the gamut at that honest bound.
        assertTrue("CAM16 forward∘inverse max abs XYZ error $maxErr must be ≤ 0.012", maxErr <= 0.012f)
    }

    @Test
    fun `inverse from JMh agrees with inverse from JCh after the M-to-C conversion`() {
        val vc = vc()
        // M = C · FL^0.25, so JMh and JCh inverses must reconstruct the same XYZ.
        val xyz = SnceColorPrimitives.mulV(
            SnceIlluminantEstimator.REC709_TO_XYZ, floatArrayOf(0.5f, 0.4f, 0.3f)
        )
        val app = SnceCam16.forward(xyz, vc)
        val fromC = SnceCam16.inverseFromJch(app.j, app.c, app.h, vc)
        val fromM = SnceCam16.inverseFromJmh(app.j, app.m, app.h, vc)
        for (i in 0..2) {
            assertTrue("JMh vs JCh inverse must agree", kotlin.math.abs(fromM[i] - fromC[i]) <= 1e-3f)
        }
    }

    @Test
    fun `degree of adaptation D stays within 0 to 1 across adapting luminance`() {
        for (la in floatArrayOf(0f, 1f, 10f, 100f, 1_000f, 10_000f)) {
            for (s in SnceCam16.Surround.values()) {
                val d = vc(la, s).d
                assertTrue("D=$d out of [0,1] at la=$la surround=$s", d in 0f..1f)
            }
        }
    }

    @Test
    fun `D increases monotonically with adapting luminance and is highest for AVERAGE surround`() {
        val las = floatArrayOf(1f, 5f, 25f, 100f, 500f, 5_000f)
        var prev = -1f
        for (la in las) {
            val d = vc(la, SnceCam16.Surround.AVERAGE).d
            assertTrue("D must be non-decreasing in adapting luminance (got $d after $prev)", d >= prev - 1e-6f)
            prev = d
        }
        // A darker surround reduces full adaptation at a fixed luminance.
        val avg = vc(100f, SnceCam16.Surround.AVERAGE).d
        val dim = vc(100f, SnceCam16.Surround.DIM).d
        val dark = vc(100f, SnceCam16.Surround.DARK).d
        assertTrue("AVERAGE ≥ DIM ≥ DARK adaptation", avg >= dim && dim >= dark)
    }
}
