package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlMathTest {
    @Test
    fun `display point maps through front mirror rotation and crop`() {
        val mapped = CameraControlMath.mapDisplayPointToSensor(
            normX = 0.2f,
            normY = 0.3f,
            lensFacing = CameraCharacteristics.LENS_FACING_FRONT,
            displayRotationDegrees = 90,
            normalizedCropRegion = NormalizedCropRegion(0.1f, 0.2f, 0.9f, 0.8f)
        )

        assertEquals(0.34f, mapped.first, 0.0001f)
        assertEquals(0.32f, mapped.second, 0.0001f)
    }

    @Test
    fun `crop region preserves requested aspect before zoom`() {
        val crop = CameraControlMath.buildAspectAndZoomCropRegion(
            sensorRect = CameraRect(0, 0, 4000, 3000),
            targetAspectRatio = 16f / 9f,
            zoom = 1f,
            maxZoom = 10f
        )

        assertEquals(CameraRect(0, 375, 4000, 2625), crop)
    }

    @Test
    fun `live repeating requests keep full sensor aspect instead of UI framing crop`() {
        val aspect = CameraControlMath.resolveCaptureRequestAspectRatio(
            sensorAspectRatio = 4f / 3f,
            targetOutputAspectRatio = 16f / 9f,
            includeAspectCrop = false
        )

        assertEquals(4f / 3f, aspect, 0.0001f)
    }

    @Test
    fun `still capture requests preserve selected framing aspect`() {
        val aspect = CameraControlMath.resolveCaptureRequestAspectRatio(
            sensorAspectRatio = 4f / 3f,
            targetOutputAspectRatio = 16f / 9f,
            includeAspectCrop = true
        )

        assertEquals(16f / 9f, aspect, 0.0001f)
    }

    @Test
    fun `preview viewport display aspect is normalized before camera crop`() {
        assertEquals(16f / 9f, CameraControlMath.sensorOutputAspectFromPreviewViewport(9f / 16f)!!, 0.0001f)
        assertEquals(4f / 3f, CameraControlMath.sensorOutputAspectFromPreviewViewport(3f / 4f)!!, 0.0001f)
        assertEquals(16f / 9f, CameraControlMath.sensorOutputAspectFromPreviewViewport(16f / 9f)!!, 0.0001f)
        assertNull(CameraControlMath.sensorOutputAspectFromPreviewViewport(null))
    }

    @Test
    fun `preview exposure budget respects 60fps target`() {
        val exposure = CameraControlMath.previewSafeExposure(
            requestedExposureNs = 40_000_000L,
            previewFps = 30,
            exposureLowerNs = 100_000L,
            exposureUpperNs = 1_000_000_000L
        )

        assertEquals(16_666_666L, exposure)
    }

    @Test
    fun `fps resolver prefers exact or enclosing 60fps range`() {
        val range = CameraControlMath.resolveFpsRange(
            targetFps = 60,
            fpsList = listOf(
                CameraIntRange(24, 30),
                CameraIntRange(30, 60),
                CameraIntRange(60, 60)
            )
        )

        assertEquals(CameraIntRange(60, 60), range)
    }

    @Test
    fun `aspect size chooser keeps closest aspect under bounds`() {
        val size = CameraControlMath.chooseSizeForAspect(
            sizes = listOf(CameraSize(1920, 1080), CameraSize(1440, 1080), CameraSize(1280, 720)),
            targetAspect = 4f / 3f,
            maxWidth = 1920,
            maxHeight = 1440
        )

        assertEquals(CameraSize(1440, 1080), size)
    }

    @Test
    fun `exposure region refresh is disabled while manual exposure is active`() {
        val shouldRefresh = CameraControlMath.shouldRefreshExposureRegion(
            supportsManualSensor = true,
            manualIso = 200,
            manualExposureNs = 8_000_000L,
            currentRegion = CameraRect(0, 0, 100, 100),
            lastUpdateElapsedMs = 1000L,
            nowElapsedMs = 3000L,
            candidate = CameraRect(20, 20, 120, 120)
        )

        assertFalse(shouldRefresh)
    }

    @Test
    fun `largest size chooser falls back to any largest when bounds exclude all`() {
        val size = CameraControlMath.chooseLargestSizeWithinBounds(
            sizes = listOf(CameraSize(4000, 3000), CameraSize(1920, 1080)),
            maxWidth = 1280,
            maxHeight = 720
        )

        assertNotNull(size)
        assertTrue(size == CameraSize(4000, 3000))
    }

    @Test
    fun `largest size chooser remains correct for very large resolutions`() {
        val size = CameraControlMath.chooseLargestSizeWithinBounds(
            sizes = listOf(
                CameraSize(50_000, 50_000),
                CameraSize(44_000, 44_000)
            ),
            maxWidth = Int.MAX_VALUE,
            maxHeight = Int.MAX_VALUE
        )

        assertEquals(CameraSize(50_000, 50_000), size)
    }
}
