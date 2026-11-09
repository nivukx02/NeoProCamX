package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOverlayControlPolicyTest {

    @Test
    fun resolve_keepsMeteringAndPreviewControlsVisibleInVideoMode() {
        val state = VideoOverlayControlPolicy.resolve(AppMode.VIDEO)

        assertTrue(state.showFocusAssist)
        assertTrue(state.showExposureLockControl)
        assertTrue(state.showRightStatusRail)
    }

    @Test
    fun resolve_keepsMeteringAndPreviewControlsVisibleInProMode() {
        val state = VideoOverlayControlPolicy.resolve(AppMode.PRO)

        assertTrue(state.showFocusAssist)
        assertTrue(state.showExposureLockControl)
        assertTrue(state.showRightStatusRail)
    }
}
