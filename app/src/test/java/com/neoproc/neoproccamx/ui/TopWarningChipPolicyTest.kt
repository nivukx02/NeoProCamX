package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.pipeline.HighEndRuntimeTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopWarningChipPolicyTest {
    @Test
    fun resolve_hidesChipWhenThereIsNoWarning() {
        val state = TopWarningChipPolicy.resolve(
            hardwareWarning = null,
            shutterGuidance = null,
            thermalTier = HighEndRuntimeTier.CINEMA,
            thermalThrottled = false
        )

        assertFalse(state.visible)
        assertEquals("", state.text)
    }

    @Test
    fun resolve_prefersHardwareWarningOverThermalWarning() {
        val state = TopWarningChipPolicy.resolve(
            hardwareWarning = "RAW sidecar fallback",
            shutterGuidance = "Use 1/120 shutter",
            thermalTier = HighEndRuntimeTier.RECOVERY,
            thermalThrottled = true
        )

        assertTrue(state.visible)
        assertEquals("RAW sidecar fallback", state.text)
    }

    @Test
    fun resolve_mapsThermalThrottleToCompactTopChip() {
        val state = TopWarningChipPolicy.resolve(
            hardwareWarning = null,
            shutterGuidance = null,
            thermalTier = HighEndRuntimeTier.STANDARD,
            thermalThrottled = true
        )

        assertTrue(state.visible)
        assertEquals("THERMAL THROTTLING", state.text)
    }

    @Test
    fun topStatusVisible_keepsHudVisibleWhenWarningExistsDuringRecording() {
        assertTrue(
            TopWarningChipPolicy.topStatusVisible(
                controlsVisible = false,
                warningVisible = true,
                recording = false
            )
        )
        assertFalse(
            TopWarningChipPolicy.topStatusVisible(
                controlsVisible = false,
                warningVisible = false,
                recording = false
            )
        )
    }

    @Test
    fun topStatusVisible_keepsHudVisibleForRecordingTimer() {
        assertTrue(
            TopWarningChipPolicy.topStatusVisible(
                controlsVisible = false,
                warningVisible = false,
                recording = true
            )
        )
    }

    @Test
    fun resolve_suppressesBypassCapabilityDisplayChip() {
        val state = TopWarningChipPolicy.resolve(
            hardwareWarning = "Bypass capability: RAW TRUE RAW | VIDEO PARTIAL ISP OFF | FULL SENSOR VENDOR REQUIRED",
            shutterGuidance = null,
            thermalTier = HighEndRuntimeTier.CINEMA,
            thermalThrottled = false
        )

        assertFalse(state.visible)
        assertEquals("", state.text)
    }

    @Test
    fun resolve_suppressesBypassCapabilityButStillShowsThermalWarning() {
        val state = TopWarningChipPolicy.resolve(
            hardwareWarning = "Bypass capability: RAW TRUE RAW",
            shutterGuidance = null,
            thermalTier = HighEndRuntimeTier.STANDARD,
            thermalThrottled = true
        )

        assertTrue(state.visible)
        assertEquals("THERMAL THROTTLING", state.text)
    }
}
