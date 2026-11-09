package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RecordingHdrVideoLookPolicy

data class CinemaModeSessionRefreshDecision(
    val shouldRefresh: Boolean,
    val rebuildReaders: Boolean,
    val forceRebuild: Boolean,
    val shouldRetargetRepeatingRequest: Boolean = false
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
        rawReaderGraphChanged: Boolean = true,
        /** Entering/leaving sensor-linear NDR adds/removes RAW_SENSOR without changing CinemaVideoMode. */
        auxiliaryRawReaderGraphChanged: Boolean = false,
        // True when this is an HDR->HDR switch whose Camera2 dynamic-range transport
        // (DynamicRangeProfile) is UNCHANGED — e.g. NDR10+ <-> NIVUKX Vision (both HLG10).
        hdrToHdrSameTransport: Boolean = false
    ): CinemaModeSessionRefreshDecision {
        if (!videoModeActive || captureActive) return NoRefresh

        if (auxiliaryRawReaderGraphChanged) {
            return CinemaModeSessionRefreshDecision(
                shouldRefresh = true,
                rebuildReaders = true,
                forceRebuild = true
            )
        }

        val rawBoundary = previousMode == CinemaVideoMode.RAW || currentMode == CinemaVideoMode.RAW
        if (rawBoundary) {
            if (!rawReaderGraphChanged) {
                // The standby graph already contains both full-quality outputs, but the repeating
                // request still has to move between RAW_SENSOR and P010 immediately at the boundary.
                return NoRefresh.copy(shouldRetargetRepeatingRequest = true)
            }
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
