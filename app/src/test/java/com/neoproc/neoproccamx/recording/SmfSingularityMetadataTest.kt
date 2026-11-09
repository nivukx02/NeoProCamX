package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.SingularityFrameGraphRegistry
import com.neoproc.neoproccamx.quality.SingularityOutputEvidence
import com.neoproc.neoproccamx.quality.SingularityQualityGateResult
import com.neoproc.neoproccamx.quality.SingularityReleaseClass
import com.neoproc.neoproccamx.quality.SingularityReleaseGate
import com.neoproc.neoproccamx.quality.SingularityReleaseGateInput
import com.neoproc.neoproccamx.quality.SingularityStage4QualityGateAutomation
import com.neoproc.neoproccamx.quality.SingularityStage5HighEndEnginePolicy
import com.neoproc.neoproccamx.quality.SingularityStageEvidence
import com.neoproc.neoproccamx.quality.SingularityStageExecutionState
import com.neoproc.neoproccamx.quality.SingularityQualityGateTestFixtures
import com.neoproc.neoproccamx.quality.SingularityTransportClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmfSingularityMetadataTest {

    @Test
    fun envelopeLocksNamespaceStageCountAndExternalCertificationFlagsFalse() {
        val input = passingInput()
        val decision = SingularityReleaseGate.evaluate(input)
        val metadata = SmfSingularityMetadata.fromReleaseDecision(
            input = input,
            decision = decision,
            thresholdSetId = "foundation-generic-1080p30",
            thresholdSetHash = "hash-required-at-release",
            nestedMetadataReferences = listOf(
                SmfSingularityNestedMetadataReference(
                    schemaId = "NPV10 container version",
                    version = "NPV10-1",
                    evidenceReference = "npv10-session-hash",
                    finalized = true
                )
            ),
            evidenceReferences = mapOf("vulkanTransportEvidence" to "AHB_VULKAN_ZERO_COPY")
        )
        val json = metadata.toJsonText()

        assertEquals("SMF-SINGULARITY-1", metadata.formatVersion)
        assertEquals("com.neoproc.neoproccamx", metadata.applicationId)
        assertEquals(256, metadata.frameGraphStages)
        assertEquals("SINGULARITY-100000", metadata.releaseLabel)
        assertFalse(metadata.officialImaxCertificationClaimed)
        assertFalse(metadata.officialDolbyVisionCertificationClaimed)
        assertFalse(metadata.officialHdr10PlusCertificationClaimed)
        assertFalse(metadata.officialHdr10CertificationClaimed)
        assertTrue(json.contains("\"officialImaxCertificationClaimed\": false"))
        assertTrue(json.contains("\"officialDolbyVisionCertificationClaimed\": false"))
        assertTrue(json.contains("\"officialHdr10PlusCertificationClaimed\": false"))
        assertTrue(json.contains("\"officialHdr10CertificationClaimed\": false"))
        assertTrue(json.contains("\"frameGraphStages\": 256"))
        assertTrue(json.contains("\"stageName\": \"frame_identity\""))
        assertTrue(json.contains("\"stageName\": \"final_release_seal\""))
    }

    @Test
    fun evidenceBridgeWrapsExistingNeoProfileWithoutVendorClaims() {
        val metadata = NeoProfileTruthMetadata(
            selectedProfile = "NDR10+",
            activeTransport = "BT.2100_PQ_MAIN10",
            transfer = "ST2084/PQ",
            primaries = "BT.2020",
            matrix = "BT.2020-NCL",
            encodedBitDepth = 10,
            internalMathBitDepth = 12,
            dynamicMetadataWritten = false,
            npv10MasterAvailable = false,
            proprietaryProfilePreserved = true,
            neoHdrNoVendorDependency = true
        )

        val bundle = SmfSingularityEvidenceBridge.fromExistingEvidence(
            neoProfileTruthMetadata = metadata,
            vulkanEvidenceReference = "vulkan-import-stats"
        )

        assertEquals(1, bundle.nestedMetadataReferences.size)
        assertEquals("NeoProfileTruthMetadata", bundle.nestedMetadataReferences.single().schemaId)
        assertTrue(bundle.nestedMetadataReferences.single().finalized)
        assertEquals("true", bundle.evidenceReferences.getValue("neoProfileNoVendorCertification"))
        assertEquals("vulkan-import-stats", bundle.evidenceReferences.getValue("vulkanTransportEvidence"))
    }

    @Test
    fun evidenceBridgePromotesStage2CertificateMetadataToNestedReference() {
        val certificate = RecordingQualityEvidenceCertificate(
            status = RecordingQualityEvidenceStatus.PASS,
            failures = emptyList(),
            request = recordingQualityRequest(),
            metadata = mapOf(
                "singularityStage2MappedStageCount" to "48",
                "singularityStage2CaptureStageCount" to "32",
                "singularityStage2OutputStageCount" to "16",
                "singularityStage2TransportClassification" to "AHB_GPU_COMPATIBILITY"
            )
        )

        val bundle = SmfSingularityEvidenceBridge.fromExistingEvidence(
            recordingQualityCertificate = certificate
        )

        assertTrue(bundle.nestedMetadataReferences.any { reference ->
            reference.schemaId == "SingularityStage2Evidence" &&
                reference.version == "SINGULARITY_STAGE2_CAPTURE_TRANSPORT_V1" &&
                reference.finalized
        })
        assertEquals(
            "AHB_GPU_COMPATIBILITY",
            bundle.evidenceReferences.getValue("singularityStage2TransportClassification")
        )
    }

    @Test
    fun evidenceBridgePromotesStage3CertificateMetadataToNestedReference() {
        val stageIds = (32..223).joinToString(separator = ",") { it.toString().padStart(3, '0') }
        val certificate = RecordingQualityEvidenceCertificate(
            status = RecordingQualityEvidenceStatus.PASS,
            failures = emptyList(),
            request = recordingQualityRequest(),
            metadata = mapOf(
                "singularityStage3MappedStageCount" to "192",
                "singularityStage3StageIds" to stageIds,
                "singularityStage3ExecutionSummary" to "EXECUTED=80 IDENTITY_PASS=6 DISABLED_BY_DEVICE=106"
            )
        )

        val bundle = SmfSingularityEvidenceBridge.fromExistingEvidence(
            recordingQualityCertificate = certificate
        )

        assertTrue(bundle.nestedMetadataReferences.any { reference ->
            reference.schemaId == "SingularityStage3Evidence" &&
                reference.version == "SINGULARITY_STAGE3_EXISTING_PIPELINE_MAP_V1" &&
                reference.finalized
        })
        assertEquals(
            "192",
            bundle.evidenceReferences.getValue("singularityStage3MappedStageCount")
        )
    }

    @Test
    fun evidenceBridgePromotesStage4QualityGateMetadataToNestedReference() {
        val gateIds = (1..16).joinToString(separator = ",") { id -> "QG${id.toString().padStart(2, '0')}" }
        val certificate = RecordingQualityEvidenceCertificate(
            status = RecordingQualityEvidenceStatus.PASS,
            failures = emptyList(),
            request = recordingQualityRequest().copy(recordingId = "stage4"),
            metadata = mapOf(
                "singularityStage4GateCount" to "16",
                "singularityStage4GateIds" to gateIds,
                "singularityStage4GateSummary" to "PASS=16 WARN=0 FAIL=0 NOT_APPLICABLE=0 NOT_TESTED=0",
                "singularityStage4ArtifactRelativePath" to "singularity_quality/stage4/qg-results.json"
            )
        )

        val bundle = SmfSingularityEvidenceBridge.fromExistingEvidence(
            recordingQualityCertificate = certificate
        )

        assertTrue(bundle.nestedMetadataReferences.any { reference ->
            reference.schemaId == "SingularityStage4QualityGates" &&
                reference.version == SingularityStage4QualityGateAutomation.VERSION &&
                reference.finalized
        })
        assertEquals(
            "16",
            bundle.evidenceReferences.getValue("singularityStage4GateCount")
        )
    }

    @Test
    fun evidenceBridgePromotesStage5EngineMetadataToNestedReference() {
        val engineIds = listOf(
            "RAW_RECONSTRUCTION",
            "OPTICAL_RECOVERY",
            "EXPOSURE_DYNAMIC_RANGE",
            "NOISE_TEXTURE_SEPARATION",
            "MOTION_TEMPORAL",
            "MULTI_LENS_FUSION",
            "SUBJECT_DEPTH",
            "TEXTURE_AUTHORITY",
            "RESOLUTION_HONESTY",
            "COLOR_AUTHORITY",
            "TONE_STABILITY",
            "QC_AUTOMATION"
        ).joinToString(separator = ",")
        val certificate = RecordingQualityEvidenceCertificate(
            status = RecordingQualityEvidenceStatus.PASS,
            failures = emptyList(),
            request = recordingQualityRequest().copy(recordingId = "stage5"),
            metadata = mapOf(
                "singularityStage5EngineCount" to "12",
                "singularityStage5EngineIds" to engineIds,
                "singularityStage5StatusSummary" to "IMPLEMENTED_AND_EVIDENCED=8 DISABLED_BY_DEVICE=2 FALLBACK_EXECUTED=2",
                "singularityStage5ArtifactRelativePath" to "singularity_quality/stage5/stage5-engine-report.json"
            )
        )

        val bundle = SmfSingularityEvidenceBridge.fromExistingEvidence(
            recordingQualityCertificate = certificate
        )

        assertTrue(bundle.nestedMetadataReferences.any { reference ->
            reference.schemaId == "SingularityStage5HighEndEngines" &&
                reference.version == SingularityStage5HighEndEnginePolicy.VERSION &&
                reference.finalized
        })
        assertEquals(
            "12",
            bundle.evidenceReferences.getValue("singularityStage5EngineCount")
        )
    }

    private fun passingInput(
        stageEvidence: List<SingularityStageEvidence> = SingularityFrameGraphRegistry.evidenceForAll(
            executionBackend = "VULKAN",
            executionState = SingularityStageExecutionState.EXECUTED,
            evidenceReference = "validated-stage-ledger"
        ),
        qualityGateResults: List<SingularityQualityGateResult> =
            SingularityQualityGateTestFixtures.allPassingResults()
    ): SingularityReleaseGateInput =
        SingularityReleaseGateInput(
            requestedReleaseClass = SingularityReleaseClass.SINGULARITY_100000,
            transportClassification = SingularityTransportClassification.AHB_VULKAN_ZERO_COPY,
            stageEvidence = stageEvidence,
            qualityGateResults = qualityGateResults,
            outputEvidence = SingularityOutputEvidence(
                outputModeRequested = "NPV10-SINGULARITY-MASTER",
                outputModeActual = "NPV10-SINGULARITY-MASTER",
                finalized = true,
                playable = true,
                recordedFileBytes = 4096L,
                smfSidecarFinalized = true,
                nestedSidecarsFinalized = true,
                shaderRegistryValidated = true,
                modelRegistryValidated = true,
                lutRegistryValidated = true,
                lookRegistryValidated = true,
                largeScreenReviewPassed = true,
                enduranceValidationPassed = true
            )
        )

    private fun recordingQualityRequest(): RecordingQualityEvidenceRequest =
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
            width = 1920,
            height = 1080,
            fps = 30,
            bitrateBps = 40_000_000,
            selectedResolution = "1920x1080",
            actualResolution = "1920x1080",
            normalizedHdrVersion = "NDR10+",
            normalizedLogVersion = "OFF",
            rawSensorOriginal = false,
            captureTruthSignature = "capture",
            previewQualitySignature = "quality",
            recordingQualitySignature = "quality",
            expectedFrameCount = 30L,
            actualFrameCount = 30L,
            droppedFrames = 0,
            maxAllowedDroppedFrames = 0,
            probe = null,
            ffprobeValidated = false,
            mediaInfoValidated = false,
            deviceLogValidated = false,
            recordingId = "stage2",
            recordedFileName = "stage2.mp4",
            recordedFileBytes = 4096L
        )
}
