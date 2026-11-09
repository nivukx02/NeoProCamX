package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraModeRailCenterSelectionPolicyTest {

    @Test
    fun resolveCenteredPreset_returnsModeClosestToViewportCenter() {
        val result = CameraModeRailCenterSelectionPolicy.resolveCenteredPreset(
            viewportScrollX = 48,
            viewportWidth = 200,
            items = listOf(
                CameraModeRailItem(CameraModePreset.PRO, left = 0, right = 84),
                CameraModeRailItem(CameraModePreset.HDR, left = 106, right = 190),
                CameraModeRailItem(CameraModePreset.LOG_VIDEO, left = 212, right = 296)
            )
        )

        assertEquals(CameraModePreset.HDR, result)
    }

    @Test
    fun resolveCenteredPreset_returnsNullWhenNoModesAreVisible() {
        val result = CameraModeRailCenterSelectionPolicy.resolveCenteredPreset(
            viewportScrollX = 0,
            viewportWidth = 200,
            items = emptyList()
        )

        assertNull(result)
    }

    @Test
    fun scrollTargetForCenteredItem_clampsToStartWhenItemIsNearLeftEdge() {
        val result = CameraModeRailCenterSelectionPolicy.scrollTargetForCenteredItem(
            viewportWidth = 220,
            contentWidth = 600,
            item = CameraModeRailItem(CameraModePreset.VIDEO, left = 0, right = 84)
        )

        assertEquals(0, result)
    }
}
