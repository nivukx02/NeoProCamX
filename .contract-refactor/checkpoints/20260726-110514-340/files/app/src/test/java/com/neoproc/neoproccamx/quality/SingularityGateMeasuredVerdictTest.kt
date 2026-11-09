package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * s8-f09 — proves each Singularity verdict traces to a real measurement (not synthesis). A measured
 * metric below the pass threshold PASSes, above the warn threshold FAILs with its failure code, and an
 * absent metric is NOT_TESTED. Unverifiable claims (neural resolution with no claim, false vendor
 * certification) land NOT_APPLICABLE / FAIL — never PASS.
 */
class SingularityGateMeasuredVerdictTest {

    @Test
    fun qg01EdgeOvershootPassesFailsAndIsNotTestedByMeasurement() {
        val pass = gate(SingularityQualityGateId.QG01, mapOf("edgeOvershootPercent" to "0.050"))
        assertEquals(SingularityQualityGateStatus.PASS, pass.status)
        assertEquals(0.050, pass.measuredValue!!, 1e-9)

        val fail = gate(SingularityQualityGateId.QG01, mapOf("edgeOvershootPercent" to "0.150"))
        assertEquals(SingularityQualityGateStatus.FAIL, fail.status)
        assertEquals("EDGE_OVERSHOOT_EXCEEDS_LIMIT", fail.failureCode)

        val absent = gate(SingularityQualityGateId.QG01, emptyMap())
        assertEquals(SingularityQualityGateStatus.NOT_TESTED, absent.status)
    }

    @Test
    fun qg09NeuralResolutionIsNotApplicableWithoutClaimAndNotTestedWhenClaimedButUnmeasured() {
        val noClaim = gate(SingularityQualityGateId.QG09, emptyMap())
        assertEquals(SingularityQualityGateStatus.NOT_APPLICABLE, noClaim.status)

        val claimedButUnmeasured = gate(
            SingularityQualityGateId.QG09,
            mapOf("neuralResolutionClaimed" to "true")
        )
        assertEquals(SingularityQualityGateStatus.NOT_TESTED, claimedButUnmeasured.status)
    }

    @Test
    fun qg16FailsOnFalseVendorClaimAndPassesOnHonestBaseline() {
        val falseClaim = gate(
            SingularityQualityGateId.QG16,
            mapOf(
                "singularityStage2MappedStageCount" to "48",
                "singularityStage3MappedStageCount" to "192",
                "vendorCertifiedDolbyVision" to "true"
            )
        )
        assertEquals(SingularityQualityGateStatus.FAIL, falseClaim.status)
        assertEquals("VENDORCERTIFIEDDOLBYVISION_FALSE_CLAIM", falseClaim.failureCode)

        val honest = gate(
            SingularityQualityGateId.QG16,
            mapOf(
                "singularityStage2MappedStageCount" to "48",
                "singularityStage3MappedStageCount" to "192"
            )
        )
        assertEquals(SingularityQualityGateStatus.PASS, honest.status)
    }

    private fun gate(
        id: SingularityQualityGateId,
        metadata: Map<String, String>
    ): SingularityQualityGateResult =
        SingularityStage4QualityGateAutomation.evaluate(buildInput(metadata))
            .results.first { it.gateId == id }

    private fun buildInput(metadata: Map<String, String>) = SingularityStage4QualityGateInput(
        recordingId = "clip-verdict",
        outputMode = "NDR10+",
        width = 1920,
        height = 1080,
        fps = 30,
        expectedFrameCount = 30,
        actualFrameCount = 30,
        droppedFrames = 0,
        maxAllowedDroppedFrames = 0,
        recordedFileBytes = 42_000_000L,
        finalized = true,
        playable = true,
        previewQualitySignature = "sig",
        recordingQualitySignature = "sig",
        metadata = metadata
    )
}
