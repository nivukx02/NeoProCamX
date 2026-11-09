package com.neoproc.neoproccamx.ui.overlays

internal object UIOverlayChromePolicy {
    fun shouldDrawHardwareProfileBadge(
        cleanHudEnabled: Boolean,
        hardwareProfile: String?
    ): Boolean = !cleanHudEnabled && !hardwareProfile.isNullOrBlank()
}
