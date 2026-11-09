package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EliteVisionQualityContractTest {

    @Test
    fun allAcceptanceMeasurementsPassWhenThresholdsAreSatisfied() {
        val report = EliteVisionQualityContract.evaluate(passingMeasurements())

        assertEquals(25, report.results.size)
        assertTrue(report.passed)
        assertTrue(report.toJson().contains("\"A15\""))
        assertTrue(report.toJson().contains("\"S10\""))
        assertTrue(report.toJson().contains("\"passed\":true"))
    }

    @Test
    fun tierOmegaMeasurementsRejectPreviousTierSMetricFloors() {
        val report = EliteVisionQualityContract.evaluate(
            passingMeasurements().copy(
                lumaSnrBaseDb = 49.0,
                lumaSnrHighIsoDb = 39.0,
                chromaNoiseStd = 0.42,
                temporalFlicker = 0.009,
                mtf50CyPerPx = 0.33,
                detailRetentionVsRaw = 0.92,
                colorCheckerDeltaE2000Mean = 1.4
            )
        )

        assertTrue(report.results.any { it.id == "S1" && !it.passed })
        assertTrue(report.results.any { it.id == "S2" && !it.passed })
        assertTrue(report.results.any { it.id == "S3" && !it.passed })
        assertTrue(report.results.any { it.id == "S4" && !it.passed })
        assertTrue(report.results.any { it.id == "S5" && !it.passed })
        assertTrue(report.results.any { it.id == "S6" && !it.passed })
        assertEquals(false, report.passed)
    }

    @Test
    fun failingNoiseOrMtfMeasurementBlocksEliteVisionClaim() {
        val report = EliteVisionQualityContract.evaluate(
            passingMeasurements().copy(
                noiseSigmaIreBase = 0.48,
                mtf50Retention = 0.89
            )
        )

        assertTrue(report.results.any { it.id == "A8" && !it.passed })
        assertTrue(report.results.any { it.id == "A9" && !it.passed })
        assertEquals(false, report.passed)
    }

    @Test
    fun failingTierSMeasurementsBlockReferenceHdrClaim() {
        val report = EliteVisionQualityContract.evaluate(
            passingMeasurements().copy(
                lumaSnrBaseDb = 45.0,
                mtf50CyPerPx = 0.29,
                colorCheckerDeltaE2000Mean = 1.9,
                previewRecordExportParity = false
            )
        )

        assertTrue(report.results.any { it.id == "S1" && !it.passed })
        assertTrue(report.results.any { it.id == "S4" && !it.passed })
        assertTrue(report.results.any { it.id == "S6" && !it.passed })
        assertTrue(report.results.any { it.id == "S10" && !it.passed })
        assertEquals(false, report.passed)
    }

    private fun passingMeasurements(): EliteVisionQualityMeasurements =
        EliteVisionQualityMeasurements(
            hueDeltaE2000Max = 0.42,
            skinDeltaE2000Max = 1.6,
            specularChromaAtClip = 0.18,
            skyHueDriftDegrees = 0.7,
            lookRoundTripDeltaE2000Max = 0.62,
            gamutBoundaryOvershoot = 0.0,
            lightnessDriftMax = 0.0,
            noiseSigmaIreBase = 0.32,
            noiseSigmaIreIso3200 = 0.65,
            mtf50Retention = 0.94,
            acutanceGain = 0.28,
            haloOvershoot = 0.025,
            bandingDeltaCodeMax = 0.0,
            metadataBitrateRatio = 0.0012,
            tmbSignatureVerified = true,
            fallbackEvidenceHonest = true,
            nlogsRoundTripDeltaE2000Max = 0.22,
            deterministicHashMatch = true,
            lumaSnrBaseDb = 51.0,
            lumaSnrHighIsoDb = 41.0,
            chromaNoiseStd = 0.34,
            temporalFlicker = 0.007,
            mtf50CyPerPx = 0.35,
            detailRetentionVsRaw = 0.94,
            colorCheckerDeltaE2000Mean = 1.1,
            colorCheckerDeltaE2000Max = 2.4,
            grayDeltaAAbs = 0.7,
            grayDeltaBAbs = 0.8,
            deadHotPixels = 0,
            redClipBlueShadowSafe = true,
            previewRecordExportParity = true,
            wbApplied = true,
            ccmNonIdentity = true,
            vendorProfileFree = true
        )
}
