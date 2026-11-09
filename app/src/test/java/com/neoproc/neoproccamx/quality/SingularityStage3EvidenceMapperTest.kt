package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingularityStage3EvidenceMapperTest {

    @Test
    fun mapsEveryExistingPipelineStageInCanonicalOrder() {
        val bundle = SingularityStage3EvidenceMapper.map(
            SingularityStage3PipelineEvidence(
                proprietaryProfilePreserved = true,
                vendorHdrCertificationClaimed = false,
                previewRecordingParityStatus = "PASS",
                stageSignals = mapOf(
                    32 to proven("RAW_PRIVATE_MASTER"),
                    95 to proven("NEO_DYNAMIC_RANGE"),
                    197 to proven("NEO_GAMUT"),
                    216 to proven("VULKAN_PARITY")
                )
            )
        )

        assertEquals((32..223).toList(), bundle.stages.map { it.globalStageId })
        assertEquals(192, bundle.stages.size)
        assertTrue(bundle.proprietaryProfilePreserved)
        assertFalse(bundle.vendorHdrCertificationClaimed)
    }

    @Test
    fun sourcePresenceNeverClaimsRuntimeExecution() {
        val bundle = SingularityStage3EvidenceMapper.map(
            SingularityStage3PipelineEvidence(
                stageSignals = mapOf(
                    116 to SingularityStage3StageSignal(
                        state = SingularityStage3SignalState.SOURCE_AVAILABLE_ONLY,
                        backend = "OPTICAL_FLOW_SOURCE"
                    )
                )
            )
        )
        val stage = bundle.stages.single { it.globalStageId == 116 }

        assertEquals(SingularityStageExecutionState.DISABLED_BY_DEVICE, stage.executionState)
        assertEquals(
            SingularityStage3EvidenceBundle.SOURCE_AVAILABLE_RUNTIME_NOT_PROVEN,
            stage.fallbackId
        )
        assertTrue(stage.fallbackReason.contains("runtime", ignoreCase = true))
    }

    @Test
    fun cleanCreativeStagesDefaultToIdentityPass() {
        val bundle = SingularityStage3EvidenceMapper.map(
            SingularityStage3PipelineEvidence(stageSignals = emptyMap())
        )

        assertTrue(
            bundle.stages
                .filter { it.globalStageId in 217..222 }
                .all { it.executionState == SingularityStageExecutionState.IDENTITY_PASS }
        )
    }

    @Test
    fun parityFailureIsBlockingAndVisible() {
        val bundle = SingularityStage3EvidenceMapper.map(
            SingularityStage3PipelineEvidence(
                previewRecordingParityStatus = "FAIL",
                stageSignals = mapOf(
                    216 to SingularityStage3StageSignal(
                        state = SingularityStage3SignalState.FAILED,
                        backend = "VULKAN_PARITY",
                        fallbackId = "PREVIEW_FILE_TONE_MATCH_FAILED",
                        fallbackReason = "Preview and recording endpoints differ."
                    )
                )
            )
        )
        val stage = bundle.stages.single { it.globalStageId == 216 }

        assertEquals(SingularityStageExecutionState.FAILED_WITH_DOWNGRADE, stage.executionState)
        assertTrue(stage.isBlockingFailure)
        assertTrue(stage.fallbackReason.isNotBlank())
    }

    private fun proven(backend: String): SingularityStage3StageSignal =
        SingularityStage3StageSignal(
            state = SingularityStage3SignalState.PROVEN,
            backend = backend,
            outputResources = listOf("proven")
        )
}
