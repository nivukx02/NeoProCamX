package com.neoproc.neoproccamx.apex

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexToneMapperTest {

    @Test
    fun piecewisePerceptualLumaIsMonotonicAndBounded() {
        val metrics = SceneMetrics(
            dynamicRangeScore = 0.74f,
            shadowNeed = 0.58f,
            localContrastScore = 0.46f,
            clipRisk = 0.62f,
            noiseRisk = 0.08f,
            sceneWhite = 0.30f
        )
        val params = ApexProfileParams.compute(metrics, ApexMode.CINEMA)

        var previous = 0f
        for (i in 0..64) {
            val input = params.sourcePeakNits * (i / 64f)
            val mapped = ApexToneMapper.mapLumaNits(input, params, metrics)
            assertTrue("tone curve must be monotonic at sample $i", mapped + 0.0001f >= previous)
            assertTrue("tone curve must stay within target peak", mapped <= params.targetPeakNits + 0.001f)
            previous = mapped
        }
    }

    @Test
    fun shoulderIsC1ContinuousAtAdaptiveKnee() {
        val metrics = SceneMetrics(
            dynamicRangeScore = 0.86f,
            shadowNeed = 0.66f,
            localContrastScore = 0.52f,
            clipRisk = 0.70f,
            noiseRisk = 0.06f,
            sceneWhite = 0.31f
        )
        val params = ApexProfileParams.compute(metrics, ApexMode.SHARP)
        val knee = params.sourcePeakNits * params.kneeRatio
        val delta = params.sourcePeakNits * 0.0005f

        val belowSlope = (
            ApexToneMapper.mapLumaNits(knee, params, metrics) -
                ApexToneMapper.mapLumaNits(knee - delta, params, metrics)
            ) / delta
        val aboveSlope = (
            ApexToneMapper.mapLumaNits(knee + delta, params, metrics) -
                ApexToneMapper.mapLumaNits(knee, params, metrics)
            ) / delta

        assertTrue("knee slope mismatch ${abs(belowSlope - aboveSlope)}", abs(belowSlope - aboveSlope) < 0.08f)
    }
}
