package com.neoproc.neoproccamx.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NeoLogCurveV3ContractTest {

    @Test
    fun nLogContractNamesSceneLinearFamilyWithoutUnmeasuredLatitudeClaim() {
        assertEquals("N-LOG-v5.0-APEX-LOG", NeoLogCurve.VERSION)
        assertEquals("DEVICE_MEASUREMENT_REQUIRED", NeoLogCurve.DYNAMIC_RANGE_EVIDENCE)
        assertEquals(0.18f, NeoLogCurve.SCENE_GRAY_18, 0f)
        assertEquals(0.50f, NeoLogCurve.GRAY_18_CODE, 0f)
        assertEquals(1.0f, NeoLogCurve.LINEAR_WHITE_POINT, 0f)
    }

    @Test
    fun nLogFamilyVariantsRoundTripAndShareGrayAnchor() {
        NeoLogCurve.Variant.entries.forEach { variant ->
            assertEquals(NeoLogCurve.GRAY_18_CODE, NeoLogCurve.encode(0.18f, variant), 0.0015f)
            listOf(0f, 0.002f, 0.018f, 0.18f, 0.55f, 1f).forEach { linear ->
                val decoded = NeoLogCurve.decode(NeoLogCurve.encode(linear, variant), variant)
                assertEquals("$variant round trip failed at $linear", linear, decoded, 0.0015f)
            }
        }
    }

    @Test
    fun nLogGpuLutIs1024EntryMonotonicAndSpansDeclaredSceneRange() {
        val lut = NeoLogCurve.generateGpuEncodeLut1024()

        assertEquals(1024, lut.size)
        assertEquals(NeoLogCurve.encode(0.0f), lut.first(), 0.0f)
        assertEquals(NeoLogCurve.encode(NeoLogCurve.maxLinear()), lut.last(), 0.000001f)
        for (index in 0 until lut.lastIndex) {
            assertTrue("LUT must be monotonic at $index", lut[index + 1] >= lut[index])
        }
    }

    @Test
    fun nLogFamilyToeJoinsLogBodyWithContinuousFirstDerivative() {
        NeoLogCurve.Variant.entries.forEach { variant ->
            val parameters = NeoLogCurve.v5Parameters(variant)
            val toeSlopeAtKnee =
                (parameters.c1 + 3f * parameters.c3 + 4f * parameters.c4 + 5f * parameters.c5) /
                    parameters.toeCut

            assertEquals("$variant must be C1 at the toe boundary", parameters.m1, toeSlopeAtKnee, 0.001f)
        }
    }

    @Test
    fun nLogV3PiecewiseFormulaExposesC1ContinuousCoefficientsForEveryVariant() {
        NeoLogCurve.Variant.entries.forEach { variant ->
            val coefficients = NeoLogCurve.v3Coefficients(variant)
            val boundaryLinear = coefficients.a * coefficients.e + coefficients.b
            val boundaryLog = coefficients.c *
                kotlin.math.ln(coefficients.d * coefficients.e + coefficients.f) +
                coefficients.g
            val derivativeLinear = coefficients.a
            val derivativeLog = coefficients.c * coefficients.d /
                (coefficients.d * coefficients.e + coefficients.f)

            assertEquals("$variant value continuity", boundaryLinear, boundaryLog, 0.00001f)
            assertEquals("$variant derivative continuity", derivativeLinear, derivativeLog, 0.00001f)
            listOf(0f, 0.003f, coefficients.e, 0.18f, 0.64f, 1.0f).forEach { linear ->
                val encoded = NeoLogCurve.encodeV3(linear, variant)
                val decoded = NeoLogCurve.decodeV3(encoded, variant)
                assertEquals("$variant v3 round trip failed at $linear", linear, decoded, 0.0015f)
            }
        }
    }

    @Test
    fun acesAp0AndAp1MatricesUseProfessionalRowMajorConstants() {
        assertArrayEquals(
            floatArrayOf(0.4397010f, 0.3829780f, 0.1773350f),
            NeoLogCurve.sRgbToAcesAp0Matrix().copyOfRange(0, 3),
            0.000001f
        )
        assertArrayEquals(
            floatArrayOf(2.5214006f, -1.1340097f, -0.3873909f),
            NeoLogCurve.acesAp0ToSrgbMatrix().copyOfRange(0, 3),
            0.000001f
        )
        assertEquals(9, NeoLogCurve.sRgbToAcesAp1Matrix().size)
        assertEquals(9, NeoLogCurve.acesAp1ToSrgbMatrix().size)
    }

    @Test
    fun oklabHighlightRolloffKeepsBrightColorsFiniteAndHueStable() {
        val mapped = NeoLogCurve.oklabHuePreservingHighlightRolloff(
            r = 0.34f,
            g = 0.50f,
            b = 3.80f
        )

        assertTrue(mapped.r in 0f..1f)
        assertTrue(mapped.g in 0f..1f)
        assertTrue(mapped.b in 0f..1f)
        assertTrue(mapped.b >= mapped.g)
        assertTrue(mapped.g >= mapped.r)
        assertTrue(abs(mapped.b - mapped.g) > 0.005f)
    }
}
