package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraModePresetSelectionPolicyTest {
    @Test
    fun resolve_prefersHdrOverManualControls() {
        val result = CameraModePresetSelectionPolicy.resolve(
            currentPreset = CameraModePreset.PRO,
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            manualControlsVisible = true,
            rawEnabled = false,
            logEnabled = false,
            hdrEnabled = true,
            hdrMode = "HDR10"
        )

        assertEquals(CameraModePreset.HDR, result)
    }

    @Test
    fun resolve_treatsOffHdrModeAsAuthoritativeWhenHdrBooleanIsStale() {
        val result = CameraModePresetSelectionPolicy.resolve(
            currentPreset = CameraModePreset.HDR,
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            manualControlsVisible = true,
            rawEnabled = false,
            logEnabled = false,
            hdrEnabled = true,
            hdrMode = "OFF"
        )

        assertEquals(CameraModePreset.PRO, result)
    }

    @Test
    fun resolve_prefersRawOverOtherVideoFlags() {
        val result = CameraModePresetSelectionPolicy.resolve(
            currentPreset = CameraModePreset.HDR,
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.RAW,
            manualControlsVisible = true,
            rawEnabled = true,
            logEnabled = true,
            hdrEnabled = true,
            hdrMode = "HDR10"
        )

        assertEquals(CameraModePreset.RAW_MODE, result)
    }

    @Test
    fun resolve_fallsBackToCurrentPresetWhenNoModeForcesSelection() {
        val result = CameraModePresetSelectionPolicy.resolve(
            currentPreset = CameraModePreset.LOG_VIDEO,
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            manualControlsVisible = false,
            rawEnabled = false,
            logEnabled = false,
            hdrEnabled = false,
            hdrMode = "OFF"
        )

        assertEquals(CameraModePreset.LOG_VIDEO, result)
    }
}
