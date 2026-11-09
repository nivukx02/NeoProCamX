package com.neoproc.neoproccamx.ui

internal object CameraModePresetOrderPolicy {
    // LOG is convert-only — recorded RAW is developed to NeoLog offline, so LOG_VIDEO is
    // not a live capture preset and is omitted from the rail. RAW remains a separate NPRAW
    // sensor route while VIDEO/PRO/HDR use the normal processed recording pipeline.
    fun videoRailOrder(): List<CameraModePreset> = listOf(
        CameraModePreset.VIDEO,
        CameraModePreset.PRO,
        CameraModePreset.HDR,
        CameraModePreset.RAW_MODE
    )

    fun defaultPreset(): CameraModePreset = CameraModePreset.VIDEO
}
