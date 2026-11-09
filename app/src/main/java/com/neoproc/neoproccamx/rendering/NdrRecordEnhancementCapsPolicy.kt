package com.neoproc.neoproccamx.rendering

import kotlin.math.sqrt

/**
 * ISO/noise-adaptive denoise and sharpen caps for the offscreen EGL shader.
 *
 * Live NDR accepts physical noise evidence only: the current Camera2 sensor ISO and a per-frame
 * SENSOR_NOISE_PROFILE. A scene-global luma statistic is dominated by legitimate contrast and edges,
 * so it has no authority unless a caller explicitly identifies it as a trusted, noise-isolated
 * measurement. This prevents clean detailed scenes from being denoised into softness while retaining
 * stronger cleanup for genuinely noisy captures.
 */
object NdrRecordEnhancementCapsPolicy {

    data class Caps(val denoiseCap: Float, val sharpenCap: Float)

    private const val HDR_DENOISE_CAP_CLEAN = 0.35f
    private const val HDR_SHARPEN_CAP_CLEAN = 0.45f
    private const val HDR_DENOISE_CAP_NOISY = 0.72f
    private const val HDR_SHARPEN_CAP_NOISY = 0.30f

    private const val ISO_CLEAN = 800f
    private const val ISO_NOISY = 6400f

    fun resolve(
        family: GpuSignalFamily?,
        iso: Float,
        noiseMetric: Float,
        noiseMetricConfidence: Float = 0f,
        sensorShotNoiseCoefficient: Float = 0f,
        sensorReadNoiseCoefficient: Float = 0f,
        sensorNoiseModelConfidence: Float = 0f
    ): Caps = when (family) {
        GpuSignalFamily.SDR -> Caps(denoiseCap = 0.45f, sharpenCap = 0.45f)
        null -> Caps(denoiseCap = 0.50f, sharpenCap = 0.55f)
        GpuSignalFamily.HDR, GpuSignalFamily.LOG, GpuSignalFamily.RAW -> {
            val severity = noiseSeverity(
                iso = iso,
                noiseMetric = noiseMetric,
                noiseMetricConfidence = noiseMetricConfidence,
                sensorShotNoiseCoefficient = sensorShotNoiseCoefficient,
                sensorReadNoiseCoefficient = sensorReadNoiseCoefficient,
                sensorNoiseModelConfidence = sensorNoiseModelConfidence
            )
            Caps(
                denoiseCap = lerp(HDR_DENOISE_CAP_CLEAN, HDR_DENOISE_CAP_NOISY, severity),
                sharpenCap = lerp(HDR_SHARPEN_CAP_CLEAN, HDR_SHARPEN_CAP_NOISY, severity)
            )
        }
    }

    /** Returns 0 for clean through 1 for very noisy, using only qualified evidence. */
    fun noiseSeverity(
        iso: Float,
        noiseMetric: Float,
        noiseMetricConfidence: Float = 0f,
        sensorShotNoiseCoefficient: Float = 0f,
        sensorReadNoiseCoefficient: Float = 0f,
        sensorNoiseModelConfidence: Float = 0f
    ): Float {
        val safeIso = finiteNonNegative(iso)
        val isoTerm = if (safeIso <= ISO_CLEAN) {
            0f
        } else {
            ((safeIso - ISO_CLEAN) / (ISO_NOISY - ISO_CLEAN)).coerceIn(0f, 1f)
        }

        // Evaluate the heteroscedastic variance at 18% scene-linear gray. A characteristics-derived
        // range envelope is not a current-frame measurement, so the caller must supply confidence.
        val modelConfidence = unitOrZero(sensorNoiseModelConfidence)
        val shot = finiteNonNegative(sensorShotNoiseCoefficient)
        val read = finiteNonNegative(sensorReadNoiseCoefficient)
        val referenceSigma = sqrt((shot * REFERENCE_LINEAR_LUMA + read).toDouble()).toFloat()
        val sensorModelTerm = if (modelConfidence > 0f && (shot > 0f || read > 0f)) {
            ((referenceSigma - SENSOR_SIGMA_CLEAN) / (SENSOR_SIGMA_NOISY - SENSOR_SIGMA_CLEAN))
                .coerceIn(0f, 1f) * modelConfidence
        } else {
            0f
        }

        // Kept for offline spatial/temporal measurements. Live scene contrast passes confidence=0.
        val metricTerm = (finiteNonNegative(noiseMetric) / TRUSTED_METRIC_NOISY)
            .coerceIn(0f, 1f) * unitOrZero(noiseMetricConfidence)
        return maxOf(isoTerm, sensorModelTerm, metricTerm).coerceIn(0f, 1f)
    }

    private fun finiteNonNegative(value: Float): Float =
        if (value.isFinite() && value > 0f) value else 0f

    private fun unitOrZero(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    private fun lerp(a: Float, b: Float, t: Float): Float =
        a + (b - a) * t.coerceIn(0f, 1f)

    private const val REFERENCE_LINEAR_LUMA = 0.18f
    private const val SENSOR_SIGMA_CLEAN = 0.045f
    private const val SENSOR_SIGMA_NOISY = 0.14f
    private const val TRUSTED_METRIC_NOISY = 0.60f
}
