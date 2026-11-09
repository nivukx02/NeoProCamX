package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingularityStage5HighEndEngineTest {

    @Test
    fun highEndEvidenceEmitsEveryEngineAndBoundedStage4Metrics() {
        val bundle = SingularityStage5HighEndEnginePolicy.evaluate(
            input(metadata = highEndMetadata())
        )

        assertEquals(SingularityStage5EngineId.entries.toSet(), bundle.records.map { it.engineId }.toSet())
        assertEquals(12, bundle.records.size)
        assertEquals(
            SingularityStage5EngineStatus.DISABLED_BY_DEVICE,
            bundle.records.single { it.engineId == SingularityStage5EngineId.MULTI_LENS_FUSION }.status
        )
        assertEquals(
            SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED,
            bundle.records.single { it.engineId == SingularityStage5EngineId.TEXTURE_AUTHORITY }.status
        )
        assertEquals(
            SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED,
            bundle.records.single { it.engineId == SingularityStage5EngineId.NOISE_TEXTURE_SEPARATION }.status
        )
        assertEquals(
            SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED,
            bundle.records.single { it.engineId == SingularityStage5EngineId.MOTION_TEMPORAL }.status
        )
        assertEquals("false", bundle.stage4MetricMetadata.getValue("singularityStage5NeuralResolutionClaimed"))
        assertFalse(bundle.stage4MetricMetadata.containsKey("stage4HallucinationRisk"))
        listOf(
            "stage4EdgeOvershootPercent",
            "stage4TemporalShimmerPercent",
            "stage4ChromaCrawlScore",
            "stage4HighlightRolloffError",
            "stage4ShadowDepthError",
            "stage4SkinDeltaE",
            "stage4FineTextureRetention",
            "stage4TextStabilityScore",
            "stage4ToneFlickerPercent",
            "stage4NeutralDeltaE",
            "stage5ZeroGrainClarityScore",
            "stage5GrainResidualScore",
            "stage5DenoiseSafetyScore",
            "stage5MicrodetailHonestyScore",
            "stage5SharpnessSafetyScore",
            "stage5MotionBlurResidual",
            "stage5AntiMotionBlurScore"
        ).forEach { key ->
            assertTrue("$key missing", bundle.stage4MetricMetadata.containsKey(key))
        }
        assertEquals(
            "zero-grain-clarity,microdetail-honesty,anti-motion-blur",
            bundle.stage4MetricMetadata.getValue("singularityStage5V20EvidenceProfile")
        )
        assertTrue(bundle.toJsonText().contains("\"MULTI_LENS_FUSION\""))
    }

    @Test
    fun rawMasterWithoutDebayerProofUsesVisibleFallback() {
        val bundle = SingularityStage5HighEndEnginePolicy.evaluate(
            input(
                outputMode = "NRAW-SINGULARITY",
                metadata = highEndMetadata() + mapOf(
                    "rawSensorCapture" to "true",
                    "rawPrivateMasterUnmodified" to "true",
                    "stage3CinemaDebayeredFrames" to "0",
                    "cinemaDebayeredFrames" to "0"
                )
            )
        )
        val raw = bundle.records.single { it.engineId == SingularityStage5EngineId.RAW_RECONSTRUCTION }

        assertEquals(SingularityStage5EngineStatus.FALLBACK_EXECUTED, raw.status)
        assertTrue(raw.fallbackReason.contains("not claimed", ignoreCase = true))
    }

    @Test
    fun neuralResolutionClaimRequiresHallucinationEvidence() {
        val withoutRisk = SingularityStage5HighEndEnginePolicy.evaluate(
            input(metadata = highEndMetadata() + mapOf("stage4NeuralResolutionClaimed" to "true"))
        )
        val withRisk = SingularityStage5HighEndEnginePolicy.evaluate(
            input(
                metadata = highEndMetadata() + mapOf(
                    "stage4NeuralResolutionClaimed" to "true",
                    "stage5HallucinationRisk" to "0.018"
                )
            )
        )

        assertFalse(withoutRisk.stage4MetricMetadata.containsKey("stage4HallucinationRisk"))
        assertEquals("0.018000", withRisk.stage4MetricMetadata.getValue("stage4HallucinationRisk"))
        assertEquals(
            SingularityStage5EngineStatus.IMPLEMENTED_NEEDS_DEVICE_EVIDENCE,
            withRisk.records.single { it.engineId == SingularityStage5EngineId.RESOLUTION_HONESTY }.status
        )
    }

    @Test
    fun missingDenoiseEvidenceCapsZeroGrainAuthority() {
        val bundle = SingularityStage5HighEndEnginePolicy.evaluate(
            input(metadata = highEndMetadata() - "enhancementLumaDenoise")
        )
        val noise = bundle.records.single { it.engineId == SingularityStage5EngineId.NOISE_TEXTURE_SEPARATION }

        assertEquals(SingularityStage5EngineStatus.IMPLEMENTED_NEEDS_DEVICE_EVIDENCE, noise.status)
        assertFalse(bundle.stage4MetricMetadata.containsKey("stage5ZeroGrainClarityScore"))
        assertTrue(noise.fallbackReason.contains("Zero-grain clarity"))
    }

    @Test
    fun unsafeSharpnessEvidenceStopsMicrodetailEvidence() {
        val bundle = SingularityStage5HighEndEnginePolicy.evaluate(
            input(
                metadata = highEndMetadata() + mapOf(
                    "detailSharpeningStrength" to "0.980",
                    "detailHaloRisk" to "0.450"
                )
            )
        )
        val texture = bundle.records.single { it.engineId == SingularityStage5EngineId.TEXTURE_AUTHORITY }

        assertEquals(SingularityStage5EngineStatus.IMPLEMENTED_NEEDS_DEVICE_EVIDENCE, texture.status)
        assertTrue(bundle.stage4MetricMetadata.getValue("singularityStage5V20UnsafeReasons").contains("sharpnessSafetyScore"))
    }

    @Test
    fun unsafeMotionBlurEvidenceStopsAntiMotionBlurEvidence() {
        val bundle = SingularityStage5HighEndEnginePolicy.evaluate(
            input(metadata = highEndMetadata() + ("stage5MotionBlurResidual" to "0.220"))
        )
        val motion = bundle.records.single { it.engineId == SingularityStage5EngineId.MOTION_TEMPORAL }

        assertEquals(SingularityStage5EngineStatus.IMPLEMENTED_NEEDS_DEVICE_EVIDENCE, motion.status)
        assertTrue(bundle.stage4MetricMetadata.getValue("singularityStage5V20UnsafeReasons").contains("motionBlurResidual"))
    }

    @Test
    fun syntheticGoldenAndTemporalMetricsRejectRingingAndInstability() {
        val cleanEdge = floatArrayOf(0f, 0f, 0.1f, 0.4f, 0.8f, 1f, 1f, 1f)
        val ringingEdge = floatArrayOf(0f, 0.04f, 0f, 0.46f, 1f, 0.82f, 1f, 0.96f)
        val stableFrames = listOf(
            floatArrayOf(0.2f, 0.4f, 0.6f),
            floatArrayOf(0.201f, 0.399f, 0.601f),
            floatArrayOf(0.2f, 0.4f, 0.6f)
        )
        val unstableFrames = listOf(
            floatArrayOf(0.2f, 0.4f, 0.6f),
            floatArrayOf(0.35f, 0.25f, 0.75f),
            floatArrayOf(0.1f, 0.55f, 0.45f)
        )
        val referenceTexture = floatArrayOf(0f, 1f, 0f, 1f, 0f, 1f)
        val preservedTexture = floatArrayOf(0.02f, 0.96f, 0.04f, 0.94f, 0.03f, 0.97f)
        val erasedTexture = floatArrayOf(0.45f, 0.55f, 0.47f, 0.53f, 0.46f, 0.54f)

        assertTrue(
            SingularityStage5ImageQualityEngine.edgeOvershootPercent(ringingEdge) >
                SingularityStage5ImageQualityEngine.edgeOvershootPercent(cleanEdge)
        )
        assertTrue(
            SingularityStage5ImageQualityEngine.temporalShimmerPercent(unstableFrames) >
                SingularityStage5ImageQualityEngine.temporalShimmerPercent(stableFrames)
        )
        assertTrue(
            SingularityStage5ImageQualityEngine.fineTextureRetention(referenceTexture, preservedTexture) >
                SingularityStage5ImageQualityEngine.fineTextureRetention(referenceTexture, erasedTexture)
        )
        assertTrue(
            SingularityStage5ImageQualityEngine.chromaCrawlScore(unstableFrames, unstableFrames) >
                SingularityStage5ImageQualityEngine.chromaCrawlScore(stableFrames, stableFrames)
        )
    }

    private fun input(
        outputMode: String = "NDR10+",
        metadata: Map<String, String>
    ): SingularityStage5EngineInput =
        SingularityStage5EngineInput(
            recordingId = "stage5",
            outputMode = outputMode,
            sourceBitDepth = 10,
            encodedBitDepth = 10,
            width = 3840,
            height = 2160,
            fps = 60,
            metadata = metadata
        )

    private fun highEndMetadata(): Map<String, String> =
        mapOf(
            "gpuProcessingValidated" to "true",
            "processedRecordingFramesValidated" to "true",
            "recordingGpuFrameImportValidated" to "true",
            "vulkanEncodedProcessedFrames" to "120",
            "selectedColorSpace" to "Rec.2020",
            "selectedColorScience" to "NDR10+",
            "normalizedHdrVersion" to "NDR10+",
            "proprietaryProfilePreserved" to "true",
            "vendorHdrCertification" to "false",
            "officialHdr10PlusMetadataWritten" to "false",
            "vendorCertifiedHdr10Plus" to "false",
            "vendorCertifiedDolbyVision" to "false",
            "dolbyRpuWritten" to "false",
            "externalHdrStandardSubstitution" to "false",
            "previewRecordingParityStatus" to "PASS",
            "detailEdgeConfidence" to "0.900",
            "detailSharpeningStrength" to "0.720",
            "detailHaloRisk" to "0.100",
            "detailHaloSuppression" to "0.940",
            "detailMoireAliasRisk" to "0.050",
            "detailTextureConfidence" to "0.900",
            "detailReconstruction" to "0.700",
            "detailSkinTextureGuard" to "0.800",
            "detailClarity" to "0.920",
            "detailTrueResolutionClaim" to "TRUE_NATIVE_3840x2160_NO_FAKE_SUPER_RESOLUTION",
            "chromaNoise" to "0.020",
            "lumaNoise" to "0.020",
            "temporalInstability" to "0.100",
            "frameToFrameRgbVariance" to "0.020",
            "enhancementTemporalBlend" to "0.240",
            "enhancementLumaDenoise" to "0.930",
            "enhancementChromaDenoise" to "0.960",
            "enhancementUniformSignature" to "gpu-v1|science=NDR10_PLUS|highlight=true|shadow=true",
            "dynamicRange" to "0.840",
            "motion" to "0.120",
            "opticalFlowConfidence" to "0.880",
            "stage5MotionBlurResidual" to "0.055",
            "skinToneProtection" to "BT2100_SKIN_RED_RATIO_PROTECTION",
            "stage3AiFaceCount" to "1",
            "stage3AiPrimarySubjectPresent" to "true",
            "stage3AiSubjectMaskPresent" to "true",
            "stage3AiDepthMapPresent" to "true",
            "stage3AiDepthConfidenceAvailable" to "true",
            "singularityPhysicalCameraStreamProven" to "false",
            "stage3TemporalHistoryFrames" to "3"
        )
}
