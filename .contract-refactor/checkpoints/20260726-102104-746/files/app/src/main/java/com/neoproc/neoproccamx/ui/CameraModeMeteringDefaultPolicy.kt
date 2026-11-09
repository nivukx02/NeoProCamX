package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode

internal object CameraModeMeteringDefaultPolicy {
    fun shouldEnableForPreset(@Suppress("UNUSED_PARAMETER") preset: CameraModePreset): Boolean = false

    fun shouldEnableForVideoMode(
        @Suppress("UNUSED_PARAMETER") uiMode: AppMode,
        @Suppress("UNUSED_PARAMETER") cinemaMode: CinemaVideoMode,
        @Suppress("UNUSED_PARAMETER") hdrMode: String
    ): Boolean = false
}
