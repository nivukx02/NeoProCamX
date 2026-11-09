package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RecordingHdrVideoLookPolicy

internal data class VideoHdrModeTransitionDecision(
    val normalizedHdrMode: String,
    val cinemaModeOverride: CinemaVideoMode? = null
)

internal object VideoHdrModeTransitionPolicy {
    fun resolve(
        requestedMode: String,
        uiMode: AppMode,
        currentCinemaMode: CinemaVideoMode
    ): VideoHdrModeTransitionDecision {
        val normalizedMode = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(requestedMode)
        if (
            normalizedMode != "OFF" &&
            uiMode == AppMode.VIDEO &&
            (currentCinemaMode == CinemaVideoMode.LOG || currentCinemaMode == CinemaVideoMode.RAW)
        ) {
            return VideoHdrModeTransitionDecision(
                normalizedHdrMode = normalizedMode,
                cinemaModeOverride = CinemaVideoMode.PRO
            )
        }
        return VideoHdrModeTransitionDecision(normalizedHdrMode = normalizedMode)
    }
}
