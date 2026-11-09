package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewMonitoringExposurePolicyTest {

    @Test
    fun logPreviewUsesTruthfulExposureBias() {
        val bias = PreviewMonitoringExposurePolicy.resolveEvCompensationBoost(
            logEnabled = true,
            hdrEnabled = false,
            rawEnabled = false,
            exposureCompensationUpperBound = 12
        )

        assertEquals(2, bias)
    }

    @Test
    fun hdrPreviewUsesTruthfulExposureBias() {
        val bias = PreviewMonitoringExposurePolicy.resolveEvCompensationBoost(
            logEnabled = false,
            hdrEnabled = true,
            rawEnabled = false,
            exposureCompensationUpperBound = 12
        )

        assertEquals(1, bias)
    }

    @Test
    fun previewBiasRespectsUnavailableAeHeadroom() {
        val bias = PreviewMonitoringExposurePolicy.resolveEvCompensationBoost(
            logEnabled = true,
            hdrEnabled = false,
            rawEnabled = false,
            exposureCompensationUpperBound = 0
        )

        assertEquals(0, bias)
    }

    @Test
    fun rawPreviewUsesDedicatedExposureBias() {
        val bias = PreviewMonitoringExposurePolicy.resolveEvCompensationBoost(
            logEnabled = false,
            hdrEnabled = false,
            rawEnabled = true,
            exposureCompensationUpperBound = 12
        )

        assertEquals(3, bias)
    }
}
