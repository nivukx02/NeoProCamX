package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame-rate picker exposes only the launch cadence contract requested by the product: exact
 * 25 fps at 4K and exact 25/30 fps at 1K/2K. Final admission remains capability-driven when a
 * camera and encoder are available.
 * Tests the pure Int-overload logic (android.util.Size isn't JVM-mockable in plain unit tests).
 */
class SettingsFpsByResolutionTest {

    @Test
    fun `4K and higher resolutions expose only the verified 25fps ceiling`() {
        assertEquals(25, SettingsOptionCatalog.maxFpsForResolution(3840, 2160))
        assertEquals(25, SettingsOptionCatalog.maxFpsForResolution(4096, 2160))
        assertEquals(25, SettingsOptionCatalog.maxFpsForResolution(4096, 3072))
        assertEquals(25, SettingsOptionCatalog.maxFpsForResolution(7680, 4320))
        assertEquals(25, SettingsOptionCatalog.maxFpsForResolution(4096, 1716))
        assertEquals(25, SettingsOptionCatalog.maxFpsForResolution(3840, 1920))
        assertEquals(30, SettingsOptionCatalog.maxFpsForResolution(3264, 2448))
        assertEquals(25, SettingsOptionCatalog.maxFpsForResolution(2160, 3840))
    }

    @Test
    fun `1K and 2K allow up to 30`() {
        assertEquals(30, SettingsOptionCatalog.maxFpsForResolution(1920, 1080))
        assertEquals(30, SettingsOptionCatalog.maxFpsForResolution(2048, 1080))
        assertEquals(30, SettingsOptionCatalog.maxFpsForResolution(1280, 720))
    }

    @Test
    fun `4K fps choices contain no cadence above 25`() {
        val choices = SettingsOptionCatalog.fpsChoicesForResolution(3840, 2160).map { it.value }
        assertEquals(listOf(25), choices)
        assertFalse(choices.any { it > 25 })
        assertEquals(
            listOf(25),
            SettingsOptionCatalog.fpsChoicesForResolution(4096, 1716).map { it.value }
        )
    }

    @Test
    fun `1K and 2K fps choices expose only 25 and 30`() {
        val choices = SettingsOptionCatalog.fpsChoicesForResolution(1920, 1080).map { it.value }
        assertEquals(listOf(25, 30), choices)
        assertTrue("2K must offer 30", choices.contains(30))
        val fourK = SettingsOptionCatalog.fpsChoicesForResolution(3840, 2160).map { it.value }
        assertFalse(fourK.contains(30))
        assertFalse(fourK.contains(60))
    }

    @Test
    fun `stale high fps selection normalizes to 25 when 4K is selected`() {
        assertEquals(25, SettingsOptionCatalog.selectedFpsForResolution(60, 3840, 2160))
        assertEquals(25, SettingsOptionCatalog.selectedFpsForResolution(30, 4096, 1716))
        assertEquals(25, SettingsOptionCatalog.selectedFpsForResolution(24, 4096, 3072))
        assertEquals(25, SettingsOptionCatalog.selectedFpsForResolution(25, 2160, 3840))
    }
}
