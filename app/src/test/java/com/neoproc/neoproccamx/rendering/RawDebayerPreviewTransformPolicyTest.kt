package com.neoproc.neoproccamx.rendering

import android.hardware.camera2.CameraCharacteristics
import com.neoproc.neoproccamx.utils.OrientationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDebayerPreviewTransformPolicyTest {
    @Test
    fun surfaceOwnedGeometryPreventsDoubleRotationAndMirror() {
        val transform = RawDebayerPreviewTransformPolicy.resolve(90, true, true)
        assertEquals(0, transform.rotationDegrees)
        assertFalse(transform.mirrored)
    }

    @Test
    fun textureOwnedGeometryUsesInverseSamplingRotation() {
        val transform = RawDebayerPreviewTransformPolicy.resolve(-90, true, false)
        assertEquals(90, transform.rotationDegrees)
        assertTrue(transform.mirrored)
    }

    @Test
    fun portraitDisplayRotationMapsToOppositeTextureSamplingRotation() {
        assertEquals(
            270,
            RawDebayerPreviewTransformPolicy.resolve(90, false, false).rotationDegrees
        )
        assertEquals(
            90,
            RawDebayerPreviewTransformPolicy.resolve(270, false, false).rotationDegrees
        )
        assertEquals(
            180,
            RawDebayerPreviewTransformPolicy.resolve(180, false, false).rotationDegrees
        )
    }

    @Test
    fun backCameraUsesCurrentSensorToDisplayGeometryForEveryPhysicalOrientation() {
        val expectedSamplingRotations = mapOf(
            0 to 270,
            90 to 0,
            180 to 90,
            270 to 180
        )

        expectedSamplingRotations.forEach { (deviceRotation, expectedSamplingRotation) ->
            val previewRotation = OrientationManager.getSensorToOutputRotation(
                sensorOrientation = 90,
                deviceRotationDegrees = deviceRotation,
                lensFacing = CameraCharacteristics.LENS_FACING_BACK
            )
            val transform = RawDebayerPreviewTransformPolicy.resolve(
                previewRotationDegrees = previewRotation,
                previewMirrored = false,
                surfacePresentationOwnsGeometry = false
            )

            assertEquals(
                "Incorrect RAW sampling rotation for device rotation $deviceRotation",
                expectedSamplingRotation,
                transform.rotationDegrees
            )
            assertFalse(transform.mirrored)
        }
    }

    @Test
    fun frontCameraUsesCurrentSensorToDisplayGeometryAndMirrorForEveryOrientation() {
        val expectedSamplingRotations = mapOf(
            0 to 270,
            90 to 180,
            180 to 90,
            270 to 0
        )

        expectedSamplingRotations.forEach { (deviceRotation, expectedSamplingRotation) ->
            val previewRotation = OrientationManager.getSensorToOutputRotation(
                sensorOrientation = 90,
                deviceRotationDegrees = deviceRotation,
                lensFacing = CameraCharacteristics.LENS_FACING_FRONT
            )
            val transform = RawDebayerPreviewTransformPolicy.resolve(
                previewRotationDegrees = previewRotation,
                previewMirrored = true,
                surfacePresentationOwnsGeometry = false
            )

            assertEquals(
                "Incorrect front RAW sampling rotation for device rotation $deviceRotation",
                expectedSamplingRotation,
                transform.rotationDegrees
            )
            assertTrue(transform.mirrored)
        }
    }
}
