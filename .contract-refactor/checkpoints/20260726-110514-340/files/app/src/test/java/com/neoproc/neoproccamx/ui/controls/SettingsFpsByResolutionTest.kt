package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ELITE — the Frame-Rate picker must depend on the selected resolution: 4K-class processed recording is
 * capped at 25 fps, 30 fps is not exposed, and 1080p/2K show the remaining high-cadence rates.
 * Tests the pure Int-overload logic (android.util.Size isn't JVM-mockable in plain unit tests).
 */
class SettingsFpsByResolutionTest {

    @Test
    fun `4K class resolutions cap the framerate at 25`() {
        assertEquals(25, SettingsOptionCatalog.maxFpsForResolution(3840, 2160)) // UHD
        assertEquals(25, SettingsOptionCatalog.maxFpsForResolution(4096, 2160)) // DCI 4K
        assertEquals(25, SettingsOptionCatalog.maxFpsForResolution(4096, 3072)) // 4K full 4:3
        assertEquals(25, SettingsOptionCatalog.maxFpsForResolution(3264, 2448)) // 8 MP 4K-class
        assertEquals(25, SettingsOptionCatalog.maxFpsForResolution(2160, 3840)) // rotated still 4K
    }

    @Test
    fun `1080p and 2K allow up to 60`() {
        assertEquals(60, SettingsOptionCatalog.maxFpsForResolution(1920, 1080))
        assertEquals(60, SettingsOptionCatalog.maxFpsForResolution(2048, 1080))
        assertEquals(60, SettingsOptionCatalog.maxFpsForResolution(1280, 720))
    }

    @Test
    fun `4K fps choices hide everything above 25 and remove 30`() {
        val choices = SettingsOptionCatalog.fpsChoicesForResolution(3840, 2160).map { it.value }
        assertTrue("4K must offer 25", choices.contains(25))
        assertFalse("4K must not offer 30", choices.contains(30))
        assertFalse("4K must not offer 60", choices.contains(60))
        assertFalse("4K must not offer 48", choices.contains(48))
        assertTrue("every 4K option is <= 25", choices.all { it <= 25 })
        assertTrue("4K options are non-empty", choices.isNotEmpty())
    }

    @Test
    fun `1080p fps choices expose the full high-cadence set`() {
        val choices = SettingsOptionCatalog.fpsChoicesForResolution(1920, 1080).map { it.value }
        assertTrue("1080p must offer 60", choices.contains(60))
        assertFalse("1080p settings must not expose 30", choices.contains(30))
        // 1080p shows every rate the app supports (superset of the 4K list).
        val fourK = SettingsOptionCatalog.fpsChoicesForResolution(3840, 2160).map { it.value }
        assertTrue("1080p list must be a superset of the 4K list", choices.containsAll(fourK))
        assertTrue("1080p offers strictly more rates than 4K", choices.size > fourK.size)
    }
}
