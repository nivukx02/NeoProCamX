package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode

internal enum class CameraModePreset {
    VIDEO,
    PRO,
    HDR,
    LOG_VIDEO,
    RAW_MODE
}

internal object CameraModePresetSelectionPolicy {
    fun resolve(
        currentPreset: CameraModePreset,
        uiMode: AppMode,
        cinemaMode: CinemaVideoMode,
        manualControlsVisible: Boolean,
        rawEnabled: Boolean,
        logEnabled: Boolean,
        hdrEnabled: Boolean,
        hdrMode: String
    ): CameraModePreset {
        val rawVideoActive =
            uiMode == AppMode.VIDEO &&
                cinemaMode == CinemaVideoMode.RAW &&
                rawEnabled
        val hdrVideoActive =
            uiMode == AppMode.VIDEO &&
                !hdrMode.equals("OFF", ignoreCase = true)
        return when {
            rawVideoActive -> CameraModePreset.RAW_MODE
            uiMode == AppMode.VIDEO && logEnabled -> CameraModePreset.LOG_VIDEO
            hdrVideoActive -> CameraModePreset.HDR
            manualControlsVisible -> CameraModePreset.PRO
            else -> currentPreset
        }
    }
}
