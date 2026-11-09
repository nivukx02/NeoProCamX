package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareAbstractionLayerDistortionTest {

    @Test
    fun `raw bypass profiles request distortion correction off for strict native geometry`() {
        assertEquals(
            CaptureRequest.DISTORTION_CORRECTION_MODE_OFF,
            HardwareAbstractionLayer.strictBypassDistortionCorrectionMode(Build.VERSION_CODES.P)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            assertEquals(
                CaptureRequest.DISTORTION_CORRECTION_MODE_OFF,
                HardwareAbstractionLayer.BEYOND_IMAX_RAW.distortionCorrectionMode
            )
            assertEquals(
                CaptureRequest.DISTORTION_CORRECTION_MODE_OFF,
                HardwareAbstractionLayer.SUPREME_FIDELITY_BYPASS.distortionCorrectionMode
            )
        } else {
            assertNull(HardwareAbstractionLayer.BEYOND_IMAX_RAW.distortionCorrectionMode)
            assertNull(HardwareAbstractionLayer.BEYOND_IMAX_CINEMA.distortionCorrectionMode)
            assertNull(HardwareAbstractionLayer.SUPREME_FIDELITY_BYPASS.distortionCorrectionMode)
        }
    }

    @Test
    fun `processed cinema profile requests high quality distortion correction`() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            assertEquals(
                CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
                HardwareAbstractionLayer.BEYOND_IMAX_CINEMA.distortionCorrectionMode
            )
        } else {
            assertNull(HardwareAbstractionLayer.BEYOND_IMAX_CINEMA.distortionCorrectionMode)
        }
    }
}
