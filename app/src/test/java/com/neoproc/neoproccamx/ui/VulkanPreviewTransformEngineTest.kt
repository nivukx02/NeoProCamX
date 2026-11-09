package com.neoproc.neoproccamx.ui

import android.hardware.camera2.CameraCharacteristics
import com.neoproc.neoproccamx.camera.FrameGeometry
import com.neoproc.neoproccamx.camera.NormalizedCropRegion
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Test

class VulkanPreviewTransformEngineTest {

    @Test
    fun buildTransformMatrix_prefersResolvedDisplayContentAspectOverSensorSpaceAspect() {
        val geometry = FrameGeometry(
            sensorOrientationDegrees = 90,
            deviceRotationDegrees = 0,
            finalRotationDegrees = 0,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            sensorAspectRatio = 4f / 3f,
            outputAspectRatio = 4f / 3f,
            cropRegion = null,
            normalizedCropRegion = NormalizedCropRegion.FULL
        )

        val matrix = VulkanPreviewTransformEngine.buildTransformMatrix(
            geometry = geometry,
            displayAspect = 1080f / 2400f,
            fallbackContentAspect = null,
            resolvedDisplayContentAspect = 9f / 16f
        )

        assertEquals(1f, matrix[0], 0.0001f)
        assertEquals(1f, matrix[5], 0.0001f)
        assertEquals(0f, matrix[13], 0.0001f)
    }

    @Test
    fun buildTransformMatrix_doesNotReflipResolvedPortraitAspectDuringQuarterTurnRotation() {
        val geometry = FrameGeometry(
            sensorOrientationDegrees = 90,
            deviceRotationDegrees = 0,
            finalRotationDegrees = 90,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            sensorAspectRatio = 4f / 3f,
            outputAspectRatio = 4f / 3f,
            cropRegion = null,
            normalizedCropRegion = NormalizedCropRegion.FULL
        )

        val matrix = VulkanPreviewTransformEngine.buildTransformMatrix(
            geometry = geometry,
            displayAspect = 3f / 4f,
            fallbackContentAspect = 4f / 3f,
            resolvedDisplayContentAspect = 3f / 4f
        )

        assertEquals(1f, abs(matrix[1]), 0.0001f)
        assertEquals(1f, abs(matrix[4]), 0.0001f)
    }

    @Test
    fun buildTransformMatrix_fitsResolvedPortraitContentWithoutSurfaceLayerStretch() {
        val matrix = VulkanPreviewTransformEngine.buildTransformMatrix(
            geometry = null,
            displayAspect = 1080f / 2400f,
            fallbackContentAspect = 4f / 3f,
            resolvedDisplayContentAspect = 9f / 16f
        )

        assertEquals(1f, matrix[0], 0.0001f)
        assertEquals(1f, matrix[5], 0.0001f)
        assertEquals(0f, matrix[12], 0.0001f)
        assertEquals(0f, matrix[13], 0.0001f)
    }

    @Test
    fun buildTransformMatrix_keepsFourThreePortraitContentUncroppedAndCentered() {
        val matrix = VulkanPreviewTransformEngine.buildTransformMatrix(
            geometry = null,
            displayAspect = 1080f / 2400f,
            fallbackContentAspect = 4f / 3f,
            resolvedDisplayContentAspect = 3f / 4f
        )

        assertEquals(1f, matrix[0], 0.0001f)
        assertEquals(1f, matrix[5], 0.0001f)
        assertEquals(0f, matrix[12], 0.0001f)
        assertEquals(0f, matrix[13], 0.0001f)
    }
}
