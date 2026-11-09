package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingularityFrameGraphRegistryTest {

    @Test
    fun registryContainsContiguous256StagesInSixteenBlocks() {
        val stages = SingularityFrameGraphRegistry.stages

        assertEquals(256, stages.size)
        assertEquals((0 until 256).toList(), stages.map { it.globalStageId })
        assertEquals((0 until 16).toSet(), stages.map { it.blockIndex }.toSet())
        stages.groupBy { it.blockIndex }.forEach { (_, blockStages) ->
            assertEquals(16, blockStages.size)
            assertEquals((0 until 16).toList(), blockStages.map { it.localStageIndex })
        }
        assertEquals(256, stages.map { it.stageName }.toSet().size)
    }

    @Test
    fun registryPreservesAuthoritativeStageNamesAndSmfKeys() {
        assertEquals("frame_identity", SingularityFrameGraphRegistry.stageById(0)?.stageName)
        assertEquals("linear_rgb_reconstruction_output", SingularityFrameGraphRegistry.stageById(47)?.stageName)
        assertEquals("scene_linear_dynamic_range_output", SingularityFrameGraphRegistry.stageById(95)?.stageName)
        assertEquals("output_mode_capability_validation", SingularityFrameGraphRegistry.stageById(224)?.stageName)
        assertEquals("final_release_seal", SingularityFrameGraphRegistry.stageById(255)?.stageName)
        assertEquals(
            "stage_000_frame_identity",
            SingularityFrameGraphRegistry.stageById(0)?.smfRecordKey
        )
        assertEquals(
            "stage_255_final_release_seal",
            SingularityFrameGraphRegistry.stageById(255)?.smfRecordKey
        )
        assertNotNull(SingularityFrameGraphRegistry.stageByName("qg14_qg16_endurance_drop_metadata"))
    }

    @Test
    fun evidenceRecordsCarryRegistryIdentityAndExecutionState() {
        val evidence = SingularityStageEvidence.fromDefinition(
            definition = SingularityFrameGraphRegistry.stageById(230)!!,
            executionBackend = "VULKAN",
            executionState = SingularityStageExecutionState.EXECUTED,
            gpuDurationNs = 1000L,
            evidenceReferences = listOf("vulkan-pass/hash")
        )

        assertEquals(230, evidence.globalStageId)
        assertEquals("final_gpu_output_transform", evidence.stageName)
        assertEquals(SingularityStageExecutionState.EXECUTED, evidence.executionState)
        assertTrue(evidence.qcGateIds.contains(SingularityQualityGateId.QG12))
        assertTrue(evidence.evidenceReferences.contains("vulkan-pass/hash"))
    }

    @Test
    fun fallbackEvidenceRequiresVisibleFallbackReason() {
        val definition = SingularityFrameGraphRegistry.stageById(141)!!

        val failed = runCatching {
            SingularityStageEvidence.fromDefinition(
                definition = definition,
                executionBackend = "VULKAN",
                executionState = SingularityStageExecutionState.FALLBACK_EXECUTED,
                fallbackId = "MULTI_LENS_DISABLED"
            )
        }

        assertTrue(failed.isFailure)
    }
}
