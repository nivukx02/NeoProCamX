package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSubjectMeteringPolicyTest {

    @Test
    fun continuousSubjectTrackingKeepsExistingAeRegionUntilRefreshIsNeeded() {
        val currentAeRegion = CameraRect(100, 100, 500, 500)

        val update = CameraSubjectMeteringPolicy.resolve(
            focusRegion = CameraRect(140, 120, 540, 520),
            exposureRegion = CameraRect(120, 120, 520, 520),
            currentExposureRegion = currentAeRegion,
            supportsManualSensor = false,
            manualIso = -1,
            manualExposureNs = -1L,
            lastExposureUpdateElapsedMs = 1_000L,
            nowElapsedMs = 1_220L
        )

        assertEquals(CameraRect(140, 120, 540, 520), update.focusRegion)
        assertEquals(currentAeRegion, update.exposureRegion)
        assertFalse(update.exposureRegionUpdated)
    }

    @Test
    fun continuousSubjectTrackingRefreshesAeRegionAfterMeaningfulMove() {
        val candidateAeRegion = CameraRect(220, 180, 620, 580)

        val update = CameraSubjectMeteringPolicy.resolve(
            focusRegion = CameraRect(240, 180, 640, 580),
            exposureRegion = candidateAeRegion,
            currentExposureRegion = CameraRect(100, 100, 500, 500),
            supportsManualSensor = false,
            manualIso = -1,
            manualExposureNs = -1L,
            lastExposureUpdateElapsedMs = 1_000L,
            nowElapsedMs = 1_220L
        )

        assertEquals(candidateAeRegion, update.exposureRegion)
        assertTrue(update.exposureRegionUpdated)
    }
}
