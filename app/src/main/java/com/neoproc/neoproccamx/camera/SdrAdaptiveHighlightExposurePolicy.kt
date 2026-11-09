package com.neoproc.neoproccamx.camera

/**
 * Scene-driven highlight protection for true SDR capture.
 *
 * Post-processing cannot recover a sensor channel after it has clipped. This policy therefore uses
 * the existing low-resolution scene meter to add a bounded negative Camera2 AE compensation before
 * the Rec.709 rendering stage. It is deliberately neutral for ordinary scenes, attacks clipping
 * quickly, and releases slowly so video exposure does not pump.
 */
internal object SdrAdaptiveHighlightExposurePolicy {
    const val MAX_PROTECTION_STOPS = -1.0f
    const val LIVE_METRICS_MAX_AGE_MS = 1_250L
    const val STALE_METRICS_RELEASE_MS = 1_500L
    const val STALE_METRICS_RELEASE_TICK_MS = 250L

    fun resolveProtectionStops(
        averageLuma: Float,
        highlightClipFraction: Float,
        redHighlightClipFraction: Float = 0f
    ): Float {
        val luma = averageLuma.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.5f
        val clip = highlightClipFraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val redClip = redHighlightClipFraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f

        // Protect broad skies/windows strongly, but leave tiny specular highlights alone.
        val clipStops = when {
            clip >= 0.25f -> -0.83f
            clip >= 0.12f -> -0.58f
            clip >= 0.05f -> -0.38f
            clip >= 0.02f -> -0.22f
            clip >= 0.008f -> -0.10f
            else -> 0f
        }
        // Pre-empt clipping in an unusually bright frame before the terminal histogram bins fill.
        val lumaStops = when {
            luma >= 0.84f -> -0.38f
            luma >= 0.74f -> -0.24f
            luma >= 0.66f -> -0.12f
            else -> 0f
        }
        // Warm lamps and skin can clip red before Rec.709 luma reports a broad highlight.
        val redStops = when {
            redClip >= 0.20f -> -0.75f
            redClip >= 0.10f -> -0.48f
            redClip >= 0.05f -> -0.30f
            redClip >= 0.02f -> -0.14f
            else -> 0f
        }
        return minOf(clipStops, lumaStops, redStops).coerceAtLeast(MAX_PROTECTION_STOPS)
    }

    fun smoothProtectionStops(currentStops: Float, targetStops: Float): Float {
        val current = currentStops.takeIf(Float::isFinite)
            ?.coerceIn(MAX_PROTECTION_STOPS, 0f) ?: 0f
        val target = targetStops.takeIf(Float::isFinite)
            ?.coerceIn(MAX_PROTECTION_STOPS, 0f) ?: 0f
        val alpha = if (target < current) 0.72f else 0.10f
        return (current + (target - current) * alpha)
            .coerceIn(MAX_PROTECTION_STOPS, 0f)
    }

    fun resolveAppliedProtectionStops(measuredStops: Float, metricsAgeMs: Long): Float {
        val measured = measuredStops.takeIf(Float::isFinite)
            ?.coerceIn(MAX_PROTECTION_STOPS, 0f) ?: 0f
        val age = metricsAgeMs.coerceAtLeast(0L)
        if (age <= LIVE_METRICS_MAX_AGE_MS) return measured
        val releaseEnd = LIVE_METRICS_MAX_AGE_MS + STALE_METRICS_RELEASE_MS
        if (age >= releaseEnd) return 0f
        val release = (age - LIVE_METRICS_MAX_AGE_MS).toFloat() /
            STALE_METRICS_RELEASE_MS.toFloat()
        return (measured * (1f - release)).coerceIn(MAX_PROTECTION_STOPS, 0f)
    }
}
