package com.neoproc.neoproccamx.color

import com.neoproc.neoproccamx.snce.SnceColorPrimitives
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s2-f10 / CP44 — green-magenta (Duv) tint axis on CAT16 chromatic adaptation.
 *
 * Locks: (a) NO-DOWNGRADE — `duv = 0` reproduces today's CCT-only adaptation bit-for-bit;
 * (b) the off-locus source-white helper round-trips (CCT, Duv) — i.e. the Duv axis is
 * orthogonal to CCT (moving Duv does NOT shift the recovered CCT); (c) adapting a real
 * off-Planckian illuminant with its measured Duv neutralises the green/magenta tint, where a
 * CCT-only adaptation leaves it.
 */
class NeoGamutCat16DuvAxisTest {

    private fun xyToXyz(x: Float, y: Float) = floatArrayOf(x / y, 1f, (1f - x - y) / y)

    @Test
    fun `duv zero reproduces todays adaptation bit-for-bit`() {
        for (k in floatArrayOf(3_000f, 4_000f, 5_000f, 6_500f, 8_000f)) {
            for (lux in floatArrayOf(50f, 400f, 5_000f)) {
                val baseline = NeoGamutCat16Math.calculateAdaptiveCat16ToD65(k, lux)
                val withZeroDuv = NeoGamutCat16Math.calculateAdaptiveCat16ToD65(k, lux, duv = 0f)
                assertArrayEquals("D65 adaptation must be identical at duv=0 (k=$k lux=$lux)",
                    baseline, withZeroDuv, 0f)
                val baseline55 = NeoGamutCat16Math.calculateAdaptiveCat16ToD55(k, lux)
                val withZeroDuv55 = NeoGamutCat16Math.calculateAdaptiveCat16ToD55(k, lux, duv = 0f)
                assertArrayEquals("D55 adaptation must be identical at duv=0 (k=$k lux=$lux)",
                    baseline55, withZeroDuv55, 0f)
            }
        }
    }

    @Test
    fun `kelvinDuvToCieXy at duv zero equals kelvinToCieXy exactly`() {
        for (k in floatArrayOf(2_500f, 4_000f, 6_500f, 9_000f)) {
            val a = NeoGamutCat16Math.kelvinToCieXy(k)
            val b = NeoGamutCat16Math.kelvinDuvToCieXy(k, 0f)
            assertEquals(a.first, b.first, 0f)
            assertEquals(a.second, b.second, 0f)
        }
    }

    @Test
    fun `the Duv axis is orthogonal to CCT - offsetting Duv does not move the recovered CCT`() {
        for (k in floatArrayOf(3_500f, 5_000f, 6_500f)) {
            for (d in floatArrayOf(-0.010f, -0.005f, 0.006f, 0.012f)) {
                val xy = NeoGamutCat16Math.kelvinDuvToCieXy(k, d)
                val (cctOut, duvOut) = SnceColorPrimitives.cctAndDuv(xyToXyz(xy.first, xy.second))
                assertEquals("CCT must stay ≈ $k after a Duv offset of $d", k, cctOut, 450f)
                assertEquals("recovered Duv must ≈ $d", d, duvOut, 0.0035f)
            }
        }
    }

    @Test
    fun `CP44 adaptation neutralises an off-locus tint that a CCT-only adaptation leaves`() {
        val k = 4_000f
        val measuredDuv = 0.012f          // a greenish fluorescent sitting above the locus
        val lux = 10_000f                 // bright → near-complete adaptation (D ≈ 1)

        // The actual illuminant white (off-locus).
        val whiteXy = NeoGamutCat16Math.kelvinDuvToCieXy(k, measuredDuv)
        val whiteXyz = xyToXyz(whiteXy.first, whiteXy.second)

        // CCT-only adaptation assumes the light is on the locus → residual tint remains.
        val cctOnly = NeoGamutCat16Math.calculateAdaptiveCat16ToD65(k, lux)
        val adaptedCctOnly = NeoGamutCat16Math.multiplyVector(cctOnly, whiteXyz)
        val duvCctOnly = SnceColorPrimitives.cctAndDuv(adaptedCctOnly).second

        // CP44: feed the measured Duv → the off-locus white maps onto D65 (tint removed).
        val cp44 = NeoGamutCat16Math.calculateAdaptiveCat16ToD65(k, lux, duv = measuredDuv)
        val adaptedCp44 = NeoGamutCat16Math.multiplyVector(cp44, whiteXyz)
        val duvCp44 = SnceColorPrimitives.cctAndDuv(adaptedCp44).second

        // The adapted white IS the D65 target, which itself sits slightly off the Planckian
        // locus (daylight) — so "neutral" means matching the target white's own Duv, not 0.
        val d65 = ApexColorConfig.WhitePoint.D65
        val targetDuv = SnceColorPrimitives.cctAndDuv(xyToXyz(d65.x, d65.y)).second

        assertTrue("CP44 must reduce residual tint (cp44=$duvCp44 vs cctOnly=$duvCctOnly)",
            kotlin.math.abs(duvCp44) < kotlin.math.abs(duvCctOnly))
        assertEquals("CP44 must land the gray card on the D65 target's own Duv",
            targetDuv, duvCp44, 0.0015f)
    }
}
