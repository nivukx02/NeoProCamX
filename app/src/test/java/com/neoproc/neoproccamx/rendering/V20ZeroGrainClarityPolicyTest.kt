package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.pipeline.SceneRenderProfile
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V20ZeroGrainClarityPolicyTest {

    @Test
    fun noisySdrRecordingRaisesZeroGrainDenoiseAndCapsUnsafeSharpening() {
        val plan = V20ZeroGrainClarityPolicy.resolve(
            baseRequest(
                signalFamily = GpuSignalFamily.SDR,
                noiseMetric = 0.82f,
                iso = 6400f,
                localChromaSpikeSmoothing = 0.74f,
                profile = profile(
                    spatialDenoise = 0.42f,
                    chromaDenoise = 0.56f,
                    sharpening = 0.92f,
                    noiseMetrics = VideoNoiseMetrics(
                        chromaNoise = 0.84f,
                        lumaNoise = 0.78f,
                        temporalInstability = 0.36f,
                        frameToFrameRgbVariance = 0.88f
                    )
                ),
                sharpeningFloor = 0.18f,
                sharpeningCeiling = 0.86f
            )
        )

        assertTrue(plan.active)
        assertTrue("zero=${plan.zeroGrainLock}", plan.zeroGrainLock >= 0.92f)
        assertTrue("spatial=${plan.spatialDenoise}", plan.spatialDenoise in 0.58f..0.82f)
        assertTrue("chroma=${plan.chromaDenoise}", plan.chromaDenoise in 0.68f..0.90f)
        assertTrue("sharpen=${plan.sharpening}", plan.sharpening <= 0.62f)
        assertTrue(plan.signature.contains("V20_ZERO_GRAIN_CLARITY|active=true"))
    }

    @Test
    fun cleanStaticExtremeDetailRetainsMicroDetailWithinArtifactSafeCeiling() {
        val plan = V20ZeroGrainClarityPolicy.resolve(
            baseRequest(
                signalFamily = GpuSignalFamily.SDR,
                noiseMetric = 0.06f,
                iso = 160f,
                localChromaSpikeSmoothing = 0.05f,
                videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL,
                profile = profile(
                    spatialDenoise = 0.36f,
                    chromaDenoise = 0.40f,
                    sharpening = 0.68f,
                    noiseMetrics = VideoNoiseMetrics(
                        chromaNoise = 0.04f,
                        lumaNoise = 0.05f,
                        temporalInstability = 0.03f,
                        frameToFrameRgbVariance = 0.04f
                    ),
                    detailSharpeningMetadata = mapOf(
                        "detailEdgeConfidence" to "0.92",
                        "detailTextureConfidence" to "0.88",
                        "detailMicroContrast" to "0.80",
                        "detailReconstruction" to "0.76"
                    )
                ),
                sharpeningFloor = 0.42f,
                sharpeningCeiling = 0.94f
            )
        )

        assertTrue(plan.active)
        assertTrue("honesty=${plan.microDetailHonesty}", plan.microDetailHonesty >= 0.70f)
        assertTrue("sharpen=${plan.sharpening}", plan.sharpening >= 0.68f)
        assertTrue("ceiling=${plan.sharpeningCeiling}", plan.sharpeningCeiling <= 0.94f)
        assertTrue("spatial=${plan.spatialDenoise}", plan.spatialDenoise < 0.88f)
    }

    @Test
    fun rawMonitorDoesNotPretendProcessedZeroGrainPathIsActive() {
        val plan = V20ZeroGrainClarityPolicy.resolve(
            baseRequest(signalFamily = GpuSignalFamily.RAW)
        )

        assertFalse(plan.active)
        assertTrue(plan.signature.contains("active=false"))
    }

    private fun baseRequest(
        signalFamily: GpuSignalFamily = GpuSignalFamily.SDR,
        strictHighEnd: Boolean = true,
        normalSdrRealtimeLock: Boolean = true,
        profile: SceneRenderProfile = profile(),
        noiseMetric: Float = 0.20f,
        iso: Float = 400f,
        localChromaSpikeSmoothing: Float = 0.20f,
        sharpeningFloor: Float = 0.24f,
        sharpeningCeiling: Float = 0.86f,
        skinDetailProtection: Float = 0.64f,
        videoDetailProfile: VideoDetailProfile? = VideoDetailProfile.NATURAL_DETAIL
    ): V20ZeroGrainClarityRequest =
        V20ZeroGrainClarityRequest(
            signalFamily = signalFamily,
            strictHighEnd = strictHighEnd,
            normalSdrRealtimeLock = normalSdrRealtimeLock,
            profile = profile,
            noiseMetric = noiseMetric,
            iso = iso,
            localChromaSpikeSmoothing = localChromaSpikeSmoothing,
            sharpeningFloor = sharpeningFloor,
            sharpeningCeiling = sharpeningCeiling,
            skinDetailProtection = skinDetailProtection,
            videoDetailProfile = videoDetailProfile,
            subjectMaskAvailable = false,
            temporalBlend = profile.temporalBlend,
            spatialDenoise = profile.spatialDenoise,
            chromaDenoise = profile.chromaDenoise,
            sharpening = profile.sharpening,
            subjectDetailGain = 0f
        )

    private fun profile(
        temporalBlend: Float = 0.40f,
        spatialDenoise: Float = 0.54f,
        chromaDenoise: Float = 0.62f,
        sharpening: Float = 0.54f,
        noiseMetrics: VideoNoiseMetrics = VideoNoiseMetrics.EMPTY,
        detailSharpeningMetadata: Map<String, String> = emptyMap()
    ): SceneRenderProfile =
        SceneRenderProfile(
            temporalBlend = temporalBlend,
            spatialDenoise = spatialDenoise,
            chromaDenoise = chromaDenoise,
            shadowBoost = 0.18f,
            sharpening = sharpening,
            warmth = 0f,
            contrast = 1.0f,
            saturation = 1.0f,
            highlightCompression = 0.24f,
            logStrength = 0f,
            ditherStrength = 0f,
            videoHdrFusionStrength = 0f,
            noiseMetrics = noiseMetrics,
            detailSharpeningMetadata = detailSharpeningMetadata
        )
}
