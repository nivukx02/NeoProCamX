package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillCaptureQualityReadbackPolicyTest {

    private val requested = StillCaptureQualitySnapshot(
        edgeMode = 2,
        noiseReductionMode = 2,
        hotPixelMode = 2,
        shadingMode = 2,
        tonemapMode = 1,
        colorAberrationMode = 2,
        distortionCorrectionMode = 2,
        aeMode = 0,
        awbMode = 0,
        afMode = 0,
        colorCorrectionMode = 0,
        sensorSensitivityIso = 400,
        exposureTimeNs = 8_000_000L,
        focusDistanceDiopters = 1.25f,
        jpegQuality = 100,
        jpegOrientationDegrees = 90
    )

    @Test
    fun exactReadback_isProven_andRetainsRequestOnlyJpegEvidence() {
        val report = StillCaptureQualityReadbackPolicy.verify(
            requested = requested,
            reported = requested.copy(jpegQuality = null, jpegOrientationDegrees = null)
        )

        assertEquals(StillCaptureQualityVerdict.PROVEN, report.verdict)
        assertTrue(report.passed)
        assertEquals(100, report.requestedJpegQuality)
        assertEquals(90, report.requestedJpegOrientationDegrees)
        assertTrue(report.unreportedKeys.isEmpty())
        assertTrue(report.mismatchedKeys.isEmpty())
    }

    @Test
    fun missingHalKeys_arePartial_notFabricatedAsProven() {
        val report = StillCaptureQualityReadbackPolicy.verify(
            requested = requested,
            reported = requested.copy(shadingMode = null, distortionCorrectionMode = null)
        )

        assertEquals(StillCaptureQualityVerdict.PARTIAL, report.verdict)
        assertTrue(report.passed)
        assertEquals(listOf("shading", "distortion"), report.unreportedKeys)
    }

    @Test
    fun contradictoryReadback_failsQualityProof() {
        val report = StillCaptureQualityReadbackPolicy.verify(
            requested = requested,
            reported = requested.copy(noiseReductionMode = 0, sensorSensitivityIso = 800)
        )

        assertEquals(StillCaptureQualityVerdict.NOT_PROVEN, report.verdict)
        assertFalse(report.passed)
        assertTrue(report.mismatchedKeys.any { it.startsWith("noiseReduction(") })
        assertTrue(report.mismatchedKeys.any { it.startsWith("iso(") })
    }

    @Test
    fun boundedAnalogReadbackDifferences_areAccepted() {
        val report = StillCaptureQualityReadbackPolicy.verify(
            requested = requested,
            reported = requested.copy(
                exposureTimeNs = 8_100_000L,
                focusDistanceDiopters = 1.27f
            )
        )

        assertEquals(StillCaptureQualityVerdict.PROVEN, report.verdict)
    }
}
