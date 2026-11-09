package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.SingularityQualityGateId
import com.neoproc.neoproccamx.quality.SingularityQualityGateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SingularityStage4RecordingQualityPolicyTest {

    @Test
    fun recordingPolicyPersistsCanonicalGateMetadataAndReportJson() {
        val request = baseRequest()
        val stage2 = SingularityStage2RecordingEvidencePolicy.metadataFor(request, runtimeMetadata())
        val stage3 = SingularityStage3RecordingEvidencePolicy.metadataFor(request, runtimeMetadata() + stage2)
        val bundle = SingularityStage4RecordingQualityPolicy.fromQualityRequest(
            request = request,
            runtimeMetadata = runtimeMetadata() + passingStage4Metrics() + stage2 + stage3
        )
        val metadata = SingularityStage4RecordingQualityPolicy.metadataFor(request, bundle)

        assertEquals("16", metadata.getValue("singularityStage4GateCount"))
        assertEquals(
            "QG01,QG02,QG03,QG04,QG05,QG06,QG07,QG08,QG09,QG10,QG11,QG12,QG13,QG14,QG15,QG16",
            metadata.getValue("singularityStage4GateIds")
        )
        assertTrue(metadata.getValue("singularityStage4GateSummary").contains("PASS=16"))
        assertTrue(metadata.getValue("singularityStage4ReportJson").contains("\"formatVersion\":\"SINGULARITY_STAGE4_QG_REPORT_1\""))
        assertEquals("singularity_quality/stage4/qg-results.json", metadata.getValue("singularityStage4ArtifactRelativePath"))
    }

    @Test
    fun missingStage2AndStage3EvidenceFailsMetadataHonesty() {
        val bundle = SingularityStage4RecordingQualityPolicy.fromQualityRequest(
            request = baseRequest(),
            runtimeMetadata = runtimeMetadata() + passingStage4Metrics()
        )
        val qg16 = bundle.results.single { it.gateId == SingularityQualityGateId.QG16 }

        assertEquals(SingularityQualityGateStatus.FAIL, qg16.status)
        assertEquals("STAGE2_EVIDENCE_MISSING", qg16.failureCode)
    }

    @Test
    fun artifactStoreWritesMachineReadableQGReport() {
        val request = baseRequest()
        val stage2 = SingularityStage2RecordingEvidencePolicy.metadataFor(request, runtimeMetadata())
        val stage3 = SingularityStage3RecordingEvidencePolicy.metadataFor(request, runtimeMetadata() + stage2)
        val bundle = SingularityStage4RecordingQualityPolicy.fromQualityRequest(
            request = request,
            runtimeMetadata = runtimeMetadata() + passingStage4Metrics() + stage2 + stage3
        )
        val root = Files.createTempDirectory("stage4-qg").toFile()
        try {
            val file = SingularityQualityGateArtifactStore.write(
                rootDirectory = root,
                recordingId = "stage4 recording/1",
                reportJson = bundle.toJsonText()
            )

            assertTrue(file.isFile)
            assertTrue(file.absolutePath.contains("singularity_quality"))
            assertTrue(file.readText(Charsets.UTF_8).contains("\"qualityGates\""))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun baseRequest(): RecordingQualityEvidenceRequest =
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
            recordingId = "stage4",
            recordedFileName = "stage4.mp4",
            recordedFileBytes = 4096L,
            mediaStoreValidated = true,
            gpuProcessingValidated = true,
            processedRecordingFramesValidated = true,
            gpuProcessingPath = "VULKAN_OFFSCREEN_AHB_EGL_ENCODER",
            enhancementUniformSignature = "gpu-v1|science=NDR10_PLUS",
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
                "detailSignature" to "detail|edge=0.9|truth=true",
                "detailTextureConfidence" to "0.850",
                "selectedColorSpace" to "Rec.2020",
                "selectedColorScience" to "NDR10+",
                "skinToneProtection" to "BT2100_SKIN_RED_RATIO_PROTECTION"
            )
        )

    private fun runtimeMetadata(): Map<String, String> =
        mapOf(
            "stage2PlayableValidated" to "true",
            "previewRecordingParityStatus" to "PASS",
            "renderGraphShaderSha256" to "shader",
            "renderGraphLutSha256" to "lut",
            "renderGraphStagesSignature" to "preview=enhance>output|recording=enhance>output",
            "manualColorSelectionReady" to "true",
            "selectedColorSpace" to "Rec.2020",
            "selectedColorScience" to "NDR10+",
            "vulkanEncodedProcessedFrames" to "120",
            "vulkanAverageComputeMs" to "4.2",
            "singularitySensorNoiseProfilePairs" to "4",
            "stage3AiFrameResultObserved" to "true",
            "stage3AiFaceCount" to "1",
            "stage3AiPrimarySubjectPresent" to "true",
            "stage3AiSubjectMaskPresent" to "true",
            "stage3AiSegmentationPresent" to "true",
            "stage3AiDepthMapPresent" to "true",
            "stage3AiDepthConfidenceAvailable" to "true",
            "stage3AiSemanticTemporalStabilized" to "true"
        )

    private fun passingStage4Metrics(): Map<String, String> =
        mapOf(
            "stage4EdgeOvershootPercent" to "0.040",
            "stage4TemporalShimmerPercent" to "0.010",
            "stage4ChromaCrawlScore" to "0.020",
            "stage4HighlightRolloffError" to "0.060",
            "stage4ShadowDepthError" to "0.080",
            "stage4SkinDeltaE" to "2.000",
            "stage4FineTextureRetention" to "0.900",
            "stage4TextStabilityScore" to "0.860",
            "stage4NeuralResolutionClaimed" to "true",
            "stage4HallucinationRisk" to "0.010",
            "stage4ToneFlickerPercent" to "0.010",
            "stage4NeutralDeltaE" to "1.500",
            "stage4EncodedVsPreEncodeLoss" to "0.020",
            "stage4PreviewFileDelta" to "0.010",
            "stage4ValidatedEnduranceMinutes" to "12.0"
        )
}
