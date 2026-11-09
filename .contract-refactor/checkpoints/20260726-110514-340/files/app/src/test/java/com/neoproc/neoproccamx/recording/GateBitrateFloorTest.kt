package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f07 — bitrate is a blocking quality-floor gate (all-intra mastering data-rate). Below-floor
 * recordings FAIL, and a manual cap that lands below the floor must be surfaced explicitly (never
 * silently honoured). Characterization lock over already-enforced behaviour (no production change).
 */
class GateBitrateFloorTest {

    @Test
    fun belowFloorFailsWithFloorMetadata() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            EliteVisionGateTestSupport.validNdrRequest().copy(
                bitrateBps = 330_000_000,
                probe = EliteVisionGateTestSupport.ndrProbe(bitrateBps = 330_000_000)
            )
        )
        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.BITRATE_FLOOR_NOT_MET })
        // 1080p Beyond-IMAX floor = 420 Mbps, surfaced in the certificate metadata.
        assertEquals("420000000", certificate.metadata.getValue("tierOmegaMinimumBitrateBps"))
        assertEquals("420000000", certificate.metadata.getValue("beyondImaxMinimumBitrateBps"))
    }

    @Test
    fun manualCapBelowFloorIsExplicitNotSilent() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            EliteVisionGateTestSupport.validNdrRequest().copy(
                width = 3840,
                height = 2160,
                selectedResolution = "3840x2160",
                actualResolution = "3840x2160",
                bitrateBps = 160_000_000,
                requestedBitrateBps = 1_200_000_000,
                appliedBitrateBps = 160_000_000,
                encoderCapabilityUpperBoundBps = 160_000_000,
                manualBitrateSelectionEnabled = true,
                probe = EliteVisionGateTestSupport.ndrProbe(
                    bitrateBps = 160_000_000,
                    width = 3840,
                    height = 2160
                )
            )
        )
        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.BITRATE_FLOOR_NOT_MET })
        assertTrue(
            certificate.failures.any {
                it.code == RecordingQualityGateCode.MANUAL_BITRATE_CAPPED_BELOW_QUALITY_FLOOR
            }
        )
    }
}
