package com.neoproc.neoproccamx.camera

import android.util.Size

internal data class CameraRepeatingRequestTargetPlan(
    val includeRawSensor: Boolean,
    val includeYuvReadout: Boolean
) {
    val hasReadoutSidecar: Boolean
        get() = includeRawSensor || includeYuvReadout
}

/**
 * Decides whether a runtime policy change actually requires publishing a new Camera2 repeating
 * request. Keeping this decision separate from the policy flag prevents a no-op control publication
 * from invalidating a cadence-proven RAW/NPV10 producer during its startup lease.
 */
internal object CameraRepeatingRequestRetargetPolicy {
    fun requiresRepublish(
        before: CameraRepeatingRequestTargetPlan,
        after: CameraRepeatingRequestTargetPlan
    ): Boolean = before != after
}

internal object CameraRepeatingRequestTargetPolicy {

    @Suppress("UNUSED_PARAMETER")
    fun resolve(
        recordingActive: Boolean,
        uncompressedReadoutRecording: Boolean = false,
        previewAnalysisRequested: Boolean = false,
        recordingThermalAnalysisIsolationEnabled: Boolean = false,
        rawVideoCaptureEnabled: Boolean,
        sensorLinearNdrCaptureEnabled: Boolean = false,
        hasRawReader: Boolean,
        hasYuvReader: Boolean,
        yuvReaderConfiguredAsStandby: Boolean = false,
        recordingReadoutOverrideSize: Size?,
        analysisTargetSize: Size?,
        readoutSidecarMode: CameraReadoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
        rawPreviewActive: Boolean = false
    ): CameraRepeatingRequestTargetPlan {
        // A mode-switch/NPV10 standby reader is deliberately part of the CameraCaptureSession so RAW
        // and processed modes can share one stable output graph. Session membership must not make
        // that reader a repeating-request target: a targeted 4K P010 surface consumes ISP bandwidth
        // and queues frames even when RAW is the only live readout. NPV10 recording and explicit
        // preview analysis are the only consumers that make the preconfigured P010/YUV output live.
        val uncompressedWriterActive = recordingActive && uncompressedReadoutRecording
        val hasTargetableYuvReader =
            hasYuvReader &&
                (!yuvReaderConfiguredAsStandby ||
                    uncompressedWriterActive ||
                    previewAnalysisRequested)

        if (sensorLinearNdrCaptureEnabled) {
            // Sensor-linear NDR owns the RAW readout exclusively. Treat a simultaneous uncompressed
            // flag as stale mode-switch state: targeting P010 beside RAW is outside the supported graph
            // on affected Qualcomm providers and can abort the camera service asynchronously. The NDR
            // encoder receives the project-owned FP16 render output, not a vendor P010 camera target.
            return CameraRepeatingRequestTargetPlan(
                includeRawSensor = hasRawReader,
                includeYuvReadout = false
            )
        }

        if (!recordingActive) {
            // A YUV analysis reader is only a live session output during preview when proprietary
            // scene analysis is requested (see CinemaStreamGraphPolicy). Target it so the internal
            // exposure/highlight pipeline receives frames.
            // When no analysis stream is in session hasYuvReader is false and this stays a no-op,
            // so the preview-only graph is byte-identical to before.
            // RAW cinema-preview: when RAW preview is active and the RAW reader is in-session, target
            // it during preview too so the debayer receives frames (gated — off for all other modes).
            return CameraRepeatingRequestTargetPlan(
                includeRawSensor = rawPreviewActive && hasRawReader,
                // Preserve ordinary still/video analysis readers. A pre-armed NPV10 P010 reader
                // remains membership-only when it has no preview consumer, but NIV99 may explicitly
                // activate that already-configured reader for full-frame preview statistics. Its
                // callback continuously drains the queue, so this does not accumulate pre-record
                // buffers or change the eventual recording format/precision.
                includeYuvReadout =
                    hasTargetableYuvReader &&
                        (!uncompressedReadoutRecording || previewAnalysisRequested)
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
                hasTargetableYuvReader &&
                (
                    uncompressedWriterActive ||
                        (previewAnalysisRequested && !recordingThermalAnalysisIsolationEnabled)
                    )

        return CameraRepeatingRequestTargetPlan(
            includeRawSensor = includeRaw,
            includeYuvReadout = includeYuv
        )
    }
}
