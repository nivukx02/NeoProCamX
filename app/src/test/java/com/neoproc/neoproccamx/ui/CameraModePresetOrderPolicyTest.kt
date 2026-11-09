package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraModePresetOrderPolicyTest {

    @Test
    fun `home video rail contains only video pro and raw`() {
        val order = CameraModePresetOrderPolicy.videoRailOrder()

        assertEquals(
            listOf(CameraModePreset.VIDEO, CameraModePreset.PRO, CameraModePreset.RAW_MODE),
            order
        )
    }

    @Test
    fun `internal ndr and log states project to a visible home preset`() {
        assertEquals(
            CameraModePreset.VIDEO,
            CameraModePresetOrderPolicy.projectToVideoRail(
                CameraModePreset.HDR,
                manualControlsVisible = false
            )
        )
        assertEquals(
            CameraModePreset.PRO,
            CameraModePresetOrderPolicy.projectToVideoRail(
                CameraModePreset.HDR,
                manualControlsVisible = true
            )
        )
        assertEquals(
            CameraModePreset.VIDEO,
            CameraModePresetOrderPolicy.projectToVideoRail(
                CameraModePreset.LOG_VIDEO,
                manualControlsVisible = false
            )
        )
    }

    @Test
    fun `default launch preset is video`() {
        assertEquals(CameraModePreset.VIDEO, CameraModePresetOrderPolicy.defaultPreset())
    }
}
