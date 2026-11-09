package com.neoproc.neoproccamx.snce

import com.neoproc.neoproccamx.snce.SnceCam16.SnceCam16Appearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnceAppearancePhenomenaTest {

    @Test
    fun `hunt multiplier is unity at daylight and boosts chroma in dim light`() {
        assertEquals(1f, SnceAppearancePhenomena.huntChromaMultiplier(64f), 0.02f)
        val dim = SnceAppearancePhenomena.huntChromaMultiplier(4f)
        val dark = SnceAppearancePhenomena.huntChromaMultiplier(1f)
        assertTrue("dim light boosts chroma", dim > 1f)
        assertTrue("darker boosts more", dark > dim)
    }

    @Test
    fun `stevens lightness is identity at the reference luminance`() {
        assertEquals(50f, SnceAppearancePhenomena.stevensLightness(50f, 64f), 0.05f)
        // monotonic in J at any luminance
        val low = SnceAppearancePhenomena.stevensLightness(30f, 200f)
        val high = SnceAppearancePhenomena.stevensLightness(70f, 200f)
        assertTrue(high > low)
    }

    @Test
    fun `HK leaves achromatic brightness unchanged and lifts saturated brightness`() {
        val achromatic = SnceAppearancePhenomena.hkBrightness(q = 100f, s = 0f, hueDeg = 30f)
        assertEquals(100f, achromatic, 1e-3f)
        val saturated = SnceAppearancePhenomena.hkBrightness(q = 100f, s = 60f, hueDeg = 30f)
        assertTrue("chromatic stimulus appears brighter", saturated > 100f)
    }

    @Test
    fun `bezold-brucke is identity at reference and shifts yellow and blue at high luminance`() {
        assertEquals(65f, SnceAppearancePhenomena.bezoldBruckeHue(65f, 64f), 1e-3f)
        val yellowShift = SnceAppearancePhenomena.bezoldBruckeHue(65f, 640f) - 65f
        val blueShift = SnceAppearancePhenomena.bezoldBruckeHue(240f, 640f) - 240f
        assertTrue("yellow hue moves at high luminance", kotlin.math.abs(yellowShift) > 0.5f)
        assertTrue("blue hue moves at high luminance", kotlin.math.abs(blueShift) > 0.5f)
        // red is stable
        assertEquals(20f, SnceAppearancePhenomena.bezoldBruckeHue(20f, 640f), 1e-3f)
    }

    @Test
    fun `abney shift vanishes at full saturation and is full at zero saturation`() {
        // cyan (180 deg) has a +8 deg shift at zero saturation per the directive table
        assertEquals(8f, SnceAppearancePhenomena.abneyShiftAtZeroSaturation(180f), 1e-3f)
        val fullySaturated = SnceAppearancePhenomena.abneyHue(180f, 1f)
        assertEquals("no shift at full saturation", 180f, fullySaturated, 1e-3f)
        val desaturated = SnceAppearancePhenomena.abneyHue(180f, 0f)
        assertEquals("full shift at zero saturation", 188f, desaturated, 0.5f)
    }

    @Test
    fun `simultaneous contrast induces opposite chroma only in flat regions`() {
        // flat region (low contrast): gray (a=b=0) on a red surround (+a) turns slightly -a
        val (a, b) = SnceAppearancePhenomena.simultaneousContrast(
            a = 0f, b = 0f, surroundA = 10f, surroundB = 0f, localContrast = 0f
        )
        assertTrue("induced toward cyan-green", a < 0f)
        assertEquals(0f, b, 1e-4f)
        // high-contrast edge: induction is masked
        val (a2, b2) = SnceAppearancePhenomena.simultaneousContrast(
            a = 1f, b = 2f, surroundA = 10f, surroundB = 5f, localContrast = 0.5f
        )
        assertEquals(1f, a2, 1e-4f)
        assertEquals(2f, b2, 1e-4f)
    }

    @Test
    fun `composed appearance correction stays finite with a wrapped hue`() {
        val app = SnceCam16Appearance(
            j = 45f, c = 30f, h = 58f, q = 120f, m = 26f, s = 40f, a = 8f, b = 5f
        )
        val corrected = SnceAppearancePhenomena.correctAppearance(app, sceneLa = 6f)
        listOf(corrected.j, corrected.c, corrected.h, corrected.q, corrected.m).forEach {
            assertTrue(it.isFinite())
        }
        assertTrue(corrected.h in 0f..360f)
        assertTrue("dim light raised chroma", corrected.c > app.c)
    }
}
