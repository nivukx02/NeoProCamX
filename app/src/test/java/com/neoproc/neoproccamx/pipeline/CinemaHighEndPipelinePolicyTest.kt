package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.camera.Camera2NoiseCleanupProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaHighEndPipelinePolicyTest {

    @Test
    fun strictLowLightLogRecordingBuildsCinemaNoiseSuppressionPlan() {
        val plan = CinemaHighEndPipelinePolicy.resolve(
            request = CinemaHighEndPipelineRequest(
                width = 3840,
                height = 2160,
                fps = 60,
                recording = true,
                mode = CinemaHighEndCaptureMode.LOG,
                estimatedLux = 3f,
                motionScore = 0.04f,
                normalizedNoiseFloor = 0.94f,
                flatRegionStdDev = 15f,
                realIso = 64_000f,
                edgeDensity = 0.07f,
                perceptualSharpness = 0.18f,
                strictHighEnd = true
            )
        )

        assertEquals(Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT, plan.cameraNoiseCleanupProfile)
        assertTrue(plan.forcePreviewRecordingParity)
        assertTrue("temporal=${plan.temporalDenoise}", plan.temporalDenoise >= 0.72f)
        assertTrue("spatial=${plan.spatialDenoise}", plan.spatialDenoise >= 0.98f)
        assertTrue("chroma=${plan.chromaDenoise}", plan.chromaDenoise >= 0.999f)
        assertTrue("sharpeningCeiling=${plan.sharpeningCeiling}", plan.sharpeningCeiling in 0.18f..0.34f)
        assertTrue("sharpeningFloor=${plan.sharpeningFloor}", plan.sharpeningFloor in 0.18f..plan.sharpeningCeiling)
        assertTrue("bitrateMultiplier=${plan.encoderBitrateMultiplier}", plan.encoderBitrateMultiplier >= 2.5f)
        assertTrue(plan.disableSyntheticDither)
    }

    @Test
    fun cleanDetailedRecordingPreservesMoreDetailThanNoisyShadowRecording() {
        val clean = CinemaHighEndPipelinePolicy.resolve(
            request = CinemaHighEndPipelineRequest(
                width = 3840,
                height = 2160,
                fps = 30,
                recording = true,
                mode = CinemaHighEndCaptureMode.PRO,
                estimatedLux = 120f,
                motionScore = 0.02f,
                normalizedNoiseFloor = 0.04f,
                flatRegionStdDev = 1.2f,
                realIso = 160f,
                edgeDensity = 0.30f,
                perceptualSharpness = 0.74f,
                strictHighEnd = true
            )
        )
        val noisy = CinemaHighEndPipelinePolicy.resolve(
            request = CinemaHighEndPipelineRequest(
                width = 3840,
                height = 2160,
                fps = 30,
                recording = true,
                mode = CinemaHighEndCaptureMode.PRO,
                estimatedLux = 4f,
                motionScore = 0.04f,
                normalizedNoiseFloor = 0.90f,
                flatRegionStdDev = 14f,
                realIso = 48_000f,
                edgeDensity = 0.06f,
                perceptualSharpness = 0.18f,
                strictHighEnd = true
            )
        )

        assertTrue(clean.spatialDenoise < noisy.spatialDenoise)
        assertTrue(clean.chromaDenoise < noisy.chromaDenoise)
        assertTrue(clean.sharpeningFloor > noisy.sharpeningFloor)
        assertTrue(clean.sharpeningCeiling > noisy.sharpeningCeiling)
    }

    @Test
    fun logRecordingAccumulatesModeCleanupOnTopOfRecordingCleanup() {
        val pro = CinemaHighEndPipelinePolicy.resolve(
            request = cleanNonStrictRequest(mode = CinemaHighEndCaptureMode.PRO)
        )
        val log = CinemaHighEndPipelinePolicy.resolve(
            request = cleanNonStrictRequest(mode = CinemaHighEndCaptureMode.LOG)
        )

        assertTrue("temporal pro=${pro.temporalDenoise} log=${log.temporalDenoise}",
            log.temporalDenoise > pro.temporalDenoise + 0.04f)
        assertTrue("spatial pro=${pro.spatialDenoise} log=${log.spatialDenoise}",
            log.spatialDenoise > pro.spatialDenoise + 0.05f)
        assertTrue("chroma pro=${pro.chromaDenoise} log=${log.chromaDenoise}",
            log.chromaDenoise > pro.chromaDenoise + 0.05f)
    }

    @Test
    fun rawRecordingKeepsTruthfulCameraRequestButRequiresCalibration() {
        val plan = CinemaHighEndPipelinePolicy.resolve(
            request = CinemaHighEndPipelineRequest(
                width = 8064,
                height = 6048,
                fps = 24,
                recording = true,
                mode = CinemaHighEndCaptureMode.RAW,
                estimatedLux = 24f,
                motionScore = 0.02f,
                normalizedNoiseFloor = 0.28f,
                flatRegionStdDev = 5.5f,
                realIso = 1_200f,
                edgeDensity = 0.18f,
                perceptualSharpness = 0.40f,
                strictHighEnd = true
            )
        )

        assertEquals(Camera2NoiseCleanupProfile.RAW_SENSOR_TRUTHFUL, plan.cameraNoiseCleanupProfile)
        assertTrue(plan.rawCalibrationRequired)
        assertTrue(plan.highlightRecovery >= 0.24f)
        assertFalse(plan.forceProcessedIspForRaw)
    }

    private fun cleanNonStrictRequest(
        mode: CinemaHighEndCaptureMode
    ): CinemaHighEndPipelineRequest =
        CinemaHighEndPipelineRequest(
            width = 3840,
            height = 2160,
            fps = 30,
            recording = true,
            mode = mode,
            estimatedLux = 120f,
            motionScore = 0.02f,
            normalizedNoiseFloor = 0.04f,
            flatRegionStdDev = 1.2f,
            realIso = 160f,
            edgeDensity = 0.30f,
            perceptualSharpness = 0.74f,
            highEndEnabled = true,
            strictHighEnd = false
        )
}
