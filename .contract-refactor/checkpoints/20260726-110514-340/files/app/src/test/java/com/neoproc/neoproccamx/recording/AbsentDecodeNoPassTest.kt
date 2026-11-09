package com.neoproc.neoproccamx.recording

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f06 / CP-Q8 — proves an absent / undecodable master can NEVER synthesize a PASS. The
 * honest-dormant device-gated decoder ([EliteVisionMediaCodecDecodeProvider]) supplies no frames, so
 * the producer emits only the provenance marker NOT_AVAILABLE (no metric keys, no real-pixel stamp),
 * and the gate lands TIER_OMEGA_METRICS_NOT_PROVEN before any threshold check — never PASS.
 */
class AbsentDecodeNoPassTest {

    @After
    fun reset() {
        EliteVisionMeasurementFlag.set(false)
    }

    @Test
    fun absentDecodeYieldsUnprovenNeverPass() {
        EliteVisionMeasurementFlag.set(true)

        val metadata = EliteVisionProductionMeasurement.measuredTierOmegaMetadata(
            EliteVisionMediaCodecDecodeProvider
        )

        // Null frames force literal NOT_AVAILABLE provenance (the device-gated label never surfaces);
        // no measured metric key and no real-pixel source stamp are emitted.
        assertEquals(
            mapOf(EliteVisionProductionMeasurement.PRODUCER_KEY to "NOT_AVAILABLE"),
            metadata
        )
        assertFalse(metadata.containsKey(EliteVisionMeasurementHarness.TIER_OMEGA_METRICS_SOURCE_KEY))

        // The lone tierOmega-prefixed producer key activates the gate's Tier-Omega branch, which lands
        // NOT_PROVEN (no provenance stamp) and returns before threshold-checking any value.
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            EliteVisionGateTestSupport.validNdrRequest().copy(detailMetadata = metadata)
        )
        assertTrue(
            "absent decode must never PASS",
            certificate.status != RecordingQualityEvidenceStatus.PASS
        )
        assertTrue(
            certificate.failures.any {
                it.code == RecordingQualityGateCode.TIER_OMEGA_METRICS_NOT_PROVEN
            }
        )
    }
}
