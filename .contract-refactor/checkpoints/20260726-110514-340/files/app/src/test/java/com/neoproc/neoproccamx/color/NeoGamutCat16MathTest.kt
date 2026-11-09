package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoGamutCat16MathTest {
    @Test
    fun `NeoGamut matrices round trip within single precision tolerance`() {
        val product = NeoGamutCat16Math.multiply3x3(
            NeoGamutCat16Math.XYZ_TO_NEO,
            NeoGamutCat16Math.NEO_TO_XYZ
        )
        val identity = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )

        identity.indices.forEach { index ->
            assertEquals(identity[index], product[index], 0.00001f)
        }
    }

    @Test
    fun `CAT16 returns identity for the D55 source white`() {
        val matrix = NeoGamutCat16Math.calculateCat16ToD55(
            NeoGamutCat16Math.D55_X,
            NeoGamutCat16Math.D55_Y
        )
        val identity = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )

        identity.indices.forEach { index ->
            assertEquals(identity[index], matrix[index], 0.00001f)
        }
    }

    @Test
    fun `manual white conversion and CAT16 never emit non finite values`() {
        listOf(2_000f, 3_200f, 5_500f, 6_500f, 10_000f).forEach { kelvin ->
            val white = NeoGamutCat16Math.kelvinToCieXy(kelvin)
            val matrix = NeoGamutCat16Math.calculateCat16ToD55(white.first, white.second)

            assertTrue(white.first.isFinite())
            assertTrue(white.second.isFinite())
            assertTrue(white.second > 0f)
            assertTrue(matrix.all { it.isFinite() })
        }
    }

    @Test
    fun `adaptive CAT16 changes smoothly with lux and kelvin estimates`() {
        val warmLowLux = NeoGamutCat16Math.calculateAdaptiveCat16ToD55(
            kelvin = 3_200f,
            lux = 24f
        )
        val warmAdjacent = NeoGamutCat16Math.calculateAdaptiveCat16ToD55(
            kelvin = 3_300f,
            lux = 26f
        )
        val daylight = NeoGamutCat16Math.calculateAdaptiveCat16ToD55(
            kelvin = 6_500f,
            lux = 1_200f
        )

        assertTrue(warmLowLux.all { it.isFinite() })
        assertTrue(daylight.all { it.isFinite() })
        val adjacentDelta = warmLowLux.indices.sumOf { index ->
            kotlin.math.abs((warmLowLux[index] - warmAdjacent[index]).toDouble())
        }
        val daylightDelta = warmLowLux.indices.sumOf { index ->
            kotlin.math.abs((warmLowLux[index] - daylight[index]).toDouble())
        }
        assertTrue("Adjacent white estimates must not jump", adjacentDelta < 0.14)
        assertTrue("Different scene whites should produce a meaningful adaptation", daylightDelta > adjacentDelta)
    }

    @Test
    fun `CIECAM16 degree of adaptation follows standardized La equation and metadata confidence`() {
        val lux = 400f
        val confidence = 0.62f
        val la = lux / (5f * Math.PI.toFloat())
        val expected = (
            1f * (1f - (1f / 3.6f) * kotlin.math.exp(((-la - 42f) / 92f))) *
                confidence
            ).coerceIn(0f, 1f)

        val actual = NeoGamutCat16Math.ciecam16DegreeOfAdaptation(
            lux = lux,
            surroundF = ApexColorConfig.SURROUND_AVERAGE,
            metaConfidence = confidence
        )

        assertEquals(expected, actual, 0.000001f)
        assertEquals(
            0f,
            NeoGamutCat16Math.ciecam16DegreeOfAdaptation(
                lux = lux,
                surroundF = ApexColorConfig.SURROUND_AVERAGE,
                metaConfidence = 0f
            ),
            0f
        )
    }

    @Test
    fun `APEX CAT16 applies D inside cone scale instead of whole matrix lerp`() {
        val d = 0.47f
        val source = ApexColorConfig.whitePointXyz(ApexColorConfig.WhitePoint.D55)
        val target = ApexColorConfig.whitePointXyz(ApexColorConfig.WhitePoint.D65)
        val sourceResponse = NeoGamutCat16Math.multiplyVector(ApexColorConfig.CAT16_M16, source)
        val targetResponse = NeoGamutCat16Math.multiplyVector(ApexColorConfig.CAT16_M16, target)
        val scale = floatArrayOf(
            d * (targetResponse[0] / sourceResponse[0]) + (1f - d),
            d * (targetResponse[1] / sourceResponse[1]) + (1f - d),
            d * (targetResponse[2] / sourceResponse[2]) + (1f - d)
        )
        val expected = NeoGamutCat16Math.multiply3x3(
            ApexColorConfig.CAT16_M16_INVERSE,
            floatArrayOf(
                ApexColorConfig.CAT16_M16[0] * scale[0],
                ApexColorConfig.CAT16_M16[1] * scale[0],
                ApexColorConfig.CAT16_M16[2] * scale[0],
                ApexColorConfig.CAT16_M16[3] * scale[1],
                ApexColorConfig.CAT16_M16[4] * scale[1],
                ApexColorConfig.CAT16_M16[5] * scale[1],
                ApexColorConfig.CAT16_M16[6] * scale[2],
                ApexColorConfig.CAT16_M16[7] * scale[2],
                ApexColorConfig.CAT16_M16[8] * scale[2]
            )
        )

        val actual = NeoGamutCat16Math.calculateCat16MatrixCiecam16(
            sourceX = ApexColorConfig.WhitePoint.D55.x,
            sourceY = ApexColorConfig.WhitePoint.D55.y,
            targetX = ApexColorConfig.WhitePoint.D65.x,
            targetY = ApexColorConfig.WhitePoint.D65.y,
            degreeOfAdaptation = d
        )

        expected.indices.forEach { index ->
            assertEquals(expected[index], actual[index], 0.000001f)
        }
    }
}
