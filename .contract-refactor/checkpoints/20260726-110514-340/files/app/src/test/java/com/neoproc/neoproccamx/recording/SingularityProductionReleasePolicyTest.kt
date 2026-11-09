package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.SingularityElite100xAuthority
import com.neoproc.neoproccamx.quality.SingularityElite100xStatus
import com.neoproc.neoproccamx.quality.SingularityQualityGateId
import com.neoproc.neoproccamx.quality.SingularityQualityGateStatus
import com.neoproc.neoproccamx.quality.SingularityReleaseClass
import com.neoproc.neoproccamx.quality.SingularityStage6DeviceMatrixPolicy
import com.neoproc.neoproccamx.quality.SingularityStage5EngineId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SingularityProductionReleasePolicyTest {

    @Test
    fun productionReleaseBuildsComplete256StageLedgerAndMetadata() {
        val request = request()
        val runtime = runtimeMetadata()
        val stage2 = SingularityStage2RecordingEvidencePolicy.fromQualityRequest(request, runtime)
        val stage2Metadata = stage2.toMetadata()
        val stage3 = SingularityStage3RecordingEvidencePolicy.fromQualityRequest(request, runtime + stage2Metadata)
        val stage3Metadata = stage3.toMetadata()
        val stage5 = SingularityStage5RecordingEnginePolicy.fromQualityRequest(request, runtime + stage2Metadata + stage3Metadata)
        val stage5Metadata = SingularityStage5RecordingEnginePolicy.metadataFor(request, stage5)
        val stage4 = SingularityStage4RecordingQualityPolicy.fromQualityRequest(
            request,
            runtime + stage2Metadata + stage3Metadata + stage5Metadata + passingQGArtifacts()
        )

        val release = SingularityProductionReleasePolicy.evaluate(
            request = request,
            runtimeMetadata = runtime + stage2Metadata + stage3Metadata + stage5Metadata + passingQGArtifacts(),
            stage2Bundle = stage2,
            stage3Bundle = stage3,
            stage5Bundle = stage5,
            stage4Bundle = stage4
        )

        assertEquals(256, release.releaseInput.stageEvidence.size)
        assertTrue(release.releaseInput.stageEvidence.any { it.globalStageId == 255 })
        assertEquals("true", release.releaseMetadata.getValue("singularityProductionReleaseEvaluated"))
        assertEquals("256", release.releaseMetadata.getValue("singularityProductionStageLedgerSize"))
        assertEquals("false", release.releaseMetadata.getValue("singularityProductionElite100xQualified"))
        assertTrue(stage5.records.any { it.engineId == SingularityStage5EngineId.MULTI_LENS_FUSION })
    }

    @Test
    fun mandatoryNotTestedGateCapsReleaseAndIsCarriedIntoMetadata() {
        val request = request().copy(expectedFrameCount = 0L, actualFrameCount = 0L)
        val runtime = runtimeMetadata()
        val stage2 = SingularityStage2RecordingEvidencePolicy.fromQualityRequest(request, runtime)
        val stage2Metadata = stage2.toMetadata()
        val stage3 = SingularityStage3RecordingEvidencePolicy.fromQualityRequest(request, runtime + stage2Metadata)
        val stage3Metadata = stage3.toMetadata()
        val stage5 = SingularityStage5RecordingEnginePolicy.fromQualityRequest(request, runtime + stage2Metadata + stage3Metadata)
        val stage5Metadata = SingularityStage5RecordingEnginePolicy.metadataFor(request, stage5)
        val stage4 = SingularityStage4RecordingQualityPolicy.fromQualityRequest(
            request,
            runtime + stage2Metadata + stage3Metadata + stage5Metadata + passingQGArtifacts()
        )

        val qg15 = stage4.results.single { it.gateId == SingularityQualityGateId.QG15 }
        val release = SingularityProductionReleasePolicy.evaluate(
            request = request,
            runtimeMetadata = runtime + stage2Metadata + stage3Metadata + stage5Metadata + passingQGArtifacts(),
            stage2Bundle = stage2,
            stage3Bundle = stage3,
            stage5Bundle = stage5,
            stage4Bundle = stage4
        )

        assertEquals(SingularityQualityGateStatus.NOT_TESTED, qg15.status)
        assertTrue(release.releaseDecision.releaseClass.rank <= SingularityReleaseClass.SINGULARITY_75.rank)
        assertTrue(release.releaseMetadata.getValue("singularityMandatoryGateSummary").contains("NOT_TESTED"))
    }

    @Test
    fun legacyReviewAndEnduranceBooleansDoNotUnlockStage6Release() {
        val request = request()
        val runtime = runtimeMetadata()
        val stage2 = SingularityStage2RecordingEvidencePolicy.fromQualityRequest(request, runtime)
        val stage2Metadata = stage2.toMetadata()
        val stage3 = SingularityStage3RecordingEvidencePolicy.fromQualityRequest(request, runtime + stage2Metadata)
        val stage3Metadata = stage3.toMetadata()
        val stage5 = SingularityStage5RecordingEnginePolicy.fromQualityRequest(request, runtime + stage2Metadata + stage3Metadata)
        val stage5Metadata = SingularityStage5RecordingEnginePolicy.metadataFor(request, stage5)
        val stage4 = SingularityStage4RecordingQualityPolicy.fromQualityRequest(
            request,
            runtime + stage2Metadata + stage3Metadata + stage5Metadata + passingQGArtifacts()
        )

        val release = SingularityProductionReleasePolicy.evaluate(
            request = request,
            runtimeMetadata = runtime + stage2Metadata + stage3Metadata + stage5Metadata + passingQGArtifacts(),
            stage2Bundle = stage2,
            stage3Bundle = stage3,
            stage5Bundle = stage5,
            stage4Bundle = stage4
        )

        assertEquals(SingularityReleaseClass.CORE_HONEST, release.releaseInput.requestedReleaseClass)
        assertFalse(release.releaseInput.outputEvidence.largeScreenReviewPassed)
        assertFalse(release.releaseInput.outputEvidence.enduranceValidationPassed)
        assertEquals("NOT_TESTED", release.releaseMetadata.getValue("singularityStage6OverallStatus"))
    }

    @Test
    fun stage6DeviceMatrixProofFeedsProductionReleaseInputAndMetadata() {
        val request = request()
        val runtime = runtimeMetadata() + stage6RuntimeMetadata() + v20PassingRuntimeMetadata()
        val stage2 = SingularityStage2RecordingEvidencePolicy.fromQualityRequest(request, runtime)
        val stage2Metadata = stage2.toMetadata()
        val stage3 = SingularityStage3RecordingEvidencePolicy.fromQualityRequest(request, runtime + stage2Metadata)
        val stage3Metadata = stage3.toMetadata()
        val stage5 = SingularityStage5RecordingEnginePolicy.fromQualityRequest(request, runtime + stage2Metadata + stage3Metadata)
        val stage5Metadata = SingularityStage5RecordingEnginePolicy.metadataFor(request, stage5)
        val stage4 = SingularityStage4RecordingQualityPolicy.fromQualityRequest(
            request,
            runtime + stage2Metadata + stage3Metadata + stage5Metadata + passingQGArtifacts()
        )

        val release = SingularityProductionReleasePolicy.evaluate(
            request = request,
            runtimeMetadata = runtime + stage2Metadata + stage3Metadata + stage5Metadata + passingQGArtifacts(),
            stage2Bundle = stage2,
            stage3Bundle = stage3,
            stage5Bundle = stage5,
            stage4Bundle = stage4
        )

        assertEquals(
            release.elite100x.toMetadata().getValue("singularityElite100xReportJson"),
            SingularityReleaseClass.SINGULARITY_100000,
            release.releaseInput.requestedReleaseClass
        )
        assertEquals("true", release.releaseMetadata.getValue("singularityProductionElite100xQualified"))
        assertEquals("SINGULARITY-100000", release.releaseMetadata.getValue("singularityProductionElite100xReleaseCap"))
        assertEquals("PASS", release.releaseMetadata.getValue("singularityProductionFrameLineageParityStatus"))
        assertEquals("SINGULARITY-100000", release.releaseMetadata.getValue("singularityProductionFrameLineageParityReleaseCap"))
        assertTrue(
            release.releaseMetadata.getValue("singularityElite100x.FRAME_LINEAGE_PARITY")
                .startsWith("PASS|cap=SINGULARITY-100000")
        )
        assertEquals(
            SingularityElite100xStatus.PASS,
            release.elite100x.results.single {
                it.authority == SingularityElite100xAuthority.FRAME_LINEAGE_PARITY
            }.status
        )
        assertTrue(release.releaseInput.outputEvidence.largeScreenReviewPassed)
        assertTrue(release.releaseInput.outputEvidence.enduranceValidationPassed)
        assertEquals("PASS", release.releaseMetadata.getValue("singularityStage6OverallStatus"))
        assertEquals("true", release.releaseMetadata.getValue("singularityProductionDeviceMatrixValidated"))
        assertEquals("true", release.releaseMetadata.getValue("singularityProductionNativePathValidated"))
    }

    @Test
    fun missingFrameLineageCapsProductionReleaseAtSingularity75() {
        val release = productionRelease(
            runtime = runtimeMetadata().withoutFrameLineage() + stage6RuntimeMetadata()
        )

        assertFalse(release.elite100x.qualified)
        assertEquals(SingularityReleaseClass.SINGULARITY_75, release.elite100x.releaseCap)
        assertEquals(SingularityReleaseClass.SINGULARITY_75, release.releaseInput.requestedReleaseClass)
        assertEquals("NOT_TESTED", release.releaseMetadata.getValue("singularityProductionFrameLineageParityStatus"))
        assertEquals("SINGULARITY-75", release.releaseMetadata.getValue("singularityProductionFrameLineageParityReleaseCap"))
        assertEquals("", release.releaseMetadata.getValue("singularityProductionFrameLineageParityBlockingCode"))
    }

    @Test
    fun failedFrameLineageCapsProductionReleaseAtSingularity50() {
        val release = productionRelease(
            runtime = runtimeMetadata() + stage6RuntimeMetadata() + mapOf(
                "camera2FrameLineageMatchRatio" to "0.990",
                "camera2FrameLineagePreviewEncoderFrameDifference" to "2"
            )
        )

        assertFalse(release.elite100x.qualified)
        assertEquals(SingularityReleaseClass.SINGULARITY_50, release.elite100x.releaseCap)
        assertEquals(SingularityReleaseClass.SINGULARITY_50, release.releaseInput.requestedReleaseClass)
        assertEquals("FAIL", release.releaseMetadata.getValue("singularityProductionFrameLineageParityStatus"))
        assertEquals("SINGULARITY-50", release.releaseMetadata.getValue("singularityProductionFrameLineageParityReleaseCap"))
        assertEquals(
            "ELITE100X_FRAME_LINEAGE_REQUIREMENTS_FAILED",
            release.releaseMetadata.getValue("singularityProductionFrameLineageParityBlockingCode")
        )
    }

    @Test
    fun smfStoreWritesAtomicEnvelopeAndReleaseReport() {
        val request = request()
        val runtime = runtimeMetadata()
        val stage2 = SingularityStage2RecordingEvidencePolicy.fromQualityRequest(request, runtime)
        val stage2Metadata = stage2.toMetadata()
        val stage3 = SingularityStage3RecordingEvidencePolicy.fromQualityRequest(request, runtime + stage2Metadata)
        val stage3Metadata = stage3.toMetadata()
        val stage5 = SingularityStage5RecordingEnginePolicy.fromQualityRequest(request, runtime + stage2Metadata + stage3Metadata)
        val stage5Metadata = SingularityStage5RecordingEnginePolicy.metadataFor(request, stage5)
        val stage4 = SingularityStage4RecordingQualityPolicy.fromQualityRequest(
            request,
            runtime + stage2Metadata + stage3Metadata + stage5Metadata + passingQGArtifacts()
        )
        val release = SingularityProductionReleasePolicy.evaluate(
            request = request,
            runtimeMetadata = runtime + stage2Metadata + stage3Metadata + stage5Metadata + passingQGArtifacts(),
            stage2Bundle = stage2,
            stage3Bundle = stage3,
            stage5Bundle = stage5,
            stage4Bundle = stage4
        )
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            request.copy(detailMetadata = request.detailMetadata + release.releaseMetadata)
        )
        val bridge = SmfSingularityEvidenceBridge.fromExistingEvidence(certificate)
        val smf = SmfSingularityMetadata.fromReleaseDecision(
            input = release.releaseInput,
            decision = release.releaseDecision,
            thresholdSetId = stage4.thresholdSetId,
            thresholdSetHash = stage4.thresholdSetHash,
            nestedMetadataReferences = bridge.nestedMetadataReferences,
            evidenceReferences = bridge.evidenceReferences
        )
        val root = Files.createTempDirectory("smf-stage5").toFile()
        try {
            val result = SmfSingularityMetadataStore.write(root, "stage5", smf, release.releaseDecision)

            assertTrue(result.smfFile.isFile)
            assertTrue(result.releaseReportFile.isFile)
            assertTrue(result.smfFile.readText().contains("\"formatVersion\": \"SMF-SINGULARITY-1\""))
            assertTrue(result.releaseReportFile.readText().contains("\"formatVersion\":\"SINGULARITY_RELEASE_REPORT_1\""))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun request(): RecordingQualityEvidenceRequest =
        RecordingQualityEvidenceRequest(
            mode = EliteVideoPipelineMode.HDR,
            selectedProfile = "NDR10+",
            activeTransport = "BT.2100_PQ_MAIN10",
            transfer = "ST2084/PQ",
            primaries = "BT.2020",
            matrix = "BT.2020-NCL",
            codec = "hevc",
            encodedBitDepth = 10,
            internalMathBitDepth = 12,
            sourceBitDepth = 10,
            storageBitDepth = 10,
            width = 3840,
            height = 2160,
            fps = 60,
            bitrateBps = 120_000_000,
            measuredOutputBitrateBps = 120_000_000,
            selectedResolution = "3840x2160",
            actualResolution = "3840x2160",
            normalizedHdrVersion = "NDR10+",
            normalizedLogVersion = "OFF",
            rawSensorOriginal = false,
            captureTruthSignature = "capture",
            previewQualitySignature = "quality",
            recordingQualitySignature = "quality",
            expectedFrameCount = 120L,
            actualFrameCount = 120L,
            droppedFrames = 0,
            maxAllowedDroppedFrames = 0,
            probe = null,
            ffprobeValidated = false,
            mediaInfoValidated = false,
            deviceLogValidated = false,
            recordingId = "stage5",
            recordedFileName = "stage5.mp4",
            recordedFileBytes = 4096L,
            mediaStoreValidated = true,
            gpuProcessingValidated = true,
            processedRecordingFramesValidated = true,
            zeroCopyValidated = true,
            performanceTelemetryValidated = true,
            gpuProcessingPath = "VULKAN_OFFSCREEN_AHB_EGL_ENCODER",
            enhancementUniformSignature = "gpu-v1|science=NDR10_PLUS|highlight=true|shadow=true",
            previewEnhancementUniformSignature = "uniform",
            recordingEnhancementUniformSignature = "uniform",
            enhancementTemporalBlend = 0.24f,
            enhancementSpatialDenoise = 0.92f,
            enhancementChromaDenoise = 0.96f,
            enhancementSharpening = 0.72f,
            enhancementSubjectDetailGain = 0.22f,
            enhancementDetailProfile = "EXTREME_DETAIL",
            detailMetadata = mapOf(
                "proprietaryProfilePreserved" to "true",
                "vendorHdrCertification" to "false",
                "officialHdr10PlusMetadataWritten" to "false",
                "vendorCertifiedHdr10Plus" to "false",
                "vendorCertifiedDolbyVision" to "false",
                "dolbyRpuWritten" to "false",
                "externalHdrStandardSubstitution" to "false",
                "detailEdgeConfidence" to "0.900",
                "detailSharpeningStrength" to "0.720",
                "detailHaloRisk" to "0.100",
                "detailHaloSuppression" to "0.940",
                "detailMoireAliasRisk" to "0.050",
                "detailTextureConfidence" to "0.900",
                "detailReconstruction" to "0.700",
                "detailSkinTextureGuard" to "0.800",
                "detailTrueResolutionClaim" to "TRUE_NATIVE_3840x2160_NO_FAKE_SUPER_RESOLUTION",
                "selectedColorSpace" to "Rec.2020",
                "selectedColorScience" to "NDR10+",
                "skinToneProtection" to "BT2100_SKIN_RED_RATIO_PROTECTION"
            )
        )

    private fun runtimeMetadata(): Map<String, String> =
        mapOf(
            "stage2PlayableValidated" to "true",
            "previewRecordingParityStatus" to "PASS",
            "vulkanSessionImportedFrames" to "120",
            "vulkanEncodedProcessedFrames" to "120",
            "vulkanFrameImportEnabled" to "true",
            "vulkanEncoderSurfaceBound" to "true",
            "stage2CopyCountEvidenceProven" to "true",
            "fullFrameCpuReadbackCount" to "0",
            "singularityStreamGraphMode" to "AHB_VULKAN_ZERO_COPY",
            "singularityCameraId" to "0",
            "manualColorSelectionReady" to "true",
            "selectedColorSpace" to "Rec.2020",
            "selectedColorScience" to "NDR10+",
            "chromaNoise" to "0.020",
            "lumaNoise" to "0.020",
            "temporalInstability" to "0.100",
            "frameToFrameRgbVariance" to "0.020",
            "dynamicRange" to "0.840",
            "motion" to "0.120",
            "stage3AiFaceCount" to "1",
            "stage3AiPrimarySubjectPresent" to "true",
            "stage3AiSubjectMaskPresent" to "true",
            "stage3AiDepthMapPresent" to "true",
            "stage3AiDepthConfidenceAvailable" to "true",
            "stage3TemporalHistoryFrames" to "3",
            "stage5HallucinationRisk" to "0.010",
            "shaderRegistryValidated" to "true",
            "modelRegistryValidated" to "true",
            "lutRegistryValidated" to "true",
            "lookRegistryValidated" to "true",
            "largeScreenReviewPassed" to "true",
            "enduranceValidationPassed" to "true",
            "camera2FrameLineageCertified" to "true",
            "camera2FrameLineageFrameCount" to "120",
            "camera2FrameLineageDurationMs" to "2000",
            "camera2FrameLineageMatchRatio" to "0.996",
            "camera2FrameLineageStaleGenerationCount" to "0",
            "camera2FrameLineageNonMonotonicTimestampCount" to "0",
            "camera2FrameLineageDuplicateEncoderSubmissionCount" to "0",
            "camera2FrameLineageEndpointOmissionCount" to "0",
            "camera2FrameLineagePreviewEncoderFrameDifference" to "1"
        )

    private fun passingQGArtifacts(): Map<String, String> =
        mapOf(
            "stage4EncodedVsPreEncodeLoss" to "0.020",
            "stage4PreviewFileDelta" to "0.010",
            "stage4ValidatedEnduranceMinutes" to "12.0",
            "stage4NeuralResolutionClaimed" to "true",
            "stage4HallucinationRisk" to "0.010",
            "stage4EncodedVsPreEncodeLossArtifact" to "artifacts/qg12.json",
            "stage4PreviewFileComparisonArtifact" to "artifacts/qg13.json",
            "singularityEliteMeasuredGateIds" to
                "QG01,QG02,QG03,QG04,QG05,QG06,QG07,QG08,QG09,QG10,QG11,QG12,QG13",
            "singularityEliteArtifactManifestSha256" to
                "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        )

    private fun v20PassingRuntimeMetadata(): Map<String, String> =
        mapOf(
            "zeroGrainClarity" to "0.950",
            "stage5MotionBlurResidual" to "0.040",
            "temporalShimmerPercent" to "0.010",
            "opticalFlowConfidence" to "0.950"
        )

    private fun stage6RuntimeMetadata(): Map<String, String> {
        val outputMode = "NDR10X-SINGULARITY"
        val combinationId = SingularityStage6DeviceMatrixPolicy.combinationId(
            deviceFingerprint = "google/panther/prod",
            cameraId = "0",
            outputMode = outputMode,
            resolution = "3840x2160",
            fps = 60
        )
        return mapOf(
            "singularityStage6DeviceFingerprint" to "google/panther/prod",
            "singularityStage6BuildId" to "UP1A.240000.001",
            "singularityStage6DeviceClass" to "HIGH_END_VALIDATED",
            "singularityStage6CalibrationId" to "lab-calibration-2026-06-08",
            "singularityStage6CameraId" to "0",
            "singularityStage6TestedCombinationId" to combinationId,
            "singularityStage6TestedModeIds" to SingularityStage6DeviceMatrixPolicy.REQUIRED_MODES.joinToString(),
            "singularityStage6CompletedFunctionalChecks" to
                SingularityStage6DeviceMatrixPolicy.REQUIRED_FUNCTIONAL_CHECKS.joinToString(),
            "singularityStage6CompletedScenes" to SingularityStage6DeviceMatrixPolicy.REQUIRED_SCENES.joinToString(),
            "singularityStage6CompletedCharts" to SingularityStage6DeviceMatrixPolicy.REQUIRED_CHARTS.joinToString(),
            "singularityStage6CompletedEnduranceScenarios" to
                SingularityStage6DeviceMatrixPolicy.REQUIRED_ENDURANCE.joinToString(),
            "singularityStage6CompletedReviewSurfaces" to
                SingularityStage6DeviceMatrixPolicy.REQUIRED_REVIEW_SURFACES.joinToString(),
            "singularityStage6DeviceLabStatus" to "PASS",
            "singularityStage6DeviceCapabilitiesStatus" to "PASS",
            "singularityStage6FunctionalStatus" to "PASS",
            "singularityStage6ModeMatrixStatus" to "PASS",
            "singularityStage6SceneMatrixStatus" to "PASS",
            "singularityStage6ChartMatrixStatus" to "PASS",
            "singularityStage6EnduranceStatus" to "PASS",
            "singularityStage6PerformanceStatus" to "PASS",
            "singularityStage6LargeScreenReviewStatus" to "PASS",
            "singularityStage6UiStatus" to "PASS",
            "singularityStage6NativePathStatus" to "PASS",
            "singularityThresholdSetId" to "panther-4k60-ndr",
            "singularityThresholdSetHash" to
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        )
    }

    private fun productionRelease(
        runtime: Map<String, String>,
        request: RecordingQualityEvidenceRequest = request()
    ): SingularityProductionReleaseBundle {
        val stage2 = SingularityStage2RecordingEvidencePolicy.fromQualityRequest(request, runtime)
        val stage2Metadata = stage2.toMetadata()
        val stage3 = SingularityStage3RecordingEvidencePolicy.fromQualityRequest(request, runtime + stage2Metadata)
        val stage3Metadata = stage3.toMetadata()
        val stage5 = SingularityStage5RecordingEnginePolicy.fromQualityRequest(
            request,
            runtime + stage2Metadata + stage3Metadata
        )
        val stage5Metadata = SingularityStage5RecordingEnginePolicy.metadataFor(request, stage5)
        val artifacts = passingQGArtifacts()
        val stage4 = SingularityStage4RecordingQualityPolicy.fromQualityRequest(
            request,
            runtime + stage2Metadata + stage3Metadata + stage5Metadata + artifacts
        )
        return SingularityProductionReleasePolicy.evaluate(
            request = request,
            runtimeMetadata = runtime + stage2Metadata + stage3Metadata + stage5Metadata + artifacts,
            stage2Bundle = stage2,
            stage3Bundle = stage3,
            stage5Bundle = stage5,
            stage4Bundle = stage4
        )
    }

    private fun Map<String, String>.withoutFrameLineage(): Map<String, String> =
        filterKeys { !it.startsWith("camera2FrameLineage") }
}
