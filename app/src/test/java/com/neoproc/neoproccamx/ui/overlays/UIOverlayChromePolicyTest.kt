package com.neoproc.neoproccamx.ui.overlays

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UIOverlayChromePolicyTest {
    @Test
    fun shouldDrawHardwareProfileBadge_cleanHudHidesDiagnostics() {
        assertFalse(
            UIOverlayChromePolicy.shouldDrawHardwareProfileBadge(
                cleanHudEnabled = true,
                hardwareProfile = "CinemaPro"
            )
        )
    }

    @Test
    fun shouldDrawHardwareProfileBadge_requiresProfileWhenDiagnosticsEnabled() {
        assertFalse(
            UIOverlayChromePolicy.shouldDrawHardwareProfileBadge(
                cleanHudEnabled = false,
                hardwareProfile = ""
            )
        )
        assertTrue(
            UIOverlayChromePolicy.shouldDrawHardwareProfileBadge(
                cleanHudEnabled = false,
                hardwareProfile = "CinemaPro"
            )
        )
    }
}
