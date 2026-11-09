package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RecordingCapabilityPolicy
import com.neoproc.neoproccamx.recording.RecordingHdrVideoLookPolicy

internal data class VideoModeRestoreDecision(
    val hdrMode: String,
    val hdrEnabled: Boolean,
    val cinemaMode: CinemaVideoMode,
    val rawEnabled: Boolean,
    val logEnabled: Boolean,
    val requestedCinemaMode: CinemaVideoMode,
    val cinemaModeAdmission: RecordingCapabilityPolicy.CinemaModeAdmission
)

internal object VideoModeRestorePolicy {
    fun resolve(
        requestedHdrMode: String,
        hdrRequested: Boolean,
        uiMode: AppMode,
        requestedCinemaMode: CinemaVideoMode,
        rawSupported: Boolean
    ): VideoModeRestoreDecision {
        val normalizedHdrMode = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(requestedHdrMode)
        val activeHdrMode = if (hdrRequested && normalizedHdrMode != "OFF") {
            normalizedHdrMode
        } else {
            "OFF"
        }
        val cinemaModeAdmission = RecordingCapabilityPolicy.resolveCinemaModeAdmission(
            requestedMode = requestedCinemaMode,
            rawSupported = rawSupported
        )
        val identityPreservingCinemaMode = cinemaModeAdmission.requestedMode
        val transition = VideoHdrModeTransitionPolicy.resolve(
            requestedMode = activeHdrMode,
            uiMode = uiMode,
            currentCinemaMode = identityPreservingCinemaMode
        )
        val resolvedHdrMode = transition.normalizedHdrMode
        val resolvedHdrEnabled = resolvedHdrMode != "OFF"
        val resolvedCinemaMode = transition.cinemaModeOverride ?: identityPreservingCinemaMode

        return VideoModeRestoreDecision(
            hdrMode = resolvedHdrMode,
            hdrEnabled = resolvedHdrEnabled,
            cinemaMode = resolvedCinemaMode,
            rawEnabled = !resolvedHdrEnabled &&
                resolvedCinemaMode == CinemaVideoMode.RAW &&
                cinemaModeAdmission.accepted,
            logEnabled = !resolvedHdrEnabled &&
                resolvedCinemaMode == CinemaVideoMode.LOG &&
                cinemaModeAdmission.accepted,
            requestedCinemaMode = requestedCinemaMode,
            cinemaModeAdmission = cinemaModeAdmission
        )
    }
}
