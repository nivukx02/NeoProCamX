package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f01 — locks the scene→IRE mapping. The flag-off path must be byte-identical to today's legacy
 * HUD expressions (golden lock); the legal-range and SmallHD-band math is verified independently.
 */
class HudIreScaleTest {

    @Test
    fun defaultFlagOffZebraTriggerEqualsLegacyFrameAnalysisMath() {
        // Legacy expression was ((t * 255f).toInt()); must stay bit-identical while flag is off.
        for (t in listOf(0f, 0.25f, 0.5f, 0.9f, 1f)) {
            assertEquals("t=$t", (t * 255f).toInt(), HudIreScale.zebraTriggerCode8(t))
        }
    }

    @Test
    fun defaultFlagOffNormalizedEqualsLegacyLinearMapping() {
        for (s in 0..255) {
            assertEquals("s=$s", s / 255f, HudIreScale.normalizedFromCode8(s), 0f)
        }
    }

    @Test
    fun legalRangeMapsCode16To0AndCode235To100Ire() {
        assertEquals(0f, HudIreScale.legalRangeIre(16), 1f)
        assertEquals(100f, HudIreScale.legalRangeIre(235), 1f)
        // Monotonic non-decreasing across the ramp.
        var prev = -1f
        for (c in 0..255) {
            val ire = HudIreScale.legalRangeIre(c)
            assertTrue("monotonic at $c", ire >= prev)
            prev = ire
        }
    }

    @Test
    fun ireToLegalCodeIsInverseWithinOneCode() {
        assertEquals(235, HudIreScale.ireToLegalCode8(100f))
        assertEquals(16, HudIreScale.ireToLegalCode8(0f))
        // 70 IRE -> 16 + 0.70*219 = 169.3 -> 169 (within 1 code).
        assertTrue("70 IRE near code 169", kotlin.math.abs(HudIreScale.ireToLegalCode8(70f) - 169) <= 1)
    }

    @Test
    fun smallHdBandsLandWithinOneIreOfTheCalibratedScale() {
        assertEquals(HudIreScale.FalseColorBand.PURPLE, HudIreScale.bandForIre(2.4f))
        assertEquals(HudIreScale.FalseColorBand.GREEN_18, HudIreScale.bandForIre(40f))
        assertEquals(HudIreScale.FalseColorBand.PINK_SKIN, HudIreScale.bandForIre(54f))
        assertEquals(HudIreScale.FalseColorBand.YELLOW_NEARCLIP, HudIreScale.bandForIre(97f))
        assertEquals(HudIreScale.FalseColorBand.RED_CLIP, HudIreScale.bandForIre(100f))
        // Boundaries are within ±1 IRE of the SmallHD reference (green 38-42, skin 52-56, clip 100).
        assertEquals(HudIreScale.FalseColorBand.GREEN_18, HudIreScale.bandForIre(38f))
        assertEquals(HudIreScale.FalseColorBand.AQUA, HudIreScale.bandForIre(42f))
        assertEquals(HudIreScale.FalseColorBand.PINK_SKIN, HudIreScale.bandForIre(52f))
        assertEquals(HudIreScale.FalseColorBand.TEAL, HudIreScale.bandForIre(56f))
    }

    @Test
    fun legacyFalseColorBandsMatchDormantOverlayBoundaries() {
        // FalseColorOverlay code-value bands: <3,<10,<26,<51,<102,<153,<230, else.
        assertEquals(HudIreScale.LegacyFalseColorBand.MAGENTA, HudIreScale.bandForCode8(2))
        assertEquals(HudIreScale.LegacyFalseColorBand.BLUE, HudIreScale.bandForCode8(9))
        assertEquals(HudIreScale.LegacyFalseColorBand.CYAN, HudIreScale.bandForCode8(25))
        assertEquals(HudIreScale.LegacyFalseColorBand.GREEN, HudIreScale.bandForCode8(50))
        assertEquals(HudIreScale.LegacyFalseColorBand.YELLOW, HudIreScale.bandForCode8(101))
        assertEquals(HudIreScale.LegacyFalseColorBand.ORANGE, HudIreScale.bandForCode8(152))
        assertEquals(HudIreScale.LegacyFalseColorBand.RED, HudIreScale.bandForCode8(229))
        assertEquals(HudIreScale.LegacyFalseColorBand.PINK, HudIreScale.bandForCode8(255))
    }
}
