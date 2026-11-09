package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.recording.VideoBitrateMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRuntimeFormatterTest {

    @Test
    fun `custom bitrate label omits auto prefix`() {
        assertEquals(
            "120 Mbps",
            SettingsRuntimeFormatter.formatBitrateLabel(
                bitrateMode = VideoBitrateMode.CUSTOM,
                bitrateBitsPerSecond = 120_000_000
            )
        )
    }

    @Test
    fun `capped custom bitrate displays selected target and applied output`() {
        assertEquals(
            "800 Mbps HQ target / 160 Mbps applied (hardware cap)",
            SettingsRuntimeFormatter.formatBitrateStatusLabel(
                bitrateMode = VideoBitrateMode.CUSTOM,
                requestedBitrateBitsPerSecond = 800_000_000,
                appliedBitrateBitsPerSecond = 160_000_000
            )
        )
    }

    @Test
    fun `uncapped custom bitrate does not duplicate applied value`() {
        assertEquals(
            "200 Mbps",
            SettingsRuntimeFormatter.formatBitrateStatusLabel(
                bitrateMode = VideoBitrateMode.CUSTOM,
                requestedBitrateBitsPerSecond = 200_000_000,
                appliedBitrateBitsPerSecond = 200_000_000
            )
        )
    }

    @Test
    fun `auto bitrate label includes auto prefix`() {
        assertEquals(
            "AUTO 220 Mbps",
            SettingsRuntimeFormatter.formatBitrateLabel(
                bitrateMode = VideoBitrateMode.AUTO,
                bitrateBitsPerSecond = 220_000_000
            )
        )
    }

    @Test
    fun `cinema master bitrate label includes master prefix`() {
        assertEquals(
            "MASTER 500 Mbps HQ",
            SettingsRuntimeFormatter.formatBitrateLabel(
                bitrateMode = VideoBitrateMode.CINEMA_MASTER,
                bitrateBitsPerSecond = 500_000_000
            )
        )
    }

    @Test
    fun `custom bitrate mode enables manual slider`() {
        assertTrue(SettingsRuntimeFormatter.isCustomBitrateEnabled(VideoBitrateMode.CUSTOM))
        assertFalse(SettingsRuntimeFormatter.isCustomBitrateEnabled(VideoBitrateMode.AUTO))
        assertFalse(SettingsRuntimeFormatter.isCustomBitrateEnabled(VideoBitrateMode.CINEMA_MASTER))
    }
}
