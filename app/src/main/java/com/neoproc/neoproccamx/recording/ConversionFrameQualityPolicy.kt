package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.pipeline.EliteDetailProfilePolicy
import com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics
import com.neoproc.neoproccamx.rendering.GpuSignalFamily

/** Content-aware offline conversion quality selected from measurements of each decoded frame. */
internal object ConversionFrameQualityPolicy {
    data class Plan(
        val sharpen: Float,
        val denoise: Float,
        val severity: Float,
        val textureConfidence: Float = 0f
    )

    fun resolve(
        family: GpuSignalFamily,
        detailProfile: VideoDetailProfile,
        metrics: VideoNoiseMetrics,
        sceneDetail: P010SceneDetailMetrics? = null,
        sharpnessScale: Float = 1f,
        denoiseScale: Float = 1f
    ): Plan {
        require(sharpnessScale in 0.25f..1.25f) { "sharpnessScale must be in [0.25,1.25]" }
        require(denoiseScale in 0.75f..1.75f) { "denoiseScale must be in [0.75,1.75]" }
        val m = metrics.clamped()
        // Luma standard deviation contains real scene contrast, so it receives a deliberately small
        // weight. Temporal/chroma instability are stronger evidence of noise in an offline frame pair.
        val severity = maxOf(
            (m.lumaNoise / 0.75f).coerceIn(0f, 0.35f),
            (m.chromaNoise * 0.72f).coerceIn(0f, 0.72f),
            (m.temporalInstability * 1.15f).coerceIn(0f, 1f),
            (m.frameToFrameRgbVariance * 0.90f).coerceIn(0f, 0.90f)
        )
        val profileTuning = EliteDetailProfilePolicy.resolve(detailProfile)
        val cleanSharp = profileTuning.conversionCleanSharpen
        val noisySharp = profileTuning.conversionNoisySharpen
        // HDR/NDR masters showed texture erasure when high temporal/chroma noise pushed the old denoise
        // ceiling to 0.72. Keep the filter useful but below that destructive range; actual source texture
        // further backs denoise off while flat shadows may add a small, bounded amount back.
        val cleanDenoise = if (family == GpuSignalFamily.SDR) 0.32f else 0.28f
        val noisyDenoise = if (family == GpuSignalFamily.SDR) 0.66f else 0.58f
        val textureConfidence = sceneDetail?.textureConfidence?.coerceIn(0f, 1f) ?: 0f
        val shadowPressure = sceneDetail?.shadowFraction?.coerceIn(0f, 1f) ?: 0f
        val highlightGuard = sceneDetail?.highlightFraction?.coerceIn(0f, 1f) ?: 0f
        val textureSharpnessLift = textureConfidence * (1f - severity) * 0.10f
        val resolvedSharpen =
            (lerp(cleanSharp, noisySharp, severity) + textureSharpnessLift - highlightGuard * 0.025f)
        val resolvedDenoise =
            lerp(cleanDenoise, noisyDenoise, severity) * (1f - textureConfidence * 0.22f) +
                shadowPressure * 0.035f
        return Plan(
            sharpen = (resolvedSharpen * sharpnessScale).coerceIn(0.12f, 0.72f),
            denoise = (resolvedDenoise * denoiseScale).coerceIn(0.22f, 0.76f),
            severity = severity,
            textureConfidence = textureConfidence
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
}
