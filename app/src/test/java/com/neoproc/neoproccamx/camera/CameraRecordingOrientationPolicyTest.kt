package com.neoproc.neoproccamx.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraRecordingOrientationPolicyTest {

    @Test
    fun `fallback orientation uses sensor to output rotation instead of raw device rotation`() {
        val hint = CameraRecordingOrientationPolicy.resolve(
            frameGeometry = null,
            sensorOrientationDegrees = 90,
            deviceRotationDegrees = 90,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK
        )

        assertEquals(0, hint)
    }

    @Test
    fun `frame geometry orientation wins when available`() {
        val geometry = FrameGeometry(
            sensorOrientationDegrees = 90,
            deviceRotationDegrees = 270,
            finalRotationDegrees = 180,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            sensorAspectRatio = 4f / 3f,
            outputAspectRatio = 16f / 9f,
            cropRegion = Rect(0, 0, 4000, 3000),
            normalizedCropRegion = NormalizedCropRegion.FULL
        )

        val hint = CameraRecordingOrientationPolicy.resolve(
            frameGeometry = geometry,
            sensorOrientationDegrees = 90,
            deviceRotationDegrees = 0,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK
        )

        assertEquals(180, hint)
    }
}
