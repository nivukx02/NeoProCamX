package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.ImaxStrictProfile
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max

data class VideoNoiseMetrics(
    val chromaNoise: Float,
    val lumaNoise: Float,
    val temporalInstability: Float,
    val frameToFrameRgbVariance: Float
) {
    fun clamped(): VideoNoiseMetrics =
        VideoNoiseMetrics(
            chromaNoise = chromaNoise.coerceIn(0f, 1f),
            lumaNoise = lumaNoise.coerceIn(0f, 1f),
            temporalInstability = temporalInstability.coerceIn(0f, 1f),
            frameToFrameRgbVariance = frameToFrameRgbVariance.coerceIn(0f, 1f)
        )

    fun toMetadata(prefix: String = ""): Map<String, String> {
        val metrics = clamped()
        return linkedMapOf(
            metricKey(prefix, "chromaNoise") to format(metrics.chromaNoise),
            metricKey(prefix, "lumaNoise") to format(metrics.lumaNoise),
            metricKey(prefix, "temporalInstability") to format(metrics.temporalInstability),
            metricKey(prefix, "frameToFrameRgbVariance") to format(metrics.frameToFrameRgbVariance)
        )
    }

    companion object {
        val EMPTY = VideoNoiseMetrics(
            chromaNoise = 0f,
            lumaNoise = 0f,
            temporalInstability = 0f,
            frameToFrameRgbVariance = 0f
        )

        fun fromScene(scene: SceneAnalysis?): VideoNoiseMetrics {
            if (scene == null) return EMPTY
            val normalizedNoise = scene.normalizedNoiseFloor.coerceIn(0f, 1f)
            val flatVariance = (scene.flatRegionStdDev / 18f).coerceIn(0f, 1f)
            val isoPressure = isoPressure(scene.realIsoNoiseFloor)
            val shadowPressure = scene.shadowRatio.coerceIn(0f, 1f)
            val lowLumaPressure = (1f - (scene.averageLuma / 0.60f)).coerceIn(0f, 1f)
            return VideoNoiseMetrics(
                chromaNoise = (
                    normalizedNoise * 0.40f +
                        flatVariance * 0.34f +
                        isoPressure * 0.16f +
                        shadowPressure * 0.10f
                    ).coerceIn(0f, 1f),
                lumaNoise = (
                    normalizedNoise * 0.46f +
                        flatVariance * 0.22f +
                        isoPressure * 0.20f +
                        lowLumaPressure * 0.12f
                    ).coerceIn(0f, 1f),
                temporalInstability = (
                    scene.motionScore.coerceIn(0f, 1f) * 0.42f +
                        normalizedNoise * 0.28f +
                        flatVariance * 0.18f +
                        isoPressure * 0.12f
                    ).coerceIn(0f, 1f),
                frameToFrameRgbVariance = (
                    flatVariance * 0.42f +
                        normalizedNoise * 0.28f +
                        isoPressure * 0.18f +
                        shadowPressure * 0.12f
                    ).coerceIn(0f, 1f)
            )
        }

        fun fromMetadata(
            valueForKey: (String) -> String?
        ): VideoNoiseMetrics =
            VideoNoiseMetrics(
                chromaNoise = valueForKey("chromaNoise").toMetricFloat(),
                lumaNoise = valueForKey("lumaNoise").toMetricFloat(),
                temporalInstability = valueForKey("temporalInstability").toMetricFloat(),
                frameToFrameRgbVariance = valueForKey("frameToFrameRgbVariance").toMetricFloat()
            ).clamped()

        private fun metricKey(prefix: String, key: String): String =
            if (prefix.isBlank()) {
                key
            } else {
                prefix + key.replaceFirstChar { char -> char.uppercase(Locale.US) }
            }

        private fun format(value: Float): String =
            String.format(Locale.US, "%.6f", value.coerceIn(0f, 1f))

        private fun String?.toMetricFloat(): Float =
            this?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
    }
}

internal data class VideoNoiseReductionRequest(
    val signalFamily: VideoImageSignalFamily,
    val recording: Boolean,
    val lumaLevel: Float,
    val estimatedIso: Float,
    val motionScore: Float,
    val skinToneConfidence: Float,
    val textureConfidence: Float,
    val edgeDensity: Float,
    val metrics: VideoNoiseMetrics,
    val strictHighEnd: Boolean,
    val aiMotionCompensation: AiMotionCompensationRuntimeHints =
        AiMotionCompensationRuntimeHints.disabled(),
    val videoDetailProfile: VideoDetailProfile? = null
)

internal data class VideoNoiseReductionPlan(
    val temporalBlend: Float,
    val motionSafeTemporalCeiling: Float,
    val lumaDenoise: Float,
    val chromaDenoise: Float,
    val neuralDenoise: NeuralDenoiseTuning,
    val shadowChromaLock: Float,
    val motionProtection: Float,
    val skinTexturePreservation: Float,
    val detailSafeSharpeningCeiling: Float,
    val metrics: VideoNoiseMetrics,
    val motionCompensationPlan: AiMotionCompensationPlan,
    val signature: String
)

internal data class NeuralDenoiseTuning(
    val enabled: Boolean,
    val lumaThreshold: Float,
    val chromaThreshold: Float,
    val texturePreservation: Float,
    val temporalWeight: Float
)

internal object VideoNoiseReductionPolicy {

    fun resolve(request: VideoNoiseReductionRequest): VideoNoiseReductionPlan {
        val metrics = request.metrics.clamped()
        val luma = request.lumaLevel.coerceIn(0f, 1f)
        val motion = request.motionScore.coerceIn(0f, 1f)
        val skin = request.skinToneConfidence.coerceIn(0f, 1f)
        val texture = max(
            request.textureConfidence.coerceIn(0f, 1f),
            (request.edgeDensity / 0.32f).coerceIn(0f, 1f)
        )
        val iso = isoPressure(request.estimatedIso)
        val shadow = (1f - ((luma - 0.05f) / 0.55f)).coerceIn(0f, 1f)
        val highlight = ((luma - 0.55f) / 0.45f).coerceIn(0f, 1f)
        val strictSdr = request.signalFamily == VideoImageSignalFamily.SDR && request.strictHighEnd
        val noisePressure = (
            metrics.chromaNoise * 0.32f +
                metrics.lumaNoise * 0.28f +
                metrics.frameToFrameRgbVariance * 0.22f +
                metrics.temporalInstability * 0.18f
            ).coerceIn(0f, 1f)
        val extremeDetailProfile =
            request.videoDetailProfile == VideoDetailProfile.EXTREME_DETAIL &&
                request.signalFamily != VideoImageSignalFamily.RAW
        val extremeCleanupBoost = if (extremeDetailProfile) {
            (noisePressure * 0.06f + shadow * noisePressure * 0.04f + iso * 0.03f)
                .coerceIn(0f, 0.10f)
        } else {
            0f
        }
        val strictSdrNoiseRecovery =
            strictSdr &&
                (
                    noisePressure >= 0.20f ||
                        metrics.frameToFrameRgbVariance >= 0.20f ||
                        iso >= 0.28f
                    )
        val recordingBoost = if (request.recording) 0.08f else 0.02f
        val strictBoost = if (request.strictHighEnd) 0.025f else 0f
        val strictChromaBoost = if (request.strictHighEnd) 0.02f else 0f
        val strictShadowLockBoost = if (request.strictHighEnd) 0.02f else 0f
        val modeChromaBoost = when (request.signalFamily) {
            VideoImageSignalFamily.RAW -> 0.04f
            VideoImageSignalFamily.LOG -> 0.06f
            VideoImageSignalFamily.HDR -> 0.08f
            VideoImageSignalFamily.SDR -> if (strictSdr) 0.09f else 0.04f
        }
        val skinDetailGuard = max(skin, texture * 0.42f).coerceIn(0f, 1f)
        val skinTexturePreservation = (
            0.40f +
                skin * 0.38f +
                texture * 0.28f +
                motion * 0.10f
            ).coerceIn(0.35f, 0.98f)
        val denoiseGuard = (skinDetailGuard * 0.16f + texture * 0.10f).coerceIn(0f, 0.30f)
        val baseLumaDenoise = (
            0.22f +
                noisePressure * 0.42f +
                shadow * 0.16f +
                iso * 0.10f +
                recordingBoost +
                strictBoost -
                highlight * 0.08f -
                denoiseGuard +
                extremeCleanupBoost
            ).coerceIn(0.12f, 0.86f)
        val lumaDenoise = if (strictSdrNoiseRecovery) {
            maxOf(
                baseLumaDenoise,
                (
                    0.28f +
                        noisePressure * 0.38f +
                        iso * 0.12f +
                        shadow * noisePressure * 0.10f -
                        motion * 0.16f -
                        skinDetailGuard * 0.10f -
                        texture * 0.08f
                    ).coerceIn(0.24f, 0.82f)
            )
        } else {
            baseLumaDenoise
        }
        val baseChromaDenoise = (
            ImaxStrictProfile.CHROMA_DENOISE_FLOOR +
                metrics.chromaNoise * 0.16f +
                metrics.frameToFrameRgbVariance * 0.08f +
                shadow * 0.10f +
                iso * 0.04f +
                modeChromaBoost * 0.35f +
                recordingBoost * 0.25f +
                strictChromaBoost -
                highlight * 0.10f -
                skinDetailGuard * 0.12f -
                texture * 0.04f +
                extremeCleanupBoost
            ).coerceIn(ImaxStrictProfile.CHROMA_DENOISE_FLOOR, 0.9999f)
        val chromaDenoise = if (strictSdrNoiseRecovery) {
            maxOf(
                baseChromaDenoise,
                (
                    0.42f +
                        metrics.chromaNoise * 0.30f +
                        metrics.frameToFrameRgbVariance * 0.16f +
                        shadow * noisePressure * 0.08f -
                        skinDetailGuard * 0.08f
                    ).coerceIn(0.38f, 0.90f)
            )
        } else {
            baseChromaDenoise
        }
        val shadowChromaLock = (
            chromaDenoise +
                shadow * 0.05f +
                metrics.frameToFrameRgbVariance * 0.02f +
                strictShadowLockBoost
            ).coerceIn(maxOf(chromaDenoise, ImaxStrictProfile.SHADOW_CHROMA_LOCK_FLOOR), 0.99999f)
        val motionProtection = (
            motion * 1.20f +
                skin * 0.18f +
                texture * 0.12f
            ).coerceIn(0f, 1f)
        val motionSafeCeiling = when {
            motion >= 0.60f -> 0.46f
            motion >= ImaxStrictProfile.TNR_MOTION_GATE -> 0.54f
            motion >= 0.18f -> 0.68f
            else -> ImaxStrictProfile.TNR_STRICT_CEILING
        }
        val baseTemporalBlend = (
            0.36f +
                noisePressure * 0.34f +
                iso * 0.10f +
                shadow * 0.12f +
                recordingBoost +
                strictBoost -
                motion * 0.32f -
                skinTexturePreservation * 0.04f
            ).coerceIn(0.18f, motionSafeCeiling)
        val temporalBlend = if (strictSdrNoiseRecovery) {
            val strictSdrTemporalFloor = minOf(if (request.recording) 0.34f else 0.30f, motionSafeCeiling)
            maxOf(
                baseTemporalBlend,
                (
                    0.28f +
                        noisePressure * 0.30f +
                        iso * 0.08f +
                        shadow * noisePressure * 0.06f -
                        texture * 0.06f -
                        motion * 0.20f
                    ).coerceIn(strictSdrTemporalFloor, motionSafeCeiling)
            )
        } else {
            baseTemporalBlend
        }
        val neuralDenoise = resolveNeuralDenoiseTuning(
            iso = iso,
            shadow = shadow,
            motion = motion,
            texture = texture,
            skin = skin,
            noisePressure = noisePressure,
            chromaNoise = metrics.chromaNoise,
            temporalBlend = temporalBlend,
            strictHighEnd = request.strictHighEnd
        )
        val motionCompensationPlan = AiMotionCompensationPolicy.resolve(
            request = request,
            baseTemporalBlend = temporalBlend,
            motionSafeTemporalCeiling = motionSafeCeiling
        )
        // Consume the motion-compensation plan's temporal blend when it is active. For the DISABLED
        // state (the default when AI Motion Compensation is off) the plan echoes the base blend, so
        // this is byte-identical unless the user enabled the feature.
        val effectiveTemporalBlend = when (motionCompensationPlan.state) {
            AiMotionCompensationState.NEURAL_FLOW_ACTIVE,
            AiMotionCompensationState.HEURISTIC_ACTIVE -> motionCompensationPlan.effectiveTemporalBlend
            else -> temporalBlend
        }
        val baseDetailSafeSharpeningCeiling = (
            ImaxStrictProfile.SHARPENING_STRICT_CEILING -
                noisePressure * 0.18f -
                metrics.frameToFrameRgbVariance * 0.08f +
                texture * 0.12f -
                motion * 0.05f
            ).coerceIn(0.18f, ImaxStrictProfile.SHARPENING_STRICT_CEILING)
        val detailSafeSharpeningCeiling = if (strictSdr || extremeDetailProfile) {
            val floor = when {
                strictSdrNoiseRecovery -> 0.58f
                extremeDetailProfile && noisePressure <= 0.28f -> 0.86f
                extremeDetailProfile -> 0.72f
                else -> 0.76f
            }
            maxOf(baseDetailSafeSharpeningCeiling, floor)
                .coerceAtMost(ImaxStrictProfile.SHARPENING_STRICT_CEILING)
        } else {
            baseDetailSafeSharpeningCeiling
        }
        return VideoNoiseReductionPlan(
            temporalBlend = effectiveTemporalBlend,
            motionSafeTemporalCeiling = motionSafeCeiling,
            lumaDenoise = lumaDenoise,
            chromaDenoise = chromaDenoise,
            neuralDenoise = neuralDenoise,
            shadowChromaLock = shadowChromaLock,
            motionProtection = motionProtection,
            skinTexturePreservation = skinTexturePreservation,
            detailSafeSharpeningCeiling = detailSafeSharpeningCeiling,
            metrics = metrics,
            motionCompensationPlan = motionCompensationPlan,
            signature = listOf(
                request.signalFamily.name,
                "iso=${String.format(Locale.US, "%.3f", iso)}",
                "motion=${String.format(Locale.US, "%.3f", motion)}",
                "luma=${String.format(Locale.US, "%.3f", luma)}",
                "tnr=${String.format(Locale.US, "%.3f", effectiveTemporalBlend)}",
                "ldn=${String.format(Locale.US, "%.3f", lumaDenoise)}",
                "cdn=${String.format(Locale.US, "%.3f", chromaDenoise)}",
                "nLuma=${String.format(Locale.US, "%.3f", neuralDenoise.lumaThreshold)}",
                "nChroma=${String.format(Locale.US, "%.3f", neuralDenoise.chromaThreshold)}",
                "motionComp=${motionCompensationPlan.state.name}"
            ).joinToString(separator = "|")
        )
    }

    private fun resolveNeuralDenoiseTuning(
        iso: Float,
        shadow: Float,
        motion: Float,
        texture: Float,
        skin: Float,
        noisePressure: Float,
        chromaNoise: Float,
        temporalBlend: Float,
        strictHighEnd: Boolean
    ): NeuralDenoiseTuning {
        val texturePreservation = (
            0.42f +
                texture * 0.42f +
                skin * 0.18f +
                motion * 0.04f
            ).coerceIn(0.38f, 0.96f)
        val lumaThreshold = (
            0.18f +
                noisePressure * 0.22f +
                iso * 0.20f +
                shadow * 0.08f -
                texturePreservation * 0.18f
            ).coerceIn(0.10f, if (strictHighEnd) 0.58f else 0.46f)
        val chromaThreshold = (
            0.36f +
                chromaNoise * 0.30f +
                iso * 0.28f +
                shadow * 0.14f +
                if (strictHighEnd) 0.08f else 0.02f
            ).coerceIn(lumaThreshold, 0.99f)
        val temporalWeight = (
            temporalBlend * 0.85f +
                iso * 0.12f -
                motion * 0.22f
            ).coerceIn(0.04f, 0.70f)
        return NeuralDenoiseTuning(
            enabled = iso > 0.02f || noisePressure > 0.18f || chromaNoise > 0.20f,
            lumaThreshold = lumaThreshold,
            chromaThreshold = chromaThreshold,
            texturePreservation = texturePreservation,
            temporalWeight = temporalWeight
        )
    }
}

private fun isoPressure(iso: Float): Float {
    val safeIso = iso.coerceAtLeast(100f)
    val stopsOverBase = ln((safeIso / 400f).toDouble()) / ln(2.0)
    return (stopsOverBase.coerceAtLeast(0.0) / 5.0).toFloat().coerceIn(0f, 1f)
}
