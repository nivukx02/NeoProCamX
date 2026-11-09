package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class RadianceValidationHarnessTest {

    @Test
    fun heliosValidationReportPassesAllSyntheticMathGates() {
        val report = RadianceValidationHarness.run()

        assertTrue("hue drift=${report.maxIctcpHueDriftDegrees}", report.maxIctcpHueDriftDegrees <= 2f)
        assertTrue("PQ error=${report.pqDiffuseWhiteErrorPercent}", report.pqDiffuseWhiteErrorPercent <= 1f)
        assertTrue("shadow slope=${report.shadowKneeSlopeDiscontinuity}", report.shadowKneeSlopeDiscontinuity <= 0.12f)
        assertTrue("RGC hue=${report.rgcHueErrorEstimate}", report.rgcHueErrorEstimate <= 2f)
        assertTrue("SNR=${report.mergeShadowSnrGainDb}", report.mergeShadowSnrGainDb >= 6f)
        assertTrue("v1 bit match", report.v1BitMatch)
        assertTrue("pumping=${report.brightnessPumpingPercent}", report.brightnessPumpingPercent <= 1f)
        assertTrue("report=$report", report.passed)
    }
}
