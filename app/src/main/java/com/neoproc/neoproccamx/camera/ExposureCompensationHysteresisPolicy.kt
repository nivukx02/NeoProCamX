package com.neoproc.neoproccamx.camera

import kotlin.math.roundToInt

/**
 * Schmitt-trigger quantizer for Camera2 AE compensation.
 *
 * A smoothed exposure target can hover around a half-step boundary. Plain rounding then alternates
 * the repeating request between adjacent compensation indices, which is visible as exposure shimmer
 * and needlessly rebuilds the request. This policy requires a small extra crossing on attack and a
 * wider crossing on release. Large scene changes still move directly to the nearest valid index.
 */
internal object ExposureCompensationHysteresisPolicy {
    private const val ATTACK_MARGIN_STEPS = 0.05f
    private const val RELEASE_MARGIN_STEPS = 0.20f

    fun resolveIndex(
        currentIndex: Int?,
        baseEvIndex: Int,
        biasStops: Float,
        evStepStops: Float,
        evLowerIndex: Int,
        evUpperIndex: Int,
        maxDecreaseSteps: Int = Int.MAX_VALUE,
        maxIncreaseSteps: Int = Int.MAX_VALUE
    ): Int {
        val lower = minOf(evLowerIndex, evUpperIndex)
        val upper = maxOf(evLowerIndex, evUpperIndex)
        val base = baseEvIndex.coerceIn(lower, upper)
        if (!biasStops.isFinite() || !evStepStops.isFinite() || evStepStops <= 0f) return base

        val targetIndexPosition = (base.toFloat() + biasStops / evStepStops)
            .coerceIn(lower.toFloat(), upper.toFloat())
        val nearestIndex = targetIndexPosition.roundToInt().coerceIn(lower, upper)
        val current = currentIndex?.coerceIn(lower, upper) ?: return nearestIndex
        if (nearestIndex == current) return current

        val hystereticIndex = when {
            targetIndexPosition < current.toFloat() -> {
                val attackThreshold = current - 0.5f - ATTACK_MARGIN_STEPS
                if (targetIndexPosition <= attackThreshold) nearestIndex else current
            }
            targetIndexPosition > current.toFloat() -> {
                val releaseThreshold = current + 0.5f + RELEASE_MARGIN_STEPS
                if (targetIndexPosition >= releaseThreshold) nearestIndex else current
            }
            else -> current
        }
        val boundedDecrease = maxDecreaseSteps.coerceAtLeast(0)
        val boundedIncrease = maxIncreaseSteps.coerceAtLeast(0)
        val minimumAllowed = (current.toLong() - boundedDecrease.toLong())
            .coerceAtLeast(lower.toLong()).toInt()
        val maximumAllowed = (current.toLong() + boundedIncrease.toLong())
            .coerceAtMost(upper.toLong()).toInt()
        return hystereticIndex.coerceIn(
            minimumAllowed,
            maximumAllowed
        )
    }
}
