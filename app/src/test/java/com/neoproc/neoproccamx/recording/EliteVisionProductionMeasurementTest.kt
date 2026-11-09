package com.neoproc.neoproccamx.recording

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s6-f08 — proves the production measurement caller closes the loop honestly: OFF is a no-op, ON
 * with a full producer emits the measured tierOmega* keys, ON without a real producer emits ONLY a
 * provenance marker (→ gate NOT_PROVEN, never a synthesized PASS / never a fixture metric).
 */
class EliteVisionProductionMeasurementTest {

    private val requiredKeys = listOf(
        "tierOmegaLumaSnrBaseDb", "tierOmegaLumaSnrHighIsoDb",
        "tierOmegaChromaNoiseBaseStd", "tierOmegaChromaNoiseHighIsoStd",
        "tierOmegaTemporalFlickerPercent", "tierOmegaMtf50CyPerPx",
        "tierOmegaOvershootPercent", "tierOmegaDetailRetentionRaw",
        "tierOmegaDetailRetentionHighIsoRaw", "tierOmegaDeltaE2000Mean",
        "tierOmegaDeltaE2000Max", "tierOmegaGrayDeltaAAbs", "tierOmegaGrayDeltaBAbs",
        "tierOmegaBandingScore", "tierOmegaDeadHotPixels", "tierOmegaWbApplied",
        "tierOmegaCcmNonIdentity", "tierOmegaPreviewRecordExportParity"
    )

    @After
    fun reset() {
        EliteVisionMeasurementFlag.set(false)
    }

    @Test
    fun flagOffReturnsEmptyMapSoGateBranchStaysDormant() {
        EliteVisionMeasurementFlag.set(false)
        assertEquals(
            emptyMap<String, String>(),
            EliteVisionProductionMeasurement.measuredTierOmegaMetadata(EliteVisionDecodedFrameProvider.DeterministicSynthetic)
        )
    }

    @Test
    fun flagOnWithSyntheticProviderEmitsAllRequiredKeysPlusProvenance() {
        EliteVisionMeasurementFlag.set(true)
        val m = EliteVisionProductionMeasurement.measuredTierOmegaMetadata(EliteVisionDecodedFrameProvider.DeterministicSynthetic)
        requiredKeys.forEach { assertTrue("missing $it", m.containsKey(it)) }
        assertEquals("SYNTHETIC", m[EliteVisionProductionMeasurement.PRODUCER_KEY])
    }

    @Test
    fun flagOnSyntheticMtf50KeyEqualsMeasuredValue() {
        EliteVisionMeasurementFlag.set(true)
        val frames = EliteVisionDecodedFrameProvider.DeterministicSynthetic.framesOrNull()
        val measured = EliteVisionMeasurementHarness.measure(frames, EliteVisionProductionMeasurement.NEUTRAL_BASELINE)
        val m = EliteVisionProductionMeasurement.measuredTierOmegaMetadata(EliteVisionDecodedFrameProvider.DeterministicSynthetic)
        assertEquals(measured.mtf50CyPerPx, m["tierOmegaMtf50CyPerPx"]!!.toDouble(), 1e-6)
    }

    @Test
    fun flagOnUnavailableProducerReturnsOnlyNotProvenMarkerNeverSynthesizesPass() {
        EliteVisionMeasurementFlag.set(true)
        val m = EliteVisionProductionMeasurement.measuredTierOmegaMetadata(EliteVisionDecodedFrameProvider.Unavailable)
        assertEquals(mapOf(EliteVisionProductionMeasurement.PRODUCER_KEY to "NOT_AVAILABLE"), m)
        // No required metric key is present -> the gate's missing-metrics branch fires NOT_PROVEN.
        requiredKeys.forEach { assertFalse("must not emit $it", m.containsKey(it)) }
    }

    @Test
    fun cpQ5SyntheticWrapperMetricSubsetEqualsHarnessOutput() {
        EliteVisionMeasurementFlag.set(true)
        val frames = EliteVisionDecodedFrameProvider.DeterministicSynthetic.framesOrNull()
        val expected = EliteVisionMeasurementHarness.toTierOmegaMetadata(
            EliteVisionMeasurementHarness.measure(frames, EliteVisionProductionMeasurement.NEUTRAL_BASELINE)
        )
        val actual = EliteVisionProductionMeasurement.measuredTierOmegaMetadata(EliteVisionDecodedFrameProvider.DeterministicSynthetic)
        // The production wrapper introduces no drift vs the locked harness output (provenance aside).
        assertEquals(expected, actual.filterKeys { it != EliteVisionProductionMeasurement.PRODUCER_KEY })
    }
}
