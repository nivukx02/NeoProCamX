package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSaveWatermarkStylePolicyTest {

    @Test
    fun resolve_usesSettingsStyleDarkSurfaceAndYellowText() {
        val style = CameraSaveWatermarkStylePolicy.resolve("Video saved")

        assertEquals("VIDEO SAVED", style.text)
        assertEquals(0xF2050505.toInt(), style.surfaceColor)
        assertEquals(0x33FFFFFF, style.strokeColor)
        assertEquals(0xFFF8CC55.toInt(), style.textColor)
        assertTrue(style.visibleDurationMs in 900L..1700L)
    }
}
