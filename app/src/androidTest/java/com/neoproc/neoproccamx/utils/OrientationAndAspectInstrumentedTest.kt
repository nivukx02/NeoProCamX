package com.neoproc.neoproccamx.utils

import android.hardware.camera2.CameraCharacteristics
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.ui.PreviewAspectRatio
import com.neoproc.neoproccamx.ui.SensorCropEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrientationAndAspectInstrumentedTest {

    @Test
    fun backCameraRotationMatchesExpectedQuadrants() {
        assertEquals(90, OrientationManager.getSensorToOutputRotation(90, 0, CameraCharacteristics.LENS_FACING_BACK))
        assertEquals(0, OrientationManager.getSensorToOutputRotation(90, 90, CameraCharacteristics.LENS_FACING_BACK))
        assertEquals(270, OrientationManager.getSensorToOutputRotation(90, 180, CameraCharacteristics.LENS_FACING_BACK))
        assertEquals(180, OrientationManager.getSensorToOutputRotation(90, 270, CameraCharacteristics.LENS_FACING_BACK))
    }

    @Test
    fun frontCameraRotationMatchesExpectedQuadrants() {
        assertEquals(90, OrientationManager.getSensorToOutputRotation(90, 0, CameraCharacteristics.LENS_FACING_FRONT))
        assertEquals(180, OrientationManager.getSensorToOutputRotation(90, 90, CameraCharacteristics.LENS_FACING_FRONT))
        assertEquals(270, OrientationManager.getSensorToOutputRotation(90, 180, CameraCharacteristics.LENS_FACING_FRONT))
        assertEquals(0, OrientationManager.getSensorToOutputRotation(90, 270, CameraCharacteristics.LENS_FACING_FRONT))
    }

    @Test
    fun autoAspectRatiosRemainStableAtApiLevel() {
        val previous = SensorCropEngine.fullSensorAspectRatio()
        SensorCropEngine.setFullSensorAspectRatio(4f / 3f)
        try {
            assertEquals(4f / 3f, PreviewAspectRatio.AUTO.outputRatio()!!, 0.0001f)
            assertEquals("1333:1000", PreviewAspectRatio.AUTO.layoutRatio(false))
        } finally {
            SensorCropEngine.setFullSensorAspectRatio(previous)
        }
    }

    @Test
    fun sensorCropEngineReturnsIdentityCropForFullSensorAspect() {
        val previous = SensorCropEngine.fullSensorAspectRatio()
        SensorCropEngine.setFullSensorAspectRatio(4f / 3f)
        try {
            val crop = SensorCropEngine.centerCropSensorSpace(
                SensorCropEngine.fullSensorAspectRatio(),
                SensorCropEngine.fullSensorAspectRatio()
            )
            assertEquals(0f, crop.left)
            assertEquals(0f, crop.top)
            assertEquals(1f, crop.right)
            assertEquals(1f, crop.bottom)
            assertTrue(crop.width > 0f)
            assertTrue(crop.height > 0f)
        } finally {
            SensorCropEngine.setFullSensorAspectRatio(previous)
        }
    }

    @Test
    fun quantizedPhysicalRotationMatchesExpectedBuckets() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = false
        try {
            assertEquals(0, OrientationManager.quantizePhysicalDeviceRotation(5))
            assertEquals(90, OrientationManager.quantizePhysicalDeviceRotation(90))
            assertEquals(180, OrientationManager.quantizePhysicalDeviceRotation(179))
            assertEquals(270, OrientationManager.quantizePhysicalDeviceRotation(315))
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }
}
