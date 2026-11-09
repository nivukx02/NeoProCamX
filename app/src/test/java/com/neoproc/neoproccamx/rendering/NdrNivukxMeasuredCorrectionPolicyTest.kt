package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrNivukxMeasuredCorrectionPolicyTest {

    @Test
    fun `normal mode receives no measured correction`() {
        val correction = NdrNivukxMeasuredCorrectionPolicy.resolve("OFF")

        assertFalse(correction.active)
        assertEquals(NdrNivukxMeasuredMode.OFF, correction.mode)
        assertEquals(1f, correction.redScale, 0.000001f)
        assertEquals(0f, correction.highlightRolloffBias, 0.000001f)
        assertEquals(0f, correction.shadowLiftBias, 0.000001f)
        assertEquals(1f, correction.saturationScale, 0.000001f)
    }

    @Test
    fun `ndr10 uses measured highlight and hue correction without channel scaling`() {
        val correction = NdrNivukxMeasuredCorrectionPolicy.resolve("NDR10+")

        assertTrue(correction.active)
        assertEquals(NdrNivukxMeasuredMode.NDR10_PLUS, correction.mode)
        assertEquals(1f, correction.redScale, 0.000001f)
        assertEquals(1f, correction.greenScale, 0.000001f)
        assertEquals(1f, correction.blueScale, 0.000001f)
        assertEquals(0.034662f, correction.highlightRolloffBias, 0.000001f)
        assertEquals(0f, correction.shadowLiftBias, 0.000001f)
        assertEquals(6.999f, correction.skyHueCorrectionDegrees, 0.000001f)
    }

    @Test
    fun `nivukx uses measured red and cyan pressure correction`() {
        val correction = NdrNivukxMeasuredCorrectionPolicy.resolve("NIVUKXVISION")

        assertTrue(correction.active)
        assertEquals(NdrNivukxMeasuredMode.NIVUKX_VISION, correction.mode)
        assertEquals(0.974587f, correction.redScale, 0.000001f)
        assertEquals(0.991713f, correction.greenScale, 0.000001f)
        assertEquals(0.991713f, correction.blueScale, 0.000001f)
        assertEquals(0.050035f, correction.highlightRolloffBias, 0.000001f)
        assertEquals(2.493f, correction.skinHueCorrectionDegrees, 0.000001f)
        assertEquals(1f, correction.calibrationConfidence, 0.000001f)
    }

    @Test
    fun `second pass keeps corrections localized instead of adding global channel casts`() {
        val ndr10 = NdrNivukxMeasuredCorrectionPolicy.resolve("NDR10+")
        val nivukx = NdrNivukxMeasuredCorrectionPolicy.resolve("NIVUKX")

        assertEquals(1f, ndr10.redScale, 0.000001f)
        assertEquals(1f, ndr10.greenScale, 0.000001f)
        assertEquals(1f, ndr10.blueScale, 0.000001f)
        assertTrue(nivukx.highlightRolloffBias > 0.020f)
        assertTrue(ndr10.highlightRolloffBias > 0.020f)
    }

    @Test
    fun `low confidence calibration is neutralized once for every consumer`() {
        val measured = NdrNivukxMeasuredCorrectionPolicy.resolve("NIVUKX")
        val rejected = NdrNivukxMeasuredCorrectionPolicy.confidenceGate(
            measured.copy(
                redScale = 0.85f,
                shadowLiftBias = 0.08f,
                saturationScale = 1.15f,
                calibrationConfidence = 0.20f
            )
        )

        assertFalse(rejected.active)
        assertEquals(NdrNivukxMeasuredMode.NIVUKX_VISION, rejected.mode)
        assertEquals(1f, rejected.redScale, 0.000001f)
        assertEquals(0f, rejected.shadowLiftBias, 0.000001f)
        assertEquals(1f, rejected.saturationScale, 0.000001f)
    }

    @Test
    fun `accepted confidence scales every correction toward identity`() {
        val measured = NdrNivukxMeasuredCorrectionPolicy.resolve("NIVUKX")
        val scaled = NdrNivukxMeasuredCorrectionPolicy.confidenceGate(
            measured.copy(calibrationConfidence = 0.775f)
        )

        assertTrue(scaled.active)
        assertTrue(scaled.redScale > measured.redScale)
        assertTrue(scaled.highlightRolloffBias < measured.highlightRolloffBias)
        assertTrue(scaled.skinHueCorrectionDegrees < measured.skinHueCorrectionDegrees)
    }
}
