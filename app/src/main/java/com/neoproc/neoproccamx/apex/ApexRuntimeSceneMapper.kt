package com.neoproc.neoproccamx.apex

import com.neoproc.neoproccamx.camera.LiveResult
import com.neoproc.neoproccamx.pipeline.CaptureRecommendation
import com.neoproc.neoproccamx.pipeline.SceneAnalysis
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Converts the app's live YUV/Camera2 telemetry into the APEX metric model.
 *
 * APEX does not own a second frame pipeline; it consumes the same scene analysis
 * that drives preview and recording so both outputs receive the same adaptive
 * tone/noise/detail decisions.
 */
object ApexRuntimeSceneMapper {

    private const val DEFAULT_EXPOSURE_NS = 33_333_333L
    private const val MIN_SCENE_WHITE = 0.18f
    private const val MAX_SCENE_WHITE = 0.82f

    fun fromPipeline(
        scene: SceneAnalysis?,
        recommendation: CaptureRecommendation?,
        liveResult: LiveResult?
    ): SceneMetrics {
        if (scene == null) {
            return fromLiveResult(liveResult)
        }

        val iso = resolveIso(scene, liveResult)
        val exposureNs = resolveExposureNs(recommendation, liveResult)
        val targetExposureNs = recommendation?.targetExposureNs?.takeIf { it > 0L } ?: exposureNs
        val exposureDelta = if (targetExposureNs > 0L) {
            abs(exposureNs - targetExposureNs).toFloat() / targetExposureNs.toFloat()
        } else {
            0f
        }

        val dynamicRangeScore = scene.dynamicRangeScore.coerceIn(0f, 1f)
        val shadowRatio = scene.shadowRatio.coerceIn(0f, 1f)
        val highlightRatio = scene.highlightRatio.coerceIn(0f, 1f)
        val motionScore = scene.motionScore.coerceIn(0f, 1f)
        val isoNoiseRisk = ((iso - 400f) / 3_200f).coerceIn(0f, 1f)
        val lumaNoiseRisk = scene.normalizedNoiseFloor.coerceIn(0f, 1f)
        val lowLightRisk = if (scene.isLowLight) 0.18f else 0f
        val noiseRisk = max(max(isoNoiseRisk, lumaNoiseRisk), lowLightRisk).coerceIn(0f, 1f)
        val flatRegionRisk = ((scene.flatRegionStdDev - 3f) / 14f).coerceIn(0f, 1f)
        val chromaNoiseRisk = (
            lumaNoiseRisk * 0.50f +
                flatRegionRisk * 0.25f +
                isoNoiseRisk * 0.20f +
                lowLightRisk * 0.05f
            ).coerceIn(0f, 1f)
        val exposureStability = (1f - motionScore * 0.42f - exposureDelta * 0.28f)
            .coerceIn(0f, 1f)
        val shadowNeed = (
            shadowRatio * 0.58f +
                noiseRisk * 0.24f +
                (1f - exposureStability) * 0.18f
            ).coerceIn(0f, 1f)
        val sceneWhite = (
            MIN_SCENE_WHITE +
                scene.averageLuma.coerceIn(0f, 1f) * 0.36f +
                highlightRatio * 0.22f -
                shadowRatio * 0.10f
            ).coerceIn(MIN_SCENE_WHITE, MAX_SCENE_WHITE)

        val p50Nits = normalizedLumaToNits(scene.averageLuma)
        val dynamicSpread = 220f + dynamicRangeScore * 9_000f
        val p01Nits = (p50Nits * (0.035f + (1f - shadowRatio) * 0.085f))
            .coerceAtLeast(0.01f)
        val p95Nits = (p50Nits + dynamicSpread * (0.12f + highlightRatio * 0.34f))
            .coerceIn(p50Nits + 0.01f, 9_500f)
        val p99Nits = (p95Nits + dynamicSpread * (0.06f + highlightRatio * 0.20f))
            .coerceIn(p95Nits + 0.01f, 10_000f)
        val maxCllNits = (p99Nits * (1.02f + highlightRatio * 0.30f))
            .coerceIn(p99Nits, 10_000f)
        val maxFallNits = (p50Nits * 0.62f + p95Nits * 0.38f)
            .coerceIn(p50Nits, maxCllNits)
        val histogramSpread = ((p95Nits - p01Nits) / 10_000f).coerceIn(0f, 1f)
        val localContrastScore = max(scene.edgeDensity, scene.perceptualSharpness)
            .coerceIn(0f, 1f)
        val clipRisk = max(
            highlightRatio * 0.78f,
            ((maxCllNits - p99Nits) / 2_000f).coerceIn(0f, 1f)
        ).coerceIn(0f, 1f)
        val perceptualStopEstimate = (
            ln(((p99Nits + 0.1f) / max(p01Nits + 0.1f, 0.1f)).toDouble()) / ln(2.0)
            ).toFloat().coerceIn(0f, 24f)

        return SceneMetrics(
            p01Nits = p01Nits,
            p50Nits = p50Nits,
            p95Nits = p95Nits,
            p99Nits = p99Nits,
            maxCllNits = maxCllNits,
            maxFallNits = maxFallNits,
            dynamicRangeScore = dynamicRangeScore,
            shadowRatio = shadowRatio,
            noiseRisk = noiseRisk,
            exposureStability = exposureStability,
            shadowNeed = shadowNeed,
            motionScore = motionScore,
            chromaNoiseRisk = chromaNoiseRisk,
            highlightRatio = highlightRatio,
            sceneWhite = sceneWhite,
            localContrastScore = localContrastScore,
            histogramSpread = histogramSpread,
            clipRisk = clipRisk,
            perceptualStopEstimate = perceptualStopEstimate,
            iso = iso,
            exposureTimeNs = exposureNs,
            frameTimestampNs = scene.timestamp
        )
    }

    private fun fromLiveResult(liveResult: LiveResult?): SceneMetrics {
        val iso = liveResult?.iso?.takeIf { it > 0 } ?: 100
        val exposureNs = liveResult?.exposureNs?.takeIf { it > 0L } ?: DEFAULT_EXPOSURE_NS
        val isoNoiseRisk = ((iso - 400f) / 3_200f).coerceIn(0f, 1f)
        val longExposureRisk = ((exposureNs - 16_666_667L).toFloat() / 50_000_000f).coerceIn(0f, 1f)
        val noiseRisk = max(isoNoiseRisk, longExposureRisk).coerceIn(0f, 1f)
        val shadowNeed = (noiseRisk * 0.42f).coerceIn(0f, 1f)

        return SceneMetrics(
            p01Nits = 0.06f,
            p50Nits = 92f,
            p95Nits = 760f,
            p99Nits = 1_200f,
            maxCllNits = 1_500f,
            maxFallNits = 346f,
            dynamicRangeScore = 0.36f,
            shadowRatio = shadowNeed,
            noiseRisk = noiseRisk,
            exposureStability = 0.82f,
            shadowNeed = shadowNeed,
            motionScore = 0f,
            chromaNoiseRisk = noiseRisk * 0.72f,
            highlightRatio = 0.08f,
            sceneWhite = 0.24f,
            localContrastScore = 0.18f,
            histogramSpread = 0.12f,
            clipRisk = 0.04f,
            perceptualStopEstimate = 14.2f,
            iso = iso,
            exposureTimeNs = exposureNs,
            frameTimestampNs = 0L
        )
    }

    private fun resolveIso(scene: SceneAnalysis, liveResult: LiveResult?): Int =
        liveResult?.iso?.takeIf { it > 0 }
            ?: scene.realIsoNoiseFloor.roundToInt().coerceAtLeast(100)

    private fun resolveExposureNs(
        recommendation: CaptureRecommendation?,
        liveResult: LiveResult?
    ): Long =
        liveResult?.exposureNs?.takeIf { it > 0L }
            ?: recommendation?.targetExposureNs?.takeIf { it > 0L }
            ?: DEFAULT_EXPOSURE_NS

    private fun normalizedLumaToNits(normalizedLuma: Float): Float {
        val linear = normalizedLuma.coerceIn(0.001f, 1f).toDouble().pow(2.20).toFloat()
        return (linear * 1_000f).coerceIn(0.01f, 1_000f)
    }
}
