package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraCaptureRotationPolicyTest {
    @Test
    fun `locked portrait capture follows physical device rotation instead of locked display`() {
        val rotation = CameraCaptureRotationPolicy.resolveCameraDeviceRotationDegrees(
            physicalDeviceRotationDegrees = 180,
            displayRotationDegrees = 0
        )

        assertEquals(180, rotation)
    }

    @Test
    fun `unknown physical orientation falls back to display rotation`() {
        val rotation = CameraCaptureRotationPolicy.resolveCameraDeviceRotationDegrees(
            physicalDeviceRotationDegrees = -1,
            displayRotationDegrees = 270
        )

        assertEquals(270, rotation)
    }

    @Test
    fun `physical landscape directions are converted to camera2 rotation convention`() {
        assertEquals(
            270,
            CameraCaptureRotationPolicy.resolveCameraDeviceRotationDegrees(
                physicalDeviceRotationDegrees = 90,
                displayRotationDegrees = 0
            )
        )
        assertEquals(
            90,
            CameraCaptureRotationPolicy.resolveCameraDeviceRotationDegrees(
                physicalDeviceRotationDegrees = 270,
                displayRotationDegrees = 0
            )
        )
    }
}
