package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPreviewAntibandingPolicyTest {

    @Test
    fun `video preview prefers fixed 60hz over auto for 60 fps`() {
        val mode = CameraPreviewAntibandingPolicy.resolveMode(
            availableModes = intArrayOf(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            ),
            requestedPreviewFps = 60,
            videoMode = true,
            usingManualExposure = false,
            powerGridCountryCode = "US"
        )

        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ, mode)
    }

    @Test
    fun `video preview prefers fixed 50hz for cinema 25 fps`() {
        val mode = CameraPreviewAntibandingPolicy.resolveMode(
            availableModes = intArrayOf(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
            ),
            requestedPreviewFps = 25,
            videoMode = true,
            usingManualExposure = false,
            powerGridCountryCode = "US"
        )

        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ, mode)
    }

    @Test
    fun `video preview prefers fixed 50hz on 50hz power grids even at 60 fps`() {
        val mode = CameraPreviewAntibandingPolicy.resolveMode(
            availableModes = intArrayOf(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            ),
            requestedPreviewFps = 60,
            videoMode = true,
            usingManualExposure = false,
            powerGridCountryCode = "IN"
        )

        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ, mode)
    }

    @Test
    fun `photo preview falls back to auto antibanding`() {
        val mode = CameraPreviewAntibandingPolicy.resolveMode(
            availableModes = intArrayOf(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            ),
            requestedPreviewFps = 60,
            videoMode = false,
            usingManualExposure = false,
            powerGridCountryCode = "IN"
        )

        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO, mode)
    }

    @Test
    fun `video preview prefers auto antibanding for 90 fps high refresh lighting`() {
        val mode = CameraPreviewAntibandingPolicy.resolveMode(
            availableModes = intArrayOf(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            ),
            requestedPreviewFps = 90,
            videoMode = true,
            usingManualExposure = false,
            powerGridCountryCode = "IN"
        )

        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO, mode)
    }

    @Test
    fun `video preview infers 50hz grid from India timezone when country is unknown`() {
        val mode = CameraPreviewAntibandingPolicy.resolveMode(
            availableModes = intArrayOf(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            ),
            requestedPreviewFps = 60,
            videoMode = true,
            usingManualExposure = false,
            powerGridCountryCode = "",
            powerGridTimeZoneId = "Asia/Kolkata"
        )

        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ, mode)
    }

    @Test
    fun `video preview lets timezone override default locale country when country is not explicit`() {
        val mode = CameraPreviewAntibandingPolicy.resolveMode(
            availableModes = intArrayOf(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            ),
            requestedPreviewFps = 60,
            videoMode = true,
            usingManualExposure = false,
            powerGridCountryCode = null,
            powerGridTimeZoneId = "Asia/Kolkata"
        )

        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ, mode)
    }

    @Test
    fun `video preview infers 60hz grid from US timezone when country is unknown`() {
        val mode = CameraPreviewAntibandingPolicy.resolveMode(
            availableModes = intArrayOf(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            ),
            requestedPreviewFps = 60,
            videoMode = true,
            usingManualExposure = false,
            powerGridCountryCode = "",
            powerGridTimeZoneId = "America/New_York"
        )

        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ, mode)
    }

    @Test
    fun `manual video exposure still resolves antibanding for flicker safe timing`() {
        val mode = CameraPreviewAntibandingPolicy.resolveMode(
            availableModes = intArrayOf(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
            ),
            requestedPreviewFps = 60,
            videoMode = true,
            usingManualExposure = true,
            powerGridCountryCode = "IN"
        )

        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ, mode)
    }
}
