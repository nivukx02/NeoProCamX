package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RecordingHdrVideoLookPolicy

data class CinemaModeSessionRefreshDecision(
    val shouldRefresh: Boolean,
    val rebuildReaders: Boolean,
    val forceRebuild: Boolean
)

object CinemaModeSessionRefreshPolicy {
    private val NoRefresh = CinemaModeSessionRefreshDecision(
        shouldRefresh = false,
        rebuildReaders = false,
        forceRebuild = false
    )

    fun resolve(
        previousMode: CinemaVideoMode,
        currentMode: CinemaVideoMode,
        videoModeActive: Boolean,
        hdrMode: String,
        captureActive: Boolean,
        previousPipelineSignature: String? = null,
        currentPipelineSignature: String? = null,
        // True when this is an HDR->HDR switch whose Camera2 dynamic-range transport
        // (DynamicRangeProfile) is UNCHANGED — e.g. NDR10+ <-> NIVUKX Vision (both HLG10).
        hdrToHdrSameTransport: Boolean = false
    ): CinemaModeSessionRefreshDecision {
        if (!videoModeActive || captureActive) return NoRefresh

        val rawBoundary = previousMode == CinemaVideoMode.RAW || currentMode == CinemaVideoMode.RAW
        if (rawBoundary) {
            return CinemaModeSessionRefreshDecision(
                shouldRefresh = true,
                rebuildReaders = true,
                forceRebuild = true
            )
        }

        // HDR->HDR switch with an identical dynamic-range transport needs NO session rebuild: the
        // OutputConfiguration/DynamicRangeProfile is unchanged, so rebuilding only flashes one
        // SDR-interpreted (reddish) HDR frame before HLG is re-applied. The per-mode look difference
        // (tone curve / grade) is applied downstream per-frame, not via the Camera2 session.
        if (hdrToHdrSameTransport) return NoRefresh

        val pipelineChanged =
            previousPipelineSignature != null &&
                currentPipelineSignature != null &&
                previousPipelineSignature != currentPipelineSignature
        if (pipelineChanged) {
            return CinemaModeSessionRefreshDecision(
                shouldRefresh = true,
                rebuildReaders = false,
                forceRebuild = true
            )
        }

        val hdrActive = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(hdrMode) != "OFF"
        if (previousMode != currentMode || hdrActive) {
            return CinemaModeSessionRefreshDecision(
                shouldRefresh = true,
                rebuildReaders = false,
                forceRebuild = true
            )
        }

        return NoRefresh
    }
}
