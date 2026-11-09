package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Camera2RecordingNoiseCleanupPolicyTest {

    @Test
    fun `encoded recording forces high quality cleanup even when noise toggle is off`() {
        val resolved = Camera2RecordingNoiseCleanupPolicy.resolve(
            profile = Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT,
            androidApiLevel = Build.VERSION_CODES.TIRAMISU
        )

        requireNotNull(resolved)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, resolved.noiseReductionMode)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, resolved.hotPixelMode)
        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, resolved.edgeMode)
        assertEquals(CaptureRequest.SHADING_MODE_HIGH_QUALITY, resolved.shadingMode)
        assertEquals(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY, resolved.tonemapMode)
        assertEquals(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
            resolved.aberrationMode
        )
        assertEquals(
            CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
            resolved.distortionCorrectionMode
        )
    }

    @Test
    fun `encoded recording leaves fast-only cleanup modes unset in strict profile`() {
        val resolved = Camera2RecordingNoiseCleanupPolicy.resolve(
            profile = Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT,
            androidApiLevel = Build.VERSION_CODES.TIRAMISU,
            availability = IspBypassAvailability(
                tonemapModes = listOf(CaptureRequest.TONEMAP_MODE_FAST),
                edgeModes = listOf(CaptureRequest.EDGE_MODE_FAST),
                noiseReductionModes = listOf(CaptureRequest.NOISE_REDUCTION_MODE_FAST),
                hotPixelModes = listOf(CaptureRequest.HOT_PIXEL_MODE_FAST),
                shadingModes = listOf(CaptureRequest.SHADING_MODE_FAST),
                colorAberrationModes = listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST),
                distortionCorrectionModes = listOf(CaptureRequest.DISTORTION_CORRECTION_MODE_FAST)
            )
        )

        requireNotNull(resolved)
        assertNull(resolved.noiseReductionMode)
        assertNull(resolved.hotPixelMode)
        assertNull(resolved.edgeMode)
        assertNull(resolved.shadingMode)
        assertNull(resolved.tonemapMode)
        assertNull(resolved.aberrationMode)
        assertNull(resolved.distortionCorrectionMode)
    }

    @Test
    fun `raw sensor recording omits unsupported off cleanup keys instead of requesting unavailable modes`() {
        val resolved = Camera2RecordingNoiseCleanupPolicy.resolve(
            profile = Camera2NoiseCleanupProfile.RAW_SENSOR_TRUTHFUL,
            androidApiLevel = Build.VERSION_CODES.TIRAMISU,
            availability = IspBypassAvailability(
                tonemapModes = emptyList(),
                edgeModes = listOf(CaptureRequest.EDGE_MODE_FAST),
                noiseReductionModes = listOf(CaptureRequest.NOISE_REDUCTION_MODE_FAST),
                hotPixelModes = listOf(CaptureRequest.HOT_PIXEL_MODE_FAST),
                shadingModes = listOf(CaptureRequest.SHADING_MODE_FAST),
                colorAberrationModes = listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST),
                distortionCorrectionModes = listOf(CaptureRequest.DISTORTION_CORRECTION_MODE_FAST)
            )
        )

        requireNotNull(resolved)
        assertNull(resolved.noiseReductionMode)
        assertNull(resolved.hotPixelMode)
        assertNull(resolved.edgeMode)
        assertNull(resolved.shadingMode)
        assertNull(resolved.aberrationMode)
        assertNull(resolved.distortionCorrectionMode)
    }

    @Test
    fun `raw sensor recording keeps hardware cleanup off for truthful raw output`() {
        val resolved = Camera2RecordingNoiseCleanupPolicy.resolve(
            profile = Camera2NoiseCleanupProfile.RAW_SENSOR_TRUTHFUL,
            androidApiLevel = Build.VERSION_CODES.TIRAMISU
        )

        requireNotNull(resolved)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_OFF, resolved.noiseReductionMode)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_OFF, resolved.hotPixelMode)
        assertEquals(CaptureRequest.EDGE_MODE_OFF, resolved.edgeMode)
        assertEquals(CaptureRequest.SHADING_MODE_OFF, resolved.shadingMode)
        assertNull(resolved.tonemapMode)
        assertEquals(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF, resolved.aberrationMode)
        assertEquals(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF, resolved.distortionCorrectionMode)
    }

    @Test
    fun `preview does not override the normal preview processing policy`() {
        assertNull(
            Camera2RecordingNoiseCleanupPolicy.resolve(
                profile = Camera2NoiseCleanupProfile.PREVIEW,
                androidApiLevel = Build.VERSION_CODES.TIRAMISU
            )
        )
    }
}
