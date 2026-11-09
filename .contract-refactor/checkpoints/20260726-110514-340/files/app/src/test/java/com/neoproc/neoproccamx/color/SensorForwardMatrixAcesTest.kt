package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * CP11 — correct DNG SENSOR_FORWARD_MATRIX based sensor→XYZ(D50)→ACEScg path that the cinema-debayer
 * (CP10) will consume, instead of the dormant SENSOR_COLOR_TRANSFORM path (which is the inverse
 * direction). Verifies mired interpolation and end-to-end neutral preservation.
 */
class SensorForwardMatrixAcesTest {

    private fun maxChannelSpread(v: FloatArray): Float =
        max(max(abs(v[0] - v[1]), abs(v[1] - v[2])), abs(v[0] - v[2]))

    @Test
    fun forwardInterpolationReturnsEndpointsAtReferenceCcts() {
        val fm1 = FloatArray(9) { 1f }
        val fm2 = FloatArray(9) { 2f }
        val cctA = ApexColorConfig.DUAL_ILLUMINANT_CCT_A
        val cctD65 = ApexColorConfig.DUAL_ILLUMINANT_CCT_D65

        val atCct1 = NeoGamutApexPipeline.buildSensorToXyzD50FromForward(fm1, fm2, cctA, cctA, cctD65)
        val atCct2 = NeoGamutApexPipeline.buildSensorToXyzD50FromForward(fm1, fm2, cctD65, cctA, cctD65)
        atCct1.forEach { assertEquals(1f, it, 1e-5f) } // weight=1 -> fm1
        atCct2.forEach { assertEquals(2f, it, 1e-5f) } // weight=0 -> fm2

        // A CCT between the two references must blend strictly between the endpoints.
        val mid = NeoGamutApexPipeline.buildSensorToXyzD50FromForward(fm1, fm2, 4000f, cctA, cctD65)
        mid.forEach { assertTrue("blend must be within (1,2): $it", it in 1f..2f) }
    }

    @Test
    fun xyzD50WhiteMapsToNeutralAcesCg() {
        val m = NeoGamutApexPipeline.xyzD50ToAcesAp1Matrix()
        val ap1 = NeoGamutCat16Math.multiplyVector(m, ApexColorConfig.WhitePoint.D50.xyz)
        // D50 white must come out achromatic (R≈G≈B) in ACEScg.
        assertTrue("D50 white must map to neutral ACEScg, got ${ap1.toList()}", maxChannelSpread(ap1) < 0.02f)
        assertTrue("neutral must be positive", ap1[0] > 0f)
    }

    @Test
    fun whiteBalancedNeutralSensorMapsToNeutralAcesCg() {
        // A forward matrix whose neutral (1,1,1) maps exactly to the D50 white point.
        val d50 = ApexColorConfig.WhitePoint.D50.xyz
        val fm = floatArrayOf(
            d50[0] / 3f, d50[0] / 3f, d50[0] / 3f,
            d50[1] / 3f, d50[1] / 3f, d50[1] / 3f,
            d50[2] / 3f, d50[2] / 3f, d50[2] / 3f
        )
        val sensorToXyz = NeoGamutApexPipeline.buildSensorToXyzD50FromForward(
            fm, fm, ApexColorConfig.DUAL_ILLUMINANT_CCT_D65
        )
        val xyzToAp1 = NeoGamutApexPipeline.xyzD50ToAcesAp1Matrix()
        val sensorToAp1 = NeoGamutCat16Math.multiply3x3(xyzToAp1, sensorToXyz)
        val ap1 = NeoGamutCat16Math.multiplyVector(sensorToAp1, floatArrayOf(1f, 1f, 1f))
        assertTrue(
            "white-balanced neutral sensor must map to neutral ACEScg, got ${ap1.toList()}",
            maxChannelSpread(ap1) < 0.02f
        )
        assertTrue(ap1[0] > 0f)
    }

    @Test
    fun forwardMatrixRejectsWrongSize() {
        var threw = false
        try {
            NeoGamutApexPipeline.buildSensorToXyzD50FromForward(FloatArray(6), FloatArray(9), 5000f)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("must reject non-3x3 forward matrix", threw)
    }
}
