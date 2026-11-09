package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.ManualVideoColorSelectionPolicy
import kotlin.math.abs

internal enum class PreviewRecordingParityStatus {
    PASS,
    DEFERRED_DEVELOP,
    ISP_FALLBACK,
    FAIL
}

internal enum class PreviewRecordingParityFailureCode {
    PREVIEW_RECORDING_SIGNATURE_MISMATCH,
    PREVIEW_RECORDING_QUALITY_SIGNATURE_MISMATCH,
    PREVIEW_RECORDING_SHADER_HASH_MISMATCH,
    PREVIEW_RECORDING_LUT_HASH_MISMATCH,
    PREVIEW_RECORDING_STAGE_MISMATCH,
    PREVIEW_RECORDING_COLOR_SPACE_MISMATCH,
    PREVIEW_RECORDING_COLOR_UNIFORM_MISMATCH,
    PREVIEW_RECORDING_ENHANCEMENT_IDENTITY_MISMATCH,
    PREVIEW_RECORDING_GRADE_IDENTITY_MISMATCH,
    PREVIEW_RECORDING_OUTPUT_FORMAT_MISMATCH,
    PREVIEW_RECORDING_OUTPUT_TRANSFER_MISMATCH,
    PREVIEW_RECORDING_OUTPUT_GAMUT_MISMATCH,
    PREVIEW_RECORDING_OUTPUT_BIT_DEPTH_MISMATCH,
    PREVIEW_RECORDING_OUTPUT_QUANTIZATION_MISMATCH,
    PREVIEW_RECORDING_ENDPOINT_EVIDENCE_MISSING,
    PREVIEW_RECORDING_DENOISE_PROFILE_MISMATCH,
    PREVIEW_RECORDING_SHARPNESS_PROFILE_MISMATCH,
    PREVIEW_RECORDING_COLOR_SCIENCE_MISMATCH,
    PREVIEW_RECORDING_GEOMETRY_MISMATCH,
    SDR_OR_LOG_HDR_TRANSPORT_ENABLED,
    HDR_TRANSPORT_DISABLED,
    RAW_TRANSPORT_NOT_RAW_SENSOR,
    CAMERA2_DIRECT_FALLBACK_FOR_PROCESSED_MODE,
    PIXEL_REALITY_BYPASS_DETECTED,
    AVERAGE_PERFORMANCE_BUDGET_EXCEEDED,
    CAMERA2_FRAME_LINEAGE_CERTIFICATE_MISSING,
    CAMERA2_FRAME_LINEAGE_WINDOW_INSUFFICIENT,
    CAMERA2_FRAME_LINEAGE_TIMESTAMP_MATCH_RATIO_LOW,
    CAMERA2_FRAME_LINEAGE_STALE_GENERATION,
    CAMERA2_FRAME_LINEAGE_NON_MONOTONIC_TIMESTAMPS,
    CAMERA2_FRAME_LINEAGE_DUPLICATE_ENCODER_SUBMISSION,
    CAMERA2_FRAME_LINEAGE_ENDPOINT_OMISSION,
    CAMERA2_FRAME_LINEAGE_PREVIEW_ENCODER_FRAME_DELTA_EXCEEDED
}

internal data class PreviewRecordingRenderGraphLock(
    val previewShaderSha256: String = "",
    val recordingShaderSha256: String = "",
    val previewLutSha256: String = "",
    val recordingLutSha256: String = "",
    val previewColorSpace: String = "",
    val recordingColorSpace: String = "",
    val previewColorScience: String = "",
    val recordingColorScience: String = "",
    val previewColorUniformsSha256: String = "",
    val recordingColorUniformsSha256: String = "",
    val monitorDeliveryColorUniformDifferenceAllowed: Boolean = false,
    val previewEnhancementIdentity: String = "",
    val recordingEnhancementIdentity: String = "",
    val previewGradeIdentity: String = "",
    val recordingGradeIdentity: String = "",
    val previewDenoiseProfile: String = "",
    val recordingDenoiseProfile: String = "",
    val previewSharpnessProfile: String = "",
    val recordingSharpnessProfile: String = "",
    val previewStages: List<String> = emptyList(),
    val recordingStages: List<String> = emptyList(),
    val previewOutputFormat: String = "",
    val recordingOutputFormat: String = "",
    val previewOutputTransfer: String = "",
    val recordingOutputTransfer: String = "",
    val previewOutputGamut: String = "",
    val recordingOutputGamut: String = "",
    val previewOutputBitDepth: Int = 0,
    val recordingOutputBitDepth: Int = 0,
    val previewOutputQuantizationLevels: Int = 0,
    val recordingOutputQuantizationLevels: Int = 0,
    val pixelRealityBypassDetected: Boolean = false,
    val timelineSemaphoreRequired: Boolean = true,
    val averageFrameProcessingMs: Double? = null,
    val averageRuntimeBudgetMs: Double = 14.0,
    val runtimeMetadata: Map<String, String> = emptyMap(),
    val geometryMatrix: FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    ),
    val previewGeometryMatrix: FloatArray = geometryMatrix,
    val recordingGeometryMatrix: FloatArray = previewGeometryMatrix
)

internal data class PreviewRecordingPipelineDecision(
    val deliveryKind: VideoDeliveryKind,
    val previewDeliveryKind: VideoDeliveryKind,
    val recordingDeliveryKind: VideoDeliveryKind,
    val feedPath: RecordingFeedPath,
    val toneMap: VideoPipelineToneMap,
    val selectedColorSpace: String,
    val selectedColorScience: String,
    val effectiveHdrMode: String,
    val logSdrWideGamut: Boolean,
    val hdrTransportRequired: Boolean,
    val rawSensorOriginal: Boolean,
    val tenBitTransportRequested: Boolean,
    val denoiseProfile: VideoDenoiseProfile,
    val sharpnessProfile: VideoSharpnessProfile,
    val stabilizationMode: String,
    val metadataSignature: String,
    val previewSignature: String,
    val recordingSignature: String,
    val previewQualitySignature: String,
    val recordingQualitySignature: String,
    val renderGraphShaderSha256: String,
    val renderGraphLutSha256: String,
    val renderGraphStagesSignature: String,
    val geometryMatrixSignature: String,
    val timelineSemaphoreRequired: Boolean,
    val viewportResolutionScale: Float,
    val decisionSignature: String
)

internal data class PreviewRecordingParityCertificate(
    val status: PreviewRecordingParityStatus,
    val previewSignature: String,
    val recordingSignature: String,
    val previewQualitySignature: String,
    val recordingQualitySignature: String,
    val plannedFeedPath: RecordingFeedPath,
    val actualFeedPath: RecordingFeedPath,
    val directCamera2PathDetected: Boolean,
    val camera2DirectFallback: Boolean,
    val fallbackReason: String,
    val failureCodes: List<String>,
    val renderGraphShaderSha256: String,
    val renderGraphLutSha256: String,
    val renderGraphStagesSignature: String,
    val geometryMatrixSignature: String,
    val timelineSemaphoreRequired: Boolean,
    val viewportResolutionScale: Float,
    val averagePerformanceMeasurementAvailable: Boolean,
    val measuredAverageFrameProcessingMs: Double?,
    val averageRuntimeBudgetMs: Double,
    val averagePerformanceBudgetSatisfied: Boolean,
    val peakPerformanceMeasurementAvailable: Boolean,
    val measuredPeakFrameProcessingMs: Double?,
    val peakPerformanceBudgetCertified: Boolean,
    val strictVulkanParityCertified: Boolean,
    val recordingBlocked: Boolean,
    val certificateSignature: String
)

internal data class RecordingPipelineParityPlan(
    val deliveryKind: VideoDeliveryKind,
    val feedPath: RecordingFeedPath,
    val hdrRequestedForSessionPlan: Boolean,
    val rawRequestedForSessionPlan: Boolean,
    val logEnabledForSessionPlan: Boolean,
    val tenBitRequestedForSessionPlan: Boolean,
    val encoderHdrMode: String,
    val encoderColorSpace: String,
    val encoderLogEnabled: Boolean,
    val encoderRawEnabled: Boolean,
    val recordingGradePreview: Boolean,
    val graphSignature: String,
    val sharedDecision: PreviewRecordingPipelineDecision,
    val parityCertificate: PreviewRecordingParityCertificate,
    val parityQualitySignature: String,
    val viewportResolutionScale: Float,
    val paritySignature: String
)

internal object RecordingPipelineParityPolicy {
    private const val REALTIME_VULKAN_ENCODER_FEED_ENABLED = true
    private const val CAMERA2_FRAME_LINEAGE_PREFIX = "camera2FrameLineage"
    private const val CAMERA2_FRAME_LINEAGE_CERTIFICATE_PRESENT =
        "camera2FrameLineageCertificatePresent"
    private const val CAMERA2_FRAME_LINEAGE_EVIDENCE_WINDOW_FRAMES =
        "camera2FrameLineageEvidenceWindowFrames"
    private const val CAMERA2_FRAME_LINEAGE_EVIDENCE_WINDOW_DURATION_MS =
        "camera2FrameLineageEvidenceWindowDurationMs"
    private const val CAMERA2_FRAME_LINEAGE_TIMESTAMP_MATCH_RATIO =
        "camera2FrameLineageTimestampMatchRatio"
    private const val CAMERA2_FRAME_LINEAGE_STALE_GENERATION_COUNT =
        "camera2FrameLineageStaleGenerationCount"
    private const val CAMERA2_FRAME_LINEAGE_NON_MONOTONIC_TIMESTAMP_COUNT =
        "camera2FrameLineageNonMonotonicTimestampCount"
    private const val CAMERA2_FRAME_LINEAGE_DUPLICATE_ENCODER_SUBMISSION_COUNT =
        "camera2FrameLineageDuplicateEncoderSubmissionCount"
    private const val CAMERA2_FRAME_LINEAGE_PREVIEW_ENDPOINT_OMISSION_COUNT =
        "camera2FrameLineagePreviewEndpointOmissionCount"
    private const val CAMERA2_FRAME_LINEAGE_ENCODER_ENDPOINT_OMISSION_COUNT =
        "camera2FrameLineageEncoderEndpointOmissionCount"
    private const val CAMERA2_FRAME_LINEAGE_SUBMITTED_FRAME_DIFFERENCE =
        "camera2FrameLineageSubmittedFrameDifference"
    private const val RECORD_DEVELOP_LIGHT_MASTER = "recordDevelopLightMaster"
    private const val RECORD_DEVELOP_OFFLINE_REQUIRED = "recordDevelopOfflineDevelopRequired"
    private const val RECORD_DEVELOP_LIVE_COLOR_PROCESSING = "recordDevelopLiveColorProcessing"
    private const val RECORD_DEVELOP_OFFLINE_OUTPUT_FORMAT = "recordDevelopOfflineOutputFormat"
    private const val RECORD_DEVELOP_LIVE_FLAT_MASTER = "FLAT_10BIT_MASTER_ONLY"
    private const val RECORD_DEVELOP_OFFLINE_R210 = "R210_444"
    private const val CAMERA2_FRAME_LINEAGE_MIN_WINDOW_FRAMES = 60L
    private const val CAMERA2_FRAME_LINEAGE_MIN_WINDOW_DURATION_MS = 2_000L
    private const val CAMERA2_FRAME_LINEAGE_MIN_TIMESTAMP_MATCH_RATIO = 0.995
    private const val CAMERA2_FRAME_LINEAGE_MAX_SUBMITTED_FRAME_DIFFERENCE = 1L
    private val evidenceOnlyParityFailures = setOf(
        PreviewRecordingParityFailureCode.PREVIEW_RECORDING_SIGNATURE_MISMATCH,
        PreviewRecordingParityFailureCode.PREVIEW_RECORDING_QUALITY_SIGNATURE_MISMATCH,
        PreviewRecordingParityFailureCode.PREVIEW_RECORDING_ENHANCEMENT_IDENTITY_MISMATCH,
        PreviewRecordingParityFailureCode.PREVIEW_RECORDING_GRADE_IDENTITY_MISMATCH,
        PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_CERTIFICATE_MISSING,
        PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_WINDOW_INSUFFICIENT,
        PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_TIMESTAMP_MATCH_RATIO_LOW,
        PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_STALE_GENERATION,
        PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_NON_MONOTONIC_TIMESTAMPS,
        PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_DUPLICATE_ENCODER_SUBMISSION,
        PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_ENDPOINT_OMISSION,
        PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_PREVIEW_ENCODER_FRAME_DELTA_EXCEEDED
    )

    private data class Camera2FrameLineageRuntimeEvidence(
        val required: Boolean,
        val metadataPresent: Boolean,
        val certificatePresent: Boolean,
        val evidenceWindowFrames: Long?,
        val evidenceWindowDurationMs: Long?,
        val timestampMatchRatio: Double?,
        val staleGenerationCount: Long,
        val nonMonotonicTimestampCount: Long,
        val duplicateEncoderSubmissionCount: Long,
        val previewEndpointOmissionCount: Long,
        val encoderEndpointOmissionCount: Long,
        val submittedFrameDifference: Long?,
        val failureCodes: List<PreviewRecordingParityFailureCode>
    ) {
        val strictCertified: Boolean =
            required && certificatePresent && failureCodes.isEmpty()
        val releaseCapped: Boolean =
            required && !strictCertified
        val signature: String = listOf(
            "camera2FrameLineageRequired=$required",
            "camera2FrameLineageMetadataPresent=$metadataPresent",
            "camera2FrameLineageCertificatePresent=$certificatePresent",
            "camera2FrameLineageEvidenceWindowFrames=${evidenceWindowFrames ?: "unknown"}",
            "camera2FrameLineageEvidenceWindowDurationMs=${evidenceWindowDurationMs ?: "unknown"}",
            "camera2FrameLineageTimestampMatchRatio=${timestampMatchRatio ?: "unknown"}",
            "camera2FrameLineageStaleGenerationCount=$staleGenerationCount",
            "camera2FrameLineageNonMonotonicTimestampCount=$nonMonotonicTimestampCount",
            "camera2FrameLineageDuplicateEncoderSubmissionCount=$duplicateEncoderSubmissionCount",
            "camera2FrameLineagePreviewEndpointOmissionCount=$previewEndpointOmissionCount",
            "camera2FrameLineageEncoderEndpointOmissionCount=$encoderEndpointOmissionCount",
            "camera2FrameLineageSubmittedFrameDifference=${submittedFrameDifference ?: "unknown"}",
            "camera2FrameLineageStrictCertified=$strictCertified",
            "camera2FrameLineageReleaseCapped=$releaseCapped"
        ).joinToString(separator = "|")
    }

    fun resolve(
        graphContract: VideoPipelineContract,
        rawSupported: Boolean,
        vulkanRuntimeSupported: Boolean,
        offscreenAhbEglEncoderSupported: Boolean = false,
        byteBufferP010EncoderSupported: Boolean = false,
        preferByteBufferP010Encoder: Boolean = false,
        selectedColorScience: String = "USER_MANUAL_COLOR",
        stabilizationMode: String = "APP_CONTROLLED_STABILIZATION",
        actualFeedPathOverride: RecordingFeedPath? = null,
        renderGraphLock: PreviewRecordingRenderGraphLock = PreviewRecordingRenderGraphLock()
    ): RecordingPipelineParityPlan {
        val effectiveDeliveryKind =
            if (graphContract.deliveryKind == VideoDeliveryKind.RAW && !rawSupported) {
                VideoDeliveryKind.SDR
            } else {
                graphContract.deliveryKind
            }
        val rawRequested = effectiveDeliveryKind == VideoDeliveryKind.RAW
        val logEnabled = effectiveDeliveryKind == VideoDeliveryKind.LOG
        val hdrRequested = effectiveDeliveryKind == VideoDeliveryKind.HDR &&
            graphContract.effectiveHdrMode != "OFF"
        val encoderHdrMode = if (hdrRequested) graphContract.effectiveHdrMode else "OFF"
        val encoderColorSpace = when (effectiveDeliveryKind) {
            VideoDeliveryKind.RAW -> "RAW"
            VideoDeliveryKind.SDR -> if (graphContract.deliveryKind == VideoDeliveryKind.RAW) {
                "Rec.709"
            } else {
                graphContract.deliveryState.effectiveColorSpace
            }
            VideoDeliveryKind.HDR,
            VideoDeliveryKind.LOG -> graphContract.deliveryState.effectiveColorSpace
        }
        val tenBitRequested = when (effectiveDeliveryKind) {
            VideoDeliveryKind.RAW -> false
            VideoDeliveryKind.HDR,
            VideoDeliveryKind.LOG,
            VideoDeliveryKind.SDR -> graphContract.tenBitRequested
        }
        val internalHardwareImportStable = vulkanRuntimeSupported && !rawRequested
        val realtimeVulkanFeedAllowed =
            REALTIME_VULKAN_ENCODER_FEED_ENABLED &&
                internalHardwareImportStable
        val plannedFeedPath = RecordingPreviewContinuityPolicy.resolveFeedPath(
            rawVideoEnabled = rawRequested,
            vulkanRuntimeSupported = vulkanRuntimeSupported,
            offscreenAhbEglEncoderSupported = offscreenAhbEglEncoderSupported && !rawRequested,
            byteBufferP010EncoderSupported = byteBufferP010EncoderSupported && !rawRequested,
            preferByteBufferP010Encoder = preferByteBufferP010Encoder && !rawRequested,
            preferInternalHardwareImport = realtimeVulkanFeedAllowed,
            internalHardwareImportStable = internalHardwareImportStable
        )
        val feedPath = actualFeedPathOverride ?: plannedFeedPath
        val resolvedColorScience = resolveSelectedColorScience(
            selectedColorScience = selectedColorScience,
            deliveryKind = effectiveDeliveryKind,
            encoderHdrMode = encoderHdrMode,
            encoderColorSpace = encoderColorSpace
        )
        val paritySignature = paritySignature(
            graphContract = graphContract,
            deliveryKind = effectiveDeliveryKind,
            feedPath = feedPath,
            hdrRequested = hdrRequested,
            rawRequested = rawRequested,
            logEnabled = logEnabled,
            tenBitRequested = tenBitRequested,
            encoderHdrMode = encoderHdrMode,
            encoderColorSpace = encoderColorSpace,
            selectedColorScience = resolvedColorScience
        )
        val parityQualitySignature = parityQualitySignature(
            deliveryKind = effectiveDeliveryKind,
            feedPath = feedPath,
            encoderColorSpace = encoderColorSpace,
            selectedColorScience = resolvedColorScience,
            denoiseProfile = graphContract.denoiseProfile,
            sharpnessProfile = graphContract.sharpnessProfile
        )
        val sharedDecision = sharedDecision(
            graphContract = graphContract,
            deliveryKind = effectiveDeliveryKind,
            feedPath = feedPath,
            hdrRequested = hdrRequested,
            rawRequested = rawRequested,
            logEnabled = logEnabled,
            tenBitRequested = tenBitRequested,
            encoderHdrMode = encoderHdrMode,
            encoderColorSpace = encoderColorSpace,
            selectedColorScience = resolvedColorScience,
            stabilizationMode = stabilizationMode,
            renderGraphLock = renderGraphLock
        )
        val parityCertificate = parityCertificate(
            graphContract = graphContract,
            sharedDecision = sharedDecision,
            deliveryKind = effectiveDeliveryKind,
            plannedFeedPath = plannedFeedPath,
            actualFeedPath = feedPath,
            renderGraphLock = renderGraphLock
        )
        return RecordingPipelineParityPlan(
            deliveryKind = effectiveDeliveryKind,
            feedPath = feedPath,
            hdrRequestedForSessionPlan = hdrRequested,
            rawRequestedForSessionPlan = rawRequested,
            logEnabledForSessionPlan = logEnabled,
            tenBitRequestedForSessionPlan = tenBitRequested,
            encoderHdrMode = encoderHdrMode,
            encoderColorSpace = encoderColorSpace,
            encoderLogEnabled = logEnabled,
            encoderRawEnabled = rawRequested,
            recordingGradePreview = graphContract.recordingGradePreview,
            graphSignature = graphContract.graphSignature,
            sharedDecision = sharedDecision,
            parityCertificate = parityCertificate,
            parityQualitySignature = parityQualitySignature,
            viewportResolutionScale = sharedDecision.viewportResolutionScale,
            paritySignature = listOf(
                paritySignature,
                parityQualitySignature,
                sharedDecision.decisionSignature,
                parityCertificate.certificateSignature
            ).joinToString(separator = "|")
        )
    }

    private fun sharedDecision(
        graphContract: VideoPipelineContract,
        deliveryKind: VideoDeliveryKind,
        feedPath: RecordingFeedPath,
        hdrRequested: Boolean,
        rawRequested: Boolean,
        logEnabled: Boolean,
        tenBitRequested: Boolean,
        encoderHdrMode: String,
        encoderColorSpace: String,
        selectedColorScience: String,
        stabilizationMode: String,
        renderGraphLock: PreviewRecordingRenderGraphLock
    ): PreviewRecordingPipelineDecision {
        val modeContract = graphContract.deliveryState.pipelineContract
        val toneMap = when (deliveryKind) {
            VideoDeliveryKind.SDR -> modeContract.toneMap
            VideoDeliveryKind.HDR -> modeContract.toneMap
            VideoDeliveryKind.LOG -> VideoPipelineToneMap.LOG_ORIGINAL
            VideoDeliveryKind.RAW -> VideoPipelineToneMap.RAW_LINEAR_MONITOR
        }
        val colorScience = resolveSelectedColorScience(
            selectedColorScience = selectedColorScience,
            deliveryKind = deliveryKind,
            encoderHdrMode = encoderHdrMode,
            encoderColorSpace = encoderColorSpace
        )
        val metadataSignature = metadataSignature(
            graphContract = graphContract,
            deliveryKind = deliveryKind,
            rawRequested = rawRequested,
            logEnabled = logEnabled,
            tenBitRequested = tenBitRequested,
            encoderHdrMode = encoderHdrMode,
            encoderColorSpace = encoderColorSpace,
            selectedColorScience = colorScience
        )
        val previewOutput = modeContract.previewStagesDetail
            .lastOrNull { it.stage == VideoSensorPipelineStage.OUTPUT }
            ?: modeContract.previewStagesDetail.lastOrNull()
        val recordingOutput = modeContract.recordingStagesDetail
            .lastOrNull { it.stage == VideoSensorPipelineStage.OUTPUT }
            ?: modeContract.recordingStagesDetail.lastOrNull()
        val previewShader =
            renderGraphLock.previewShaderSha256.ifBlank { graphContract.graphSignature }
        val recordingShader =
            renderGraphLock.recordingShaderSha256.ifBlank { graphContract.graphSignature }
        val previewLut =
            renderGraphLock.previewLutSha256.ifBlank { "${graphContract.graphSignature}:lut" }
        val recordingLut =
            renderGraphLock.recordingLutSha256.ifBlank { "${graphContract.graphSignature}:lut" }
        val previewColorSpace =
            renderGraphLock.previewColorSpace.ifBlank { encoderColorSpace }
        val recordingColorSpace =
            renderGraphLock.recordingColorSpace.ifBlank { encoderColorSpace }
        val previewColorScience =
            renderGraphLock.previewColorScience.ifBlank { colorScience }
        val recordingColorScience =
            renderGraphLock.recordingColorScience.ifBlank { colorScience }
        val previewColorUniforms = renderGraphLock.previewColorUniformsSha256
        val recordingColorUniforms = renderGraphLock.recordingColorUniformsSha256
        val previewEnhancementIdentity = renderGraphLock.previewEnhancementIdentity
        val recordingEnhancementIdentity = renderGraphLock.recordingEnhancementIdentity
        val previewGradeIdentity = renderGraphLock.previewGradeIdentity
        val recordingGradeIdentity = renderGraphLock.recordingGradeIdentity
        val previewDenoise = renderGraphLock.previewDenoiseProfile.ifBlank {
            graphContract.denoiseProfile.name
        }
        val recordingDenoise = renderGraphLock.recordingDenoiseProfile.ifBlank {
            graphContract.denoiseProfile.name
        }
        val previewSharpness = renderGraphLock.previewSharpnessProfile.ifBlank {
            graphContract.sharpnessProfile.name
        }
        val recordingSharpness = renderGraphLock.recordingSharpnessProfile.ifBlank {
            graphContract.sharpnessProfile.name
        }
        val previewStages = renderGraphLock.previewStages.ifEmpty {
            modeContract.previewPipelineStages.map { it.name }
        }
        val recordingStages = renderGraphLock.recordingStages.ifEmpty {
            modeContract.recordingPipelineStages.map { it.name }
        }
        val previewOutputFormat = canonicalOutputFormat(
            renderGraphLock.previewOutputFormat.ifBlank { previewOutput?.vulkanFormat.orEmpty() }
        )
        val recordingOutputFormat = canonicalOutputFormat(
            renderGraphLock.recordingOutputFormat.ifBlank { recordingOutput?.vulkanFormat.orEmpty() }
        )
        val previewOutputTransfer =
            renderGraphLock.previewOutputTransfer.ifBlank { previewOutput?.transferFunction.orEmpty() }
        val recordingOutputTransfer =
            renderGraphLock.recordingOutputTransfer.ifBlank { recordingOutput?.transferFunction.orEmpty() }
        val previewOutputGamut =
            renderGraphLock.previewOutputGamut.ifBlank { previewOutput?.gamut.orEmpty() }
        val recordingOutputGamut =
            renderGraphLock.recordingOutputGamut.ifBlank { recordingOutput?.gamut.orEmpty() }
        val previewOutputBitDepth = renderGraphLock.previewOutputBitDepth
            .takeIf { it > 0 } ?: previewOutput?.bitDepth ?: 0
        val recordingOutputBitDepth = renderGraphLock.recordingOutputBitDepth
            .takeIf { it > 0 } ?: recordingOutput?.bitDepth ?: 0
        val previewOutputQuantization = renderGraphLock.previewOutputQuantizationLevels
            .takeIf { it > 0 } ?: quantizationLevelsForBitDepth(previewOutputBitDepth)
        val recordingOutputQuantization = renderGraphLock.recordingOutputQuantizationLevels
            .takeIf { it > 0 } ?: quantizationLevelsForBitDepth(recordingOutputBitDepth)
        val renderGraphStagesSignature =
            "preview=${previewStages.joinToString(separator = ">")}|recording=${recordingStages.joinToString(separator = ">")}"
        val previewGeometryMatrixSignature = geometryMatrixSignature(renderGraphLock.previewGeometryMatrix)
        val recordingGeometryMatrixSignature = geometryMatrixSignature(renderGraphLock.recordingGeometryMatrix)
        val geometryMatrixSignature =
            if (previewGeometryMatrixSignature == recordingGeometryMatrixSignature) {
                previewGeometryMatrixSignature
            } else {
                "preview=$previewGeometryMatrixSignature|recording=$recordingGeometryMatrixSignature"
            }
        val viewportResolutionScale = 1.0f
        val modeSignature = listOf(
            deliveryKind.name,
            "hdr=$encoderHdrMode",
            "raw=$rawRequested",
            "log=$logEnabled",
            "tenBit=$tenBitRequested",
            "colorSpace=$encoderColorSpace",
            "tone=${toneMap.name}"
        ).joinToString(separator = "|")
        val previewQualitySignature = endpointQualitySignature(
            modeSignature,
            colorSpace = previewColorSpace,
            colorScience = previewColorScience,
            colorUniformsSha256 = previewColorUniforms,
            enhancementIdentity = previewEnhancementIdentity,
            gradeIdentity = previewGradeIdentity,
            denoiseProfile = previewDenoise,
            sharpnessProfile = previewSharpness,
            shaderSha256 = previewShader,
            lutSha256 = previewLut,
            stages = previewStages,
            outputFormat = previewOutputFormat,
            outputTransfer = previewOutputTransfer,
            outputGamut = previewOutputGamut,
            outputBitDepth = previewOutputBitDepth,
            outputQuantizationLevels = previewOutputQuantization,
            geometrySignature = previewGeometryMatrixSignature,
            stabilizationMode = stabilizationMode,
            timelineSemaphoreRequired = renderGraphLock.timelineSemaphoreRequired,
            viewportResolutionScale = viewportResolutionScale,
            metadataSignature = metadataSignature,
            feedPath = feedPath
        )
        val recordingQualitySignature = endpointQualitySignature(
            modeSignature,
            colorSpace = recordingColorSpace,
            colorScience = recordingColorScience,
            colorUniformsSha256 = recordingColorUniforms,
            enhancementIdentity = recordingEnhancementIdentity,
            gradeIdentity = recordingGradeIdentity,
            denoiseProfile = recordingDenoise,
            sharpnessProfile = recordingSharpness,
            shaderSha256 = recordingShader,
            lutSha256 = recordingLut,
            stages = recordingStages,
            outputFormat = recordingOutputFormat,
            outputTransfer = recordingOutputTransfer,
            outputGamut = recordingOutputGamut,
            outputBitDepth = recordingOutputBitDepth,
            outputQuantizationLevels = recordingOutputQuantization,
            geometrySignature = recordingGeometryMatrixSignature,
            stabilizationMode = stabilizationMode,
            timelineSemaphoreRequired = renderGraphLock.timelineSemaphoreRequired,
            viewportResolutionScale = viewportResolutionScale,
            metadataSignature = metadataSignature,
            feedPath = feedPath
        )
        val previewSignature =
            listOf(modeSignature, previewQualitySignature).joinToString(separator = "|")
        val recordingSignature =
            listOf(modeSignature, recordingQualitySignature).joinToString(separator = "|")
        return PreviewRecordingPipelineDecision(
            deliveryKind = deliveryKind,
            previewDeliveryKind = deliveryKind,
            recordingDeliveryKind = deliveryKind,
            feedPath = feedPath,
            toneMap = toneMap,
            selectedColorSpace = encoderColorSpace,
            selectedColorScience = colorScience,
            effectiveHdrMode = encoderHdrMode,
            logSdrWideGamut = logEnabled && encoderHdrMode == "OFF" &&
                encoderColorSpace.contains("2020", ignoreCase = true),
            hdrTransportRequired = hdrRequested && encoderHdrMode != "OFF",
            rawSensorOriginal = rawRequested,
            tenBitTransportRequested = tenBitRequested,
            denoiseProfile = graphContract.denoiseProfile,
            sharpnessProfile = graphContract.sharpnessProfile,
            stabilizationMode = stabilizationMode,
            metadataSignature = metadataSignature,
            previewSignature = previewSignature,
            recordingSignature = recordingSignature,
            previewQualitySignature = previewQualitySignature,
            recordingQualitySignature = recordingQualitySignature,
            renderGraphShaderSha256 = previewShader.takeIf { it == recordingShader } ?: "$previewShader!=$recordingShader",
            renderGraphLutSha256 = previewLut.takeIf { it == recordingLut } ?: "$previewLut!=$recordingLut",
            renderGraphStagesSignature = renderGraphStagesSignature,
            geometryMatrixSignature = geometryMatrixSignature,
            timelineSemaphoreRequired = renderGraphLock.timelineSemaphoreRequired,
            viewportResolutionScale = viewportResolutionScale,
            decisionSignature = listOf(
                "DECISION",
                "preview=$previewSignature",
                "recording=$recordingSignature",
                "feed=${feedPath.name}",
                "renderGraphShader=${previewShader.takeIf { it == recordingShader } ?: "MISMATCH"}",
                "renderGraphLut=${previewLut.takeIf { it == recordingLut } ?: "MISMATCH"}",
                renderGraphStagesSignature,
                geometryMatrixSignature,
                "viewportScale=$viewportResolutionScale"
            ).joinToString(separator = "|")
        )
    }

    private fun parityCertificate(
        graphContract: VideoPipelineContract,
        sharedDecision: PreviewRecordingPipelineDecision,
        deliveryKind: VideoDeliveryKind,
        plannedFeedPath: RecordingFeedPath,
        actualFeedPath: RecordingFeedPath,
        renderGraphLock: PreviewRecordingRenderGraphLock
    ): PreviewRecordingParityCertificate {
        val directCamera2PathDetected = actualFeedPath == RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE
        val camera2PreviewParityPathDetected =
            actualFeedPath == RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE
        val camera2DirectFallback = directCamera2PathDetected && deliveryKind != VideoDeliveryKind.RAW
        val strictVulkanRoute = actualFeedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER ||
            actualFeedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
        val enforceEndpointParity = strictVulkanRoute && deliveryKind != VideoDeliveryKind.RAW
        val lineageEvidence = camera2FrameLineageRuntimeEvidence(
            metadata = renderGraphLock.runtimeMetadata,
            required = enforceEndpointParity
        )
        val modeContract = graphContract.deliveryState.pipelineContract
        val previewOutput = modeContract.previewStagesDetail
            .lastOrNull { it.stage == VideoSensorPipelineStage.OUTPUT }
            ?: modeContract.previewStagesDetail.lastOrNull()
        val recordingOutput = modeContract.recordingStagesDetail
            .lastOrNull { it.stage == VideoSensorPipelineStage.OUTPUT }
            ?: modeContract.recordingStagesDetail.lastOrNull()
        val previewShader =
            renderGraphLock.previewShaderSha256.ifBlank { graphContract.graphSignature }
        val recordingShader =
            renderGraphLock.recordingShaderSha256.ifBlank { graphContract.graphSignature }
        val previewLut =
            renderGraphLock.previewLutSha256.ifBlank { "${graphContract.graphSignature}:lut" }
        val recordingLut =
            renderGraphLock.recordingLutSha256.ifBlank { "${graphContract.graphSignature}:lut" }
        val previewColorSpace =
            renderGraphLock.previewColorSpace.ifBlank { sharedDecision.selectedColorSpace }
        val recordingColorSpace =
            renderGraphLock.recordingColorSpace.ifBlank { sharedDecision.selectedColorSpace }
        val previewDenoise = renderGraphLock.previewDenoiseProfile.ifBlank {
            sharedDecision.denoiseProfile.name
        }
        val recordingDenoise = renderGraphLock.recordingDenoiseProfile.ifBlank {
            sharedDecision.denoiseProfile.name
        }
        val previewSharpness = renderGraphLock.previewSharpnessProfile.ifBlank {
            sharedDecision.sharpnessProfile.name
        }
        val recordingSharpness = renderGraphLock.recordingSharpnessProfile.ifBlank {
            sharedDecision.sharpnessProfile.name
        }
        val previewColorScience = renderGraphLock.previewColorScience.ifBlank {
            sharedDecision.selectedColorScience
        }
        val recordingColorScience = renderGraphLock.recordingColorScience.ifBlank {
            sharedDecision.selectedColorScience
        }
        val previewColorUniforms = renderGraphLock.previewColorUniformsSha256
        val recordingColorUniforms = renderGraphLock.recordingColorUniformsSha256
        val previewEnhancementIdentity = renderGraphLock.previewEnhancementIdentity
        val recordingEnhancementIdentity = renderGraphLock.recordingEnhancementIdentity
        val previewGradeIdentity = renderGraphLock.previewGradeIdentity
        val recordingGradeIdentity = renderGraphLock.recordingGradeIdentity
        val recordDevelopFlatMaster =
            renderGraphLock.runtimeMetadata.recordDevelopFlatMasterActive(recordingEnhancementIdentity)
        val previewStages = renderGraphLock.previewStages.ifEmpty {
            modeContract.previewPipelineStages.map { it.name }
        }
        val recordingStages = renderGraphLock.recordingStages.ifEmpty {
            modeContract.recordingPipelineStages.map { it.name }
        }
        val previewOutputFormat = canonicalOutputFormat(
            renderGraphLock.previewOutputFormat.ifBlank { previewOutput?.vulkanFormat.orEmpty() }
        )
        val recordingOutputFormat = canonicalOutputFormat(
            renderGraphLock.recordingOutputFormat.ifBlank { recordingOutput?.vulkanFormat.orEmpty() }
        )
        val previewOutputTransfer =
            renderGraphLock.previewOutputTransfer.ifBlank { previewOutput?.transferFunction.orEmpty() }
        val recordingOutputTransfer =
            renderGraphLock.recordingOutputTransfer.ifBlank { recordingOutput?.transferFunction.orEmpty() }
        val previewOutputGamut =
            renderGraphLock.previewOutputGamut.ifBlank { previewOutput?.gamut.orEmpty() }
        val recordingOutputGamut =
            renderGraphLock.recordingOutputGamut.ifBlank { recordingOutput?.gamut.orEmpty() }
        val previewOutputBitDepth = renderGraphLock.previewOutputBitDepth
            .takeIf { it > 0 } ?: previewOutput?.bitDepth ?: 0
        val recordingOutputBitDepth = renderGraphLock.recordingOutputBitDepth
            .takeIf { it > 0 } ?: recordingOutput?.bitDepth ?: 0
        val previewOutputQuantization = renderGraphLock.previewOutputQuantizationLevels
            .takeIf { it > 0 } ?: quantizationLevelsForBitDepth(previewOutputBitDepth)
        val recordingOutputQuantization = renderGraphLock.recordingOutputQuantizationLevels
            .takeIf { it > 0 } ?: quantizationLevelsForBitDepth(recordingOutputBitDepth)
        val explicitColorUniformAllowance =
            deliveryKind == VideoDeliveryKind.LOG &&
                renderGraphLock.monitorDeliveryColorUniformDifferenceAllowed &&
                previewColorUniforms.isNotBlank() &&
                recordingColorUniforms.isNotBlank()
        val stageMismatch = previewStages != recordingStages
        val previewGeometryMatrixSignature = geometryMatrixSignature(renderGraphLock.previewGeometryMatrix)
        val recordingGeometryMatrixSignature = geometryMatrixSignature(renderGraphLock.recordingGeometryMatrix)
        val geometryMismatch = previewGeometryMatrixSignature != recordingGeometryMatrixSignature
        val measuredAverageFrameProcessingMs = renderGraphLock.averageFrameProcessingMs
            ?.takeIf { it.isFinite() && it > 0.0 }
        val averageRuntimeBudgetMs = renderGraphLock.averageRuntimeBudgetMs
        val averagePerformanceMeasurementAvailable = measuredAverageFrameProcessingMs != null
        val averagePerformanceExceeded =
            measuredAverageFrameProcessingMs != null &&
                averageRuntimeBudgetMs.isFinite() &&
                averageRuntimeBudgetMs > 0.0 &&
                measuredAverageFrameProcessingMs > averageRuntimeBudgetMs
        val averagePerformanceBudgetSatisfied =
            averagePerformanceMeasurementAvailable &&
                averageRuntimeBudgetMs.isFinite() &&
                averageRuntimeBudgetMs > 0.0 &&
                !averagePerformanceExceeded
        val peakPerformanceMeasurementAvailable = false
        val measuredPeakFrameProcessingMs: Double? = null
        val peakPerformanceBudgetCertified = false
        val endpointEvidenceComplete = listOf(
            previewShader,
            recordingShader,
            previewLut,
            recordingLut,
            previewColorSpace,
            recordingColorSpace,
            previewColorScience,
            recordingColorScience,
            previewColorUniforms,
            recordingColorUniforms,
            previewEnhancementIdentity,
            recordingEnhancementIdentity,
            previewGradeIdentity,
            recordingGradeIdentity,
            previewDenoise,
            recordingDenoise,
            previewSharpness,
            recordingSharpness,
            previewOutputFormat,
            recordingOutputFormat,
            previewOutputTransfer,
            recordingOutputTransfer,
            previewOutputGamut,
            recordingOutputGamut
        ).all { it.isNotBlank() } &&
            previewStages.isNotEmpty() &&
            recordingStages.isNotEmpty() &&
            previewOutputBitDepth > 0 &&
            recordingOutputBitDepth > 0 &&
            previewOutputQuantization > 0 &&
            recordingOutputQuantization > 0
        val endpointMismatch =
            previewShader != recordingShader ||
                previewLut != recordingLut ||
                stageMismatch ||
                previewColorSpace != recordingColorSpace ||
                previewDenoise != recordingDenoise ||
                previewSharpness != recordingSharpness ||
                previewColorScience != recordingColorScience ||
                (
                    previewColorUniforms != recordingColorUniforms &&
                        !explicitColorUniformAllowance
                    ) ||
                previewEnhancementIdentity != recordingEnhancementIdentity ||
                previewGradeIdentity != recordingGradeIdentity ||
                previewOutputFormat != recordingOutputFormat ||
                previewOutputTransfer != recordingOutputTransfer ||
                previewOutputGamut != recordingOutputGamut ||
                previewOutputBitDepth != recordingOutputBitDepth ||
                previewOutputQuantization != recordingOutputQuantization ||
                geometryMismatch
        val failures = buildList {
            if (enforceEndpointParity && endpointMismatch) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_SIGNATURE_MISMATCH)
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_QUALITY_SIGNATURE_MISMATCH)
            }
            if (enforceEndpointParity && !endpointEvidenceComplete) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_ENDPOINT_EVIDENCE_MISSING)
            }
            if (enforceEndpointParity && previewShader != recordingShader) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_SHADER_HASH_MISMATCH)
            }
            if (enforceEndpointParity && previewLut != recordingLut) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_LUT_HASH_MISMATCH)
            }
            if (enforceEndpointParity && stageMismatch) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_STAGE_MISMATCH)
            }
            if (enforceEndpointParity && previewColorSpace != recordingColorSpace) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_COLOR_SPACE_MISMATCH)
            }
            if (enforceEndpointParity && previewDenoise != recordingDenoise) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_DENOISE_PROFILE_MISMATCH)
            }
            if (enforceEndpointParity && previewSharpness != recordingSharpness) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_SHARPNESS_PROFILE_MISMATCH)
            }
            if (enforceEndpointParity && previewColorScience != recordingColorScience) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_COLOR_SCIENCE_MISMATCH)
            }
            if (
                enforceEndpointParity &&
                previewColorUniforms != recordingColorUniforms &&
                !explicitColorUniformAllowance
            ) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_COLOR_UNIFORM_MISMATCH)
            }
            if (enforceEndpointParity && previewEnhancementIdentity != recordingEnhancementIdentity) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_ENHANCEMENT_IDENTITY_MISMATCH)
            }
            if (enforceEndpointParity && previewGradeIdentity != recordingGradeIdentity) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_GRADE_IDENTITY_MISMATCH)
            }
            if (enforceEndpointParity && previewOutputFormat != recordingOutputFormat) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_OUTPUT_FORMAT_MISMATCH)
            }
            if (enforceEndpointParity && previewOutputTransfer != recordingOutputTransfer) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_OUTPUT_TRANSFER_MISMATCH)
            }
            if (enforceEndpointParity && previewOutputGamut != recordingOutputGamut) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_OUTPUT_GAMUT_MISMATCH)
            }
            if (enforceEndpointParity && previewOutputBitDepth != recordingOutputBitDepth) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_OUTPUT_BIT_DEPTH_MISMATCH)
            }
            if (enforceEndpointParity && previewOutputQuantization != recordingOutputQuantization) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_OUTPUT_QUANTIZATION_MISMATCH)
            }
            if (enforceEndpointParity && geometryMismatch) {
                add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_GEOMETRY_MISMATCH)
            }
            if ((deliveryKind == VideoDeliveryKind.SDR || deliveryKind == VideoDeliveryKind.LOG) &&
                sharedDecision.hdrTransportRequired
            ) {
                add(PreviewRecordingParityFailureCode.SDR_OR_LOG_HDR_TRANSPORT_ENABLED)
            }
            if (deliveryKind == VideoDeliveryKind.HDR && !sharedDecision.hdrTransportRequired) {
                add(PreviewRecordingParityFailureCode.HDR_TRANSPORT_DISABLED)
            }
            if (deliveryKind == VideoDeliveryKind.RAW && !sharedDecision.rawSensorOriginal) {
                add(PreviewRecordingParityFailureCode.RAW_TRANSPORT_NOT_RAW_SENSOR)
            }
            if (camera2DirectFallback) {
                add(PreviewRecordingParityFailureCode.CAMERA2_DIRECT_FALLBACK_FOR_PROCESSED_MODE)
            }
            if (renderGraphLock.pixelRealityBypassDetected) {
                add(PreviewRecordingParityFailureCode.PIXEL_REALITY_BYPASS_DETECTED)
            }
            if (averagePerformanceExceeded) {
                add(PreviewRecordingParityFailureCode.AVERAGE_PERFORMANCE_BUDGET_EXCEEDED)
            }
            addAll(lineageEvidence.failureCodes)
        }
        val runtimeEvidenceOnlyFailures =
            buildSet {
                addAll(evidenceOnlyParityFailures)
                if (actualFeedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER) {
                    add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_OUTPUT_FORMAT_MISMATCH)
                }
                if (recordDevelopFlatMaster) {
                    add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_SIGNATURE_MISMATCH)
                    add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_QUALITY_SIGNATURE_MISMATCH)
                    add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_COLOR_UNIFORM_MISMATCH)
                    add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_GRADE_IDENTITY_MISMATCH)
                    add(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_OUTPUT_FORMAT_MISMATCH)
                }
            }
        val recordingBlocked = failures.any { it !in runtimeEvidenceOnlyFailures }
        val status = when {
            recordDevelopFlatMaster && !recordingBlocked ->
                PreviewRecordingParityStatus.DEFERRED_DEVELOP
            failures.isNotEmpty() -> PreviewRecordingParityStatus.FAIL
            camera2PreviewParityPathDetected && deliveryKind != VideoDeliveryKind.RAW ->
                PreviewRecordingParityStatus.ISP_FALLBACK
            else -> PreviewRecordingParityStatus.PASS
        }
        val strictVulkanParityCertified =
            status == PreviewRecordingParityStatus.PASS &&
                enforceEndpointParity &&
                endpointEvidenceComplete &&
                !endpointMismatch &&
                lineageEvidence.strictCertified
        val fallbackReason = when {
            camera2DirectFallback -> "${actualFeedPath.name}_ISP_FALLBACK_FOR_${deliveryKind.name}"
            camera2PreviewParityPathDetected -> "PREVIEW_CONTINUITY_ENCODER_FOR_STABLE_FPS"
            directCamera2PathDetected -> "RAW_SENSOR_REQUIRES_CAMERA2_DIRECT_RECORD_SURFACE"
            else -> "NONE"
        }
        val failureCodes = failures.map { it.name }
        val certificateSignature = listOf(
            "PARITY_CERT",
            status.name,
            "preview=${sharedDecision.previewSignature}",
            "recording=${sharedDecision.recordingSignature}",
            "previewQuality=${sharedDecision.previewQualitySignature}",
            "recordingQuality=${sharedDecision.recordingQualitySignature}",
            "planned=${plannedFeedPath.name}",
            "actual=${actualFeedPath.name}",
            "direct=$directCamera2PathDetected",
            "fallback=$camera2DirectFallback",
            "reason=$fallbackReason",
            "shader=${sharedDecision.renderGraphShaderSha256}",
            "lut=${sharedDecision.renderGraphLutSha256}",
            "previewColorUniform=$previewColorUniforms",
            "recordingColorUniform=$recordingColorUniforms",
            "colorUniform=$previewColorUniforms",
            "previewEnhancement=$previewEnhancementIdentity",
            "recordingEnhancement=$recordingEnhancementIdentity",
            "previewGrade=$previewGradeIdentity",
            "recordingGrade=$recordingGradeIdentity",
            "previewOutput=$previewOutputFormat:$previewOutputTransfer:$previewOutputGamut:" +
                "$previewOutputBitDepth:$previewOutputQuantization",
            "recordingOutput=$recordingOutputFormat:$recordingOutputTransfer:$recordingOutputGamut:" +
                "$recordingOutputBitDepth:$recordingOutputQuantization",
            "monitorDeliveryColorUniformDifferenceAllowed=" +
                explicitColorUniformAllowance,
            sharedDecision.renderGraphStagesSignature,
            sharedDecision.geometryMatrixSignature,
            "timeline=${sharedDecision.timelineSemaphoreRequired}",
            "viewportScale=${sharedDecision.viewportResolutionScale}",
            "averagePerformanceEvidence=" +
                (if (averagePerformanceMeasurementAvailable) "MEASURED" else "UNKNOWN"),
            "measuredAverageFrameProcessingMs=${measuredAverageFrameProcessingMs ?: "unknown"}",
            "averageRuntimeBudgetMs=$averageRuntimeBudgetMs",
            "averagePerformanceBudgetSatisfied=$averagePerformanceBudgetSatisfied",
            "peakPerformanceEvidence=" +
                (if (peakPerformanceMeasurementAvailable) "MEASURED" else "UNKNOWN"),
            "measuredPeakFrameProcessingMs=${measuredPeakFrameProcessingMs ?: "unknown"}",
            "peakPerformanceBudgetCertified=$peakPerformanceBudgetCertified",
            lineageEvidence.signature,
            "recordDevelopFlatMaster=$recordDevelopFlatMaster",
            "strictVulkanParityCertified=$strictVulkanParityCertified",
            "blocked=$recordingBlocked",
            "failures=${failureCodes.joinToString(separator = ",")}"
        ).joinToString(separator = "|")
        return PreviewRecordingParityCertificate(
            status = status,
            previewSignature = sharedDecision.previewSignature,
            recordingSignature = sharedDecision.recordingSignature,
            previewQualitySignature = sharedDecision.previewQualitySignature,
            recordingQualitySignature = sharedDecision.recordingQualitySignature,
            plannedFeedPath = plannedFeedPath,
            actualFeedPath = actualFeedPath,
            directCamera2PathDetected = directCamera2PathDetected,
            camera2DirectFallback = camera2DirectFallback,
            fallbackReason = fallbackReason,
            failureCodes = failureCodes,
            renderGraphShaderSha256 = sharedDecision.renderGraphShaderSha256,
            renderGraphLutSha256 = sharedDecision.renderGraphLutSha256,
            renderGraphStagesSignature = sharedDecision.renderGraphStagesSignature,
            geometryMatrixSignature = sharedDecision.geometryMatrixSignature,
            timelineSemaphoreRequired = sharedDecision.timelineSemaphoreRequired,
            viewportResolutionScale = sharedDecision.viewportResolutionScale,
            averagePerformanceMeasurementAvailable = averagePerformanceMeasurementAvailable,
            measuredAverageFrameProcessingMs = measuredAverageFrameProcessingMs,
            averageRuntimeBudgetMs = averageRuntimeBudgetMs,
            averagePerformanceBudgetSatisfied = averagePerformanceBudgetSatisfied,
            peakPerformanceMeasurementAvailable = peakPerformanceMeasurementAvailable,
            measuredPeakFrameProcessingMs = measuredPeakFrameProcessingMs,
            peakPerformanceBudgetCertified = peakPerformanceBudgetCertified,
            strictVulkanParityCertified = strictVulkanParityCertified,
            recordingBlocked = recordingBlocked,
            certificateSignature = certificateSignature
        )
    }

    private fun Map<String, String>.recordDevelopFlatMasterActive(
        recordingEnhancementIdentity: String
    ): Boolean =
        truthy(RECORD_DEVELOP_LIGHT_MASTER) &&
            truthy(RECORD_DEVELOP_OFFLINE_REQUIRED) &&
            clean(RECORD_DEVELOP_LIVE_COLOR_PROCESSING)
                .equals(RECORD_DEVELOP_LIVE_FLAT_MASTER, ignoreCase = true) &&
            clean(RECORD_DEVELOP_OFFLINE_OUTPUT_FORMAT)
                .equals(RECORD_DEVELOP_OFFLINE_R210, ignoreCase = true) &&
            recordingEnhancementIdentity.contains("recordDevelopLightMaster=true", ignoreCase = true)

    private fun camera2FrameLineageRuntimeEvidence(
        metadata: Map<String, String>,
        required: Boolean
    ): Camera2FrameLineageRuntimeEvidence {
        val lineageMetadata = metadata.filterKeys {
            it.startsWith(CAMERA2_FRAME_LINEAGE_PREFIX)
        }
        val metadataPresent = lineageMetadata.isNotEmpty()
        val certificatePresent = metadata[CAMERA2_FRAME_LINEAGE_CERTIFICATE_PRESENT]
            ?.trim()
            ?.equals("true", ignoreCase = true) == true
        val evidenceWindowFrames = metadata.positiveLong(CAMERA2_FRAME_LINEAGE_EVIDENCE_WINDOW_FRAMES)
        val evidenceWindowDurationMs =
            metadata.positiveLong(CAMERA2_FRAME_LINEAGE_EVIDENCE_WINDOW_DURATION_MS)
        val timestampMatchRatio = metadata.ratio(CAMERA2_FRAME_LINEAGE_TIMESTAMP_MATCH_RATIO)
        val staleGenerationCount = metadata.nonNegativeLong(CAMERA2_FRAME_LINEAGE_STALE_GENERATION_COUNT)
        val nonMonotonicTimestampCount =
            metadata.nonNegativeLong(CAMERA2_FRAME_LINEAGE_NON_MONOTONIC_TIMESTAMP_COUNT)
        val duplicateEncoderSubmissionCount =
            metadata.nonNegativeLong(CAMERA2_FRAME_LINEAGE_DUPLICATE_ENCODER_SUBMISSION_COUNT)
        val previewEndpointOmissionCount =
            metadata.nonNegativeLong(CAMERA2_FRAME_LINEAGE_PREVIEW_ENDPOINT_OMISSION_COUNT)
        val encoderEndpointOmissionCount =
            metadata.nonNegativeLong(CAMERA2_FRAME_LINEAGE_ENCODER_ENDPOINT_OMISSION_COUNT)
        val submittedFrameDifference =
            metadata.longOrNull(CAMERA2_FRAME_LINEAGE_SUBMITTED_FRAME_DIFFERENCE)?.let { abs(it) }

        val failures = buildList {
            if (!required) return@buildList
            if (!certificatePresent) {
                add(PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_CERTIFICATE_MISSING)
                return@buildList
            }
            if (
                evidenceWindowFrames == null ||
                evidenceWindowFrames < CAMERA2_FRAME_LINEAGE_MIN_WINDOW_FRAMES ||
                evidenceWindowDurationMs == null ||
                evidenceWindowDurationMs < CAMERA2_FRAME_LINEAGE_MIN_WINDOW_DURATION_MS
            ) {
                add(PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_WINDOW_INSUFFICIENT)
            }
            if (
                timestampMatchRatio == null ||
                timestampMatchRatio < CAMERA2_FRAME_LINEAGE_MIN_TIMESTAMP_MATCH_RATIO
            ) {
                add(PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_TIMESTAMP_MATCH_RATIO_LOW)
            }
            if (staleGenerationCount > 0L) {
                add(PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_STALE_GENERATION)
            }
            if (nonMonotonicTimestampCount > 0L) {
                add(PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_NON_MONOTONIC_TIMESTAMPS)
            }
            if (duplicateEncoderSubmissionCount > 0L) {
                add(PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_DUPLICATE_ENCODER_SUBMISSION)
            }
            if (previewEndpointOmissionCount > 0L || encoderEndpointOmissionCount > 0L) {
                add(PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_ENDPOINT_OMISSION)
            }
            if (
                submittedFrameDifference == null ||
                submittedFrameDifference > CAMERA2_FRAME_LINEAGE_MAX_SUBMITTED_FRAME_DIFFERENCE
            ) {
                add(
                    PreviewRecordingParityFailureCode
                        .CAMERA2_FRAME_LINEAGE_PREVIEW_ENCODER_FRAME_DELTA_EXCEEDED
                )
            }
        }
        return Camera2FrameLineageRuntimeEvidence(
            required = required,
            metadataPresent = metadataPresent,
            certificatePresent = certificatePresent,
            evidenceWindowFrames = evidenceWindowFrames,
            evidenceWindowDurationMs = evidenceWindowDurationMs,
            timestampMatchRatio = timestampMatchRatio,
            staleGenerationCount = staleGenerationCount,
            nonMonotonicTimestampCount = nonMonotonicTimestampCount,
            duplicateEncoderSubmissionCount = duplicateEncoderSubmissionCount,
            previewEndpointOmissionCount = previewEndpointOmissionCount,
            encoderEndpointOmissionCount = encoderEndpointOmissionCount,
            submittedFrameDifference = submittedFrameDifference,
            failureCodes = failures
        )
    }

    private fun Map<String, String>.positiveLong(key: String): Long? =
        nonNegativeLongOrNull(key)?.takeIf { it > 0L }

    private fun Map<String, String>.truthy(key: String): Boolean =
        when (clean(key).lowercase()) {
            "true", "1", "yes", "enabled" -> true
            else -> false
        }

    private fun Map<String, String>.clean(key: String): String =
        this[key]?.trim().orEmpty()

    private fun Map<String, String>.nonNegativeLong(key: String): Long =
        nonNegativeLongOrNull(key) ?: 0L

    private fun Map<String, String>.nonNegativeLongOrNull(key: String): Long? =
        longOrNull(key)?.takeIf { it >= 0L }

    private fun Map<String, String>.longOrNull(key: String): Long? =
        this[key]
            ?.trim()
            ?.toLongOrNull()

    private fun Map<String, String>.ratio(key: String): Double? =
        this[key]
            ?.trim()
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 0.0..1.0 }

    private fun defaultColorScience(
        deliveryKind: VideoDeliveryKind,
        encoderHdrMode: String,
        encoderColorSpace: String
    ): String =
        when (deliveryKind) {
            VideoDeliveryKind.SDR -> if (encoderColorSpace == "Rec.709") {
                "REC709_NEUTRAL"
            } else {
                "WIDE_GAMUT_SDR"
            }
            VideoDeliveryKind.HDR -> "BT2100_$encoderHdrMode"
            VideoDeliveryKind.LOG -> "LOG_ORIGINAL_SDR_WIDE_GAMUT"
            VideoDeliveryKind.RAW -> "RAW_SENSOR_ORIGINAL"
        }

    private fun resolveSelectedColorScience(
        selectedColorScience: String,
        deliveryKind: VideoDeliveryKind,
        encoderHdrMode: String,
        encoderColorSpace: String
    ): String {
        val requested = selectedColorScience.trim().ifEmpty {
            defaultColorScience(deliveryKind, encoderHdrMode, encoderColorSpace)
        }
        val canonical = ManualVideoColorSelectionPolicy.normalizeColorScience(requested)
        return canonical.takeUnless { it == ManualVideoColorSelectionPolicy.UNSELECTED } ?: defaultColorScience(
            deliveryKind = deliveryKind,
            encoderHdrMode = encoderHdrMode,
            encoderColorSpace = encoderColorSpace
        )
    }

    private fun paritySignature(
        graphContract: VideoPipelineContract,
        deliveryKind: VideoDeliveryKind,
        feedPath: RecordingFeedPath,
        hdrRequested: Boolean,
        rawRequested: Boolean,
        logEnabled: Boolean,
        tenBitRequested: Boolean,
        encoderHdrMode: String,
        encoderColorSpace: String,
        selectedColorScience: String
    ): String = listOf(
        graphContract.graphSignature,
        deliveryKind.name,
        feedPath.name,
        hdrRequested.toString(),
        rawRequested.toString(),
        logEnabled.toString(),
        tenBitRequested.toString(),
        encoderHdrMode,
        encoderColorSpace,
        selectedColorScience
    ).joinToString(separator = "|")

    private fun parityQualitySignature(
        deliveryKind: VideoDeliveryKind,
        feedPath: RecordingFeedPath,
        encoderColorSpace: String,
        selectedColorScience: String,
        denoiseProfile: VideoDenoiseProfile,
        sharpnessProfile: VideoSharpnessProfile
    ): String = listOf(
        deliveryKind.name,
        feedPath.name,
        encoderColorSpace,
        selectedColorScience,
        denoiseProfile.name,
        sharpnessProfile.name,
        "USER_MANUAL_COLOR"
    ).joinToString(separator = "|")

    private fun endpointQualitySignature(
        modeSignature: String,
        colorSpace: String,
        colorScience: String,
        colorUniformsSha256: String,
        enhancementIdentity: String,
        gradeIdentity: String,
        denoiseProfile: String,
        sharpnessProfile: String,
        shaderSha256: String,
        lutSha256: String,
        stages: List<String>,
        outputFormat: String,
        outputTransfer: String,
        outputGamut: String,
        outputBitDepth: Int,
        outputQuantizationLevels: Int,
        geometrySignature: String,
        stabilizationMode: String,
        timelineSemaphoreRequired: Boolean,
        viewportResolutionScale: Float,
        metadataSignature: String,
        feedPath: RecordingFeedPath
    ): String = listOf(
        modeSignature,
        "colorSpace=$colorSpace",
        "colorScience=$colorScience",
        "colorUniforms=$colorUniformsSha256",
        "enhancement=$enhancementIdentity",
        "grade=$gradeIdentity",
        "denoise=$denoiseProfile",
        "sharpness=$sharpnessProfile",
        "shader=$shaderSha256",
        "lut=$lutSha256",
        "stages=${stages.joinToString(separator = ">")}",
        "outputFormat=$outputFormat",
        "outputTransfer=$outputTransfer",
        "outputGamut=$outputGamut",
        "outputBitDepth=$outputBitDepth",
        "outputQuantization=$outputQuantizationLevels",
        geometrySignature,
        "stabilization=$stabilizationMode",
        "timeline=$timelineSemaphoreRequired",
        "viewportScale=$viewportResolutionScale",
        "feedPath=${feedPath.name}",
        metadataSignature
    ).joinToString(separator = "|")

    private fun quantizationLevelsForBitDepth(bitDepth: Int): Int = when {
        bitDepth <= 0 -> 0
        bitDepth >= Int.SIZE_BITS - 1 -> Int.MAX_VALUE
        else -> (1 shl bitDepth) - 1
    }

    private fun canonicalOutputFormat(format: String): String =
        when (format.trim().uppercase(java.util.Locale.US)) {
            "EGL_RGB10_A2",
            "EGL_RGBA1010102",
            "RGB10_A2",
            "RGBA1010102",
            "A2B10G10R10_UNORM_PACK32" -> "A2B10G10R10_UNORM_PACK32"
            else -> format
        }

    private fun geometryMatrixSignature(matrix: FloatArray): String =
        "geom=" + matrix.joinToString(separator = ",") { value ->
            "%.5f".format(java.util.Locale.US, value)
        }

    private fun metadataSignature(
        graphContract: VideoPipelineContract,
        deliveryKind: VideoDeliveryKind,
        rawRequested: Boolean,
        logEnabled: Boolean,
        tenBitRequested: Boolean,
        encoderHdrMode: String,
        encoderColorSpace: String,
        selectedColorScience: String
    ): String {
        val modeContract = graphContract.deliveryState.pipelineContract
        return listOf(
            "metadata",
            modeContract.captureTruthSignature,
            "delivery=${deliveryKind.name}",
            "hdr=$encoderHdrMode",
            "raw=$rawRequested",
            "log=$logEnabled",
            "tenBit=$tenBitRequested",
            "colorSpace=$encoderColorSpace",
            "colorScience=$selectedColorScience",
            "standard=${graphContract.colorStandard}",
            "transfer=${graphContract.colorTransfer}",
            "range=${graphContract.colorRange}",
            "hdrMetadata=${modeContract.requiresHdrContainerMetadata}",
            "truthfulColor=${modeContract.truthfulColorDelivery}",
            "previewStages=${modeContract.previewPipelineStages.joinToString(separator = ">") { it.name }}",
            "recordingStages=${modeContract.recordingPipelineStages.joinToString(separator = ">") { it.name }}"
        ).joinToString(separator = "|")
    }
}
