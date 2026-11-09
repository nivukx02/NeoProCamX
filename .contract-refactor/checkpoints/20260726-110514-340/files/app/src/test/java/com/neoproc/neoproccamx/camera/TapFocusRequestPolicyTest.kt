package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapFocusRequestPolicyTest {

    @Test
    fun `tap focus builds af and ae metering regions when hardware supports them`() {
        val request = TapFocusRequestPolicy.resolve(
            activeArray = CameraRect(0, 0, 4000, 3000),
            normalizedX = 0.5f,
            normalizedY = 0.5f,
            maxAfRegions = 1,
            maxAeRegions = 1
        )

        assertEquals(CameraRect(1840, 1380, 2160, 1620), request.focusRegion)
        assertEquals(CameraRect(1520, 1140, 2480, 1860), request.exposureRegion)
        assertTrue(request.shouldTriggerAf)
    }

    @Test
    fun `tap focus maps preview normalized point through real sixteen by nine crop`() {
        val request = TapFocusRequestPolicy.resolve(
            activeArray = CameraRect(0, 0, 4000, 3000),
            normalizedX = 0.5f,
            normalizedY = 0.0f,
            maxAfRegions = 1,
            maxAeRegions = 1,
            frameGeometry = FrameGeometry(
                sensorOrientationDegrees = 0,
                deviceRotationDegrees = 0,
                finalRotationDegrees = 0,
                lensFacing = CameraCharacteristics.LENS_FACING_BACK,
                sensorAspectRatio = 4f / 3f,
                outputAspectRatio = 16f / 9f,
                cropRegion = null,
                normalizedCropRegion = NormalizedCropRegion(0f, 0.125f, 1f, 0.875f)
            )
        )

        assertEquals(CameraRect(1840, 255, 2160, 495), request.focusRegion)
        assertEquals(CameraRect(1520, 15, 2480, 735), request.exposureRegion)
        assertTrue(request.shouldTriggerAf)
    }

    @Test
    fun `tap focus still meters exposure when af regions are unavailable`() {
        val request = TapFocusRequestPolicy.resolve(
            activeArray = CameraRect(0, 0, 4000, 3000),
            normalizedX = 0.5f,
            normalizedY = 0.5f,
            maxAfRegions = 0,
            maxAeRegions = 1
        )

        assertNull(request.focusRegion)
        assertNotNull(request.exposureRegion)
        assertFalse(request.shouldTriggerAf)
    }

    @Test
    fun `tap focus ignores invalid active array`() {
        val request = TapFocusRequestPolicy.resolve(
            activeArray = null,
            normalizedX = 0.5f,
            normalizedY = 0.5f,
            maxAfRegions = 1,
            maxAeRegions = 1
        )

        assertNull(request.focusRegion)
        assertNull(request.exposureRegion)
        assertFalse(request.shouldTriggerAf)
    }
}
