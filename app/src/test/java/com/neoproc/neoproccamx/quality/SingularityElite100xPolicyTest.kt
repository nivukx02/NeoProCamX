package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingularityElite100xPolicyTest {

    @Test
    fun completeAttestedEvidenceQualifiesElite100x() {
        val bundle = SingularityElite100xPolicy.evaluate(input())
        val frameLineage = bundle.results.single {
            it.authority == SingularityElite100xAuthority.FRAME_LINEAGE_PARITY
        }

        assertTrue(bundle.qualified)
        assertEquals(SingularityReleaseClass.SINGULARITY_100000, bundle.releaseCap)
        assertEquals(SingularityElite100xStatus.PASS, frameLineage.status)
        assertEquals(SingularityReleaseClass.SINGULARITY_100000, frameLineage.releaseCap)
        assertEquals("PASS=14 WARN=0 FAIL=0 NOT_TESTED=0", bundle.toMetadata().getValue("singularityElite100xAuthoritySummary"))
        assertTrue(bundle.toMetadata().getValue("singularityElite100x.FRAME_LINEAGE_PARITY").startsWith("PASS|cap=SINGULARITY-100000"))
    }

    @Test
    fun metadataOnlyQualityGatesCapElite100x() {
        val bundle = SingularityElite100xPolicy.evaluate(
            input(metadata = baseMetadata() - "singularityEliteArtifactManifestSha256")
        )

        assertFalse(bundle.qualified)
        assertEquals(SingularityReleaseClass.SINGULARITY_75, bundle.releaseCap)
        assertTrue(bundle.blockingCodes.isEmpty())
        assertTrue(bundle.toJsonText().contains("artifact-attestation-missing"))
    }

    @Test
    fun compatibilityTransportCapsElite100xAtCoreHonest() {
        val bundle = SingularityElite100xPolicy.evaluate(
            input(transport = SingularityTransportClassification.CPU_COMPATIBILITY_NON_SINGULARITY)
        )

        assertFalse(bundle.qualified)
        assertEquals(SingularityReleaseClass.CORE_HONEST, bundle.releaseCap)
        assertEquals(listOf("ELITE100X_TRANSPORT_NOT_SINGULARITY_PROVEN"), bundle.blockingCodes)
        assertTrue(bundle.compatibilityProfiles.contains("ELITE-100X-COMPATIBILITY-CAPPED"))
    }

    @Test
    fun defaultThresholdSetRequiresCanonicalAssetHash() {
        val bundle = SingularityElite100xPolicy.evaluate(
            input(
                thresholdSetId = SingularityStage4QualityGateAutomation.DEFAULT_THRESHOLD_SET_ID,
                thresholdSetHash = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            )
        )

        assertFalse(bundle.qualified)
        assertEquals(SingularityReleaseClass.SINGULARITY_75, bundle.releaseCap)
        assertTrue(bundle.toJsonText().contains("threshold:unattested"))
    }

    @Test
    fun missingFrameLineageCapsElite100xAtSingularity75() {
        val bundle = SingularityElite100xPolicy.evaluate(
            input(metadata = baseMetadata() - frameLineageKeys())
        )
        val frameLineage = bundle.results.single {
            it.authority == SingularityElite100xAuthority.FRAME_LINEAGE_PARITY
        }

        assertFalse(bundle.qualified)
        assertEquals(SingularityReleaseClass.SINGULARITY_75, bundle.releaseCap)
        assertEquals(SingularityElite100xStatus.NOT_TESTED, frameLineage.status)
        assertEquals(SingularityReleaseClass.SINGULARITY_75, frameLineage.releaseCap)
        assertTrue(bundle.blockingCodes.isEmpty())
        assertTrue(frameLineage.evidenceReference.contains("frame-lineage:missing"))
    }

    @Test
    fun failedFrameLineageValuesCapElite100xAtSingularity50() {
        val bundle = SingularityElite100xPolicy.evaluate(
            input(
                metadata = baseMetadata() + mapOf(
                    "camera2FrameLineageMatchRatio" to "0.990",
                    "camera2FrameLineageEndpointOmissionCount" to "1"
                )
            )
        )
        val frameLineage = bundle.results.single {
            it.authority == SingularityElite100xAuthority.FRAME_LINEAGE_PARITY
        }

        assertFalse(bundle.qualified)
        assertEquals(SingularityReleaseClass.SINGULARITY_50, bundle.releaseCap)
        assertEquals(SingularityElite100xStatus.FAIL, frameLineage.status)
        assertEquals(SingularityReleaseClass.SINGULARITY_50, frameLineage.releaseCap)
        assertEquals(listOf("ELITE100X_FRAME_LINEAGE_REQUIREMENTS_FAILED"), bundle.blockingCodes)
    }

    @Test
    fun frameLineageFalseClaimBlocksRelease() {
        val bundle = SingularityElite100xPolicy.evaluate(
            input(metadata = baseMetadata() + ("camera2FrameLineageFalseClaimDetected" to "true"))
        )
        val frameLineage = bundle.results.single {
            it.authority == SingularityElite100xAuthority.FRAME_LINEAGE_PARITY
        }

        assertFalse(bundle.qualified)
        assertEquals(SingularityReleaseClass.NO_RELEASE, bundle.releaseCap)
        assertEquals(SingularityElite100xStatus.FAIL, frameLineage.status)
        assertEquals(SingularityReleaseClass.NO_RELEASE, frameLineage.releaseCap)
        assertEquals(listOf("ELITE100X_FRAME_LINEAGE_FALSE_CLAIM"), bundle.blockingCodes)
    }

    @Test
    fun missingV20DenoiseEvidenceCapsElite100xAtSingularity75() {
        val bundle = SingularityElite100xPolicy.evaluate(
            input(stage5EngineRecords = criticalEnginesWithoutMetrics(SingularityStage5EngineId.NOISE_TEXTURE_SEPARATION))
        )
        val zeroGrain = bundle.results.single {
            it.authority == SingularityElite100xAuthority.ZERO_GRAIN_CLARITY
        }

        assertFalse(bundle.qualified)
        assertEquals(SingularityReleaseClass.SINGULARITY_75, bundle.releaseCap)
        assertEquals(SingularityElite100xStatus.NOT_TESTED, zeroGrain.status)
        assertEquals(SingularityReleaseClass.SINGULARITY_75, zeroGrain.releaseCap)
        assertTrue(bundle.blockingCodes.isEmpty())
        assertTrue(zeroGrain.evidenceReference.contains("zero-grain:missing"))
    }

    @Test
    fun unsafeSharpnessEvidenceFailsMicrodetailAtSingularity50() {
        val bundle = SingularityElite100xPolicy.evaluate(
            input(
                stage5EngineRecords = criticalEngines().map { record ->
                    if (record.engineId == SingularityStage5EngineId.TEXTURE_AUTHORITY) {
                        record.copy(
                            measuredMetrics = record.measuredMetrics + mapOf(
                                "sharpnessSafetyScore" to 0.700,
                                "edgeOvershootPercent" to 0.060
                            )
                        )
                    } else {
                        record
                    }
                }
            )
        )
        val microdetail = bundle.results.single {
            it.authority == SingularityElite100xAuthority.MICRODETAIL_HONESTY
        }

        assertFalse(bundle.qualified)
        assertEquals(SingularityReleaseClass.SINGULARITY_50, bundle.releaseCap)
        assertEquals(SingularityElite100xStatus.FAIL, microdetail.status)
        assertEquals(listOf("ELITE100X_V20_MICRODETAIL_UNSAFE"), bundle.blockingCodes)
    }

    @Test
    fun unsafeMotionBlurEvidenceFailsAntiMotionBlurAtSingularity50() {
        val bundle = SingularityElite100xPolicy.evaluate(
            input(
                stage5EngineRecords = criticalEngines().map { record ->
                    if (record.engineId == SingularityStage5EngineId.MOTION_TEMPORAL) {
                        record.copy(
                            measuredMetrics = record.measuredMetrics + mapOf(
                                "antiMotionBlurScore" to 0.620,
                                "motionBlurResidual" to 0.180
                            )
                        )
                    } else {
                        record
                    }
                }
            )
        )
        val motion = bundle.results.single {
            it.authority == SingularityElite100xAuthority.ANTI_MOTION_BLUR
        }

        assertFalse(bundle.qualified)
        assertEquals(SingularityReleaseClass.SINGULARITY_50, bundle.releaseCap)
        assertEquals(SingularityElite100xStatus.FAIL, motion.status)
        assertEquals(listOf("ELITE100X_V20_ANTI_MOTION_BLUR_UNSAFE"), bundle.blockingCodes)
    }

    @Test
    fun v20FalseDenoiseClaimBlocksRelease() {
        val bundle = SingularityElite100xPolicy.evaluate(
            input(metadata = baseMetadata() + ("denoiseFalseClaimDetected" to "true"))
        )
        val zeroGrain = bundle.results.single {
            it.authority == SingularityElite100xAuthority.ZERO_GRAIN_CLARITY
        }

        assertFalse(bundle.qualified)
        assertEquals(SingularityReleaseClass.NO_RELEASE, bundle.releaseCap)
        assertEquals(SingularityElite100xStatus.FAIL, zeroGrain.status)
        assertEquals(listOf("ELITE100X_V20_ZERO_GRAIN_FALSE_CLAIM"), bundle.blockingCodes)
    }

    private fun input(
        transport: SingularityTransportClassification = SingularityTransportClassification.AHB_VULKAN_ZERO_COPY,
        thresholdSetId: String = "panther-4k60-ndr",
        thresholdSetHash: String = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        metadata: Map<String, String> = baseMetadata(),
        stage5EngineRecords: List<SingularityStage5EngineRecord> = criticalEngines()
    ): SingularityElite100xInput =
        SingularityElite100xInput(
            recordingId = "elite",
            outputMode = "NDR10X-SINGULARITY",
            codec = "hevc",
            width = 3840,
            height = 2160,
            fps = 60,
            bitrateBps = 120_000_000,
            measuredOutputBitrateBps = 120_000_000,
            sourceBitDepth = 10,
            encodedBitDepth = 10,
            storageBitDepth = 10,
            rawSensorOriginal = false,
            expectedFrameCount = 120L,
            actualFrameCount = 120L,
            droppedFrames = 0,
            maxAllowedDroppedFrames = 0,
            transportClassification = transport,
            thresholdSetId = thresholdSetId,
            thresholdSetHash = thresholdSetHash,
            qualityGateResults = SingularityQualityGateTestFixtures.allPassingResults(),
            stage5EngineRecords = stage5EngineRecords,
            artifactReferences = mapOf(
                "encodedVsPreEncode" to "artifacts/qg12.json",
                "previewFileComparison" to "artifacts/qg13.json"
            ),
            metadata = metadata
        )

    private fun baseMetadata(): Map<String, String> =
        mapOf(
            "proprietaryProfilePreserved" to "true",
            "singularityStage3ProprietaryProfilePreserved" to "true",
            "vendorHdrCertification" to "false",
            "officialHdr10PlusMetadataWritten" to "false",
            "vendorCertifiedHdr10Plus" to "false",
            "vendorCertifiedDolbyVision" to "false",
            "dolbyRpuWritten" to "false",
            "externalHdrStandardSubstitution" to "false",
            "singularityStage3VendorHdrCertificationClaimed" to "false",
            "singularityEliteMeasuredGateIds" to
                "QG01,QG02,QG03,QG04,QG05,QG06,QG07,QG08,QG09,QG10,QG11,QG12,QG13",
            "singularityEliteArtifactManifestSha256" to
                "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
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

    private fun frameLineageKeys(): Set<String> =
        setOf(
            "camera2FrameLineageCertified",
            "camera2FrameLineageFrameCount",
            "camera2FrameLineageDurationMs",
            "camera2FrameLineageMatchRatio",
            "camera2FrameLineageStaleGenerationCount",
            "camera2FrameLineageNonMonotonicTimestampCount",
            "camera2FrameLineageDuplicateEncoderSubmissionCount",
            "camera2FrameLineageEndpointOmissionCount",
            "camera2FrameLineagePreviewEncoderFrameDifference"
        )

    private fun criticalEnginesWithoutMetrics(engineId: SingularityStage5EngineId): List<SingularityStage5EngineRecord> =
        criticalEngines().map { record ->
            if (record.engineId == engineId) {
                record.copy(measuredMetrics = emptyMap())
            } else {
                record
            }
        }

    private fun criticalEngines(): List<SingularityStage5EngineRecord> =
        SingularityStage5EngineId.entries.map { id ->
            SingularityStage5EngineRecord(
                engineId = id,
                status = SingularityStage5EngineStatus.IMPLEMENTED_AND_EVIDENCED,
                evidenceReference = "test:${id.name}",
                confidence = 0.9,
                measuredMetrics = v20MetricsFor(id)
            )
        }

    private fun v20MetricsFor(id: SingularityStage5EngineId): Map<String, Double> =
        when (id) {
            SingularityStage5EngineId.NOISE_TEXTURE_SEPARATION -> mapOf(
                "zeroGrainClarityScore" to 0.910,
                "grainResidualScore" to 0.018,
                "denoiseSafetyScore" to 0.930
            )
            SingularityStage5EngineId.MOTION_TEMPORAL -> mapOf(
                "antiMotionBlurScore" to 0.920,
                "motionBlurResidual" to 0.055,
                "temporalShimmerPercent" to 0.012
            )
            SingularityStage5EngineId.TEXTURE_AUTHORITY -> mapOf(
                "microdetailHonestyScore" to 0.900,
                "sharpnessSafetyScore" to 0.900,
                "edgeOvershootPercent" to 0.020,
                "fineTextureRetention" to 0.890
            )
            SingularityStage5EngineId.RESOLUTION_HONESTY -> mapOf(
                "textStabilityScore" to 0.930,
                "microdetailHonestyScore" to 0.900
            )
            else -> emptyMap()
        }
}
