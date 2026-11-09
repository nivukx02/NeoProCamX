package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.ImaxStrictProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoNoiseReductionPolicyTest {

    @Test
    fun shadowsReceiveStrongerChromaAndLumaDenoiseThanHighlights() {
        val shadow = plan(lumaLevel = 0.08f)
        val highlight = plan(lumaLevel = 0.82f)

        assertTrue("shadow chroma=${shadow.chromaDenoise} highlight=${highlight.chromaDenoise}",
            shadow.chromaDenoise > highlight.chromaDenoise)
        assertTrue("shadow luma=${shadow.lumaDenoise} highlight=${highlight.lumaDenoise}",
            shadow.lumaDenoise > highlight.lumaDenoise)
        assertTrue("shadow lock=${shadow.shadowChromaLock} highlight=${highlight.shadowChromaLock}",
            shadow.shadowChromaLock > highlight.shadowChromaLock)
    }

    @Test
    fun motionAreasReceiveLowerTemporalBlendToAvoidGhosting() {
        val staticScene = plan(motionScore = 0.02f)
        val movingScene = plan(motionScore = 0.68f)

        assertTrue(
            "moving temporal=${movingScene.temporalBlend} static=${staticScene.temporalBlend}",
            movingScene.temporalBlend < staticScene.temporalBlend
        )
        assertTrue("moving temporal must stay below smear threshold", movingScene.temporalBlend <= 0.46f)
        assertTrue("moving plan must expose a motion guard", movingScene.motionProtection >= 0.75f)
    }

    @Test
    fun strictImaxNoisePolicyRaisesStaticTemporalAndChromaCleanlinessToStrictFloors() {
        val staticShadow = plan(lumaLevel = 0.06f, motionScore = 0.0f)

        assertEquals(ImaxStrictProfile.TNR_STRICT_CEILING, staticShadow.motionSafeTemporalCeiling, 0.000001f)
        assertTrue("temporalBlend=${staticShadow.temporalBlend}", staticShadow.temporalBlend > 0.70f)
        assertTrue("temporalBlend=${staticShadow.temporalBlend}", staticShadow.temporalBlend <= ImaxStrictProfile.TNR_STRICT_CEILING)
        assertTrue("chromaDenoise=${staticShadow.chromaDenoise}", staticShadow.chromaDenoise >= ImaxStrictProfile.CHROMA_DENOISE_FLOOR)
        assertTrue("shadowChromaLock=${staticShadow.shadowChromaLock}", staticShadow.shadowChromaLock >= ImaxStrictProfile.SHADOW_CHROMA_LOCK_FLOOR)
    }

    @Test
    fun skinAndTextureGuardPreventsPlasticOverSmoothing() {
        val flatNonSkin = plan(skinToneConfidence = 0.0f, textureConfidence = 0.0f, edgeDensity = 0.04f)
        val skinTexture = plan(skinToneConfidence = 0.92f, textureConfidence = 0.86f, edgeDensity = 0.28f)

        assertTrue(
            "skin luma=${skinTexture.lumaDenoise} nonSkin=${flatNonSkin.lumaDenoise}",
            skinTexture.lumaDenoise < flatNonSkin.lumaDenoise
        )
        assertTrue(
            "skin chroma=${skinTexture.chromaDenoise} nonSkin=${flatNonSkin.chromaDenoise}",
            skinTexture.chromaDenoise < flatNonSkin.chromaDenoise
        )
        assertTrue("skin guard must be strong", skinTexture.skinTexturePreservation >= 0.86f)
    }

    @Test
    fun metricsMapKeepsMeasurableNoiseEvidence() {
        val metrics = VideoNoiseMetrics(
            chromaNoise = 0.61f,
            lumaNoise = 0.44f,
            temporalInstability = 0.35f,
            frameToFrameRgbVariance = 0.72f
        )
        val metadata = metrics.toMetadata()

        assertTrue(metadata.getValue("chromaNoise").startsWith("0.610"))
        assertTrue(metadata.getValue("lumaNoise").startsWith("0.440"))
        assertTrue(metadata.getValue("temporalInstability").startsWith("0.350"))
        assertTrue(metadata.getValue("frameToFrameRgbVariance").startsWith("0.720"))
    }

    @Test
    fun strictSdrNoiseRecoveryRaisesLumaChromaTemporalCleanupWithoutDroppingDetailCeiling() {
        val plan = VideoNoiseReductionPolicy.resolve(
            VideoNoiseReductionRequest(
                signalFamily = VideoImageSignalFamily.SDR,
                recording = true,
                lumaLevel = 0.14f,
                estimatedIso = 6_400f,
                motionScore = 0.06f,
                skinToneConfidence = 0.0f,
                textureConfidence = 0.20f,
                edgeDensity = 0.18f,
                metrics = VideoNoiseMetrics(
                    chromaNoise = 0.58f,
                    lumaNoise = 0.50f,
                    temporalInstability = 0.48f,
                    frameToFrameRgbVariance = 0.64f
                ),
                strictHighEnd = true
            )
        )

        assertTrue("luma=${plan.lumaDenoise}", plan.lumaDenoise >= 0.84f)
        assertTrue("chroma=${plan.chromaDenoise}", plan.chromaDenoise >= 0.999f)
        assertTrue("temporal=${plan.temporalBlend}", plan.temporalBlend >= 0.68f)
        assertTrue("shadowLock=${plan.shadowChromaLock}", plan.shadowChromaLock >= 0.999f)
        assertTrue("detailCeiling=${plan.detailSafeSharpeningCeiling}", plan.detailSafeSharpeningCeiling >= 0.72f)
        assertTrue(plan.signature.startsWith("SDR|"))
    }

    @Test
    fun moderateStrictSdrRgbGrainActivatesEliteCleanupAndKeepsDetailCeiling() {
        val plan = VideoNoiseReductionPolicy.resolve(
            VideoNoiseReductionRequest(
                signalFamily = VideoImageSignalFamily.SDR,
                recording = true,
                lumaLevel = 0.22f,
                estimatedIso = 1_600f,
                motionScore = 0.05f,
                skinToneConfidence = 0.0f,
                textureConfidence = 0.12f,
                edgeDensity = 0.08f,
                metrics = VideoNoiseMetrics(
                    chromaNoise = 0.26f,
                    lumaNoise = 0.22f,
                    temporalInstability = 0.18f,
                    frameToFrameRgbVariance = 0.24f
                ),
                strictHighEnd = true
            )
        )

        assertTrue("luma=${plan.lumaDenoise}", plan.lumaDenoise >= 0.78f)
        assertTrue("chroma=${plan.chromaDenoise}", plan.chromaDenoise >= 0.9988f)
        assertTrue("temporal=${plan.temporalBlend}", plan.temporalBlend >= 0.60f)
        assertTrue("detailCeiling=${plan.detailSafeSharpeningCeiling}", plan.detailSafeSharpeningCeiling >= 0.72f)
        assertTrue(plan.signature.startsWith("SDR|"))
    }

    @Test
    fun `extreme detail raises noise cleanup for noisy high detail scenes`() {
        val noisyMetrics = VideoNoiseMetrics(
            chromaNoise = 0.58f,
            lumaNoise = 0.50f,
            temporalInstability = 0.48f,
            frameToFrameRgbVariance = 0.64f
        )
        val baseline = plan(
            lumaLevel = 0.14f,
            textureConfidence = 0.64f,
            edgeDensity = 0.30f,
            videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL,
            metrics = noisyMetrics
        )
        val extreme = plan(
            lumaLevel = 0.14f,
            textureConfidence = 0.64f,
            edgeDensity = 0.30f,
            videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL,
            metrics = noisyMetrics
        )

        assertTrue(extreme.lumaDenoise > baseline.lumaDenoise)
        assertTrue(extreme.chromaDenoise >= baseline.chromaDenoise)
        assertTrue(extreme.detailSafeSharpeningCeiling >= baseline.detailSafeSharpeningCeiling)
    }

    @Test
    fun `extreme detail keeps temporal denoise motion safe`() {
        val extreme = plan(
            motionScore = 0.82f,
            textureConfidence = 0.44f,
            edgeDensity = 0.22f,
            videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL,
            metrics = VideoNoiseMetrics(
                chromaNoise = 0.42f,
                lumaNoise = 0.38f,
                temporalInstability = 0.72f,
                frameToFrameRgbVariance = 0.52f
            )
        )

        assertTrue("temporal=${extreme.temporalBlend}", extreme.temporalBlend <= 0.46f)
        assertTrue("ceiling=${extreme.motionSafeTemporalCeiling}", extreme.motionSafeTemporalCeiling <= 0.52f)
    }

    @Test
    fun `raw ignores extreme detail profile in noise reduction`() {
        val rawDefault = plan(
            signalFamily = VideoImageSignalFamily.RAW,
            videoDetailProfile = null
        )
        val rawExtreme = plan(
            signalFamily = VideoImageSignalFamily.RAW,
            videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL
        )

        assertEquals(rawDefault, rawExtreme)
    }

    @Test
    fun aiMotionCompensationPlanTemporalBlendIsConsumedWhenEnabled() {
        val off = plan(motionScore = 0.05f, aiMotionCompensationEnabled = false)
        val on = plan(motionScore = 0.05f, aiMotionCompensationEnabled = true)

        // Off (default): the motion-comp plan is inert and the temporal blend is the base value.
        assertEquals(AiMotionCompensationState.DISABLED, off.motionCompensationPlan.state)
        // On (neural unavailable in a JVM test): the honest heuristic runs and its temporal blend is
        // actually consumed by the returned plan (previously the plan output was discarded).
        assertEquals(AiMotionCompensationState.HEURISTIC_ACTIVE, on.motionCompensationPlan.state)
        assertEquals(on.motionCompensationPlan.effectiveTemporalBlend, on.temporalBlend, 1e-4f)
        // A near-still scene -> enabling strengthens (or holds) temporal denoising, never weakens it.
        assertTrue("on=${on.temporalBlend} off=${off.temporalBlend}", on.temporalBlend >= off.temporalBlend)
    }

    private fun plan(
        signalFamily: VideoImageSignalFamily = VideoImageSignalFamily.HDR,
        lumaLevel: Float = 0.16f,
        motionScore: Float = 0.05f,
        skinToneConfidence: Float = 0.0f,
        textureConfidence: Float = 0.18f,
        edgeDensity: Float = 0.16f,
        videoDetailProfile: VideoDetailProfile? = null,
        aiMotionCompensationEnabled: Boolean = false,
        metrics: VideoNoiseMetrics = VideoNoiseMetrics(
            chromaNoise = 0.58f,
            lumaNoise = 0.50f,
            temporalInstability = 0.48f,
            frameToFrameRgbVariance = 0.64f
        )
    ): VideoNoiseReductionPlan =
        VideoNoiseReductionPolicy.resolve(
            VideoNoiseReductionRequest(
                signalFamily = signalFamily,
                recording = true,
                lumaLevel = lumaLevel,
                estimatedIso = 3200f,
                motionScore = motionScore,
                skinToneConfidence = skinToneConfidence,
                textureConfidence = textureConfidence,
                edgeDensity = edgeDensity,
                metrics = metrics,
                strictHighEnd = true,
                aiMotionCompensation = AiMotionCompensationRuntimeHints.requested(aiMotionCompensationEnabled),
                videoDetailProfile = videoDetailProfile
            )
        )
}
