package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.pipeline.MemoryProfiler
import com.neoproc.neoproccamx.pipeline.RawYuvImageSinkTelemetry
import com.neoproc.neoproccamx.pipeline.RealtimeFrameDispatchTelemetry
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.recording.ManualVideoColorSelectionPolicy
import com.neoproc.neoproccamx.recording.ManualVideoColorSelectionRequest
import com.neoproc.neoproccamx.recording.RecordingStopOrigin
import com.neoproc.neoproccamx.recording.RecordingStopOriginEvidence
import com.neoproc.neoproccamx.rendering.CinemaDebayerStats
import com.neoproc.neoproccamx.rendering.GpuActiveTransport
import com.neoproc.neoproccamx.rendering.GpuColorScience
import com.neoproc.neoproccamx.rendering.GpuColorSpace
import com.neoproc.neoproccamx.rendering.GpuImageEnhancementUniforms
import com.neoproc.neoproccamx.rendering.GpuSignalFamily
import com.neoproc.neoproccamx.rendering.VulkanEncoderSurfaceStatus
import com.neoproc.neoproccamx.rendering.VulkanEncoderSurfaceStatusParser
import com.neoproc.neoproccamx.rendering.VulkanImportStats
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure metadata tests: these never claim physical quality, FPS, or hardware validation. */
class RecordingRuntimeEvidenceFormatterTest {
    @Test fun allExistingKeysAndTheirOrderArePreserved() {
        val actual = RecordingRuntimeEvidenceFormatter.format(input())
        // Frozen from the pre-extraction builder, not read from the production source at runtime.
        val expected = listOf(
            "recordingStopSessionGeneration",
            "recordingStopValidationLabel",
            "recordingStopOrigin",
            "recordingStopDetail",
            "recordingStopRequestedElapsedRealtimeMs",
            "recordingUsesCustomGpu",
            "customProcessingBypassed",
            "camera2DirectFallback",
            "parityFeedPath",
            "previewRecordingParityStatus",
            "previewRecordingParityFailureCodes",
            "previewRecordingParityBlocked",
            "strictVulkanParityCertified",
            "previewQualitySignature",
            "recordingQualitySignature",
            "renderGraphShaderSha256",
            "renderGraphLutSha256",
            "renderGraphStagesSignature",
            "selectedColorSpace",
            "selectedColorScience",
            "colorSelectionSource",
            "manualColorSelectionReady",
            "p010SourceImageCallbacks",
            "p010SourceOwnershipTransferred",
            "p010SourceSinkDeclined",
            "p010SourceUnsupportedImages",
            "p010SourceAcquisitionExceptions",
            "p010SourceDrainCapHits",
            "p010SourceFirstAcquisitionException",
            "p010SourceCadenceTargetFps",
            "p010SourceAcquiredImages",
            "p010SourceInvalidTimestamps",
            "p010SourceNonMonotonicTimestamps",
            "p010SourceMissingCadenceFrames",
            "p010SourceMaximumTimestampGapNs",
            "p010SourceFirstTimestampNs",
            "p010SourceLastTimestampNs",
            "p010SourceFirstGapPreviousTimestampNs",
            "p010SourceFirstGapTimestampNs",
            "p010SourceFirstGapMissingFrames",
            "p010SourceTerminalBoundaryTimestampNs",
            "p010SourceTerminalExcludedMissingFrames",
            "p010SourceTimestampsAfterTerminal",
            "vulkanFrameImportEnabled",
            "vulkanPreviewSurfaceBound",
            "vulkanEncoderSurfaceBound",
            "vulkanSessionImportedFrames",
            "vulkanSessionFailedImports",
            "vulkanConsecutiveFailedImports",
            "offscreenAhbEglEncoderActive",
            "offscreenEglEncoderSurfaceBound",
            "offscreenAhbImportedFrames",
            "offscreenEglSubmittedFrames",
            "offscreenEglFailedFrames",
            "offscreenEglSwapBuffers",
            "offscreenEglPresentationTimeSet",
            "realtimeDispatchAcceptedFrames",
            "realtimeDispatchDroppedFrames",
            "realtimeDispatchRejectedFrames",
            "realtimeDispatchQueuedFrames",
            "realtimeDispatchAverageQueueLatencyNs",
            "realtimeDispatchStrictNoConflation",
            "vulkanValidationPreserved",
            "vulkanValidationElapsedMs",
            "vulkanSyncPolicy",
            "vulkanQueueSubmit",
            "vulkanAhbUsage",
            "vulkanAverageComputeMs",
            "stage2CopyCountEvidenceProven",
            "stage2PoolSummary",
            "singularityThermalStatus",
            "singularityMemoryStatus",
            "vulkanEncoderSurfaceEvidenceApplicable",
            "vulkanEncoderSurfaceBoundAtValidation",
            "vulkanEncoderSurfaceFormat",
            "vulkanEncoderSurfaceBitDepth",
            "vulkanEncoderSurfacePrecision",
            "vulkanEncoderSurfacePrecisionFallback",
            "vulkanEncoderSurfaceColorSpace",
            "vulkanEncoderSurfaceDeliveryMode",
            "vulkanEncoderSurfaceDeliveryCompatible",
            "vulkanEncoderSurfacePrecisionProven",
            "vulkanEncoderSourceFormat",
            "vulkanEncoderSourceBitDepth",
            "vulkanEncoderSourcePrecision",
            "vulkanEncoderSourcePrecisionProven",
            "vulkanEncodedProcessedFrames",
            "gpuProcessingValidated",
            "zeroCopyValidated",
            "gpuProcessingPath",
            "zeroCopyEvidencePath",
            "enhancementUniformSignature",
            "previewEnhancementUniformSignature",
            "recordingEnhancementUniformSignature",
            "enhancementTemporalBlend",
            "enhancementSpatialDenoise",
            "enhancementChromaDenoise",
            "enhancementSharpening",
            "enhancementSubjectDetailGain",
            "enhancementDetailProfile",
            "recordDevelopModeEnabled",
            "recordDevelopLightMaster",
            "recordDevelopLiveColorProcessing",
            "recordDevelopOfflineDevelopRequired",
            "recordDevelopOfflineOutputFormat",
            "recordDevelopOfflineEnhanceProfile",
            "v20ZeroGrainClarityActive",
            "v20ZeroGrainLock",
            "v20RgbGrainQuarantine",
            "v20MotionBlurRejection",
            "v20MicroDetailHonesty",
            "v20ZeroGrainClaritySignature",
            "dualGainFusionEnabled",
            "dualGainMotionMaskEnabled",
            "stage3RealProcessingStages",
            "stage3CinemaDebayerPipelineActive",
            "stage3CinemaDebayeredFrames",
            "stage3CinemaDebayerFailedFrames",
            "stage3CinemaDebayerAverageMs",
            "stage3AiFrameResultObserved",
            "stage3AiFaceCount",
            "stage3AiEyeLandmarkCount",
            "stage3AiPrimarySubjectPresent",
            "stage3AiPrimarySubjectConfidence",
            "stage3AiPrimarySubjectStability",
            "stage3AiSubjectMaskPresent",
            "stage3AiSubjectMaskConfidence",
            "stage3AiSegmentationPresent",
            "stage3AiDepthMapPresent",
            "stage3AiDepthConfidenceAvailable",
            "stage3AiDepthTemporallyRefined",
            "stage3AiSemanticTemporalStabilized"
        )
        assertEquals(132, expected.size)
        assertEquals(expected, actual.keys.toList())
    }

    @Test fun missingEvidenceNeverBecomesGpuOrTenBitProof() {
        val result = RecordingRuntimeEvidenceFormatter.format(input())
        assertEquals("false", result["gpuProcessingValidated"])
        assertEquals("false", result["zeroCopyValidated"])
        assertEquals("0", result["vulkanEncodedProcessedFrames"])
        assertEquals("0", result["vulkanEncoderSurfaceBitDepth"])
        assertEquals("false", result["vulkanEncoderSurfacePrecisionProven"])
        assertEquals("", result["vulkanAverageComputeMs"])
        assertEquals("false", result["stage2CopyCountEvidenceProven"])
    }

    @Test fun preservedImportCountsSurviveStoppedLiveCountersWithoutBeingReduced() {
        val result = RecordingRuntimeEvidenceFormatter.format(input().copy(
            validatedEvidence = ValidatedGpuRecordingEvidence.EMPTY.copy(
                importedFrames = 7500,
                frameImportEnabled = true,
                encoderSurfaceBound = true,
                validatedAtElapsedMs = 98765
            ),
            stats = VulkanImportStats(sessionImportedFrames = 12)
        ))
        assertEquals("7500", result["vulkanSessionImportedFrames"])
        assertEquals("7500", result["vulkanEncodedProcessedFrames"])
        assertEquals("true", result["gpuProcessingValidated"])
        assertEquals("true", result["zeroCopyValidated"])
        assertEquals("98765", result["vulkanValidationElapsedMs"])
    }

    @Test fun offscreenCompletedFramesAndPresentationTruthRemainIndependent() {
        val baseline = input()
        val result = RecordingRuntimeEvidenceFormatter.format(baseline.copy(
            processingReport = baseline.processingReport.copy(recordingUsesCustomGpu = true),
            stats = VulkanImportStats(
                sessionImportedFrames = 25,
                offscreenEglSubmittedFrames = 29,
                offscreenEglEncoderSurfaceBound = true,
                offscreenEglFailedFrames = 2,
                offscreenEglPresentationTimeSet = false
            )
        ))
        assertEquals("29", result["vulkanSessionImportedFrames"])
        assertEquals("2", result["offscreenEglFailedFrames"])
        assertEquals("false", result["offscreenEglPresentationTimeSet"])
        assertEquals("false", result["zeroCopyValidated"])
        assertEquals("VULKAN_OFFSCREEN_AHB_EGL_ENCODER", result["gpuProcessingPath"])
    }

    @Test fun staleEncoderSurfaceCannotSupplyPrecisionProof() {
        val stale = ValidatedGpuRecordingEvidence.EMPTY.copy(
            encoderSurfaceBoundAtValidation = true,
            encoderSurfaceFormat = "A2B10G10R10_UNORM_PACK32",
            encoderSurfaceBitDepth = 10,
            encoderSurfacePrecisionProven = true
        )
        val result = RecordingRuntimeEvidenceFormatter.format(input().copy(
            validatedEvidence = stale, validatedEncoderSurfaceCurrent = false
        ))
        assertEquals("0", result["vulkanEncoderSurfaceBitDepth"])
        assertEquals("false", result["vulkanEncoderSurfacePrecisionProven"])
    }

    @Test fun liveEightBitFallbackIsReportedInsteadOfReusingStaleTenBit() {
        val live = VulkanEncoderSurfaceStatus.UNBOUND.copy(
            bound = true, bitDepth = 8, format = "R8G8B8A8_UNORM",
            precision = VulkanEncoderSurfaceStatusParser.parsePrecision("EIGHT_BIT"),
            precisionFallback = true
        )
        val result = RecordingRuntimeEvidenceFormatter.format(input().copy(
            liveEncoderSurfaceStatus = live,
            validatedEncoderSurfaceCurrent = true,
            validatedEvidence = ValidatedGpuRecordingEvidence.EMPTY.copy(
                encoderSurfaceBitDepth = 10, encoderSurfacePrecisionProven = true
            )
        ))
        assertEquals("8", result["vulkanEncoderSurfaceBitDepth"])
        assertEquals("R8G8B8A8_UNORM", result["vulkanEncoderSurfaceFormat"])
        assertEquals("true", result["vulkanEncoderSurfacePrecisionFallback"])
        assertEquals("false", result["vulkanEncoderSurfacePrecisionProven"])
    }

    @Test fun allThreeDetailProfilesAndEnhancementAmountsArePreserved() {
        val names = listOf("FOCUSED_DETAIL", "NATURAL_DETAIL", "EXTREME_DETAIL")
        names.forEachIndexed { index, name ->
            val uniforms = uniforms((index + 1).toFloat())
            val snapshot = input().copy(enhancementUniforms = uniforms)
            val result = RecordingRuntimeEvidenceFormatter.format(snapshot)
            assertEquals(name, result["enhancementDetailProfile"])
            assertEquals(uniforms.signature, result["enhancementUniformSignature"])
            assertEquals(uniforms.signature, result["recordingEnhancementUniformSignature"])
            assertEquals(uniforms.signature, result["previewEnhancementUniformSignature"])
            assertEquals("0.37", result["enhancementSharpening"])
            assertEquals("0.21", result["enhancementSpatialDenoise"])
            assertEquals("0.18", result["enhancementTemporalBlend"])
            assertEquals("0.63", result["enhancementSubjectDetailGain"])
            assertEquals(uniforms, snapshot.enhancementUniforms)
        }
    }

    @Test fun cinemaOfflineOwnershipIsPreservedForBothStates() {
        for (enabled in listOf(false, true)) {
            val result = RecordingRuntimeEvidenceFormatter.format(input().copy(
                recordDevelopModeEnabled = enabled,
                recordDevelopLightMaster = enabled
            ))
            assertEquals(enabled.toString(), result["recordDevelopLightMaster"])
            assertEquals(enabled.toString(), result["recordDevelopOfflineDevelopRequired"])
            assertEquals(if (enabled) "FLAT_10BIT_MASTER_ONLY" else "LIVE_GPU_DEVELOP",
                result["recordDevelopLiveColorProcessing"])
            assertEquals(if (enabled) "R210_444" else "", result["recordDevelopOfflineOutputFormat"])
            assertEquals(if (enabled) "ELITE_SNCE_TEMPORAL_DENOISE_DETAIL" else "",
                result["recordDevelopOfflineEnhanceProfile"])
        }
    }

    @Test fun stopOriginAndDropEvidenceCannotBeHiddenByFormatting() {
        val base = input()
        val result = RecordingRuntimeEvidenceFormatter.format(base.copy(
            stopOriginEvidence = RecordingStopOriginEvidence(
                9, "real-candidate", RecordingStopOrigin.NPV10_WRITER_FAILURE, "bounded queue", 44444
            ),
            p010SourceTelemetry = base.p010SourceTelemetry.copy(
                missingCadenceFrames = 7, sinkDeclined = 3, acquisitionExceptions = 2,
                firstAcquisitionException = "reader exhausted", terminalExcludedMissingFrames = 1
            )
        ))
        assertEquals("NPV10_WRITER_FAILURE", result["recordingStopOrigin"])
        assertEquals("bounded queue", result["recordingStopDetail"])
        assertEquals("9", result["recordingStopSessionGeneration"])
        assertEquals("real-candidate", result["recordingStopValidationLabel"])
        assertEquals("44444", result["recordingStopRequestedElapsedRealtimeMs"])
        assertEquals("7", result["p010SourceMissingCadenceFrames"])
        assertEquals("3", result["p010SourceSinkDeclined"])
        assertEquals("2", result["p010SourceAcquisitionExceptions"])
        assertEquals("reader exhausted", result["p010SourceFirstAcquisitionException"])
        assertEquals("1", result["p010SourceTerminalExcludedMissingFrames"])
    }

    @Test fun capabilityParsingRequiresExactWhitespaceDelimitedKey() {
        val result = RecordingRuntimeEvidenceFormatter.format(input().copy(
            capabilitySummary = "notqueueSubmit=wrong queueSubmit=timeline syncPolicy=explicit other=bad",
            averageComputeMs = 12.25
        ))
        assertEquals("timeline", result["vulkanQueueSubmit"])
        assertEquals("explicit", result["vulkanSyncPolicy"])
        assertEquals("12.25", result["vulkanAverageComputeMs"])
        val missing = RecordingRuntimeEvidenceFormatter.format(input().copy(
            capabilitySummary = "prefixsyncPolicy=wrong prefixqueueSubmit=wrong"
        ))
        assertEquals("", missing["vulkanQueueSubmit"])
        assertEquals("", missing["vulkanSyncPolicy"])
    }

    @Test fun callerKeepsLiveSnapshotAndFinalLineageMergeWiring() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val body = source.substringAfter("private fun buildRecordingRuntimeEvidenceMetadata(")
            .substringBefore("private fun resolveRecordingSourcePlan(")
        assertTrue(body.contains("RecordingRuntimeEvidenceFormatter.format("))
        assertTrue(body.contains("val stats = vulkanImportStats.value"))
        assertTrue(body.contains("val validatedEvidence = lastValidatedGpuRecordingEvidence"))
        assertTrue(body.contains("stats = stats"))
        assertTrue(body.contains("validatedEvidence = validatedEvidence"))
        assertTrue(body.contains("liveEncoderSurfaceStatus = vulkanRuntime.currentEncoderSurfaceStatus"))
        assertTrue(body.contains("validatedEncoderSurfaceCurrent = hasCurrentRecordingEncoderSurfaceEvidence()"))
        assertTrue(body.contains("p010SourceTelemetry = pipeline.rawYuvImageSinkTelemetry()"))
        assertTrue(body.contains("stopOriginEvidence = recordingStopOriginTracker.snapshot()"))
        val factory = body.indexOf("RecordingRuntimeEvidenceFormatter.format(")
        val sensor = body.indexOf("putAll(SensorPipelineIntegration.runtimeEvidence(pipeline))")
        val lineage = body.indexOf("putAll(camera2FrameLineageRuntimeMetadata())")
        val delivery = body.indexOf("putAll(camera.recordFrameDeliverySnapshot().toCompactMetadataMap())")
        val capture = body.indexOf("putAll(camera.singularityCaptureStage2Metadata())")
        val processed = body.indexOf("putAll(camera.processedVideoQualityEvidence())")
        assertTrue(factory >= 0 && sensor > factory && lineage > sensor &&
            delivery > lineage && capture > delivery && processed > capture)
        assertTrue(body.contains("processingReport.camera2DirectBypassesCustomProcessing &&"))
        assertTrue(body.contains("validatedEvidence.importedFrames <= 0L"))
        val formatter = File("src/main/java/com/neoproc/neoproccamx/ui/RecordingRuntimeEvidenceFormatter.kt")
            .readText().substringAfter("fun format(")
        assertFalse(formatter.contains("vulkanRuntime."))
        assertFalse(formatter.contains("camera."))
        assertFalse(formatter.contains("pipeline."))
    }

    private fun input(): RecordingRuntimeEvidenceFormatter.Input {
        val graph = VideoPipelineGraphController.resolve(VideoPipelineGraphRequest(
            appMode = AppMode.VIDEO, cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false, logEnabled = false, requestedHdrMode = "OFF",
            preferredColorSpace = "Rec.709", tenBitRequested = true,
            previewWidth = 1280, previewHeight = 960, encoderWidth = 3264,
            encoderHeight = 2448, fps = 25, recordingState = GpuRecordingState.Idle
        ))
        val plan = RecordingPipelineParityPolicy.resolve(
            graphContract = graph, rawSupported = true, vulkanRuntimeSupported = true
        )
        val report = PixelProcessingRealityPolicy.resolve(PixelProcessingRealityRequest(
            parityPlan = plan, nativePreviewActive = false, vulkanRuntimeSupported = true,
            aiFrameProcessingActive = false, multiFrameVideoProcessingActive = false
        ))
        return RecordingRuntimeEvidenceFormatter.Input(
            parityPlan = plan, processingReport = report, stats = VulkanImportStats(),
            validatedEvidence = ValidatedGpuRecordingEvidence.EMPTY, enhancementUniforms = null,
            p010SourceTelemetry = RawYuvImageSinkTelemetry(
                0, 0, 0, 0, 0, 0, "", 25, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
            ),
            liveEncoderSurfaceStatus = VulkanEncoderSurfaceStatus.UNBOUND,
            validatedEncoderSurfaceCurrent = false, activeRecordingFeedPath = null,
            capabilitySummary = "", memoryStats = MemoryProfiler.MemoryStats(
                25, 50, 10, 50000, 600, 256, 10, 25, 8000, false,
                MemoryProfiler.MemoryStats.WarningLevel.NORMAL
            ),
            thermalLabel = "SEVERE", manualColorPlan = ManualVideoColorSelectionPolicy.resolve(
                ManualVideoColorSelectionRequest("Rec.709", "Natural", true, true)
            ),
            debayerStats = CinemaDebayerStats(),
            realtimeDispatch = RealtimeFrameDispatchTelemetry(0, 0, 0, 0),
            realtimeDispatchStrictNoConflation = true, averageComputeMs = null,
            aiFaces = emptyList(), aiDepth = null, aiSubject = null, aiSubjectMask = null,
            aiObserved = false, recordDevelopLightMaster = false, recordDevelopModeEnabled = false,
            stopOriginEvidence = RecordingStopOriginEvidence(
                1, "test", RecordingStopOrigin.NOT_REQUESTED, "", 0
            ),
            dualGainFusionEnabled = false, dualGainMotionMaskEnabled = false
        )
    }

    private fun uniforms(profileId: Float) = GpuImageEnhancementUniforms(
        signalFamily = GpuSignalFamily.SDR, colorSpace = GpuColorSpace.REC709,
        colorScience = GpuColorScience.NATURAL, activeTransport = GpuActiveTransport.REC709_SDR,
        temporalBlend = 0.18f, spatialDenoise = 0.21f, chromaDenoise = 0.13f,
        shadowBoost = 0f, sharpening = 0.37f, warmth = 0f, contrast = 1f,
        saturation = 1f, highlightCompression = 0f, logStrength = 0f,
        videoHdrFusionStrength = 0f, shadowChromaDeadband = 0f, aliasSafeMaxDetailDelta = 0f,
        fineTextureThreshold = 0f, mediumEdgeThreshold = 0f, strongEdgeThreshold = 0f,
        fineTextureDetailGain = 0f, mediumDetailGain = 0f, strongDetailGain = 0f,
        temporalLumaBlend = 0f, temporalChromaBlend = 0f, iso = 100f, noiseMetric = 0f,
        localChromaSpikeSmoothing = 0f, sharpeningFloor = 0f, sharpeningCeiling = 1f,
        skinDetailProtection = 0f, migrationApplied = false, dualGainFusionEnabled = false,
        dualGainMotionMaskEnabled = false, dualGainKGain = 0f, dualGainNoiseFloor = 0f,
        dualGainSaturationThreshold = 0f, dualGainTimestampSkew = 0f,
        sensorShotNoiseCoefficient = 0f, sensorReadNoiseCoefficient = 0f,
        detailProfileId = profileId, subjectMaskAvailable = 0f,
        subjectDetailGain = 0.63f, subjectMaskFeather = 0f, signature = "unchanged-profile-$profileId"
    )
}
