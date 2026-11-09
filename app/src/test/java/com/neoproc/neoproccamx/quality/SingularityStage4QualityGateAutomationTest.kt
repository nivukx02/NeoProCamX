package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingularityStage4QualityGateAutomationTest {

    @Test
    fun explicitArtifactsCanPassAllCanonicalGates() {
        val bundle = SingularityStage4QualityGateAutomation.evaluate(
            input(metadata = baseMetadata() + passingMetricMetadata())
        )

        assertEquals(SingularityQualityGateId.entries.toSet(), bundle.results.map { it.gateId }.toSet())
        assertEquals(16, bundle.results.size)
        assertTrue(bundle.results.all { it.status == SingularityQualityGateStatus.PASS })
        assertEquals("READY_FOR_RELEASE_GATE_INPUT", bundle.releaseReadiness)
        assertTrue(bundle.toJsonText().contains("\"qualityGates\""))
        assertTrue(bundle.toMetadata().getValue("singularityStage4GateSummary").contains("PASS=16"))
    }

    @Test
    fun missingEncodedAndPreviewComparisonArtifactsStayNotTested() {
        val metadata = baseMetadata() + passingMetricMetadata() -
            "stage4EncodedVsPreEncodeLoss" -
            "stage4PreviewFileDelta"

        val bundle = SingularityStage4QualityGateAutomation.evaluate(input(metadata = metadata))
        val qg12 = bundle.results.single { it.gateId == SingularityQualityGateId.QG12 }
        val qg13 = bundle.results.single { it.gateId == SingularityQualityGateId.QG13 }

        assertEquals(SingularityQualityGateStatus.NOT_TESTED, qg12.status)
        assertEquals("QG12_ENCODED_VS_PRE_ENCODE_ARTIFACT_MISSING", qg12.fallbackDecision)
        assertEquals(SingularityQualityGateStatus.NOT_TESTED, qg13.status)
        assertEquals("QG13_PREVIEW_FILE_COMPARISON_ARTIFACT_MISSING", qg13.fallbackDecision)
        assertEquals("BLOCKS_SINGULARITY_100000_UNTIL_TESTED", bundle.releaseReadiness)
    }

    @Test
    fun flatMasterPreviewMatchIsDeferredUntilFinalDevelopedOutputExists() {
        val metadata = baseMetadata() + passingMetricMetadata() + mapOf(
            "previewRecordingParityStatus" to "DEFERRED_DEVELOP",
            "stage3PreviewRecordingParityStatus" to "DEFERRED_DEVELOP"
        )

        val bundle = SingularityStage4QualityGateAutomation.evaluate(
            input(metadata = metadata).copy(
                previewQualitySignature = "preview-developed-quality",
                recordingQualitySignature = "private-flat-master-quality"
            )
        )
        val qg13 = bundle.results.single { it.gateId == SingularityQualityGateId.QG13 }

        assertEquals(SingularityQualityGateStatus.NOT_TESTED, qg13.status)
        assertEquals("QG13_DEFERRED_TO_OFFLINE_DEVELOP_OUTPUT", qg13.fallbackDecision)
        assertTrue(qg13.blocksHighestClass)
    }

    @Test
    fun inactiveNeuralResolutionIsNotClaimedAsPassed() {
        val metadata = baseMetadata() + passingMetricMetadata() -
            "stage4NeuralResolutionClaimed" -
            "stage4HallucinationRisk"

        val bundle = SingularityStage4QualityGateAutomation.evaluate(input(metadata = metadata))
        val qg09 = bundle.results.single { it.gateId == SingularityQualityGateId.QG09 }

        assertEquals(SingularityQualityGateStatus.NOT_APPLICABLE, qg09.status)
        assertEquals("NEURAL_RESOLUTION_DISABLED_NO_CLAIM", qg09.fallbackDecision)
        assertTrue(qg09.blocksHighestClass)
    }

    @Test
    fun vendorCertificationClaimFailsMetadataHonesty() {
        val bundle = SingularityStage4QualityGateAutomation.evaluate(
            input(metadata = baseMetadata() + passingMetricMetadata() + mapOf("vendorCertifiedDolbyVision" to "true"))
        )
        val qg16 = bundle.results.single { it.gateId == SingularityQualityGateId.QG16 }

        assertEquals(SingularityQualityGateStatus.FAIL, qg16.status)
        assertEquals("VENDORCERTIFIEDDOLBYVISION_FALSE_CLAIM", qg16.failureCode)
        assertEquals("NO_RELEASE_METADATA_FAILURE", bundle.releaseReadiness)
    }

    @Test
    fun droppedFrameGateRequiresCadenceEvidence() {
        val bundle = SingularityStage4QualityGateAutomation.evaluate(
            input(metadata = baseMetadata() + passingMetricMetadata()).copy(
                expectedFrameCount = 0L,
                actualFrameCount = 0L,
                droppedFrames = 0
            )
        )
        val qg15 = bundle.results.single { it.gateId == SingularityQualityGateId.QG15 }

        assertEquals(SingularityQualityGateStatus.NOT_TESTED, qg15.status)
        assertEquals("QG15_CADENCE_EVIDENCE_MISSING", qg15.fallbackDecision)
    }

    private fun input(metadata: Map<String, String>): SingularityStage4QualityGateInput =
        SingularityStage4QualityGateInput(
            recordingId = "stage4",
            outputMode = "NDR10+",
            width = 3840,
            height = 2160,
            fps = 60,
            expectedFrameCount = 120L,
            actualFrameCount = 120L,
            droppedFrames = 0,
            maxAllowedDroppedFrames = 0,
            recordedFileBytes = 4096L,
            finalized = true,
            playable = true,
            previewQualitySignature = "parity",
            recordingQualitySignature = "parity",
            metadata = metadata
        )

    private fun baseMetadata(): Map<String, String> =
        mapOf(
            "singularityStage2MappedStageCount" to "48",
            "singularityStage3MappedStageCount" to "192",
            "proprietaryProfilePreserved" to "true",
            "singularityStage3ProprietaryProfilePreserved" to "true",
            "vendorHdrCertification" to "false",
            "officialHdr10PlusMetadataWritten" to "false",
            "vendorCertifiedHdr10Plus" to "false",
            "vendorCertifiedDolbyVision" to "false",
            "dolbyRpuWritten" to "false",
            "externalHdrStandardSubstitution" to "false",
            "singularityStage3VendorHdrCertificationClaimed" to "false"
        )

    private fun passingMetricMetadata(): Map<String, String> =
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
            "stage4ValidatedEnduranceMinutes" to "12.0",
            "stage4EncodedVsPreEncodeLossArtifact" to "artifacts/qg12.json",
            "stage4PreviewFileComparisonArtifact" to "artifacts/qg13.json",
            "stage4SceneMatrixArtifact" to "artifacts/scene-matrix.json",
            "stage4EnduranceArtifact" to "artifacts/endurance.json"
        )
}
