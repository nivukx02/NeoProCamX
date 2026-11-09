package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics
import com.neoproc.neoproccamx.rendering.GpuSignalFamily

/** Content-aware offline conversion quality selected from measurements of each decoded frame. */
internal object ConversionFrameQualityPolicy {
    data class Plan(val sharpen: Float, val denoise: Float, val severity: Float)

    fun resolve(
        family: GpuSignalFamily,
        detailProfile: VideoDetailProfile,
        metrics: VideoNoiseMetrics
    ): Plan {
        val m = metrics.clamped()
        // Luma standard deviation contains real scene contrast, so it receives a deliberately small
        // weight. Temporal/chroma instability are stronger evidence of noise in an offline frame pair.
        val severity = maxOf(
            (m.lumaNoise / 0.75f).coerceIn(0f, 0.35f),
            (m.chromaNoise * 0.72f).coerceIn(0f, 0.72f),
            (m.temporalInstability * 1.15f).coerceIn(0f, 1f),
            (m.frameToFrameRgbVariance * 0.90f).coerceIn(0f, 0.90f)
        )
        val (cleanSharp, noisySharp) = when (detailProfile) {
            VideoDetailProfile.NATURAL_DETAIL -> 0.30f to 0.18f
            VideoDetailProfile.FOCUSED_DETAIL -> 0.48f to 0.27f
            VideoDetailProfile.EXTREME_DETAIL -> 0.62f to 0.34f
        }
        val cleanDenoise = if (family == GpuSignalFamily.SDR) 0.32f else 0.35f
        val noisyDenoise = if (family == GpuSignalFamily.SDR) 0.66f else 0.72f
        return Plan(
            sharpen = lerp(cleanSharp, noisySharp, severity),
            denoise = lerp(cleanDenoise, noisyDenoise, severity),
            severity = severity
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
}
