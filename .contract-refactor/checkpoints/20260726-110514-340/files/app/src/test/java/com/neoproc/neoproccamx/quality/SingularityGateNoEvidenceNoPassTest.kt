package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f09 — no-evidence ⇒ no-PASS for the real Singularity verdict producer. Feeding empty evidence
 * (blank metadata, zeroed scalars, not finalized) to [SingularityStage4QualityGateAutomation] must
 * never manufacture a PASS: measurable gates fall to NOT_TESTED, the unverifiable encode/metadata
 * gates FAIL on absent evidence, and the neural-resolution gate is NOT_APPLICABLE with no claim.
 */
class SingularityGateNoEvidenceNoPassTest {

    @Test
    fun emptyEvidenceProducesNoSynthesizedPass() {
        val bundle = SingularityStage4QualityGateAutomation.evaluate(emptyEvidenceInput())
        assertTrue(
            "no gate may PASS without measured evidence: ${bundle.results.filter { it.status == SingularityQualityGateStatus.PASS }.map { it.gateId }}",
            bundle.results.none { it.status == SingularityQualityGateStatus.PASS }
        )
        assertNotEquals("READY_FOR_RELEASE_GATE_INPUT", bundle.releaseReadiness)
    }

    @Test
    fun emptyEvidenceGatesLandHonestNonPassStatuses() {
        val bundle = SingularityStage4QualityGateAutomation.evaluate(emptyEvidenceInput())
        // Encode survival fails without a finalized non-empty file; metadata-honesty fails without
        // the stage2/stage3 evidence; neural-resolution is not-applicable with no claim.
        assertEquals(SingularityQualityGateStatus.FAIL, statusOf(bundle, SingularityQualityGateId.QG12))
        assertEquals(SingularityQualityGateStatus.FAIL, statusOf(bundle, SingularityQualityGateId.QG16))
        assertEquals(SingularityQualityGateStatus.NOT_APPLICABLE, statusOf(bundle, SingularityQualityGateId.QG09))
        // The purely-measured gates have no artifact, so they are NOT_TESTED (never PASS).
        assertEquals(SingularityQualityGateStatus.NOT_TESTED, statusOf(bundle, SingularityQualityGateId.QG01))
    }

    private fun statusOf(
        bundle: SingularityStage4QualityGateBundle,
        id: SingularityQualityGateId
    ): SingularityQualityGateStatus = bundle.results.first { it.gateId == id }.status

    private fun emptyEvidenceInput() = SingularityStage4QualityGateInput(
        recordingId = "",
        outputMode = "",
        width = 0,
        height = 0,
        fps = 0,
        expectedFrameCount = 0L,
        actualFrameCount = 0L,
        droppedFrames = 0,
        maxAllowedDroppedFrames = 0,
        recordedFileBytes = 0L,
        finalized = false,
        playable = false,
        previewQualitySignature = "",
        recordingQualitySignature = ""
    )
}
