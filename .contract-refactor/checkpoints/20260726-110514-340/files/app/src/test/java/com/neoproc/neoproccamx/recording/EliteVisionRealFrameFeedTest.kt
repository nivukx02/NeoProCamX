package com.neoproc.neoproccamx.recording

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f06 / CP-Q8 — a real-frame provider ([EliteVisionByteBufferFrameProvider]) fed a deterministic
 * decoded fixture drives the full producer → measure → projection path and yields the measured
 * tierOmega* evidence keys with the real-pixel provenance stamp. This is the JVM-testable proof that
 * the byte-buffer seam (which a device-side MediaCodec decode populates on-device) closes the loop.
 */
class EliteVisionRealFrameFeedTest {

    // Hardcoded (TIER_OMEGA_REQUIRED_METRIC_KEYS is private to RecordingQualityEvidencePolicy);
    // mirrors EliteVisionProductionMeasurementTest / EliteVisionMeasurementHarnessTest.
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
    fun decodedFixtureProducesMeasuredTierOmegaKeysWithRealPixelProvenance() {
        EliteVisionMeasurementFlag.set(true)
        // A deterministic decoded chart frame set (stands in for device-decoded buffers, CP-Q1).
        val frames = EliteVisionDecodedFrameProvider.DeterministicSynthetic.framesOrNull()
        val provider = EliteVisionByteBufferFrameProvider(
            frames = frames,
            canMeasureAllRequiredCriteria = true
        )

        val metadata = EliteVisionProductionMeasurement.measuredTierOmegaMetadata(provider)

        // Every gate-required metric key is present and carries a measured value.
        requiredKeys.forEach { assertTrue("missing $it", metadata.containsKey(it)) }

        // The MTF50 key carries the REAL measured value, not a fixture default.
        val measured = EliteVisionMeasurementHarness.measure(
            frames, EliteVisionProductionMeasurement.NEUTRAL_BASELINE
        )
        assertEquals(
            measured.mtf50CyPerPx,
            metadata["tierOmegaMtf50CyPerPx"]!!.toDouble(),
            1e-6
        )

        // Provenance: the decoded byte-buffer label + the real-pixel source stamp.
        assertEquals(
            EliteVisionByteBufferFrameProvider.LABEL_DECODED,
            metadata[EliteVisionProductionMeasurement.PRODUCER_KEY]
        )
        assertEquals(
            EliteVisionMeasurementHarness.SOURCE_REAL_PIXEL,
            metadata[EliteVisionMeasurementHarness.TIER_OMEGA_METRICS_SOURCE_KEY]
        )
    }

    @Test
    fun partialCriteriaProviderEmitsNotProvenNeverMeasuredKeys() {
        EliteVisionMeasurementFlag.set(true)
        // Frames present, but the caller honestly declares it cannot measure all chart criteria.
        val frames = EliteVisionDecodedFrameProvider.DeterministicSynthetic.framesOrNull()
        val provider = EliteVisionByteBufferFrameProvider(
            frames = frames,
            canMeasureAllRequiredCriteria = false
        )

        val metadata = EliteVisionProductionMeasurement.measuredTierOmegaMetadata(provider)

        assertEquals(
            mapOf(
                EliteVisionProductionMeasurement.PRODUCER_KEY to
                    "${EliteVisionByteBufferFrameProvider.LABEL_DECODED}_PARTIAL_NOT_PROVEN"
            ),
            metadata
        )
        requiredKeys.forEach { assertTrue("must not emit $it", !metadata.containsKey(it)) }
    }
}
