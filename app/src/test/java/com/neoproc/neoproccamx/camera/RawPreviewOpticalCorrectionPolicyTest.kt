package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawPreviewOpticalCorrectionPolicyTest {
    @Test
    fun liveRawMonitorUsesBestAdvertisedOpticalCorrection() {
        val plan = RawPreviewOpticalCorrectionPolicy.resolve(
            rawSensorRequestActive = true,
            forStillCapture = false,
            availability = availability(
                shading = listOf(CaptureRequest.SHADING_MODE_FAST, CaptureRequest.SHADING_MODE_HIGH_QUALITY),
                aberration = listOf(
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
                )
            ),
            androidApiLevel = Build.VERSION_CODES.M
        )!!

        assertEquals(CaptureRequest.SHADING_MODE_HIGH_QUALITY, plan.shadingMode)
        assertEquals(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY, plan.aberrationMode)
    }

    @Test
    fun liveRawMonitorFallsBackToFastButNeverSelectsOffAsCorrection() {
        val fast = RawPreviewOpticalCorrectionPolicy.resolve(
            rawSensorRequestActive = true,
            forStillCapture = false,
            availability = availability(
                shading = listOf(CaptureRequest.SHADING_MODE_OFF, CaptureRequest.SHADING_MODE_FAST),
                aberration = listOf(
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST
                )
            ),
            androidApiLevel = Build.VERSION_CODES.M
        )!!
        val offOnly = RawPreviewOpticalCorrectionPolicy.resolve(
            rawSensorRequestActive = true,
            forStillCapture = false,
            availability = availability(
                shading = listOf(CaptureRequest.SHADING_MODE_OFF),
                aberration = listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
            ),
            androidApiLevel = Build.VERSION_CODES.M
        )!!

        assertEquals(CaptureRequest.SHADING_MODE_FAST, fast.shadingMode)
        assertEquals(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST, fast.aberrationMode)
        assertNull(offOnly.shadingMode)
        assertNull(offOnly.aberrationMode)
    }

    @Test
    fun policyDoesNotAlterStillOrNonRawRequests() {
        assertNull(RawPreviewOpticalCorrectionPolicy.resolve(false, false, availability(), Build.VERSION_CODES.M))
        assertNull(RawPreviewOpticalCorrectionPolicy.resolve(true, true, availability(), Build.VERSION_CODES.M))
    }

    private fun availability(
        shading: List<Int> = listOf(CaptureRequest.SHADING_MODE_HIGH_QUALITY),
        aberration: List<Int> = listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
    ) = IspBypassAvailability(
        tonemapModes = emptyList(),
        edgeModes = emptyList(),
        noiseReductionModes = emptyList(),
        hotPixelModes = emptyList(),
        shadingModes = shading,
        colorAberrationModes = aberration,
        distortionCorrectionModes = emptyList()
    )
}
