package com.neoproc.neoproccamx.color

import org.junit.Assert.assertTrue
import org.junit.Test

class ColorValidationHarnessTest {
    @Test
    fun `NeoGamut APEX validation harness reports all requested gates`() {
        val report = ColorValidationHarness.validateNeoGamutApex()

        assertTrue("mean DeltaE2000 gate", report.meanDeltaE2000 <= 2.0f)
        assertTrue("max DeltaE2000 gate", report.maxDeltaE2000 <= 4.0f)
        assertTrue("neutral u'v' gate", report.maxNeutralDeltaUv <= 0.003f)
        assertTrue("skin hue gate", report.maxSkinHueDriftDegrees <= 2.0f)
        assertTrue("video temporal u'v' gate", report.maxFrameToFrameDeltaUv <= 0.0015f)
        assertTrue("pipeline v1 gate", report.pipelineV1BitMatchesLegacy)
        assertTrue(report.illuminants.containsAll(listOf("A", "TL84", "D50", "D65", "D75")))
    }

    @Test
    fun `harness validates the real CP11 sensor to ACEScg path preserves neutrals`() {
        val report = ColorValidationHarness.validateNeoGamutApex()
        // CP-Q4: the REAL sensor->XYZ(D50)->ACEScg transform (mired-interpolated DNG forward
        // matrices) must keep a white-balanced neutral achromatic in ACEScg, not a synthetic model.
        assertTrue(
            "real sensor->ACEScg neutral spread ${report.maxSensorToAcesNeutralSpread} must be ~0",
            report.maxSensorToAcesNeutralSpread <= 0.02f
        )
    }
}
