package com.neoproc.neoproccamx.camera

import android.util.Size

internal data class CameraRepeatingRequestTargetPlan(
    val includeRawSensor: Boolean,
    val includeYuvReadout: Boolean
) {
    val hasReadoutSidecar: Boolean
        get() = includeRawSensor || includeYuvReadout
}

internal object CameraRepeatingRequestTargetPolicy {

    @Suppress("UNUSED_PARAMETER")
    fun resolve(
        recordingActive: Boolean,
        uncompressedReadoutRecording: Boolean = false,
        previewAnalysisRequested: Boolean = false,
        rawVideoCaptureEnabled: Boolean,
        hasRawReader: Boolean,
        hasYuvReader: Boolean,
        recordingReadoutOverrideSize: Size?,
        analysisTargetSize: Size?,
        readoutSidecarMode: CameraReadoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
        rawPreviewActive: Boolean = false
    ): CameraRepeatingRequestTargetPlan {
        if (!recordingActive) {
            // A YUV analysis reader is only a live session output during preview when the HUD
            // monitor analysis feed is requested (see CinemaStreamGraphPolicy). Target it so the
            // CPU pipeline (histogram / waveform / focus-peaking / zebra) actually receives frames.
            // When no analysis stream is in session hasYuvReader is false and this stays a no-op,
            // so the preview-only graph is byte-identical to before.
            // RAW cinema-preview: when RAW preview is active and the RAW reader is in-session, target
            // it during preview too so the debayer receives frames (gated — off for all other modes).
            return CameraRepeatingRequestTargetPlan(
                includeRawSensor = rawPreviewActive && hasRawReader,
                includeYuvReadout = hasYuvReader
            )
        }

        val includeRaw = rawVideoCaptureEnabled && hasRawReader
        // A YUV output that the stream graph deliberately kept in an active recording session must
        // also be targeted by the repeating request. This includes both the uncompressed readout lane
        // and the small NDR exposure/highlight-analysis lane. Previously only the uncompressed lane
        // was targeted, so ENCODED_CINEMA reported YUV_ANALYSIS in the session while delivering zero
        // analysis frames; the adaptive highlight/exposure loop therefore went stale as recording
        // started. The stream graph remains the quality/safety gate: hasYuvReader is true here only
        // when that output was admitted to this session. RAW keeps exclusive sensor ownership.
        val includeYuv =
            !includeRaw &&
                hasYuvReader &&
                (uncompressedReadoutRecording || previewAnalysisRequested)

        return CameraRepeatingRequestTargetPlan(
            includeRawSensor = includeRaw,
            includeYuvReadout = includeYuv
        )
    }
}
