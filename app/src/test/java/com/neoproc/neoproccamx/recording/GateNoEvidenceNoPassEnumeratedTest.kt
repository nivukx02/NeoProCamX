package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f07 — the headline no-evidence ⇒ no-PASS lock for the Tier-Omega group. For EVERY one of the 18
 * required metric keys, omitting exactly that key from an otherwise-passing stamped request must make
 * the certificate non-PASS via TIER_OMEGA_METRICS_NOT_PROVEN — and, because the missing-key check
 * returns before threshold-checking, it must NOT produce a TIER_OMEGA_METRIC_THRESHOLD_FAILED. Also
 * pins that removing the real-pixel provenance stamp lands NOT_PROVEN before any threshold check.
 *
 * This is a characterization lock over already-enforced behaviour (zero production change): a future
 * loosening that lets a partial/unstamped evidence map PASS trips CI.
 */
class GateNoEvidenceNoPassEnumeratedTest {

    @Test
    fun everyRequiredMetricKeyOmittedCannotPass() {
        val passing = EliteVisionGateTestSupport.tierOmegaPassingMetadata()
        // Baseline sanity: the complete stamped map PASSes, so each omission below is the only change.
        assertEquals(
            "the complete stamped evidence map must PASS as the baseline",
            RecordingQualityEvidenceStatus.PASS,
            RecordingQualityEvidencePolicy.evaluate(
                EliteVisionGateTestSupport.validNdrRequest().copy(detailMetadata = passing)
            ).status
        )
        val requiredKeys = passing.keys.filterTo(linkedSetOf()) { key ->
            key.startsWith("tierOmega") &&
                key != EliteVisionMeasurementHarness.TIER_OMEGA_METRICS_SOURCE_KEY
        }
        assertTrue("expected 18 required metric keys, got ${requiredKeys.size}", requiredKeys.size == 18)

        requiredKeys.forEach { omitted ->
            val certificate = RecordingQualityEvidencePolicy.evaluate(
                EliteVisionGateTestSupport.validNdrRequest().copy(detailMetadata = passing - omitted)
            )
            assertNotEquals(
                "omitting required key '$omitted' must not PASS",
                RecordingQualityEvidenceStatus.PASS,
                certificate.status
            )
            assertTrue(
                "omitting '$omitted' must land TIER_OMEGA_METRICS_NOT_PROVEN",
                certificate.failures.any { it.code == RecordingQualityGateCode.TIER_OMEGA_METRICS_NOT_PROVEN }
            )
            assertTrue(
                "a missing key must return before threshold-checking ('$omitted')",
                certificate.failures.none { it.code == RecordingQualityGateCode.TIER_OMEGA_METRIC_THRESHOLD_FAILED }
            )
        }
    }

    @Test
    fun missingProvenanceStampReturnsBeforeThreshold() {
        val unstamped = EliteVisionGateTestSupport.tierOmegaPassingMetadata() -
            EliteVisionMeasurementHarness.TIER_OMEGA_METRICS_SOURCE_KEY
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            EliteVisionGateTestSupport.validNdrRequest().copy(detailMetadata = unstamped)
        )
        assertNotEquals(RecordingQualityEvidenceStatus.PASS, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.TIER_OMEGA_METRICS_NOT_PROVEN })
        assertTrue(certificate.failures.none { it.code == RecordingQualityGateCode.TIER_OMEGA_METRIC_THRESHOLD_FAILED })
    }

    @Test
    fun foreignProvenanceStampCannotPass() {
        val foreign = EliteVisionGateTestSupport.tierOmegaPassingMetadata() +
            (EliteVisionMeasurementHarness.TIER_OMEGA_METRICS_SOURCE_KEY to "HAND_BUILT_FIXTURE")
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            EliteVisionGateTestSupport.validNdrRequest().copy(detailMetadata = foreign)
        )
        assertNotEquals(RecordingQualityEvidenceStatus.PASS, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.TIER_OMEGA_METRICS_NOT_PROVEN })
    }
}
