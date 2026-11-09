package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.ai.DepthData
import com.neoproc.neoproccamx.ai.DetectedFace
import com.neoproc.neoproccamx.ai.PrimarySubject
import com.neoproc.neoproccamx.ai.SubjectMask
import com.neoproc.neoproccamx.pipeline.MemoryProfiler
import com.neoproc.neoproccamx.pipeline.RawYuvImageSinkTelemetry
import com.neoproc.neoproccamx.pipeline.RealtimeFrameDispatchTelemetry
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.ManualVideoColorSelectionPlan
import com.neoproc.neoproccamx.recording.RecordingStopOriginEvidence
import com.neoproc.neoproccamx.rendering.CinemaDebayerStats
import com.neoproc.neoproccamx.rendering.GpuImageEnhancementUniforms
import com.neoproc.neoproccamx.rendering.VulkanEncoderSurfaceStatus
import com.neoproc.neoproccamx.rendering.VulkanImportStats

/**
 * Formats a recording-evidence snapshot without owning a camera, writer, codec, or GPU.
 *
 * The ViewModel snapshots live owners once and appends their final lineage evidence afterwards.
 * This is a structural extraction: metadata keys, selected quality, format, and validation rules
 * are preserved. In particular, missing GPU/precision evidence never becomes a positive claim.
 */
internal object RecordingRuntimeEvidenceFormatter {
    data class Input(
        val parityPlan: RecordingPipelineParityPlan,
        val processingReport: PixelProcessingRealityReport,
        val stats: VulkanImportStats,
        val validatedEvidence: ValidatedGpuRecordingEvidence,
        val enhancementUniforms: GpuImageEnhancementUniforms?,
        val p010SourceTelemetry: RawYuvImageSinkTelemetry,
        val liveEncoderSurfaceStatus: VulkanEncoderSurfaceStatus,
        val validatedEncoderSurfaceCurrent: Boolean,
        val activeRecordingFeedPath: RecordingFeedPath?,
        val capabilitySummary: String,
        val memoryStats: MemoryProfiler.MemoryStats,
        val thermalLabel: String,
        val manualColorPlan: ManualVideoColorSelectionPlan,
        val debayerStats: CinemaDebayerStats,
        val realtimeDispatch: RealtimeFrameDispatchTelemetry,
        val realtimeDispatchStrictNoConflation: Boolean,
        val averageComputeMs: Double?,
        val aiFaces: List<DetectedFace>,
        val aiDepth: DepthData?,
        val aiSubject: PrimarySubject?,
        val aiSubjectMask: SubjectMask?,
        val aiObserved: Boolean,
        val recordDevelopLightMaster: Boolean,
        val recordDevelopModeEnabled: Boolean,
        val stopOriginEvidence: RecordingStopOriginEvidence,
        val dualGainFusionEnabled: Boolean,
        val dualGainMotionMaskEnabled: Boolean
    )

    fun format(input: Input): MutableMap<String, String> = with(input) {
        val validatedGpuImport = validatedEvidence.importedFrames > 0L
        val recordingUsesCustomGpu =
            processingReport.recordingUsesCustomGpu || validatedGpuImport
        val customProcessingBypassed =
            processingReport.camera2DirectBypassesCustomProcessing && !validatedGpuImport
        val frameImportEnabled = stats.frameImportEnabled || validatedEvidence.frameImportEnabled
        val encoderSurfaceBound =
            stats.encoderSurfaceBound ||
                stats.offscreenEglEncoderSurfaceBound ||
                validatedEvidence.encoderSurfaceBound
        val preservedHardwareImportFrames = maxOf(stats.sessionImportedFrames, validatedEvidence.importedFrames)
        val sessionImportedFrames = maxOf(
            preservedHardwareImportFrames,
            stats.offscreenEglSubmittedFrames
        )
        val offscreenPath = parityPlan.feedPath == RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER ||
            stats.offscreenAhbEglEncoderActive ||
            stats.offscreenEglSubmittedFrames > 0L
        val gpuValidated =
            recordingUsesCustomGpu &&
                !customProcessingBypassed &&
                encoderSurfaceBound &&
                sessionImportedFrames > 0L
        val zeroCopyValidated = gpuValidated && frameImportEnabled
        val encoderSurfaceEvidenceApplicable =
            parityPlan.feedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER ||
                activeRecordingFeedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER ||
                liveEncoderSurfaceStatus.bound ||
                validatedEncoderSurfaceCurrent
        val encoderSurfaceEvidence = when {
            liveEncoderSurfaceStatus.bound -> liveEncoderSurfaceStatus
            validatedEncoderSurfaceCurrent -> VulkanEncoderSurfaceStatus(
                bound = validatedEvidence.encoderSurfaceBoundAtValidation,
                format = validatedEvidence.encoderSurfaceFormat,
                bitDepth = validatedEvidence.encoderSurfaceBitDepth,
                precision = com.neoproc.neoproccamx.rendering.VulkanEncoderSurfaceStatusParser
                    .parsePrecision(validatedEvidence.encoderSurfacePrecision),
                precisionFallback = validatedEvidence.encoderSurfacePrecisionFallback,
                colorSpace = validatedEvidence.encoderSurfaceColorSpace,
                deliveryMode = validatedEvidence.encoderSurfaceDeliveryMode,
                deliveryCompatible =
                    validatedEvidence.encoderSurfaceDeliveryCompatible &&
                        validatedEvidence.encoderSurfacePrecisionProven,
                sourceFormat = validatedEvidence.encoderSourceFormat,
                sourceBitDepth = validatedEvidence.encoderSourceBitDepth,
                sourcePrecision =
                    com.neoproc.neoproccamx.rendering.VulkanEncoderSurfaceStatusParser
                        .parsePrecision(validatedEvidence.encoderSourcePrecision),
                sourcePrecisionProven = validatedEvidence.encoderSourcePrecisionProven
            )
            else -> VulkanEncoderSurfaceStatus.UNBOUND
        }
        fun capabilityToken(key: String): String =
            Regex("""(?:^|\s)${Regex.escape(key)}=([^\s]+)""")
                .find(capabilitySummary)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
        linkedMapOf(
            "recordingStopSessionGeneration" to
                stopOriginEvidence.sessionGeneration.toString(),
            "recordingStopValidationLabel" to stopOriginEvidence.validationLabel,
            "recordingStopOrigin" to stopOriginEvidence.origin.name,
            "recordingStopDetail" to stopOriginEvidence.detail,
            "recordingStopRequestedElapsedRealtimeMs" to
                stopOriginEvidence.requestedElapsedRealtimeMs.toString(),
            "recordingUsesCustomGpu" to recordingUsesCustomGpu.toString(),
            "customProcessingBypassed" to customProcessingBypassed.toString(),
            "camera2DirectFallback" to parityPlan.parityCertificate.camera2DirectFallback.toString(),
            "parityFeedPath" to parityPlan.feedPath.name,
            "previewRecordingParityStatus" to parityPlan.parityCertificate.status.name,
            "previewRecordingParityFailureCodes" to
                parityPlan.parityCertificate.failureCodes.joinToString(separator = ","),
            "previewRecordingParityBlocked" to parityPlan.parityCertificate.recordingBlocked.toString(),
            "strictVulkanParityCertified" to parityPlan.parityCertificate.strictVulkanParityCertified.toString(),
            "previewQualitySignature" to parityPlan.sharedDecision.previewQualitySignature,
            "recordingQualitySignature" to parityPlan.sharedDecision.recordingQualitySignature,
            "renderGraphShaderSha256" to parityPlan.parityCertificate.renderGraphShaderSha256,
            "renderGraphLutSha256" to parityPlan.parityCertificate.renderGraphLutSha256,
            "renderGraphStagesSignature" to parityPlan.parityCertificate.renderGraphStagesSignature,
            "selectedColorSpace" to manualColorPlan.selectedColorSpace,
            "selectedColorScience" to manualColorPlan.selectedColorScience,
            "colorSelectionSource" to manualColorPlan.colorSelectionSource,
            "manualColorSelectionReady" to manualColorPlan.readyForRecording.toString(),
            "p010SourceImageCallbacks" to p010SourceTelemetry.sourceImageCallbacks.toString(),
            "p010SourceOwnershipTransferred" to
                p010SourceTelemetry.ownershipTransferred.toString(),
            "p010SourceSinkDeclined" to p010SourceTelemetry.sinkDeclined.toString(),
            "p010SourceUnsupportedImages" to p010SourceTelemetry.unsupportedImages.toString(),
            "p010SourceAcquisitionExceptions" to
                p010SourceTelemetry.acquisitionExceptions.toString(),
            "p010SourceDrainCapHits" to p010SourceTelemetry.drainCapHits.toString(),
            "p010SourceFirstAcquisitionException" to
                p010SourceTelemetry.firstAcquisitionException,
            "p010SourceCadenceTargetFps" to p010SourceTelemetry.cadenceTargetFps.toString(),
            "p010SourceAcquiredImages" to p010SourceTelemetry.acquiredImages.toString(),
            "p010SourceInvalidTimestamps" to
                p010SourceTelemetry.invalidTimestamps.toString(),
            "p010SourceNonMonotonicTimestamps" to
                p010SourceTelemetry.nonMonotonicTimestamps.toString(),
            "p010SourceMissingCadenceFrames" to
                p010SourceTelemetry.missingCadenceFrames.toString(),
            "p010SourceMaximumTimestampGapNs" to
                p010SourceTelemetry.maximumTimestampGapNs.toString(),
            "p010SourceFirstTimestampNs" to p010SourceTelemetry.firstTimestampNs.toString(),
            "p010SourceLastTimestampNs" to p010SourceTelemetry.lastTimestampNs.toString(),
            "p010SourceFirstGapPreviousTimestampNs" to
                p010SourceTelemetry.firstGapPreviousTimestampNs.toString(),
            "p010SourceFirstGapTimestampNs" to
                p010SourceTelemetry.firstGapTimestampNs.toString(),
            "p010SourceFirstGapMissingFrames" to
                p010SourceTelemetry.firstGapMissingFrames.toString(),
            "p010SourceTerminalBoundaryTimestampNs" to
                p010SourceTelemetry.terminalBoundaryTimestampNs.toString(),
            "p010SourceTerminalExcludedMissingFrames" to
                p010SourceTelemetry.terminalExcludedMissingFrames.toString(),
            "p010SourceTimestampsAfterTerminal" to
                p010SourceTelemetry.timestampsAfterTerminal.toString(),
            "vulkanFrameImportEnabled" to frameImportEnabled.toString(),
            "vulkanPreviewSurfaceBound" to stats.previewSurfaceBound.toString(),
            "vulkanEncoderSurfaceBound" to encoderSurfaceBound.toString(),
            "vulkanSessionImportedFrames" to sessionImportedFrames.toString(),
            "vulkanSessionFailedImports" to stats.sessionFailedImports.toString(),
            "vulkanConsecutiveFailedImports" to stats.consecutiveFailedImports.toString(),
            "offscreenAhbEglEncoderActive" to stats.offscreenAhbEglEncoderActive.toString(),
            "offscreenEglEncoderSurfaceBound" to stats.offscreenEglEncoderSurfaceBound.toString(),
            "offscreenAhbImportedFrames" to stats.sessionImportedFrames.toString(),
            "offscreenEglSubmittedFrames" to stats.offscreenEglSubmittedFrames.toString(),
            "offscreenEglFailedFrames" to stats.offscreenEglFailedFrames.toString(),
            "offscreenEglSwapBuffers" to (stats.offscreenEglSubmittedFrames > 0L).toString(),
            "offscreenEglPresentationTimeSet" to stats.offscreenEglPresentationTimeSet.toString(),
            "realtimeDispatchAcceptedFrames" to realtimeDispatch.acceptedFrames.toString(),
            "realtimeDispatchDroppedFrames" to realtimeDispatch.droppedFrames.toString(),
            "realtimeDispatchRejectedFrames" to realtimeDispatch.rejectedFrames.toString(),
            "realtimeDispatchQueuedFrames" to realtimeDispatch.queuedFrames.toString(),
            "realtimeDispatchAverageQueueLatencyNs" to
                realtimeDispatch.averageQueueLatencyNs.toString(),
            "realtimeDispatchStrictNoConflation" to
                realtimeDispatchStrictNoConflation.toString(),
            "vulkanValidationPreserved" to (validatedEvidence.importedFrames > 0L).toString(),
            "vulkanValidationElapsedMs" to validatedEvidence.validatedAtElapsedMs.toString(),
            "vulkanSyncPolicy" to capabilityToken("syncPolicy"),
            "vulkanQueueSubmit" to capabilityToken("queueSubmit"),
            "vulkanAhbUsage" to "GPU_SAMPLED_IMAGE|VIDEO_ENCODE",
            "vulkanAverageComputeMs" to
                (averageComputeMs?.toString() ?: ""),
            "stage2CopyCountEvidenceProven" to "false",
            "stage2PoolSummary" to (
                "vulkanFrameImportEnabled=$frameImportEnabled;" +
                    "sessionImportedFrames=$sessionImportedFrames;" +
                    "offscreenSubmitted=${stats.offscreenEglSubmittedFrames}"
                ),
            "singularityThermalStatus" to thermalLabel,
            "singularityMemoryStatus" to
                "level=${memoryStats.warningLevel};usageMb=${memoryStats.totalUsageMb};utilization=${memoryStats.utilizationPercent};oomReserveMb=${memoryStats.oomReserveFreeMb}",
            "vulkanEncoderSurfaceEvidenceApplicable" to
                encoderSurfaceEvidenceApplicable.toString(),
            "vulkanEncoderSurfaceBoundAtValidation" to
                encoderSurfaceEvidence.bound.toString(),
            "vulkanEncoderSurfaceFormat" to encoderSurfaceEvidence.format,
            "vulkanEncoderSurfaceBitDepth" to encoderSurfaceEvidence.bitDepth.toString(),
            "vulkanEncoderSurfacePrecision" to encoderSurfaceEvidence.precision.name,
            "vulkanEncoderSurfacePrecisionFallback" to
                encoderSurfaceEvidence.precisionFallback.toString(),
            "vulkanEncoderSurfaceColorSpace" to encoderSurfaceEvidence.colorSpace,
            "vulkanEncoderSurfaceDeliveryMode" to encoderSurfaceEvidence.deliveryMode,
            "vulkanEncoderSurfaceDeliveryCompatible" to
                encoderSurfaceEvidence.deliveryCompatible.toString(),
            "vulkanEncoderSurfacePrecisionProven" to
                encoderSurfaceEvidence.precisionProven.toString(),
            "vulkanEncoderSourceFormat" to encoderSurfaceEvidence.sourceFormat,
            "vulkanEncoderSourceBitDepth" to encoderSurfaceEvidence.sourceBitDepth.toString(),
            "vulkanEncoderSourcePrecision" to encoderSurfaceEvidence.sourcePrecision.name,
            "vulkanEncoderSourcePrecisionProven" to
                encoderSurfaceEvidence.sourcePrecisionProven.toString(),
            "vulkanEncodedProcessedFrames" to if (gpuValidated) {
                sessionImportedFrames.toString()
            } else {
                "0"
            },
            "gpuProcessingValidated" to gpuValidated.toString(),
            "zeroCopyValidated" to zeroCopyValidated.toString(),
            "gpuProcessingPath" to if (recordingUsesCustomGpu && offscreenPath) {
                "VULKAN_OFFSCREEN_AHB_EGL_ENCODER"
            } else if (recordingUsesCustomGpu) {
                "VULKAN_INTERNAL_HARDWARE_IMPORT_ENCODER"
            } else if (parityPlan.feedPath == RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE) {
                parityPlan.feedPath.name
            } else if (customProcessingBypassed) {
                "CAMERA2_ISP_ENCODED_FALLBACK"
            } else {
                parityPlan.feedPath.name
            },
            "zeroCopyEvidencePath" to if (zeroCopyValidated && offscreenPath) {
                "offscreenAhbEgl:submittedFrames=${stats.offscreenEglSubmittedFrames}:swapBuffers=${stats.offscreenEglSubmittedFrames > 0L}"
            } else if (zeroCopyValidated) {
                "vulkanImportStats:sessionImportedFrames=$sessionImportedFrames"
            } else {
                ""
            },
            "enhancementUniformSignature" to enhancementUniforms?.signature.orEmpty(),
            "previewEnhancementUniformSignature" to enhancementUniforms?.signature.orEmpty(),
            "recordingEnhancementUniformSignature" to enhancementUniforms?.signature.orEmpty(),
            "enhancementTemporalBlend" to (enhancementUniforms?.temporalBlend ?: 0f).toString(),
            "enhancementSpatialDenoise" to (enhancementUniforms?.spatialDenoise ?: 0f).toString(),
            "enhancementChromaDenoise" to (enhancementUniforms?.chromaDenoise ?: 0f).toString(),
            "enhancementSharpening" to (enhancementUniforms?.sharpening ?: 0f).toString(),
            "enhancementSubjectDetailGain" to (enhancementUniforms?.subjectDetailGain ?: 0f).toString(),
            "enhancementDetailProfile" to when (enhancementUniforms?.detailProfileId) {
                1f -> VideoDetailProfile.FOCUSED_DETAIL.name
                2f -> VideoDetailProfile.NATURAL_DETAIL.name
                3f -> VideoDetailProfile.EXTREME_DETAIL.name
                else -> "LEGACY"
            },
            "recordDevelopModeEnabled" to recordDevelopModeEnabled.toString(),
            "recordDevelopLightMaster" to recordDevelopLightMaster.toString(),
            "recordDevelopLiveColorProcessing" to if (recordDevelopLightMaster) {
                "FLAT_10BIT_MASTER_ONLY"
            } else {
                "LIVE_GPU_DEVELOP"
            },
            "recordDevelopOfflineDevelopRequired" to recordDevelopLightMaster.toString(),
            "recordDevelopOfflineOutputFormat" to if (recordDevelopLightMaster) {
                "R210_444"
            } else {
                ""
            },
            "recordDevelopOfflineEnhanceProfile" to if (recordDevelopLightMaster) {
                "ELITE_SNCE_TEMPORAL_DENOISE_DETAIL"
            } else {
                ""
            },
            "v20ZeroGrainClarityActive" to
                (enhancementUniforms?.v20ZeroGrainClarityActive ?: false).toString(),
            "v20ZeroGrainLock" to
                (enhancementUniforms?.v20ZeroGrainLock ?: 0f).toString(),
            "v20RgbGrainQuarantine" to
                (enhancementUniforms?.v20RgbGrainQuarantine ?: 0f).toString(),
            "v20MotionBlurRejection" to
                (enhancementUniforms?.v20MotionBlurRejection ?: 0f).toString(),
            "v20MicroDetailHonesty" to
                (enhancementUniforms?.v20MicroDetailHonesty ?: 0f).toString(),
            "v20ZeroGrainClaritySignature" to
                enhancementUniforms?.v20ZeroGrainClaritySignature.orEmpty(),
            "dualGainFusionEnabled" to
                dualGainFusionEnabled.toString(),
            "dualGainMotionMaskEnabled" to
                dualGainMotionMaskEnabled.toString(),
            "stage3RealProcessingStages" to
                processingReport.realStageNames.joinToString(separator = ","),
            "stage3CinemaDebayerPipelineActive" to debayerStats.pipelineActive.toString(),
            "stage3CinemaDebayeredFrames" to debayerStats.debayeredFrames.toString(),
            "stage3CinemaDebayerFailedFrames" to debayerStats.failedDebayers.toString(),
            "stage3CinemaDebayerAverageMs" to debayerStats.averageDebayerTimeMs.toString(),
            "stage3AiFrameResultObserved" to aiObserved.toString(),
            "stage3AiFaceCount" to aiFaces.size.toString(),
            "stage3AiEyeLandmarkCount" to
                aiFaces.sumOf { face ->
                    (if (face.leftEye != null) 1 else 0) + (if (face.rightEye != null) 1 else 0)
                }.toString(),
            "stage3AiPrimarySubjectPresent" to (aiSubject != null).toString(),
            "stage3AiPrimarySubjectConfidence" to (aiSubject?.confidence ?: 0f).toString(),
            "stage3AiPrimarySubjectStability" to (aiSubject?.stabilityScore ?: 0f).toString(),
            "stage3AiSubjectMaskPresent" to (aiSubjectMask != null).toString(),
            "stage3AiSubjectMaskConfidence" to (aiSubjectMask?.intensity ?: 0f).toString(),
            "stage3AiSegmentationPresent" to (aiSubjectMask != null).toString(),
            "stage3AiDepthMapPresent" to (aiDepth != null).toString(),
            "stage3AiDepthConfidenceAvailable" to (aiDepth?.confidenceMap != null).toString(),
            "stage3AiDepthTemporallyRefined" to (aiDepth?.temporallyRefined == true).toString(),
            "stage3AiSemanticTemporalStabilized" to
                (
                    aiDepth?.temporallyRefined == true ||
                        (aiSubject?.stabilityScore ?: 0f) > 0.5f
                    ).toString()
        )
    }
}
