package com.neoproc.neoproccamx.ui

internal object CameraModePresetOrderPolicy {
    // NDR and LOG remain available to internal/settings/conversion workflows, but neither is a
    // home-screen capture preset. The public rail exposes only VIDEO, PRO, and RAW.
    fun videoRailOrder(): List<CameraModePreset> = listOf(
        CameraModePreset.VIDEO,
        CameraModePreset.PRO,
        CameraModePreset.RAW_MODE
    )

    fun projectToVideoRail(
        preset: CameraModePreset,
        manualControlsVisible: Boolean
    ): CameraModePreset = when (preset) {
        CameraModePreset.HDR,
        CameraModePreset.LOG_VIDEO ->
            if (manualControlsVisible) CameraModePreset.PRO else CameraModePreset.VIDEO
        else -> preset
    }

    fun defaultPreset(): CameraModePreset = CameraModePreset.VIDEO
}
