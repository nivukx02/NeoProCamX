package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureResult
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraAwbConfidencePolicyTest {
    @Test
    fun `HAL convergence states retain the existing continuous confidence contract`() {
        assertEquals(1f, CameraAwbConfidencePolicy.resolve(CaptureResult.CONTROL_AWB_STATE_CONVERGED))
        assertEquals(1f, CameraAwbConfidencePolicy.resolve(CaptureResult.CONTROL_AWB_STATE_LOCKED))
        assertEquals(0.35f, CameraAwbConfidencePolicy.resolve(CaptureResult.CONTROL_AWB_STATE_SEARCHING))
        assertEquals(0.7f, CameraAwbConfidencePolicy.resolve(CaptureResult.CONTROL_AWB_STATE_INACTIVE))
    }

    @Test
    fun `same-frame gain evidence rejects missing evidence`() {
        // Local android.jar returns default values from RggbChannelVector getters, so valid numeric
        // evidence is covered by UniversalCameraControlCalibrationTest's Android-free entry point.
        assertEquals(
            0f,
            CameraAwbConfidencePolicy.resolve(CaptureResult.CONTROL_AWB_STATE_CONVERGED, null),
            0f
        )
    }
}
