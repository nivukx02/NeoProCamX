package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraDisplayRefreshPolicyTest {
    private val modes = listOf(
        CameraDisplayModeSpec(1, 1260, 2800, 120f),
        CameraDisplayModeSpec(2, 1260, 2800, 144f),
        CameraDisplayModeSpec(3, 1260, 2800, 90f),
        CameraDisplayModeSpec(4, 1260, 2800, 60f),
        CameraDisplayModeSpec(8, 1080, 2400, 60f)
    )

    @Test
    fun recordingSelectsNativeResolutionSixtyHertzMode() {
        val selected = CameraDisplayRefreshPolicy.select(modes, 1260, 2800, true)

        assertEquals(4, selected?.modeId)
        assertEquals(60f, selected?.refreshRate)
    }

    @Test
    fun idlePreviewRetainsNativeResolutionHighestRefreshMode() {
        val selected = CameraDisplayRefreshPolicy.select(modes, 1260, 2800, false)

        assertEquals(2, selected?.modeId)
        assertEquals(144f, selected?.refreshRate)
    }

    @Test
    fun recordingNeverTradesNativeResolutionForAnotherSixtyHertzMode() {
        val selected = CameraDisplayRefreshPolicy.select(modes, 1260, 2800, true)

        assertEquals(1260, selected?.width)
        assertEquals(2800, selected?.height)
    }

    @Test
    fun recordingUsesClosestSupportedModeWhenSixtyHertzIsUnavailable() {
        val selected = CameraDisplayRefreshPolicy.select(
            modes = listOf(
                CameraDisplayModeSpec(1, 1260, 2800, 120f),
                CameraDisplayModeSpec(3, 1260, 2800, 90f)
            ),
            currentWidth = 1260,
            currentHeight = 2800,
            recordingActive = true
        )

        assertEquals(3, selected?.modeId)
    }
}
