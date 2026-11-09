package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.AudioSrc
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.VideoRecordingContractPolicy
import com.neoproc.neoproccamx.ui.overlays.GridMode

internal object CameraPreferenceValuePolicy {
    fun audioSourceFromPref(value: String): AudioSrc =
        when (value.uppercase()) {
            "BUILTIN_MIC", "MIC" -> AudioSrc.MIC
            "UNPROCESSED" -> AudioSrc.UNPROCESSED
            "NONE" -> AudioSrc.NONE
            else -> AudioSrc.CAMCORDER
        }

    fun audioSourceFromIndex(index: Int): AudioSrc =
        when (index) {
            1 -> AudioSrc.MIC
            2 -> AudioSrc.UNPROCESSED
            3 -> AudioSrc.NONE
            else -> AudioSrc.CAMCORDER
        }

    fun gridModeFromPref(value: String): GridMode =
        runCatching { GridMode.valueOf(value.uppercase()) }.getOrDefault(GridMode.THIRDS)

    fun resolveContractRequestedFps(
        requestedFps: Int,
        rawVideoModeActive: Boolean,
        rawModeMaxFps: Int
    ): Int {
        val sanitized = VideoRecordingContractPolicy.sanitizeRequestedFps(requestedFps)
        return if (rawVideoModeActive) {
            sanitized.coerceAtMost(rawModeMaxFps)
        } else {
            sanitized
        }
    }

    fun normalizeRawCompressionForMode(
        mode: RawCompressionMode,
        cinemaMode: CinemaVideoMode
    ): RawCompressionMode =
        if (cinemaMode == CinemaVideoMode.RAW) {
            VideoRecordingContractPolicy.resolveRawCompressionMode(mode)
        } else {
            mode
        }
}
