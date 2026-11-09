package com.neoproc.neoproccamx.rendering

import android.hardware.camera2.CameraCharacteristics
import com.neoproc.neoproccamx.camera.FrameGeometry
import com.neoproc.neoproccamx.camera.NormalizedCropRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTransformStateResolverTest {

    @Test
    fun usesGeometryPreviewRotationWhenAvailable() {
        val geometry = FrameGeometry(
            sensorOrientationDegrees = 90,
            deviceRotationDegrees = 0,
            finalRotationDegrees = 270,
            lensFacing = CameraCharacteristics.LENS_FACING_FRONT,
            sensorAspectRatio = 4f / 3f,
            outputAspectRatio = 16f / 9f,
            cropRegion = null,
            normalizedCropRegion = NormalizedCropRegion.FULL
        )

        val state = PreviewTransformStateResolver.resolve(
            geometry = geometry,
            sensorOrientation = 90,
            deviceRotationDegrees = 180,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK
        )

        assertEquals(270, state.rotationDegrees)
        assertTrue(state.mirrored)
    }

    @Test
    fun fallsBackToSensorOutputRotationWithoutGeometry() {
        val state = PreviewTransformStateResolver.resolve(
            geometry = null,
            sensorOrientation = 90,
            deviceRotationDegrees = 90,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK
        )

        assertEquals(0, state.rotationDegrees)
        assertFalse(state.mirrored)
    }

    @Test
    fun mirrorsFrontPreviewWithoutGeometryFallback() {
        val state = PreviewTransformStateResolver.resolve(
            geometry = null,
            sensorOrientation = 270,
            deviceRotationDegrees = 0,
            lensFacing = CameraCharacteristics.LENS_FACING_FRONT
        )

        assertTrue(state.mirrored)
    }

    @Test
    fun normalizesGeometryRotationIntoQuarterTurnRange() {
        val geometry = FrameGeometry(
            sensorOrientationDegrees = 90,
            deviceRotationDegrees = 0,
            finalRotationDegrees = 450,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            sensorAspectRatio = 4f / 3f,
            outputAspectRatio = 16f / 9f,
            cropRegion = null,
            normalizedCropRegion = NormalizedCropRegion.FULL
        )

        val state = PreviewTransformStateResolver.resolve(
            geometry = geometry,
            sensorOrientation = 90,
            deviceRotationDegrees = 0,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK
        )

        assertEquals(90, state.rotationDegrees)
    }
}
