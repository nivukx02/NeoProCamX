package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f07 — proves the gate consumes the REAL producer's measured Tier-Omega map (the f06↔f07 seam,
 * from the gate side). The metric values come from EliteVisionMeasurementHarness.measure() over
 * deterministic decoded goldens, projected via toTierOmegaMetadata() and injected as the request's
 * detailMetadata — never hand-typed.
 *
 * Honesty note: this does NOT assert good goldens → overall PASS, because measure() carries several
 * non-pixel fields verbatim from the baseline and the recording gate floors on the stricter
 * Beyond-IMAX profile (not the laxer Imax-strict contract the synthetic goldens target). Asserting a
 * fragile PASS here would invite loosening the gate. Instead it asserts the two unambiguous facts:
 * (1) a real degraded measurement drives MTF50 below the floor → THRESHOLD_FAILED; (2) a real
 * stamped measurement is accepted as proven (never NOT_PROVEN) so it actually reaches the thresholds.
 */
class GateMeasuredTierOmegaTest {

    @Test
    fun degradedRealMeasurementDrivesMtf50BelowFloorAndFails() {
        val measured = EliteVisionMeasurementHarness.toTierOmegaMetadata(
            EliteVisionMeasurementHarness.measure(
                EliteVisionGateTestSupport.blurredEdgeGoldens(),
                EliteVisionGateTestSupport.passingBaseline()
            )
        )
        assertTrue(
            "blurred edge must drive measured MTF50 below 0.38, got ${measured["tierOmegaMtf50CyPerPx"]}",
            measured["tierOmegaMtf50CyPerPx"]!!.toDouble() < 0.38
        )

        val certificate = RecordingQualityEvidencePolicy.evaluate(
            EliteVisionGateTestSupport.validNdrRequest().copy(detailMetadata = measured)
        )
        assertTrue(certificate.status == RecordingQualityEvidenceStatus.FAIL)
        // Provenance is genuine (real-pixel stamp), so the failure is a THRESHOLD failure, not NOT_PROVEN.
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.TIER_OMEGA_METRIC_THRESHOLD_FAILED })
        assertTrue(certificate.failures.none { it.code == RecordingQualityGateCode.TIER_OMEGA_METRICS_NOT_PROVEN })
    }

    @Test
    fun goodRealMeasurementProvenanceIsAcceptedAndReachesThresholds() {
        val measured = EliteVisionMeasurementHarness.toTierOmegaMetadata(
            EliteVisionMeasurementHarness.measure(
                EliteVisionGateTestSupport.goodGoldens(),
                EliteVisionGateTestSupport.passingBaseline()
            )
        )
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            EliteVisionGateTestSupport.validNdrRequest().copy(detailMetadata = measured)
        )
        // A genuine real-pixel stamp + complete key set is accepted: the gate proceeds to threshold
        // checks rather than short-circuiting to NOT_PROVEN. (Overall PASS depends on the stricter
        // Beyond-IMAX floors vs the synthetic goldens — intentionally not asserted here.)
        assertTrue(
            "a real stamped measurement must not be rejected as unproven",
            certificate.failures.none { it.code == RecordingQualityGateCode.TIER_OMEGA_METRICS_NOT_PROVEN }
        )
    }
}
