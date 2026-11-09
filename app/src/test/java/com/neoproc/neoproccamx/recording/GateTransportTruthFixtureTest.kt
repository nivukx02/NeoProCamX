package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f07 — transport-truth is validated against the actual container/probe + transport claim, not a
 * label. Crafted RecordingProbeMetadata / transport fixtures must drive the per-mode transport-truth
 * failure codes. Characterization lock over already-enforced behaviour (no production change).
 */
class GateTransportTruthFixtureTest {

    @Test
    fun hdrClaimWithGenericPqTransportIsNotBt2100() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            EliteVisionGateTestSupport.validNdrRequest().copy(activeTransport = "ANDROID_PQ_BT2020_MAIN10")
        )
        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.HDR_TRANSPORT_NOT_10BIT_BT2020 })
    }

    @Test
    fun hdrClaimSavedAsHlgIsNotBt2100Pq() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            EliteVisionGateTestSupport.validNdrRequest().copy(
                activeTransport = "BT.2100_HLG_MAIN10",
                transfer = "HLG"
            )
        )
        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.HDR_TRANSPORT_NOT_10BIT_BT2020 })
    }

    @Test
    fun npv10EightBitExpandedCannotClaimNativeTenBit() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            EliteVisionGateTestSupport.validNpv10Request().copy(
                npv10PayloadFormat = "YUV420_8BIT_EXPANDED",
                npv10NativeTenBitClaimed = true,
                sourceBitDepth = 8,
                recordedFileBytes = 24_000_000L
            )
        )
        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(
            certificate.failures.any {
                it.code == RecordingQualityGateCode.NPV10_FALLBACK_MISLABELED_NATIVE_10BIT
            }
        )
    }
}
