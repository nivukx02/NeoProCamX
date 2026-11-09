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
    SENSOR_LINEAR_NDR_PREVIEW,
    SENSOR_LINEAR_NDR_RECORDING,
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

internal data class CameraRawRequestContract(
    val recordingActive: Boolean,
    /** A RAW_SENSOR target is active, regardless of whether it is a RAW-original or NDR source. */
    val rawSensorActive: Boolean,
    /** True only for the user-visible RAW-original/prearm path that requests public ISP-off controls. */
    val rawOriginalCaptureActive: Boolean,
    /** Project-owned NDR uses RAW_SENSOR truth with high-quality processed companion controls. */
    val sensorLinearNdrActive: Boolean
)

/** Keeps the Camera2 request byte-stable between an idle RAW prearm and active RAW capture. */
internal object CameraRawRequestContractPolicy {
    fun resolve(
        recordingActive: Boolean,
        rawVideoCaptureEnabled: Boolean,
        rawVideoPrearmEnabled: Boolean,
        sensorLinearNdrCaptureEnabled: Boolean = false
    ): CameraRawRequestContract {
        val rawOriginalCaptureActive = rawVideoCaptureEnabled || rawVideoPrearmEnabled
        val rawSensorActive = rawOriginalCaptureActive || sensorLinearNdrCaptureEnabled
        return CameraRawRequestContract(
            recordingActive = recordingActive || rawVideoPrearmEnabled,
            rawSensorActive = rawSensorActive,
            rawOriginalCaptureActive = rawOriginalCaptureActive,
            sensorLinearNdrActive = sensorLinearNdrCaptureEnabled
        )
    }
}

internal object Camera2PipelineRequestPolicy {
    fun resolve(
        recordingActive: Boolean,
        videoPreviewActive: Boolean = recordingActive,
        uncompressedReadoutRecording: Boolean = false,
        previewAnalysisRequested: Boolean = false,
        recordingThermalAnalysisIsolationEnabled: Boolean = false,
        rawVideoCaptureEnabled: Boolean,
        rawVideoPrearmEnabled: Boolean = false,
        sensorLinearNdrCaptureEnabled: Boolean = false,
        rawPreviewActive: Boolean = false,
        hasRawReader: Boolean,
        hasYuvReader: Boolean,
        yuvReaderConfiguredAsStandby: Boolean = false,
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
        val rawContract = CameraRawRequestContractPolicy.resolve(
            recordingActive = recordingActive,
            rawVideoCaptureEnabled = rawVideoCaptureEnabled,
            rawVideoPrearmEnabled = rawVideoPrearmEnabled,
            sensorLinearNdrCaptureEnabled = sensorLinearNdrCaptureEnabled
        )
        val targets = CameraRepeatingRequestTargetPolicy.resolve(
            recordingActive = rawContract.recordingActive,
            uncompressedReadoutRecording = uncompressedReadoutRecording,
            previewAnalysisRequested = previewAnalysisRequested,
            recordingThermalAnalysisIsolationEnabled =
                recordingThermalAnalysisIsolationEnabled,
            rawVideoCaptureEnabled = rawContract.rawSensorActive,
            sensorLinearNdrCaptureEnabled = sensorLinearNdrCaptureEnabled,
            hasRawReader = hasRawReader,
            hasYuvReader = hasYuvReader,
            yuvReaderConfiguredAsStandby = yuvReaderConfiguredAsStandby,
            recordingReadoutOverrideSize = recordingReadoutOverrideSize,
            analysisTargetSize = analysisTargetSize,
            readoutSidecarMode = readoutSidecarMode,
            rawPreviewActive = rawPreviewActive || rawVideoPrearmEnabled
        )

        val pipelineMode = resolvePipelineMode(
            recordingActive = rawContract.recordingActive,
            videoPreviewActive = videoPreviewActive,
            rawVideoCaptureEnabled = rawContract.rawSensorActive,
            sensorLinearNdrCaptureEnabled = sensorLinearNdrCaptureEnabled,
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
                videoMode = rawContract.recordingActive || videoPreviewActive,
                usingManualExposure = manualExposureActive,
                powerGridCountryCode = powerGridCountryCode,
                powerGridTimeZoneId = powerGridTimeZoneId
            ),
            captureIntent = resolveCaptureIntent(
                recordingActive = rawContract.recordingActive,
                pipelineMode = pipelineMode,
                rawVideoCaptureEnabled = rawContract.rawSensorActive,
                sensorLinearNdrCaptureEnabled = sensorLinearNdrCaptureEnabled,
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
        sensorLinearNdrCaptureEnabled: Boolean,
        manualExposureActive: Boolean
    ): Int = when {
        sensorLinearNdrCaptureEnabled -> CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
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
        Camera2PipelineMode.SENSOR_LINEAR_NDR_PREVIEW,
        Camera2PipelineMode.SENSOR_LINEAR_NDR_RECORDING,
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
        sensorLinearNdrCaptureEnabled: Boolean,
        targets: CameraRepeatingRequestTargetPlan
    ): Camera2PipelineMode = when {
        sensorLinearNdrCaptureEnabled && recordingActive && targets.includeRawSensor ->
            Camera2PipelineMode.SENSOR_LINEAR_NDR_RECORDING
        sensorLinearNdrCaptureEnabled && targets.includeRawSensor ->
            Camera2PipelineMode.SENSOR_LINEAR_NDR_PREVIEW
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
        Camera2PipelineMode.SENSOR_LINEAR_NDR_PREVIEW,
        Camera2PipelineMode.SENSOR_LINEAR_NDR_RECORDING,
        Camera2PipelineMode.RAW_SENSOR_RECORDING -> Camera2NoiseCleanupProfile.RAW_SENSOR_TRUTHFUL
        Camera2PipelineMode.ENCODED_RECORDING,
        Camera2PipelineMode.ENCODED_RECORDING_WITH_READOUT ->
            Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT
    }
}
