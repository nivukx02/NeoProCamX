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
        assertEquals(1f, correction.redScale, 0.000001f)
        assertEquals(0f, correction.highlightRolloffBias, 0.000001f)
    }

    @Test
    fun `ndr10 uses measured highlight and hue correction without channel scaling`() {
        val correction = NdrNivukxMeasuredCorrectionPolicy.resolve("NDR10+")

        assertTrue(correction.active)
        assertEquals(1f, correction.redScale, 0.000001f)
        assertEquals(1f, correction.greenScale, 0.000001f)
        assertEquals(1f, correction.blueScale, 0.000001f)
        assertEquals(0.022000f, correction.highlightRolloffBias, 0.000001f)
        assertEquals(6.999f, correction.skyHueCorrectionDegrees, 0.000001f)
    }

    @Test
    fun `nivukx uses measured red and cyan pressure correction`() {
        val correction = NdrNivukxMeasuredCorrectionPolicy.resolve("NIVUKXVISION")

        assertTrue(correction.active)
        assertEquals(0.974587f, correction.redScale, 0.000001f)
        assertEquals(0.991713f, correction.greenScale, 0.000001f)
        assertEquals(0.991713f, correction.blueScale, 0.000001f)
        assertEquals(0.024000f, correction.highlightRolloffBias, 0.000001f)
        assertEquals(2.493f, correction.skinHueCorrectionDegrees, 0.000001f)
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
}
