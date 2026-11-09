package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode

internal object CameraRecordingModeMetadataPolicy {
    fun resolveModeLabel(
        cinemaMode: CinemaVideoMode,
        rawEnabled: Boolean,
        logEnabled: Boolean,
        hdrMode: String,
        manualControlsVisible: Boolean
    ): String {
        return when {
            rawEnabled || cinemaMode == CinemaVideoMode.RAW -> "RAW"
            logEnabled || cinemaMode == CinemaVideoMode.LOG -> "LOG"
            !hdrMode.equals("OFF", ignoreCase = true) -> "HDR"
            manualControlsVisible -> "PRO"
            else -> "VIDEO"
        }
    }
}
