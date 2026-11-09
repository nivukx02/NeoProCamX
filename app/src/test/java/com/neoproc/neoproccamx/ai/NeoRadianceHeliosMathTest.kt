package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NeoRadianceHeliosMathTest {

    @Test
    fun heliosConfig_usesBt2408AndBt2100AnchorsExactly() {
        assertEquals(203f, NeoRadianceConfig.DIFFUSE_WHITE_NITS, 0f)
        assertEquals(1000f, NeoRadianceConfig.PEAK_NITS, 0f)
        assertEquals(0.2627f, NeoRadianceConfig.REC2020_LUMA_R, 0f)
        assertEquals(0.6780f, NeoRadianceConfig.REC2020_LUMA_G, 0f)
        assertEquals(0.0593f, NeoRadianceConfig.REC2020_LUMA_B, 0f)
        assertEquals(0.1593017578125f, NeoRadianceConfig.PQ_M1, 0f)
        assertEquals(78.84375f, NeoRadianceConfig.PQ_M2, 0f)
        assertEquals(0.55991073f, NeoRadianceConfig.HLG_C, 0f)
    }

    @Test
    fun rec2020Luma_isUsedForHeliosWhileV1LumaStaysRec709() {
        assertEquals(0.6780f, NeoRadianceMath.linearLumaRec2020(0f, 1f, 0f), 1e-7f)
        assertEquals(0.7152f, NeoRadianceMath.linearLuma(0f, 1f, 0f), 1e-7f)
    }

    @Test
    fun heliosCascadeTone_scalesRgbByLuminanceOnlySoHueRatiosSurvive() {
        val input = Triple(1.6f, 0.5f, 0.25f)
        val output = NeoRadianceMath.heliosCascadeToneRec2020(
            input.first,
            input.second,
            input.third
        )

        val scaleR = output.first / input.first
        val scaleG = output.second / input.second
        val scaleB = output.third / input.third
        assertEquals(scaleR, scaleG, 1e-5f)
        assertEquals(scaleR, scaleB, 1e-5f)
    }

    @Test
    fun pqRoundTrip_mapsDiffuseWhiteTo203NitsWithinOnePercent() {
        val pq = NeoRadianceMath.pqEncodeNitsHelios(NeoRadianceConfig.DIFFUSE_WHITE_NITS)
        val nits = NeoRadianceMath.pqDecodeNitsHelios(pq)

        assertEquals(203f, nits, 2.03f)
    }

    @Test
    fun shadowKnee_isC1ContinuousAtEightPercent() {
        fun slopeAt(x: Float): Float {
            val h = 0.0005f
            val hi = NeoRadianceMath.depthCurveHelios(x + h, strength = 0.40f)
            val lo = NeoRadianceMath.depthCurveHelios(x - h, strength = 0.40f)
            return (hi - lo) / (2f * h)
        }

        val leftSlope = slopeAt(0.079f)
        val rightSlope = slopeAt(0.081f)
        assertTrue(abs(leftSlope - rightSlope) < 0.12f)
    }

    @Test
    fun ictcpRoundTrip_staysUnderOneEminusFour() {
        val samples = listOf(
            Triple(0.18f, 0.18f, 0.18f),
            Triple(0.70f, 0.18f, 0.08f),
            Triple(0.04f, 0.55f, 0.20f),
            Triple(0.15f, 0.24f, 0.95f)
        )

        samples.forEach { sample ->
            val ictcp = NeoRadianceMath.rec2020ToIctcp(sample.first, sample.second, sample.third)
            val rgb = NeoRadianceMath.ictcpToRec2020(ictcp)
            assertEquals(sample.first, rgb.first, 1e-4f)
            assertEquals(sample.second, rgb.second, 1e-4f)
            assertEquals(sample.third, rgb.third, 1e-4f)
        }
    }

    @Test
    fun pipelineV1_matchesLegacyCascadeAndQuantumPathsBitExactly() {
        val sample = Triple(1.25f, 0.62f, 0.19f)
        val legacy = NeoRadianceMath.processV1Reference(
            sample.first,
            sample.second,
            sample.third
        )
        val routed = NeoRadianceMath.processNeoRadiance(
            sample.first,
            sample.second,
            sample.third,
            pipeline = NeoRadiancePipeline.V1
        )

        assertEquals(legacy.first, routed.first, 0f)
        assertEquals(legacy.second, routed.second, 0f)
        assertEquals(legacy.third, routed.third, 0f)
    }
}
