package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for CameraRequestQualityProfile.
 *
 * Preview stays sharpness-first, and the high-end policy keeps processed video
 * on the best available ISP quality stages unless the RAW path owns the sensor.
 */
class CameraRequestQualityProfileTest {

    private inline fun withStrictHighEndOnly(
        enabled: Boolean,
        block: () -> Unit
    ) {
        val previousMaster = HighEndQualityPolicy.enabled
        val previous = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = enabled
        HighEndQualityPolicy.strictHighEndOnly = enabled
        try {
            block()
        } finally {
            HighEndQualityPolicy.enabled = previousMaster
            HighEndQualityPolicy.strictHighEndOnly = previous
        }
    }

    @Test
    fun `preview profile uses high quality for sharpness critical stages`() {
        withStrictHighEndOnly(enabled = false) {
            val profile = CameraRequestQualityProfiles.create(
                noiseReductionEnabled = true,
                forStillCapture = false,
                faceDetectionEnabled = true,
                preferredFaceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
            )

            // Keep denoise and edge quality consistent across capture modes.
            assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, profile.noiseReductionMode)
            assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, profile.hotPixelMode)
            assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, profile.edgeMode)
            assertEquals(CaptureRequest.SHADING_MODE_HIGH_QUALITY, profile.shadingMode)
            assertEquals(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY, profile.tonemapMode)
            assertEquals(CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE, profile.faceDetectMode)

            assertEquals(
                CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
                profile.distortionCorrectionMode
            )
            assertEquals(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
                profile.aberrationMode
            )
            assertEquals(QualityTier.CINEMA, profile.qualityTier)
        }
    }

    @Test
    fun `preview profile keeps distortion correction high quality`() {
        withStrictHighEndOnly(enabled = false) {
            val profile = CameraRequestQualityProfiles.create(
                noiseReductionEnabled = true,
                forStillCapture = false,
                faceDetectionEnabled = false,
                preferredFaceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
            )

            assertEquals(
                CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
                profile.distortionCorrectionMode
            )
        }
    }

    @Test
    fun `strict high end processed video profiles use cinema hardware stages`() {
        withStrictHighEndOnly(enabled = true) {
            val profiles = listOf(
                CameraRequestQualityProfiles.create(
                    noiseReductionEnabled = true,
                    forStillCapture = false,
                    faceDetectionEnabled = true,
                    preferredFaceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
                ),
                CameraRequestQualityProfiles.createBalancedProfile(
                    faceDetectionEnabled = true,
                    preferredFaceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
                ),
                CameraRequestQualityProfiles.createFastProfile(
                    noiseReductionEnabled = true,
                    faceDetectionEnabled = true,
                    preferredFaceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
                ),
                CameraRequestQualityProfiles.createHighSpeedProfile()
            )

            profiles.forEach { profile ->
                assertEquals(QualityTier.CINEMA, profile.qualityTier)
                assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, profile.noiseReductionMode)
                assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, profile.hotPixelMode)
                assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, profile.edgeMode)
                assertEquals(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY, profile.tonemapMode)
                assertEquals(CaptureRequest.SHADING_MODE_HIGH_QUALITY, profile.shadingMode)
                assertEquals(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY, profile.distortionCorrectionMode)
                assertEquals(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY, profile.aberrationMode)
            }
        }
    }

    @Test
    fun `still profile preserves high quality hardware stages`() {
        val profile = CameraRequestQualityProfiles.create(
            noiseReductionEnabled = true,
            forStillCapture = true,
            faceDetectionEnabled = false,
            preferredFaceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL
        )

        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, profile.noiseReductionMode)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, profile.hotPixelMode)
        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, profile.edgeMode)
        assertEquals(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY, profile.tonemapMode)
        assertEquals(CaptureRequest.SHADING_MODE_HIGH_QUALITY, profile.shadingMode)
        assertEquals(CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF, profile.faceDetectMode)
        assertEquals(
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
            profile.aberrationMode
        )
    }

    @Test
    fun `noise reduction disable path disables NR but keeps edge high quality`() {
        withStrictHighEndOnly(enabled = false) {
            val profile = CameraRequestQualityProfiles.create(
                noiseReductionEnabled = false,
                forStillCapture = false,
                faceDetectionEnabled = false,
                preferredFaceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
            )

            // NR and hot pixel are disabled
            assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_OFF, profile.noiseReductionMode)
            assertEquals(CaptureRequest.HOT_PIXEL_MODE_OFF, profile.hotPixelMode)
            assertEquals(CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF, profile.faceDetectMode)

            // Edge processing remains independent from the noise-reduction toggle.
            assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, profile.edgeMode)
        }
    }

    @Test
    fun `minimal profile requests distortion correction off for strict bypass`() {
        assertEquals(
            CaptureRequest.DISTORTION_CORRECTION_MODE_OFF,
            CameraRequestQualityProfiles.minimalDistortionCorrectionMode(androidApiLevel = 28)
        )

        val profile = CameraRequestQualityProfiles.createMinimalProfile()

        assertEquals(
            CaptureRequest.DISTORTION_CORRECTION_MODE_OFF,
            profile.distortionCorrectionMode
        )
    }

    @Test
    fun `fast profile uses efficient settings while maintaining edge quality`() {
        withStrictHighEndOnly(enabled = false) {
            val profile = CameraRequestQualityProfiles.createFastProfile(
                noiseReductionEnabled = true,
                faceDetectionEnabled = true,
                preferredFaceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
            )

            // Fast profile still keeps denoise and edge quality explicit.
            assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, profile.noiseReductionMode)
            assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, profile.hotPixelMode)
            assertEquals(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY, profile.tonemapMode)
            assertEquals(CaptureRequest.SHADING_MODE_HIGH_QUALITY, profile.shadingMode)

            // Edge and lens/color cleanup stay high quality for processed cinema video.
            assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, profile.edgeMode)
            assertEquals(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY, profile.distortionCorrectionMode)

            assertEquals(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
                profile.aberrationMode
            )
            assertEquals(QualityTier.CINEMA, profile.qualityTier)
        }
    }

    @Test
    fun `high speed profile keeps denoise and edge quality elevated`() {
        withStrictHighEndOnly(enabled = false) {
            val profile = CameraRequestQualityProfiles.createHighSpeedProfile()

            assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, profile.noiseReductionMode)
            assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, profile.hotPixelMode)
            assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, profile.edgeMode)
            assertEquals(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY, profile.tonemapMode)
            assertEquals(CaptureRequest.SHADING_MODE_HIGH_QUALITY, profile.shadingMode)
            assertEquals(NoiseReductionStrategy.TEMPORAL, profile.noiseReductionStrategy)
            assertEquals(SharpeningStrategy.MAXIMUM, profile.sharpeningStrategy)

            assertEquals(
                CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
                profile.distortionCorrectionMode
            )
            assertEquals(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
                profile.aberrationMode
            )
            assertEquals(QualityTier.CINEMA, profile.qualityTier)
        }
    }

    @Test
    fun `balanced profile keeps all processed video ISP stages high quality`() {
        withStrictHighEndOnly(enabled = false) {
            val profile = CameraRequestQualityProfiles.createBalancedProfile(
                faceDetectionEnabled = true,
                preferredFaceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
            )

            assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, profile.noiseReductionMode)
            assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, profile.hotPixelMode)
            assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, profile.edgeMode)
            assertEquals(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY, profile.tonemapMode)
            assertEquals(CaptureRequest.SHADING_MODE_HIGH_QUALITY, profile.shadingMode)
            assertEquals(
                CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
                profile.distortionCorrectionMode
            )
            assertEquals(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
                profile.aberrationMode
            )
            assertEquals(QualityTier.CINEMA, profile.qualityTier)
        }
    }

    @Test
    fun `adaptive profile keeps cinema tier for 60fps recording`() {
        withStrictHighEndOnly(enabled = false) {
            val profile = CameraRequestQualityProfiles.selectAdaptiveProfile(
                targetFps = 60,
                currentIso = 400,
                isRecording = true,
                thermalFactor = 1f,
                faceDetectionEnabled = false,
                preferredFaceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
            )

            assertEquals(QualityTier.CINEMA, profile.qualityTier)
            assertEquals(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY, profile.tonemapMode)
            assertTrue(profile.estimatedOverhead < 1f)
        }
    }

    @Test
    fun `adaptive profile forces cinema tier in strict high-end mode`() {
        withStrictHighEndOnly(enabled = true) {
            val profile = CameraRequestQualityProfiles.selectAdaptiveProfile(
                targetFps = 60,
                currentIso = 400,
                isRecording = true,
                thermalFactor = 1f,
                faceDetectionEnabled = false,
                preferredFaceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
            )

            assertEquals(QualityTier.CINEMA, profile.qualityTier)
            assertEquals(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY, profile.tonemapMode)
            assertEquals(CaptureRequest.SHADING_MODE_HIGH_QUALITY, profile.shadingMode)
        }
    }
}
