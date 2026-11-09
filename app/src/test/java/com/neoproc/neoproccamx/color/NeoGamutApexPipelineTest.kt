package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoGamutApexPipelineTest {
    @Test
    fun `dual illuminant sensor matrices interpolate in mired space`() {
        val tungsten = floatArrayOf(
            1.10f, -0.04f, -0.06f,
            -0.08f, 1.14f, -0.06f,
            0.02f, -0.18f, 1.16f
        )
        val d65 = floatArrayOf(
            0.92f, 0.03f, 0.05f,
            0.04f, 0.98f, -0.02f,
            -0.03f, 0.12f, 0.91f
        )
        val cct = 4_500f
        val expectedT = ((1_000_000f / cct - 1_000_000f / ApexColorConfig.DUAL_ILLUMINANT_CCT_D65) /
            (1_000_000f / ApexColorConfig.DUAL_ILLUMINANT_CCT_A - 1_000_000f / ApexColorConfig.DUAL_ILLUMINANT_CCT_D65))
            .coerceIn(0f, 1f)

        val actualT = NeoGamutApexPipeline.miredInterpolationWeight(cct)
        val interpolated = NeoGamutApexPipeline.interpolateSensorToXyz(
            colorMatrix1 = tungsten,
            colorMatrix2 = d65,
            cctKelvin = cct
        )

        assertEquals(expectedT, actualT, 0.000001f)
        tungsten.indices.forEach { index ->
            assertEquals(
                d65[index] * (1f - expectedT) + tungsten[index] * expectedT,
                interpolated[index],
                0.000001f
            )
        }
    }

    @Test
    fun `ML white balance fusion is confidence weighted and temporally stabilized`() {
        val fused = NeoGamutApexPipeline.fuseWhiteBalance(
            mlWhite = ApexColorConfig.WhitePoint.D65.xyz,
            sensorWhite = ApexColorConfig.WhitePoint.D55.xyz,
            grayEdgeWhite = ApexColorConfig.WhitePoint.D50.xyz,
            mlConfidence = 0.75f
        )
        val expectedMlWeight = 0.75f * 0.6f
        val expectedSum = expectedMlWeight + 0.3f + 0.1f

        assertEquals(expectedMlWeight / expectedSum, fused.mlWeight, 0.000001f)
        assertEquals(0.3f / expectedSum, fused.sensorWeight, 0.000001f)
        assertEquals(0.1f / expectedSum, fused.grayEdgeWeight, 0.000001f)
        assertEquals(1f, fused.white[1], 0.000001f)

        val previous = ApexColorConfig.WhitePoint.D55.xyz
        val stabilized = NeoGamutApexPipeline.temporalWhiteBalanceEma(previous, fused.white)
        val expectedX = previous[0] * (1f - ApexColorConfig.ML_WB_TEMPORAL_EMA_ALPHA) +
            fused.white[0] * ApexColorConfig.ML_WB_TEMPORAL_EMA_ALPHA

        assertEquals(expectedX / stabilized[1], stabilized[0], 0.000001f)
        assertEquals(1f, stabilized[1], 0.000001f)
    }

    @Test
    fun `pipeline v1 delegates to the legacy Neo Wide Gamut output exactly`() {
        val input = AcesRgb(1.18f, 0.44f, 0.21f)
        val legacy = NeoWideGamut2.neoToTargetRgb(
            rgb = input,
            target = NeoWideGamut2.TargetGamut.REC2020,
            compress = true
        )

        val v1 = NeoGamutApexPipeline.processForOutput(
            rgb = input,
            pipeline = NeoGamutPipeline.V1,
            target = NeoWideGamut2.TargetGamut.REC2020
        )

        assertEquals(legacy.r, v1.r, 0f)
        assertEquals(legacy.g, v1.g, 0f)
        assertEquals(legacy.b, v1.b, 0f)
    }

    @Test
    fun `memory color guards keep protected hue drift within two degrees`() {
        val skin = NeoGamutApexPipeline.protectMemoryHue(
            hueDegrees = 53.6f,
            proposedHueDegrees = 59.4f,
            memoryClass = NeoGamutMemoryClass.SKIN
        )
        val sky = NeoGamutApexPipeline.protectMemoryHue(
            hueDegrees = 263.0f,
            proposedHueDegrees = 256.0f,
            memoryClass = NeoGamutMemoryClass.SKY
        )
        val foliage = NeoGamutApexPipeline.protectMemoryHue(
            hueDegrees = 139.0f,
            proposedHueDegrees = 150.0f,
            memoryClass = NeoGamutMemoryClass.FOLIAGE
        )

        assertTrue(kotlin.math.abs(skin - 53.6f) <= 2.0001f)
        assertTrue(kotlin.math.abs(sky - 263.0f) <= 2.0001f)
        assertTrue(kotlin.math.abs(foliage - 139.0f) <= 2.0001f)
    }
}
