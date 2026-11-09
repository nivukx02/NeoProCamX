package com.neoproc.neoproccamx.recording

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f06 / CP-Q8 — proves the gate verdict reflects REALITY: a degraded decoded frame, measured by
 * the real-pixel harness, drives the tierOmegaMtf50CyPerPx evidence key below the Beyond-IMAX floor
 * (0.38 cy/px) and the recording quality certificate FAILs specifically on MTF50.
 *
 * The MTF50 below-floor value is a REAL measurement of a blurred slanted edge (not a hand-typed
 * fixture). To isolate causation, all OTHER tierOmega keys are held at their proven-passing values,
 * so the only threshold failure is MTF50.
 */
class GateConsumesMeasuredMtf50Test {

    @After
    fun reset() {
        EliteVisionMeasurementFlag.set(false)
    }

    @Test
    fun degradedDecodedFrameDrivesMtf50BelowFloorAndGateFails() {
        EliteVisionMeasurementFlag.set(true)

        val provider = EliteVisionByteBufferFrameProvider(
            frames = EliteVisionGateTestSupport.blurredEdgeGoldens(),
            canMeasureAllRequiredCriteria = true
        )
        val measured = EliteVisionProductionMeasurement.measuredTierOmegaMetadata(provider)
        val realMtf50 = measured["tierOmegaMtf50CyPerPx"]!!.toDouble()
        assertTrue(
            "a blurred edge must drive the measured MTF50 below the Beyond-IMAX floor 0.38, got $realMtf50",
            realMtf50 < 0.38
        )

        // Hold every other key passing; substitute the REAL measured (below-floor) MTF50 only.
        val detail = EliteVisionGateTestSupport.tierOmegaPassingMetadata() +
            ("tierOmegaMtf50CyPerPx" to measured["tierOmegaMtf50CyPerPx"]!!)

        val certificate = RecordingQualityEvidencePolicy.evaluate(
            EliteVisionGateTestSupport.validNdrRequest().copy(detailMetadata = detail)
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        val thresholdFailure = certificate.failures.firstOrNull {
            it.code == RecordingQualityGateCode.TIER_OMEGA_METRIC_THRESHOLD_FAILED
        }
        assertNotNull("expected a Tier-Omega threshold failure", thresholdFailure)
        assertTrue(
            "the sole threshold failure must name MTF50, got: ${thresholdFailure!!.message}",
            thresholdFailure.message.contains("MTF50")
        )
    }
}
