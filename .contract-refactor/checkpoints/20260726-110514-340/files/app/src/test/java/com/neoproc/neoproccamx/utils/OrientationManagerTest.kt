package com.neoproc.neoproccamx.utils

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationManagerTest {

    @Test
    fun sensorToOutputRotation_usesBackCameraFormula() {
        assertEquals(90, OrientationManager.getSensorToOutputRotation(90, 0, CameraCharacteristics.LENS_FACING_BACK))
        assertEquals(0, OrientationManager.getSensorToOutputRotation(90, 90, CameraCharacteristics.LENS_FACING_BACK))
        assertEquals(270, OrientationManager.getSensorToOutputRotation(90, 180, CameraCharacteristics.LENS_FACING_BACK))
        assertEquals(180, OrientationManager.getSensorToOutputRotation(90, 270, CameraCharacteristics.LENS_FACING_BACK))
    }

    @Test
    fun sensorToOutputRotation_usesFrontCameraFormula() {
        assertEquals(90, OrientationManager.getSensorToOutputRotation(90, 0, CameraCharacteristics.LENS_FACING_FRONT))
        assertEquals(180, OrientationManager.getSensorToOutputRotation(90, 90, CameraCharacteristics.LENS_FACING_FRONT))
        assertEquals(270, OrientationManager.getSensorToOutputRotation(90, 180, CameraCharacteristics.LENS_FACING_FRONT))
        assertEquals(0, OrientationManager.getSensorToOutputRotation(90, 270, CameraCharacteristics.LENS_FACING_FRONT))
    }

    @Test
    fun normalizeDegrees_wrapsNegativeAndLargeInputs() {
        assertEquals(270, OrientationManager.normalizeDegrees(-90))
        assertEquals(90, OrientationManager.normalizeDegrees(450))
        assertEquals(0, OrientationManager.normalizeDegrees(720))
    }

    @Test
    fun quantizePhysicalDeviceRotation_bucketsSensorAnglesWithoutDisplayState() {
        assertEquals(0, OrientationManager.quantizePhysicalDeviceRotation(10))
        assertEquals(90, OrientationManager.quantizePhysicalDeviceRotation(89))
        assertEquals(180, OrientationManager.quantizePhysicalDeviceRotation(181))
        assertEquals(270, OrientationManager.quantizePhysicalDeviceRotation(269))
        assertEquals(90, OrientationManager.quantizePhysicalDeviceRotation(-1, fallbackRotationDegrees = 90))
    }

    @Test
    fun sensorToOutputRotation_quantizesNonRightAngleInputs() {
        assertEquals(
            90,
            OrientationManager.getSensorToOutputRotation(
                sensorOrientation = 95,
                deviceRotationDegrees = 2,
                lensFacing = CameraCharacteristics.LENS_FACING_BACK
            )
        )
    }

    @Test
    fun jpegOrientation_matchesVideoSensorToOutputRotationForEveryLens() {
        assertEquals(
            90,
            OrientationManager.getJpegOrientation(
                sensorOrientation = 90,
                deviceRotationDegrees = 0,
                lensFacing = CameraCharacteristics.LENS_FACING_FRONT
            )
        )
        assertEquals(
            90,
            OrientationManager.getJpegOrientation(
                sensorOrientation = 90,
                deviceRotationDegrees = 0,
                lensFacing = CameraCharacteristics.LENS_FACING_BACK
            )
        )
    }

    @Test
    fun exifOrientation_mapsAllRightAngleOutputRotations() {
        assertEquals(1, OrientationManager.exifOrientationForRotation(0))
        assertEquals(6, OrientationManager.exifOrientationForRotation(90))
        assertEquals(3, OrientationManager.exifOrientationForRotation(180))
        assertEquals(8, OrientationManager.exifOrientationForRotation(270))
    }

    @Test
    fun isRightAngleRotation_detectsOnlyRightAngles() {
        assertTrue(OrientationManager.isRightAngleRotation(90))
        assertTrue(OrientationManager.isRightAngleRotation(450))
        assertFalse(OrientationManager.isRightAngleRotation(91))
    }
}
