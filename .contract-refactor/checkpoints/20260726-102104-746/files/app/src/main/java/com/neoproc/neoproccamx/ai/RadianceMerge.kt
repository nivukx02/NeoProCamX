package com.neoproc.neoproccamx.ai

import kotlin.math.log10
import kotlin.math.pow

internal data class RadianceSample(
    val r: Float,
    val g: Float,
    val b: Float,
    val normalizedSignal: Float,
    val exposureScale: Float,
    val motionConfidence: Float
)

internal data class RadianceMergeResult(
    val r: Float,
    val g: Float,
    val b: Float,
    val usedReferenceFallback: Boolean,
    val validFrameCount: Int,
    val shadowSnrGainDb: Float
)

internal object RadianceMerge {

    fun exposureWeight(z: Float): Float {
        val safe = z.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        return (1f - (2f * safe - 1f).pow(12)).coerceIn(0f, 1f)
    }

    fun mergeExposureStack(
        samples: List<RadianceSample>,
        referenceIndex: Int = samples.lastIndex / 2
    ): RadianceMergeResult {
        if (samples.isEmpty()) {
            return RadianceMergeResult(0f, 0f, 0f, usedReferenceFallback = true, validFrameCount = 0, shadowSnrGainDb = 0f)
        }
        val reference = samples[referenceIndex.coerceIn(samples.indices)]
        if (samples.any { it.motionConfidence < NeoRadianceConfig.RADIANCE_MOTION_CONFIDENCE_MIN }) {
            return reference.toFallbackResult()
        }

        var totalWeight = 0f
        var r = 0f
        var g = 0f
        var b = 0f
        var validFrames = 0
        for (sample in samples) {
            val exposure = sample.exposureScale.takeIf { it.isFinite() && it > NeoRadianceConfig.EPSILON } ?: continue
            val weight = exposureWeight(sample.normalizedSignal) *
                sample.motionConfidence.coerceIn(0f, 1f)
            if (weight <= NeoRadianceConfig.EPSILON) continue
            r += (sample.r / exposure) * weight
            g += (sample.g / exposure) * weight
            b += (sample.b / exposure) * weight
            totalWeight += weight
            validFrames++
        }

        if (totalWeight <= NeoRadianceConfig.EPSILON || validFrames == 0) {
            return reference.toFallbackResult()
        }

        return RadianceMergeResult(
            r = r / totalWeight,
            g = g / totalWeight,
            b = b / totalWeight,
            usedReferenceFallback = false,
            validFrameCount = validFrames,
            shadowSnrGainDb = theoreticalSnrGainDb(validFrames)
        )
    }

    fun theoreticalSnrGainDb(validFrameCount: Int): Float {
        val count = validFrameCount.coerceAtLeast(1).toFloat()
        return (10f * log10(count))
    }

    fun temporalEma(
        previous: Float,
        current: Float,
        alpha: Float = NeoRadianceConfig.RADIANCE_TEMPORAL_EMA_ALPHA
    ): Float {
        val a = alpha.coerceIn(0f, 1f)
        val prev = previous.takeIf { it.isFinite() } ?: current
        val cur = current.takeIf { it.isFinite() } ?: prev
        return prev + (cur - prev) * a
    }

    fun protectHueDegrees(
        previousHueDegrees: Float,
        proposedHueDegrees: Float,
        maxDeltaDegrees: Float = 2f
    ): Float {
        var delta = proposedHueDegrees - previousHueDegrees
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return previousHueDegrees + delta.coerceIn(-maxDeltaDegrees, maxDeltaDegrees)
    }

    private fun RadianceSample.toFallbackResult(): RadianceMergeResult {
        val exposure = exposureScale.takeIf { it.isFinite() && it > NeoRadianceConfig.EPSILON } ?: 1f
        return RadianceMergeResult(
            r = r / exposure,
            g = g / exposure,
            b = b / exposure,
            usedReferenceFallback = true,
            validFrameCount = 1,
            shadowSnrGainDb = 0f
        )
    }
}
