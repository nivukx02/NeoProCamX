package com.neoproc.neoproccamx.camera

import kotlin.math.sqrt

/**
 * RAW-domain exposure governor for NeoProCamX sensor-linear NDR.
 *
 * Camera2 AE remains the exposure baseline. This policy can only add temporary negative protection
 * when the exact RAW histogram proves highlight pressure, then releases monotonically back to neutral.
 * It must never alternate across neutral: a positive RAW bias followed by a clipped-frame correction
 * creates a delayed two-state loop on deep Camera2 pipelines and visibly changes NDR brightness/chroma
 * a few seconds after startup. NDR shadow/midtone rendering remains owned by the project tone operator.
 */
internal object SensorLinearNdrExposurePolicy {
    const val MIN_STOPS = -1.0f
    const val MAX_STOPS = 0.67f
    const val LIVE_METRICS_MAX_AGE_MS = 1_000L
    const val STALE_METRICS_RELEASE_MS = 1_000L
    const val STALE_METRICS_RELEASE_TICK_MS = 250L
    // Closed-loop Camera2 pacing. A new command is issued only after the exact RAW metadata confirms
    // the previous index, then moves in bounded increments: faster toward highlight safety, gentler
    // toward shadow lift. This removes delayed-feedback exposure pumping without reducing latitude.
    const val MIN_REQUEST_INTERVAL_MS = 160L
    const val CONTROL_EVALUATION_INTERVAL_MS = 160L
    const val MAX_HIGHLIGHT_PROTECT_INDEX_STEP = 3
    const val MAX_SHADOW_LIFT_INDEX_STEP = 2

    fun resolveTargetStops(
        averageLuma: Float,
        medianLuma: Float,
        p95Luma: Float,
        p99Luma: Float,
        highlightClipFraction: Float,
        redHighlightClipFraction: Float,
        p99Red: Float = p99Luma,
        p99Green: Float = p99Luma,
        p99Blue: Float = p99Luma
    ): Float {
        val average = finiteUnit(averageLuma, 0.18f)
        val median = finiteUnit(medianLuma, 0.18f)
        val p95 = finiteUnit(p95Luma, 0.75f)
        val p99 = finiteUnit(p99Luma, 0.90f)
        val pressure = highlightPressure(
            p95 = p95,
            p99 = p99,
            clipped = finiteUnit(highlightClipFraction, 0f),
            redClipped = finiteUnit(redHighlightClipFraction, 0f),
            p99Red = finiteUnit(p99Red, p99),
            p99Green = finiteUnit(p99Green, p99),
            p99Blue = finiteUnit(p99Blue, p99)
        )
        if (pressure > 0f) return (-pressure).coerceAtLeast(MIN_STOPS)

        // Brighten only when EVERY distribution measurement proves real headroom. Each measurement
        // contributes a continuous, monotonic ceiling and the most highlight-sensitive ceiling wins.
        // The old threshold ladder jumped directly from +0.33 to +0.17 around median=0.13; Camera2 then
        // quantized that discontinuity into an alternating EV-index command. Smoothstep interpolation
        // preserves the same calibrated anchor points without an exposure-pumping boundary.
        return minOf(
            medianHeadroomStops(median),
            averageHeadroomStops(average),
            p95HeadroomStops(p95),
            p99HeadroomStops(p99)
        ).coerceIn(0f, MAX_STOPS)
    }

    /**
     * Stateful Schmitt controller for the RAW loop.
     *
     * [resolveTargetStops] describes the scene, but applying that absolute answer on every delayed
     * Camera2 frame creates a two-state oscillator: lift -> clipped frame -> negative EV -> dark frame
     * -> lift. This method instead advances the already-applied bias by one bounded control step. A wide
     * hold band separates highlight attack from shadow lift, and negative protection is released slowly.
     */
    fun resolveNextStops(
        currentStops: Float,
        averageLuma: Float,
        medianLuma: Float,
        p95Luma: Float,
        p99Luma: Float,
        p99Red: Float,
        p99Green: Float,
        p99Blue: Float,
        highlightClipFraction: Float,
        redHighlightClipFraction: Float,
        sensorSensitivityIso: Int = 0,
        sensorSensitivityUpperIso: Int = 0,
        exposureTimeNs: Long = 0L,
        frameDurationNs: Long = 0L,
        noiseS: Float = 0f,
        noiseO: Float = 0f
    ): Float {
        val current = currentStops.takeIf(Float::isFinite)
            ?.coerceIn(MIN_STOPS, MAX_STOPS) ?: 0f
        val average = finiteUnit(averageLuma, 0.18f)
        val median = finiteUnit(medianLuma, 0.18f)
        val p95 = finiteUnit(p95Luma, 0.75f)
        val p99 = finiteUnit(p99Luma, 0.90f)
        val pressure = highlightPressure(
            p95 = p95,
            p99 = p99,
            clipped = finiteUnit(highlightClipFraction, 0f),
            redClipped = finiteUnit(redHighlightClipFraction, 0f),
            p99Red = finiteUnit(p99Red, p99),
            p99Green = finiteUnit(p99Green, p99),
            p99Blue = finiteUnit(p99Blue, p99)
        )

        if (pressure > 0f) {
            // Continuous attack: broad near-clipping reduces a positive lift before crossing through
            // neutral, while a genuinely severe clipped area can still reach the safety floor quickly.
            val attackStops = 0.055f + 0.265f * pressure
            return (current - attackStops).coerceIn(MIN_STOPS, 0f)
        }

        // No highlight attack: return toward Camera2 AE's neutral baseline without ever crossing it.
        // Positive values can exist transiently after an in-process upgrade/debug A/B, so retire them
        // faster. Negative protection releases slowly to avoid exposure pumping at the clip boundary.
        val releaseStep = if (current > 0f) 0.12f else 0.035f
        return approach(current, 0f, releaseStep).coerceIn(MIN_STOPS, 0f)
    }

    /** Fast highlight attack, controlled dark-scene lift, slow neutral release to prevent pumping. */
    fun smoothStops(currentStops: Float, targetStops: Float): Float {
        val current = currentStops.takeIf(Float::isFinite)?.coerceIn(MIN_STOPS, MAX_STOPS) ?: 0f
        val target = targetStops.takeIf(Float::isFinite)?.coerceIn(MIN_STOPS, MAX_STOPS) ?: 0f
        val alpha = when {
            target < current -> 0.72f
            target > current && target > 0f -> 0.28f
            else -> 0.12f
        }
        return (current + (target - current) * alpha).coerceIn(MIN_STOPS, MAX_STOPS)
    }

    fun resolveAppliedStops(measuredStops: Float, metricsAgeMs: Long): Float {
        val measured = measuredStops.takeIf(Float::isFinite)?.coerceIn(MIN_STOPS, MAX_STOPS) ?: 0f
        val age = metricsAgeMs.coerceAtLeast(0L)
        if (age <= LIVE_METRICS_MAX_AGE_MS) return measured
        val end = LIVE_METRICS_MAX_AGE_MS + STALE_METRICS_RELEASE_MS
        if (age >= end) return 0f
        val release = (age - LIVE_METRICS_MAX_AGE_MS).toFloat() / STALE_METRICS_RELEASE_MS.toFloat()
        return measured * (1f - release)
    }

    private fun finiteUnit(value: Float, fallback: Float): Float =
        value.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: fallback

    private fun highlightPressure(
        p95: Float,
        p99: Float,
        clipped: Float,
        redClipped: Float,
        p99Red: Float,
        p99Green: Float,
        p99Blue: Float
    ): Float {
        val broadTail = smoothstep(0.885f, 0.970f, p95) *
            smoothstep(0.965f, 0.998f, p99)
        val clippedArea = smoothstep(0.012f, 0.085f, clipped)
        val redClippedArea = smoothstep(0.035f, 0.180f, redClipped)
        val maxChannelP99 = maxOf(p99Red, p99Green, p99Blue)
        val channelTail = smoothstep(0.985f, 0.999f, maxChannelP99) *
            smoothstep(0.840f, 0.955f, p95)
        return maxOf(broadTail, clippedArea, redClippedArea, channelTail)
            .coerceIn(0f, 1f)
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val x = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun inverseSmoothstep(edge0: Float, edge1: Float, value: Float): Float =
        1f - smoothstep(edge0, edge1, value)

    /**
     * Positive Camera2 EV is useful only while the sensor still has real capture headroom. At the
     * sensitivity/shutter ceiling it cannot create detail; it merely asks the HAL to hold a saturated
     * high-ISO state while the display operator amplifies read/chroma noise. The heteroscedastic RAW
     * noise model softens lift before that limit, but never changes highlight-protection authority.
     */
    private fun positiveLiftAuthority(
        average: Float,
        median: Float,
        sensorSensitivityIso: Int,
        sensorSensitivityUpperIso: Int,
        exposureTimeNs: Long,
        frameDurationNs: Long,
        noiseS: Float,
        noiseO: Float
    ): Float {
        val isoAuthority = if (sensorSensitivityIso > 0 && sensorSensitivityUpperIso > 0) {
            inverseSmoothstep(
                0.70f,
                0.98f,
                sensorSensitivityIso.toFloat() / sensorSensitivityUpperIso.toFloat()
            )
        } else {
            1f
        }
        val exposureAuthority = if (exposureTimeNs > 0L && frameDurationNs > 0L) {
            inverseSmoothstep(
                0.55f,
                0.86f,
                exposureTimeNs.toDouble().div(frameDurationNs.toDouble()).toFloat()
            )
        } else {
            1f
        }
        val captureHeadroom = minOf(isoAuthority, exposureAuthority)
        val shot = noiseS.takeIf { it.isFinite() && it > 0f }
        val read = noiseO.takeIf { it.isFinite() && it > 0f }
        if (shot == null && read == null) return captureHeadroom

        val signal = maxOf(average, median, 1.0e-6f)
        val variance = (read ?: 0f) + (shot ?: 0f) * signal
        val sigma = sqrt(variance.coerceAtLeast(1.0e-12f))
        val signalToNoise = signal / sigma
        // Retain some authority while capture headroom exists so AE can gather more photons. Once the
        // physical headroom reaches zero, the product is exactly zero regardless of scene darkness.
        val noiseAuthority = 0.35f + 0.65f * smoothstep(0.75f, 4.0f, signalToNoise)
        return (captureHeadroom * noiseAuthority).coerceIn(0f, 1f)
    }

    private fun approach(current: Float, target: Float, maxDelta: Float): Float = when {
        target > current -> minOf(current + maxDelta, target)
        target < current -> maxOf(current - maxDelta, target)
        else -> current
    }

    private fun medianHeadroomStops(value: Float): Float = when {
        value <= 0.035f -> 0.67f
        value < 0.075f -> smoothSegment(value, 0.035f, 0.075f, 0.67f, 0.50f)
        value < 0.130f -> smoothSegment(value, 0.075f, 0.130f, 0.50f, 0.33f)
        value < 0.200f -> smoothSegment(value, 0.130f, 0.200f, 0.33f, 0.17f)
        value < 0.280f -> smoothSegment(value, 0.200f, 0.280f, 0.17f, 0f)
        else -> 0f
    }

    private fun averageHeadroomStops(value: Float): Float = when {
        value <= 0.075f -> 0.67f
        value < 0.130f -> smoothSegment(value, 0.075f, 0.130f, 0.67f, 0.50f)
        value < 0.200f -> smoothSegment(value, 0.130f, 0.200f, 0.50f, 0.33f)
        value < 0.250f -> smoothSegment(value, 0.200f, 0.250f, 0.33f, 0.17f)
        value < 0.330f -> smoothSegment(value, 0.250f, 0.330f, 0.17f, 0f)
        else -> 0f
    }

    private fun p95HeadroomStops(value: Float): Float = when {
        value <= 0.350f -> 0.67f
        value < 0.520f -> smoothSegment(value, 0.350f, 0.520f, 0.67f, 0.50f)
        value < 0.680f -> smoothSegment(value, 0.520f, 0.680f, 0.50f, 0.33f)
        value < 0.780f -> smoothSegment(value, 0.680f, 0.780f, 0.33f, 0.17f)
        value < 0.900f -> smoothSegment(value, 0.780f, 0.900f, 0.17f, 0f)
        else -> 0f
    }

    private fun p99HeadroomStops(value: Float): Float = when {
        value <= 0.650f -> 0.67f
        value < 0.750f -> smoothSegment(value, 0.650f, 0.750f, 0.67f, 0.50f)
        value < 0.840f -> smoothSegment(value, 0.750f, 0.840f, 0.50f, 0.33f)
        value < 0.900f -> smoothSegment(value, 0.840f, 0.900f, 0.33f, 0.17f)
        value < 0.960f -> smoothSegment(value, 0.900f, 0.960f, 0.17f, 0f)
        else -> 0f
    }

    private fun smoothSegment(
        value: Float,
        lower: Float,
        upper: Float,
        lowerStops: Float,
        upperStops: Float
    ): Float {
        val linear = ((value - lower) / (upper - lower)).coerceIn(0f, 1f)
        val smooth = linear * linear * (3f - 2f * linear)
        return lowerStops + (upperStops - lowerStops) * smooth
    }
}
