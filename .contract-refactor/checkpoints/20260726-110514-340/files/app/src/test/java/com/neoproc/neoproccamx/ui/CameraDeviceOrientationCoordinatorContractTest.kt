package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraDeviceOrientationCoordinatorContractTest {
    @Test
    fun `display rotation is used before a physical sensor sample exists`() {
        val update = CameraDeviceOrientationPolicy.resolve(
            physicalOrientationDegrees = null,
            displayRotationDegrees = 270
        )

        assertEquals(270, update.uiRotationDegrees)
        assertEquals(270, update.cameraRotationDegrees)
    }

    @Test
    fun `physical rotation overrides a portrait locked display`() {
        val update = CameraDeviceOrientationPolicy.resolve(
            physicalOrientationDegrees = 92,
            displayRotationDegrees = 0
        )

        assertEquals(90, update.uiRotationDegrees)
        assertEquals(90, update.cameraRotationDegrees)
    }

    @Test
    fun `orientation is normalized to camera metadata quadrants`() {
        val update = CameraDeviceOrientationPolicy.resolve(
            physicalOrientationDegrees = 269,
            displayRotationDegrees = 0
        )

        assertEquals(270, update.uiRotationDegrees)
        assertEquals(270, update.cameraRotationDegrees)
    }
}
