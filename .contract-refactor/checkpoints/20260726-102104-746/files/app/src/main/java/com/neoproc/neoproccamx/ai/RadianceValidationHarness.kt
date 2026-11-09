package com.neoproc.neoproccamx.ai

import kotlin.math.abs

internal data class RadianceValidationReport(
    val maxIctcpHueDriftDegrees: Float,
    val pqDiffuseWhiteErrorPercent: Float,
    val shadowKneeSlopeDiscontinuity: Float,
    val rgcHueErrorEstimate: Float,
    val mergeShadowSnrGainDb: Float,
    val v1BitMatch: Boolean,
    val brightnessPumpingPercent: Float
) {
    val passed: Boolean
        get() = maxIctcpHueDriftDegrees <= 2f &&
            pqDiffuseWhiteErrorPercent <= 1f &&
            shadowKneeSlopeDiscontinuity <= 0.12f &&
            rgcHueErrorEstimate <= 2f &&
            mergeShadowSnrGainDb >= 6f &&
            v1BitMatch &&
            brightnessPumpingPercent <= 1f
}

internal object RadianceValidationHarness {

    fun run(): RadianceValidationReport {
        val maxHueDrift = measureIctcpHueDrift()
        val pq = NeoRadianceMath.pqEncodeNitsHelios(NeoRadianceConfig.DIFFUSE_WHITE_NITS)
        val decoded = NeoRadianceMath.pqDecodeNitsHelios(pq)
        val pqError = abs(decoded - NeoRadianceConfig.DIFFUSE_WHITE_NITS) /
            NeoRadianceConfig.DIFFUSE_WHITE_NITS * 100f
        val slopeDiscontinuity = measureShadowSlopeDiscontinuity()
        val rgcHue = measureRgcHueDrift()
        val snr = RadianceMerge.theoreticalSnrGainDb(validFrameCount = 4)
        val bitMatch = v1RouteMatchesReference()
        val pumping = measureBrightnessPumpingPercent()

        return RadianceValidationReport(
            maxIctcpHueDriftDegrees = maxHueDrift,
            pqDiffuseWhiteErrorPercent = pqError,
            shadowKneeSlopeDiscontinuity = slopeDiscontinuity,
            rgcHueErrorEstimate = rgcHue,
            mergeShadowSnrGainDb = snr,
            v1BitMatch = bitMatch,
            brightnessPumpingPercent = pumping
        )
    }

    private fun measureIctcpHueDrift(): Float {
        val samples = listOf(
            Triple(1.0f, 0.20f, 0.08f),
            Triple(0.05f, 0.75f, 0.20f),
            Triple(0.05f, 0.25f, 0.95f),
            Triple(1.0f, 0.60f, 0.04f)
        )
        var maxDrift = 0f
        for (sample in samples) {
            val scaled = Triple(sample.first * 1.4f, sample.second * 1.4f, sample.third * 1.4f)
            val before = NeoRadianceMath.rec2020ToIctcp(scaled.first, scaled.second, scaled.third)
            val managed = NeoRadianceMath.heliosIctcpChromaManagement(
                scaled.first,
                scaled.second,
                scaled.third
            )
            val after = NeoRadianceMath.rec2020ToIctcp(managed.first, managed.second, managed.third)
            maxDrift = maxOf(
                maxDrift,
                hueDistance(
                    NeoRadianceMath.ictcpHueDegrees(before),
                    NeoRadianceMath.ictcpHueDegrees(after)
                )
            )
        }
        return maxDrift
    }

    private fun measureShadowSlopeDiscontinuity(): Float {
        fun slopeAt(x: Float): Float {
            val h = 0.0005f
            return (NeoRadianceMath.depthCurveHelios(x + h) - NeoRadianceMath.depthCurveHelios(x - h)) /
                (2f * h)
        }
        return abs(slopeAt(0.079f) - slopeAt(0.081f))
    }

    private fun measureRgcHueDrift(): Float {
        val samples = listOf(
            Triple(2.4f, 0.08f, 0.02f),
            Triple(0.08f, 2.2f, 0.12f),
            Triple(0.04f, 0.24f, 2.5f)
        )
        var maxDrift = 0f
        for (sample in samples) {
            val before = NeoRadianceMath.rec2020ToIctcp(sample.first, sample.second, sample.third)
            val afterRgb = NeoRadianceMath.acesReferenceGamutCompressHelios(sample.first, sample.second, sample.third)
            val after = NeoRadianceMath.rec2020ToIctcp(afterRgb.first, afterRgb.second, afterRgb.third)
            maxDrift = maxOf(
                maxDrift,
                hueDistance(
                    NeoRadianceMath.ictcpHueDegrees(before),
                    NeoRadianceMath.ictcpHueDegrees(after)
                )
            )
        }
        return maxDrift
    }

    private fun v1RouteMatchesReference(): Boolean {
        val sample = Triple(1.25f, 0.62f, 0.19f)
        val reference = NeoRadianceMath.processV1Reference(sample.first, sample.second, sample.third)
        val routed = NeoRadianceMath.processNeoRadiance(
            sample.first,
            sample.second,
            sample.third,
            pipeline = NeoRadiancePipeline.V1
        )
        return reference.first.toRawBits() == routed.first.toRawBits() &&
            reference.second.toRawBits() == routed.second.toRawBits() &&
            reference.third.toRawBits() == routed.third.toRawBits()
    }

    private fun measureBrightnessPumpingPercent(): Float {
        val previous = 1.0f
        val current = 1.05f
        val smoothed = RadianceMerge.temporalEma(previous, current)
        return abs(smoothed - previous) / previous * 100f
    }

    private fun hueDistance(a: Float, b: Float): Float {
        var delta = abs(a - b)
        if (delta > 180f) delta = 360f - delta
        return delta
    }
}
