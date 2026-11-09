package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.SingularityStageExecutionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingularityStage3RecordingEvidencePolicyTest {

    @Test
    fun metadataForQualityRequestPersistsCanonicalStage3Summary() {
        val metadata = SingularityStage3RecordingEvidencePolicy.metadataFor(
            request = baseRequest(),
            runtimeMetadata = runtimeMetadata()
        )

        assertEquals("192", metadata.getValue("singularityStage3MappedStageCount"))
        assertEquals((32..223).joinToString(separator = ",") { it.toString().padStart(3, '0') }, metadata.getValue("singularityStage3StageIds"))
        assertEquals("true", metadata.getValue("singularityStage3ProprietaryProfilePreserved"))
        assertEquals("false", metadata.getValue("singularityStage3VendorHdrCertificationClaimed"))
        assertTrue(metadata.getValue("singularityStage3.stage216.preview_file_tone_match").startsWith("EXECUTED"))
        assertTrue(metadata.getValue("singularityStage3.stage217.creative_grain_policy").startsWith("IDENTITY_PASS"))
        assertTrue(metadata.getValue("singularityStage3.stage181.neural_resolution_capability").startsWith("DISABLED_BY_DEVICE"))
    }

    @Test
    fun rawSourceWithoutRuntimeDebayerDoesNotClaimDemosaicExecution() {
        val metadata = SingularityStage3RecordingEvidencePolicy.metadataFor(
            request = baseRequest().copy(
                mode = EliteVideoPipelineMode.RAW,
                selectedProfile = "RAW",
                activeTransport = "RAW_PRIVATE_MASTER",
                transfer = "Scene-linear",
                primaries = "RAW_SENSOR",
                matrix = "RAW",
                encodedBitDepth = 12,
                internalMathBitDepth = 12,
                sourceBitDepth = 12,
                storageBitDepth = 12,
                rawSensorOriginal = true,
                npv10PayloadFormat = "RAW12_NPV10_PACKED"
            ),
            runtimeMetadata = runtimeMetadata() + mapOf(
                "rawPrivateMasterUnmodified" to "true",
                "rawMonitorPipeline" to "RAW_MONITOR_CINEMA_DEBAYER_PREVIEW_ONLY",
                "stage3CinemaDebayeredFrames" to "0"
            )
        )

        assertTrue(metadata.getValue("singularityStage3.stage032.raw_packing_validation").startsWith("EXECUTED"))
        assertTrue(metadata.getValue("singularityStage3.stage034.green_split_balance").startsWith("DISABLED_BY_DEVICE"))
        assertTrue(
            metadata.getValue("singularityStage3SourceOnlyStageIds")
                .contains("034")
        )
        assertTrue(metadata.getValue("singularityStage3.stage047.linear_rgb_reconstruction_output").startsWith("IDENTITY_PASS"))
    }

    @Test
    fun parityMismatchMarksPreviewFileToneMatchFailed() {
        val metadata = SingularityStage3RecordingEvidencePolicy.metadataFor(
            request = baseRequest().copy(
                previewQualitySignature = "preview-quality",
                recordingQualitySignature = "recording-quality"
            ),
            runtimeMetadata = runtimeMetadata() + mapOf(
                "previewRecordingParityStatus" to "FAIL",
                "previewRecordingParityFailureCodes" to "PREVIEW_RECORDING_STAGE_MISMATCH"
            )
        )

        assertTrue(metadata.getValue("singularityStage3.stage216.preview_file_tone_match").startsWith("FAILED_WITH_DOWNGRADE"))
        assertTrue(metadata.getValue("singularityStage3FailureStageIds").contains("216"))
    }

    @Test
    fun bundleKeepsUnsupportedNeuralAndMultiLensStagesDisabled() {
        val bundle = SingularityStage3RecordingEvidencePolicy.fromQualityRequest(
            request = baseRequest(),
            runtimeMetadata = runtimeMetadata()
        )
        val neuralStages = bundle.stages.filter { it.globalStageId in 181..188 }
        val multiLensFusionStages = bundle.stages.filter { it.globalStageId in 136..142 }

        assertTrue(neuralStages.all { it.executionState == SingularityStageExecutionState.DISABLED_BY_DEVICE })
        assertTrue(multiLensFusionStages.any { it.executionState == SingularityStageExecutionState.DISABLED_BY_DEVICE })
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
            recordingId = "stage3",
            recordedFileName = "stage3.mp4",
            recordedFileBytes = 4096L,
            gpuProcessingValidated = true,
            processedRecordingFramesValidated = true,
            gpuProcessingPath = "VULKAN_OFFSCREEN_AHB_EGL_ENCODER",
            enhancementUniformSignature = "gpu-v1|signal=HDR|science=NDR10_PLUS|shotNoise=0.0030|readNoise=0.0020|highlight=true|shadow=true|contrast=true",
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
                "vendorCertifiedHdr10Plus" to "false",
                "vendorCertifiedDolbyVision" to "false",
                "dolbyRpuWritten" to "false",
                "officialHdr10PlusMetadataWritten" to "false",
                "detailSignature" to "detail|edge=0.9|truth=true",
                "detailTrueResolutionClaim" to "TRUE_NATIVE_3840x2160_NO_FAKE_SUPER_RESOLUTION",
                "detailEdgeConfidence" to "0.900",
                "detailTextureConfidence" to "0.850",
                "detailHaloSuppression" to "0.800",
                "detailReconstruction" to "0.700",
                "detailMoireAliasGuard" to "true",
                "detailNoisyShadowSharpeningSuppression" to "true",
                "detailSkinTextureGuard" to "true",
                "selectedColorSpace" to "Rec.2020",
                "selectedColorScience" to "NDR10+",
                "skinToneProtection" to "BT2100_SKIN_RED_RATIO_PROTECTION"
            )
        )

    private fun runtimeMetadata(): Map<String, String> =
        mapOf(
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
            "singularityFixedPatternNoiseProfile" to "heteroscedastic",
            "singularityImuSampleCount" to "4",
            "singularityCaptureTimestampNs" to "1000",
            "singularityRollingShutterSkewNs" to "8000000",
            "stage3AiFrameResultObserved" to "true",
            "stage3AiFaceCount" to "1",
            "stage3AiEyeLandmarkCount" to "2",
            "stage3AiPrimarySubjectPresent" to "true",
            "stage3AiPrimarySubjectConfidence" to "0.91",
            "stage3AiPrimarySubjectStability" to "0.82",
            "stage3AiSubjectMaskPresent" to "true",
            "stage3AiSegmentationPresent" to "true",
            "stage3AiDepthMapPresent" to "true",
            "stage3AiDepthConfidenceAvailable" to "true",
            "stage3AiSemanticTemporalStabilized" to "true",
            "dualGainMotionMaskEnabled" to "true",
            "dynamicRange" to "0.84",
            "avgLuma" to "0.43",
            "motion" to "0.12"
        )
}
