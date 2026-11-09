package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingularityStage5QcEvidenceMapperTest {

    @Test
    fun mapsQcMetadataStagesContiguously() {
        val bundle = SingularityStage5QcEvidenceMapper.map(input())

        assertEquals((240..255).toList(), bundle.stages.map { it.globalStageId })
        assertEquals(16, bundle.stages.size)
        assertTrue(bundle.toMetadata().getValue("singularityStage5QcStageIds").startsWith("240,241"))
        assertEquals(SingularityStageExecutionState.EXECUTED, bundle.stages.single { it.globalStageId == 253 }.executionState)
    }

    @Test
    fun qg16FailureBlocksFinalSealVisibly() {
        val gates = SingularityQualityGateTestFixtures.allPassingResults().map {
            if (it.gateId == SingularityQualityGateId.QG16) {
                it.copy(
                    status = SingularityQualityGateStatus.FAIL,
                    measuredValue = 0.0,
                    evidenceReference = "qg16",
                    failureCode = "FALSE_VENDOR_CERTIFICATION",
                    responsibleStageId = 255
                )
            } else {
                it
            }
        }
        val bundle = SingularityStage5QcEvidenceMapper.map(
            input(qualityGateResults = gates, metadataFalseClaimDetected = true)
        )

        assertEquals(
            SingularityStageExecutionState.FAILED_WITH_DOWNGRADE,
            bundle.stages.single { it.globalStageId == 255 }.executionState
        )
        assertTrue(bundle.toMetadata().getValue("singularityStage5QcFailureStageIds").contains("255"))
    }

    @Test
    fun notTestedGateProducesFallbackQcStageNotExecutedClaim() {
        val gates = SingularityQualityGateTestFixtures.allPassingResults().map {
            if (it.gateId == SingularityQualityGateId.QG12) {
                it.copy(
                    status = SingularityQualityGateStatus.NOT_TESTED,
                    measuredValue = null,
                    evidenceReference = "qg12-not-tested",
                    failureCode = "",
                    responsibleStageId = 251
                )
            } else {
                it
            }
        }
        val bundle = SingularityStage5QcEvidenceMapper.map(input(qualityGateResults = gates))
        val encodePreviewStage = bundle.stages.single { it.globalStageId == 251 }

        assertEquals(SingularityStageExecutionState.FALLBACK_EXECUTED, encodePreviewStage.executionState)
        assertEquals("QUALITY_GATE_NOT_ALL_PASS", encodePreviewStage.fallbackId)
    }

    private fun input(
        qualityGateResults: List<SingularityQualityGateResult> =
            SingularityQualityGateTestFixtures.allPassingResults(),
        metadataFalseClaimDetected: Boolean = false
    ): SingularityStage5QcEvidenceInput =
        SingularityStage5QcEvidenceInput(
            recordingId = "stage5-qc",
            stage2Mapped = true,
            stage3Mapped = true,
            stage5EnginesMapped = true,
            transportClassification = SingularityTransportClassification.AHB_VULKAN_ZERO_COPY,
            qualityGateResults = qualityGateResults,
            metadataFalseClaimDetected = metadataFalseClaimDetected,
            sidecarPlanReady = true,
            outputFinalized = true,
            outputPlayable = true
        )
}
