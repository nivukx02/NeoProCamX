package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f07 — Preview = Record = Export parity (WYSIWYG) is a blocking gate: a monitor that lies about
 * the master is the cardinal sin. Pins both the quality-signature parity and the enhancement-uniform
 * signature parity. Characterization lock (no production change).
 */
class GateParityMismatchFailTest {

    @Test
    fun qualitySignatureMismatchFails() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            EliteVisionGateTestSupport.validNdrRequest().copy(
                recordingQualitySignature = "HDR|DIFFERENT|LOW_QUALITY_RECORDING"
            )
        )
        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(
            certificate.failures.any { it.code == RecordingQualityGateCode.PREVIEW_RECORDING_PARITY_MISMATCH }
        )
    }

    @Test
    fun enhancementUniformSignatureMismatchFails() {
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            EliteVisionGateTestSupport.validNdrRequest().copy(
                recordingEnhancementUniformSignature =
                    "gpu-v1|signal=HDR|space=BT.2020|science=NDR10+|packet=tampered"
            )
        )
        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(
            certificate.failures.any {
                it.code == RecordingQualityGateCode.PREVIEW_RECORDING_ENHANCEMENT_MISMATCH
            }
        )
    }
}
