package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.util.Size
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal enum class Camera2PipelineMode {
    PREVIEW,
    VIDEO_PREVIEW_RECORDING_PARITY,
    ENCODED_RECORDING,
    ENCODED_RECORDING_WITH_READOUT,
    RAW_SENSOR_RECORDING
}

internal enum class Camera2NoiseCleanupProfile {
    PREVIEW,
    ENCODED_VIDEO_STRICT,
    RAW_SENSOR_TRUTHFUL
}

internal data class Camera2PipelineRequestPlan(
    val template: Int,
    val targets: CameraRepeatingRequestTargetPlan,
    val antibandingMode: Int?,
    val captureIntent: Int,
    val pipelineMode: Camera2PipelineMode,
    val noiseCleanupProfile: Camera2NoiseCleanupProfile
)

internal object Camera2PipelineRequestPolicy {
    fun resolve(
        recordingActive: Boolean,
        videoPreviewActive: Boolean = recordingActive,
        uncompressedReadoutRecording: Boolean = false,
        previewAnalysisRequested: Boolean = false,
        rawVideoCaptureEnabled: Boolean,
        rawPreviewActive: Boolean = false,
        hasRawReader: Boolean,
        hasYuvReader: Boolean,
        recordingReadoutOverrideSize: Size?,
        analysisTargetSize: Size?,
        readoutSidecarMode: CameraReadoutSidecarMode,
        requestedFps: Int,
        availableAntibandingModes: IntArray,
        manualExposureActive: Boolean,
        powerGridCountryCode: String? = null,
        powerGridTimeZoneId: String? = null,
        strictHighEndEnabled: Boolean = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
    ): Camera2PipelineRequestPlan {
        val targets = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = recordingActive,
            uncompressedReadoutRecording = uncompressedReadoutRecording,
            previewAnalysisRequested = previewAnalysisRequested,
            rawVideoCaptureEnabled = rawVideoCaptureEnabled,
            hasRawReader = hasRawReader,
            hasYuvReader = hasYuvReader,
            recordingReadoutOverrideSize = recordingReadoutOverrideSize,
            analysisTargetSize = analysisTargetSize,
            readoutSidecarMode = readoutSidecarMode,
            rawPreviewActive = rawPreviewActive
        )

        val pipelineMode = resolvePipelineMode(
            recordingActive = recordingActive,
            videoPreviewActive = videoPreviewActive,
            rawVideoCaptureEnabled = rawVideoCaptureEnabled,
            targets = targets
        )

        return Camera2PipelineRequestPlan(
            template = resolveTemplate(
                pipelineMode = pipelineMode
            ),
            targets = targets,
            antibandingMode = CameraPreviewAntibandingPolicy.resolveMode(
                availableModes = availableAntibandingModes,
                requestedPreviewFps = requestedFps,
                videoMode = recordingActive || videoPreviewActive,
                usingManualExposure = manualExposureActive,
                powerGridCountryCode = powerGridCountryCode,
                powerGridTimeZoneId = powerGridTimeZoneId
            ),
            captureIntent = resolveCaptureIntent(
                recordingActive = recordingActive,
                pipelineMode = pipelineMode,
                rawVideoCaptureEnabled = rawVideoCaptureEnabled,
                manualExposureActive = manualExposureActive
            ),
            pipelineMode = pipelineMode,
            noiseCleanupProfile = resolveNoiseCleanupProfile(
                pipelineMode = pipelineMode,
                strictHighEndEnabled = strictHighEndEnabled
            )
        )
    }

    private fun resolveCaptureIntent(
        recordingActive: Boolean,
        pipelineMode: Camera2PipelineMode,
        rawVideoCaptureEnabled: Boolean,
        manualExposureActive: Boolean
    ): Int = when {
        rawVideoCaptureEnabled && manualExposureActive ->
            CaptureRequest.CONTROL_CAPTURE_INTENT_MANUAL
        pipelineMode == Camera2PipelineMode.RAW_SENSOR_RECORDING ->
            CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
        pipelineMode == Camera2PipelineMode.VIDEO_PREVIEW_RECORDING_PARITY ->
            CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
        recordingActive ->
            CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
        else ->
            CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW
    }

    private fun resolveTemplate(
        pipelineMode: Camera2PipelineMode
    ): Int = when (pipelineMode) {
        Camera2PipelineMode.RAW_SENSOR_RECORDING -> CameraDevice.TEMPLATE_RECORD
        Camera2PipelineMode.ENCODED_RECORDING,
        Camera2PipelineMode.ENCODED_RECORDING_WITH_READOUT -> CameraDevice.TEMPLATE_RECORD
        Camera2PipelineMode.VIDEO_PREVIEW_RECORDING_PARITY -> CameraDevice.TEMPLATE_RECORD
        Camera2PipelineMode.PREVIEW -> CameraDevice.TEMPLATE_PREVIEW
    }

    private fun resolvePipelineMode(
        recordingActive: Boolean,
        videoPreviewActive: Boolean,
        rawVideoCaptureEnabled: Boolean,
        targets: CameraRepeatingRequestTargetPlan
    ): Camera2PipelineMode = when {
        !recordingActive && videoPreviewActive -> Camera2PipelineMode.VIDEO_PREVIEW_RECORDING_PARITY
        !recordingActive -> Camera2PipelineMode.PREVIEW
        rawVideoCaptureEnabled && targets.includeRawSensor -> Camera2PipelineMode.RAW_SENSOR_RECORDING
        !targets.hasReadoutSidecar -> Camera2PipelineMode.VIDEO_PREVIEW_RECORDING_PARITY
        else -> Camera2PipelineMode.ENCODED_RECORDING_WITH_READOUT
    }

    private fun resolveNoiseCleanupProfile(
        pipelineMode: Camera2PipelineMode,
        strictHighEndEnabled: Boolean
    ): Camera2NoiseCleanupProfile = when (pipelineMode) {
        Camera2PipelineMode.PREVIEW -> if (strictHighEndEnabled) {
            Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT
        } else {
            Camera2NoiseCleanupProfile.PREVIEW
        }
        Camera2PipelineMode.VIDEO_PREVIEW_RECORDING_PARITY -> Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT
        Camera2PipelineMode.RAW_SENSOR_RECORDING -> Camera2NoiseCleanupProfile.RAW_SENSOR_TRUTHFUL
        Camera2PipelineMode.ENCODED_RECORDING,
        Camera2PipelineMode.ENCODED_RECORDING_WITH_READOUT ->
            Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT
    }
}
