package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputationalCinemaPlanResolverTest {

    @Test
    fun maximumImaxStyleOnCapableHardwareEnablesFullLiveCinemaStack() {
        val plan = ComputationalCinemaPlanResolver.resolve(
            request = ComputationalCinemaRequest(
                mode = ComputationalCinemaMode.CINEMATIC_IMAX,
                quality = ComputationalCinemaQuality.MAXIMUM,
                zoomRatio = 2.0f,
                recording = true
            ),
            hardware = capableHardware(),
            runtime = healthyRuntime()
        )

        assertEquals(ComputationalCinemaDelivery.LIVE_REAL_TIME, plan.delivery)
        assertEquals(8, plan.multiFrameCount)
        assertTrue(plan.enableGpuPreview)
        assertTrue(plan.enableZeroCopyImport)
        assertTrue(plan.enableSmartHdr)
        assertTrue(plan.enableTemporalDenoise)
        assertTrue(plan.enableAiSuperResolution)
        assertTrue(plan.enableSkinToneProtection)
        assertTrue(plan.enableFilmicLut)
        assertTrue(plan.enableGyroStabilization)
        assertTrue(plan.requestHevcMain10)
        assertTrue(plan.requestHdrEncoding)
        assertTrue(plan.bitrateMultiplier >= 2.0f)
        assertTrue(plan.sharpeningStrength <= 0.42f)
        assertEquals(emptyList<String>(), plan.warnings)
    }

    @Test
    fun maximumFallsBackToPostProcessingWhenThermalAndFrameBudgetAreUnsafe() {
        val plan = ComputationalCinemaPlanResolver.resolve(
            request = ComputationalCinemaRequest(
                mode = ComputationalCinemaMode.CINEMATIC_IMAX,
                quality = ComputationalCinemaQuality.MAXIMUM,
                zoomRatio = 2.0f,
                recording = true
            ),
            hardware = capableHardware(),
            runtime = healthyRuntime().copy(
                previewFps = 21f,
                thermalSeverity = 4,
                droppedFrames = 18,
                encoderPressure = 0.90f
            )
        )

        assertEquals(ComputationalCinemaDelivery.LIVE_WITH_POST_PROCESS_FALLBACK, plan.delivery)
        assertFalse(plan.enableAiSuperResolution)
        assertTrue(plan.postProcessStages.contains(ComputationalCinemaStage.AI_SUPER_RESOLUTION))
        assertTrue(plan.postProcessStages.contains(ComputationalCinemaStage.HDR_FUSION))
        assertTrue(plan.warnings.any { it.contains("Maximum live pipeline exceeded runtime budget") })
        assertTrue(plan.analysisLongEdge <= 960)
    }

    @Test
    fun unsupportedHardwareReportsLimitsInsteadOfFakingHdrRawOrTenBit() {
        val plan = ComputationalCinemaPlanResolver.resolve(
            request = ComputationalCinemaRequest(
                mode = ComputationalCinemaMode.SMART_HDR,
                quality = ComputationalCinemaQuality.MAXIMUM,
                recording = true
            ),
            hardware = capableHardware().copy(
                supportsRaw = false,
                supportsHdrVideo = false,
                supportsTenBit = false,
                supportsHevcMain10 = false,
                supportsZeroCopyGpuImport = false
            ),
            runtime = healthyRuntime()
        )

        assertFalse(plan.requestRaw)
        assertFalse(plan.requestHdrEncoding)
        assertFalse(plan.requestHevcMain10)
        assertFalse(plan.enableZeroCopyImport)
        assertTrue(plan.warnings.any { it.contains("RAW unavailable") })
        assertTrue(plan.warnings.any { it.contains("HDR video unavailable") })
        assertTrue(plan.warnings.any { it.contains("10-bit unavailable") })
        assertTrue(plan.warnings.any { it.contains("Zero-copy GPU import unavailable") })
    }

    @Test
    fun nightVideoPrioritizesChromaTemporalDenoiseAndProtectsSkinTone() {
        val plan = ComputationalCinemaPlanResolver.resolve(
            request = ComputationalCinemaRequest(
                mode = ComputationalCinemaMode.NIGHT_VIDEO,
                quality = ComputationalCinemaQuality.MAXIMUM,
                recording = true
            ),
            hardware = capableHardware(),
            runtime = healthyRuntime().copy(estimatedLux = 4f, motionScore = 0.03f)
        )

        assertTrue(plan.enableTemporalDenoise)
        assertTrue(plan.chromaDenoiseStrength >= 0.78f)
        assertTrue(plan.spatialDenoiseStrength >= 0.66f)
        assertTrue(plan.skinToneProtectionStrength >= 0.82f)
        assertTrue(plan.sharpeningStrength <= 0.30f)
        assertTrue(plan.multiFrameCount >= 8)
    }

    @Test
    fun maximumNightRecordingRequestsNearCeilingAiNoiseCancellation() {
        val plan = ComputationalCinemaPlanResolver.resolve(
            request = ComputationalCinemaRequest(
                mode = ComputationalCinemaMode.NIGHT_VIDEO,
                quality = ComputationalCinemaQuality.MAXIMUM,
                recording = true
            ),
            hardware = capableHardware(),
            runtime = healthyRuntime().copy(estimatedLux = 2f, motionScore = 0.02f)
        )

        assertTrue("spatialDenoiseStrength=${plan.spatialDenoiseStrength}", plan.spatialDenoiseStrength >= 0.94f)
        assertTrue("chromaDenoiseStrength=${plan.chromaDenoiseStrength}", plan.chromaDenoiseStrength >= 0.985f)
        assertTrue("temporalDenoiseStrength=${plan.temporalDenoiseStrength}", plan.temporalDenoiseStrength >= 0.78f)
        assertTrue(plan.enableTemporalDenoise)
        assertTrue(plan.liveStages.contains(ComputationalCinemaStage.MULTI_FRAME_DENOISE))
    }

    @Test
    fun fastQualityKeepsStableFpsAndDisablesHeavyLiveSuperResolution() {
        val plan = ComputationalCinemaPlanResolver.resolve(
            request = ComputationalCinemaRequest(
                mode = ComputationalCinemaMode.AI_SUPER_RESOLUTION_ZOOM,
                quality = ComputationalCinemaQuality.FAST,
                zoomRatio = 3.0f,
                recording = true
            ),
            hardware = capableHardware(),
            runtime = healthyRuntime()
        )

        assertEquals(ComputationalCinemaDelivery.LIVE_REAL_TIME, plan.delivery)
        assertFalse(plan.enableAiSuperResolution)
        assertTrue(plan.postProcessStages.contains(ComputationalCinemaStage.AI_SUPER_RESOLUTION))
        assertTrue(plan.analysisLongEdge <= 960)
        assertTrue(plan.multiFrameCount <= 3)
    }

    private fun capableHardware() = ComputationalCinemaHardware(
        supportsRaw = true,
        supportsYuvReprocessing = true,
        supportsHdrVideo = true,
        supportsTenBit = true,
        supportsHevcMain10 = true,
        supportsOis = true,
        supportsVideoStabilization = true,
        supportsVulkan = true,
        supportsZeroCopyGpuImport = true,
        supportsAiSuperResolution = true,
        maxEncoderLongEdge = 7680,
        maxSensorLongEdge = 8192
    )

    private fun healthyRuntime() = ComputationalCinemaRuntime(
        previewFps = 60f,
        targetFps = 60,
        thermalSeverity = 0,
        droppedFrames = 0,
        encoderPressure = 0.10f,
        estimatedLux = 120f,
        motionScore = 0.04f
    )
}
