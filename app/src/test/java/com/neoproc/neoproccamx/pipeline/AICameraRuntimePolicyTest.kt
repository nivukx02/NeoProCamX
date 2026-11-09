package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AICameraRuntimePolicyTest {

    @Test
    fun resolve_relaxesToConfiguredValuesWhenPreviewIsHealthy() {
        val profile = AICameraRuntimePolicy.resolve(
            previewFps = 60f,
            recording = false,
            baseObjectDetectionStride = 3,
            basePoseDetectionStride = 3,
            baseSegmentationStride = 4,
            baseDepthEstimationStride = 5,
            baseEnhancementStride = 3,
            baseSceneDetectionStride = 8,
            baseAnalysisTimeoutMs = 12L,
            baseMaxAnalysisDimension = 640,
            highEndQualityEnabled = false
        )

        assertEquals(640, profile.analysisMaxDimension)
        assertEquals(3, profile.objectDetectionStride)
        assertEquals(28L, profile.analysisTimeoutMs)
    }

    @Test
    fun resolve_aggressivelyDegradesWhenRecordingFpsDrops() {
        val profile = AICameraRuntimePolicy.resolve(
            previewFps = 18f,
            recording = true,
            baseObjectDetectionStride = 3,
            basePoseDetectionStride = 3,
            baseSegmentationStride = 4,
            baseDepthEstimationStride = 5,
            baseEnhancementStride = 3,
            baseSceneDetectionStride = 8,
            baseAnalysisTimeoutMs = 12L,
            baseMaxAnalysisDimension = 640,
            highEndQualityEnabled = false
        )

        assertEquals(384, profile.analysisMaxDimension)
        assertEquals(9, profile.objectDetectionStride)
        assertEquals(14, profile.sceneDetectionStride)
        assertEquals(18L, profile.analysisTimeoutMs)
    }

    @Test
    fun resolve_keepsAnalysisDimensionAboveSafetyFloor() {
        val profile = AICameraRuntimePolicy.resolve(
            previewFps = 10f,
            recording = true,
            baseObjectDetectionStride = 1,
            basePoseDetectionStride = 1,
            baseSegmentationStride = 1,
            baseDepthEstimationStride = 1,
            baseEnhancementStride = 1,
            baseSceneDetectionStride = 1,
            baseAnalysisTimeoutMs = 1L,
            baseMaxAnalysisDimension = 128,
            highEndQualityEnabled = false
        )

        assertTrue(profile.analysisMaxDimension >= 256)
    }

    @Test
    fun resolve_highEndUltraTierBoostsAnalysisDensity() {
        val profile = withStrictHighEndOnly(false) {
            AICameraRuntimePolicy.resolve(
                previewFps = 60f,
                recording = false,
                baseObjectDetectionStride = 3,
                basePoseDetectionStride = 3,
                baseSegmentationStride = 4,
                baseDepthEstimationStride = 5,
                baseEnhancementStride = 3,
                baseSceneDetectionStride = 8,
                baseAnalysisTimeoutMs = 12L,
                baseMaxAnalysisDimension = 640,
                highEndQualityEnabled = true
            )
        }

        assertTrue(profile.analysisMaxDimension > 640)
        assertTrue(profile.objectDetectionStride <= 2)
        assertTrue(profile.analysisTimeoutMs >= 34L)
    }

    @Test
    fun resolve_strictHighEndOnlyBoostsAllAiStages() {
        val profile = withStrictHighEndOnly(true) {
            AICameraRuntimePolicy.resolve(
                previewFps = 60f,
                recording = false,
                baseObjectDetectionStride = 3,
                basePoseDetectionStride = 3,
                baseSegmentationStride = 4,
                baseDepthEstimationStride = 5,
                baseEnhancementStride = 3,
                baseSceneDetectionStride = 8,
                baseAnalysisTimeoutMs = 12L,
                baseMaxAnalysisDimension = 640,
                highEndQualityEnabled = true
            )
        }

        assertTrue(profile.analysisMaxDimension >= 1280)
        assertTrue(profile.objectDetectionStride <= 1)
        assertTrue(profile.poseDetectionStride <= 1)
        assertTrue(profile.segmentationStride <= 2)
        assertTrue(profile.depthEstimationStride <= 2)
        assertTrue(profile.enhancementStride <= 1)
        assertTrue(profile.sceneDetectionStride <= 2)
        assertTrue(profile.analysisTimeoutMs >= 44L)
    }

    @Test
    fun resolve_strictHighEndOnlyKeepsRecordingAiNearPerFrame() {
        val profile = withStrictHighEndOnly(true) {
            AICameraRuntimePolicy.resolve(
                previewFps = 52f,
                recording = true,
                baseObjectDetectionStride = 6,
                basePoseDetectionStride = 6,
                baseSegmentationStride = 8,
                baseDepthEstimationStride = 10,
                baseEnhancementStride = 7,
                baseSceneDetectionStride = 10,
                baseAnalysisTimeoutMs = 18L,
                baseMaxAnalysisDimension = 640,
                highEndQualityEnabled = true
            )
        }

        assertTrue(profile.analysisMaxDimension >= 960)
        assertTrue(profile.objectDetectionStride <= 2)
        assertTrue(profile.poseDetectionStride <= 2)
        assertTrue(profile.segmentationStride <= 3)
        assertTrue(profile.depthEstimationStride <= 3)
        assertTrue(profile.enhancementStride <= 2)
        assertTrue(profile.sceneDetectionStride <= 3)
        assertTrue(profile.analysisTimeoutMs >= 34L)
    }

    @Test
    fun resolve_strictHighEndOnly_lowRecordingFpsProtectsRealtimePreview() {
        val profile = withStrictHighEndOnly(true) {
            AICameraRuntimePolicy.resolve(
                previewFps = 24f,
                recording = true,
                baseObjectDetectionStride = 6,
                basePoseDetectionStride = 6,
                baseSegmentationStride = 8,
                baseDepthEstimationStride = 10,
                baseEnhancementStride = 7,
                baseSceneDetectionStride = 10,
                baseAnalysisTimeoutMs = 18L,
                baseMaxAnalysisDimension = 640,
                highEndQualityEnabled = true
            )
        }

        assertTrue(profile.analysisMaxDimension <= 896)
        assertTrue(profile.objectDetectionStride >= 4)
        assertTrue(profile.sceneDetectionStride >= 5)
        assertTrue(profile.analysisTimeoutMs <= 32L)
    }

    @Test
    fun resolve_nonStrictCinemaTierEnablesPerFrameSubjectTracking() {
        val profile = withStrictHighEndOnly(false) {
            AICameraRuntimePolicy.resolve(
                previewFps = 60f,
                recording = false,
                baseObjectDetectionStride = 3,
                basePoseDetectionStride = 3,
                baseSegmentationStride = 4,
                baseDepthEstimationStride = 5,
                baseEnhancementStride = 3,
                baseSceneDetectionStride = 8,
                baseAnalysisTimeoutMs = 12L,
                baseMaxAnalysisDimension = 640,
                highEndQualityEnabled = true
            )
        }

        assertEquals(1, profile.objectDetectionStride)
        assertTrue(profile.analysisMaxDimension >= 1024)
        assertTrue(profile.analysisTimeoutMs >= 34L)
    }

    @Test
    fun resolve_maximumComputationalCinemaPlanDrivesDenseLiveAiProfile() {
        val cinemaPlan = ComputationalCinemaPlanResolver.resolve(
            request = ComputationalCinemaRequest(
                mode = ComputationalCinemaMode.CINEMATIC_IMAX,
                quality = ComputationalCinemaQuality.MAXIMUM,
                zoomRatio = 2.0f,
                recording = true
            ),
            hardware = computationalHardware(),
            runtime = computationalRuntime()
        )

        val profile = withStrictHighEndOnly(true) {
            AICameraRuntimePolicy.resolve(
                previewFps = 60f,
                recording = true,
                baseObjectDetectionStride = 6,
                basePoseDetectionStride = 6,
                baseSegmentationStride = 8,
                baseDepthEstimationStride = 10,
                baseEnhancementStride = 7,
                baseSceneDetectionStride = 10,
                baseAnalysisTimeoutMs = 18L,
                baseMaxAnalysisDimension = 640,
                computationalCinemaPlan = cinemaPlan
            )
        }

        assertEquals(cinemaPlan.analysisLongEdge, profile.analysisMaxDimension)
        assertEquals(1, profile.objectDetectionStride)
        assertEquals(1, profile.enhancementStride)
        assertTrue(profile.analysisTimeoutMs >= 46L)
    }

    @Test
    fun resolve_postProcessFallbackPlanCapsLiveAiLoad() {
        val cinemaPlan = ComputationalCinemaPlanResolver.resolve(
            request = ComputationalCinemaRequest(
                mode = ComputationalCinemaMode.CINEMATIC_IMAX,
                quality = ComputationalCinemaQuality.MAXIMUM,
                zoomRatio = 2.0f,
                recording = true
            ),
            hardware = computationalHardware(),
            runtime = computationalRuntime().copy(
                previewFps = 21f,
                thermalSeverity = 4,
                droppedFrames = 18,
                encoderPressure = 0.90f
            )
        )

        val profile = withStrictHighEndOnly(true) {
            AICameraRuntimePolicy.resolve(
                previewFps = 21f,
                recording = true,
                baseObjectDetectionStride = 6,
                basePoseDetectionStride = 6,
                baseSegmentationStride = 8,
                baseDepthEstimationStride = 10,
                baseEnhancementStride = 7,
                baseSceneDetectionStride = 10,
                baseAnalysisTimeoutMs = 18L,
                baseMaxAnalysisDimension = 640,
                computationalCinemaPlan = cinemaPlan
            )
        }

        assertTrue(profile.analysisMaxDimension <= 960)
        assertTrue(profile.segmentationStride >= 4)
        assertTrue(profile.depthEstimationStride >= 4)
        assertTrue(profile.analysisTimeoutMs <= 34L)
    }

    private inline fun <T> withStrictHighEndOnly(
        enabled: Boolean,
        block: () -> T
    ): T {
        val previous = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.strictHighEndOnly = enabled
        return try {
            block()
        } finally {
            HighEndQualityPolicy.strictHighEndOnly = previous
        }
    }

    private fun computationalHardware() = ComputationalCinemaHardware(
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

    private fun computationalRuntime() = ComputationalCinemaRuntime(
        previewFps = 60f,
        targetFps = 60,
        thermalSeverity = 0,
        droppedFrames = 0,
        encoderPressure = 0.10f,
        estimatedLux = 120f,
        motionScore = 0.04f
    )
}
