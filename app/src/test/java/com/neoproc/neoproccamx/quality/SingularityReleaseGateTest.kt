package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingularityReleaseGateTest {

    @Test
    fun completeHardwareEvidenceCanReachSingularity100() {
        val decision = SingularityReleaseGate.evaluate(passingInput())

        assertEquals(SingularityReleaseClass.SINGULARITY_100000, decision.releaseClass)
        assertEquals("SINGULARITY-100000", decision.releaseLabel)
        assertTrue(decision.stageLedgerComplete)
        assertTrue(decision.blockingCodes.isEmpty())
    }

    @Test
    fun cpuCompatibilityTransportIsCappedAtCoreHonest() {
        val decision = SingularityReleaseGate.evaluate(
            passingInput(
                transportClassification = SingularityTransportClassification.CPU_COMPATIBILITY_NON_SINGULARITY
            )
        )

        assertEquals(SingularityReleaseClass.CORE_HONEST, decision.releaseClass)
        assertTrue(decision.reasons.any { it.contains("compatibility", ignoreCase = true) })
    }

    @Test
    fun externalCertificationClaimBlocksRelease() {
        val decision = SingularityReleaseGate.evaluate(
            passingInput(officialDolbyVisionCertificationClaimed = true)
        )

        assertEquals(SingularityReleaseClass.NO_RELEASE, decision.releaseClass)
        assertFalse(decision.releasable)
        assertEquals(listOf("OFFICIAL_DOLBY_VISION_CERTIFICATION_FALSE_CLAIM"), decision.blockingCodes)
    }

    @Test
    fun mandatoryNotTestedGateBlocksSingularity100() {
        val gates = SingularityQualityGateTestFixtures.allPassingResults().toMutableList()
        gates[0] = gates[0].copy(
            status = SingularityQualityGateStatus.NOT_TESTED,
            measuredValue = null,
            evidenceReference = "",
            failureCode = "",
            responsibleStageId = null
        )

        val decision = SingularityReleaseGate.evaluate(
            passingInput(qualityGateResults = gates)
        )

        assertEquals(SingularityReleaseClass.SINGULARITY_75, decision.releaseClass)
        assertTrue(decision.reasons.any { it.contains("not all PASS") })
    }

    @Test
    fun metadataHonestyFailureBlocksRelease() {
        val gates = SingularityQualityGateTestFixtures.allPassingResults().toMutableList()
        gates[15] = gates[15].copy(
            status = SingularityQualityGateStatus.FAIL,
            measuredValue = 1.0,
            evidenceReference = "metadata-sidecar",
            failureCode = "FALSE_VENDOR_CERTIFICATION",
            responsibleStageId = 255
        )

        val decision = SingularityReleaseGate.evaluate(
            passingInput(qualityGateResults = gates)
        )

        assertEquals(SingularityReleaseClass.NO_RELEASE, decision.releaseClass)
        assertEquals(listOf("QG16_METADATA_HONESTY_FAILED"), decision.blockingCodes)
    }

    @Test
    fun incompleteStageLedgerCannotClaimSingularityClass() {
        val decision = SingularityReleaseGate.evaluate(
            passingInput(stageEvidence = SingularityFrameGraphRegistry.evidenceForAll(
                executionBackend = "VULKAN",
                executionState = SingularityStageExecutionState.EXECUTED
            ).dropLast(1))
        )

        assertEquals(SingularityReleaseClass.CORE_HONEST, decision.releaseClass)
        assertFalse(decision.stageLedgerComplete)
    }

    private fun passingInput(
        transportClassification: SingularityTransportClassification =
            SingularityTransportClassification.AHB_VULKAN_ZERO_COPY,
        stageEvidence: List<SingularityStageEvidence> = SingularityFrameGraphRegistry.evidenceForAll(
            executionBackend = "VULKAN",
            executionState = SingularityStageExecutionState.EXECUTED,
            evidenceReference = "validated-stage-ledger"
        ),
        qualityGateResults: List<SingularityQualityGateResult> =
            SingularityQualityGateTestFixtures.allPassingResults(),
        officialDolbyVisionCertificationClaimed: Boolean = false
    ): SingularityReleaseGateInput =
        SingularityReleaseGateInput(
            requestedReleaseClass = SingularityReleaseClass.SINGULARITY_100000,
            transportClassification = transportClassification,
            stageEvidence = stageEvidence,
            qualityGateResults = qualityGateResults,
            outputEvidence = SingularityOutputEvidence(
                outputModeRequested = "NPV10-SINGULARITY-MASTER",
                outputModeActual = "NPV10-SINGULARITY-MASTER",
                finalized = true,
                playable = true,
                recordedFileBytes = 1024L,
                smfSidecarFinalized = true,
                nestedSidecarsFinalized = true,
                shaderRegistryValidated = true,
                modelRegistryValidated = true,
                lutRegistryValidated = true,
                lookRegistryValidated = true,
                largeScreenReviewPassed = true,
                enduranceValidationPassed = true
            ),
            officialDolbyVisionCertificationClaimed = officialDolbyVisionCertificationClaimed
        )
}
