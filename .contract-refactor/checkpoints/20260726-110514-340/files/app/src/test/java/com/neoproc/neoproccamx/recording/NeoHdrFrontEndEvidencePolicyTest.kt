package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoHdrFrontEndEvidencePolicyTest {

    @Test
    fun validFrontendEvidencePassesTierSColorFrontEndGate() {
        val result = NeoHdrFrontEndEvidencePolicy.validate(validEvidence())

        assertTrue(result.passed)
        assertTrue(result.toMetadata().containsValue("PASS"))
        assertTrue(result.toMetadata()["frontEndCcmNonIdentity"] == "true")
    }

    @Test
    fun missingWhiteBalanceAndIdentityCcmBlockTierSColorFrontEndGate() {
        val result = NeoHdrFrontEndEvidencePolicy.validate(
            validEvidence().copy(
                neutralAfterWb = listOf(1.18, 0.96, 0.72),
                sensorToXyz = listOf(
                    1.0, 0.0, 0.0,
                    0.0, 1.0, 0.0,
                    0.0, 0.0, 1.0
                )
            )
        )

        assertFalse(result.passed)
        assertTrue(result.failures.any { it.code == "WB_NOT_APPLIED" })
        assertTrue(result.failures.any { it.code == "SENSOR_TO_XYZ_CCM_IDENTITY" })
    }

    private fun validEvidence(): NeoHdrFrontEndEvidence =
        NeoHdrFrontEndEvidence(
            blackLevels = listOf(64.0, 64.0, 65.0, 63.0),
            whiteLevel = 4095.0,
            wbGains = listOf(2.08, 1.0, 1.74),
            neutralAfterWb = listOf(1.01, 1.0, 0.99),
            sensorToXyz = listOf(
                0.684, 0.211, 0.105,
                0.231, 0.708, 0.061,
                0.028, 0.118, 0.854
            ),
            cctKelvin = 5600,
            defectCorrectionApplied = true,
            lensShadingCorrectionApplied = true,
            linearizationGuardApplied = true,
            redRunawayClampApplied = true
        )
}
