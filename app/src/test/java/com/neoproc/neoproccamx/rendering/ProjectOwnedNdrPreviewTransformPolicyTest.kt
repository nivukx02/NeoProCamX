package com.neoproc.neoproccamx.rendering

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProjectOwnedNdrPreviewTransformPolicyTest {
    @Test
    fun `all physical rotations select the proven fixed NDR presentation`() {
        val expectedSamplingByPhysicalRotation = mapOf(
            0 to 270,
            90 to 270,
            180 to 270,
            270 to 270
        )

        expectedSamplingByPhysicalRotation.forEach { (physicalRotation, expectedSampling) ->
            val cameraRotation = (360 - physicalRotation) % 360
            val transform = ProjectOwnedNdrPreviewTransformPolicy.resolve(
                sensorOrientationDegrees = 90,
                latestDeviceRotationDegrees = cameraRotation,
                lensFacing = CameraCharacteristics.LENS_FACING_BACK
            )

            assertEquals(
                "Incorrect NDR sampling for physical rotation $physicalRotation",
                expectedSampling,
                transform.rotationDegrees
            )
            assertFalse(transform.mirrored)
        }
    }

    @Test
    fun `landscape remains on the fixed NDR presentation path`() {
        val landscapeCameraRotation = 270
        val transform = ProjectOwnedNdrPreviewTransformPolicy.resolve(
            sensorOrientationDegrees = 90,
            latestDeviceRotationDegrees = landscapeCameraRotation,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK
        )

        assertEquals(270, transform.rotationDegrees)
    }

    @Test
    fun `360 and non-quadrant input execute orientation zero resolver branch`() {
        listOf(360, -360, 45).forEach { deviceRotation ->
            val transform = ProjectOwnedNdrPreviewTransformPolicy.resolve(
                sensorOrientationDegrees = 90,
                latestDeviceRotationDegrees = deviceRotation,
                lensFacing = CameraCharacteristics.LENS_FACING_BACK
            )

            assertEquals(270, transform.rotationDegrees)
            assertFalse(transform.mirrored)
        }
    }
}
