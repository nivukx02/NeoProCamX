package com.neoproc.neoproccamx.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoWideGamut2ColorimetryTest {

    @Test
    fun neoWideGamutMatrixResolvesExactlyToD55White() {
        val white = multiply(NeoGamutCat16Math.NEO_TO_XYZ, floatArrayOf(1f, 1f, 1f))

        assertEquals(NeoWideGamut2.D55_XYZ[0], white[0], 0.000001f)
        assertEquals(NeoWideGamut2.D55_XYZ[1], white[1], 0.000001f)
        assertEquals(NeoWideGamut2.D55_XYZ[2], white[2], 0.000001f)
    }

    @Test
    fun neoWideGamutInverseIsAnalyticAndRoundTripsToIdentity() {
        val product = multiply3x3(NeoGamutCat16Math.XYZ_TO_NEO, NeoGamutCat16Math.NEO_TO_XYZ)
        val frobenius = product.indices.sumOf { index ->
            val expected = if (index / 3 == index % 3) 1.0 else 0.0
            val diff = product[index].toDouble() - expected
            diff * diff
        }.let(::sqrt)

        assertTrue("Frobenius inverse residual was $frobenius", frobenius < 0.000001)
    }

    @Test
    fun primariesRoundTripThroughXyzWithoutChromaticityDrift() {
        val primaries = listOf(
            floatArrayOf(1f, 0f, 0f) to (0.7347f to 0.2653f),
            floatArrayOf(0f, 1f, 0f) to (0.1152f to 0.8260f),
            floatArrayOf(0f, 0f, 1f) to (0.0860f to 0.0110f)
        )

        primaries.forEach { (rgb, expectedXy) ->
            val xyz = multiply(NeoGamutCat16Math.NEO_TO_XYZ, rgb)
            val roundTrip = multiply(NeoGamutCat16Math.XYZ_TO_NEO, xyz)
            val xy = xy(xyz)

            assertEquals(expectedXy.first, xy.first, 0.000001f)
            assertEquals(expectedXy.second, xy.second, 0.000001f)
            assertEquals(rgb[0], roundTrip[0], 0.000001f)
            assertEquals(rgb[1], roundTrip[1], 0.000001f)
            assertEquals(rgb[2], roundTrip[2], 0.000001f)
        }
    }

    @Test
    fun cat16AndBradfordMapD55WhiteToD65WhiteExactly() {
        val d55 = NeoWideGamut2.D55_XYZ
        val d65 = NeoWideGamut2.D65_XYZ
        val cat16 = NeoGamutCat16Math.calculateCat16Matrix(
            NeoGamutCat16Math.D55_X,
            NeoGamutCat16Math.D55_Y,
            NeoGamutCat16Math.D65_X,
            NeoGamutCat16Math.D65_Y
        )
        val bradford = NeoGamutCat16Math.calculateBradfordMatrix(
            NeoGamutCat16Math.D55_X,
            NeoGamutCat16Math.D55_Y,
            NeoGamutCat16Math.D65_X,
            NeoGamutCat16Math.D65_Y
        )

        assertWhiteMaps(cat16, d55, d65)
        assertWhiteMaps(bradford, d55, d65)
    }

    @Test
    fun neoWideGamutAcesAp1InterchangeRoundTripsWithoutDrift() {
        val samples = listOf(
            AcesRgb(0.18f, 0.18f, 0.18f),
            AcesRgb(0.80f, 0.22f, 0.08f),
            AcesRgb(0.05f, 0.84f, 0.22f),
            AcesRgb(-0.04f, 0.34f, 0.72f)
        )

        samples.forEach { sample ->
            val ap1 = AcesColorEngine.neoWideGamutToAcesAp1(sample)
            val roundTrip = AcesColorEngine.acesAp1ToNeoWideGamut(ap1)

            assertEquals(sample.r, roundTrip.r, 0.00001f)
            assertEquals(sample.g, roundTrip.g, 0.00001f)
            assertEquals(sample.b, roundTrip.b, 0.00001f)
        }
    }

    @Test
    fun perceptualGamutCompressionPreservesInGamutAndLocksHueForOutOfGamut() {
        val inGamut = AcesRgb(0.42f, 0.42f, 0.42f)
        val unchanged = AcesColorEngine.compressNeoWideGamutToTarget(
            rgb = inGamut,
            target = NeoWideGamut2.TargetGamut.REC2020
        )
        assertEquals(inGamut.r, unchanged.r, 0.000001f)
        assertEquals(inGamut.g, unchanged.g, 0.000001f)
        assertEquals(inGamut.b, unchanged.b, 0.000001f)

        val outOfGamut = AcesRgb(0.10f, 1.48f, -0.20f)
        val compressed = AcesColorEngine.compressNeoWideGamutToTarget(
            rgb = outOfGamut,
            target = NeoWideGamut2.TargetGamut.REC709
        )
        val uncompressedTarget = AcesColorEngine.compressNeoWideGamutToTarget(
            rgb = outOfGamut,
            target = NeoWideGamut2.TargetGamut.REC709,
            compress = false
        )
        val sourceHue = oklabHue(uncompressedTarget)
        val compressedHue = oklabHue(compressed)
        val hueDelta = abs(sourceHue - compressedHue).let { minOf(it, 360f - it) }

        assertTrue(compressed.r in 0f..1f)
        assertTrue(compressed.g in 0f..1f)
        assertTrue(compressed.b in 0f..1f)
        assertTrue("Hue drifted by $hueDelta degrees", hueDelta < 0.5f)
        assertTrue(chroma(compressed) < chroma(uncompressedTarget))
    }

    private fun assertWhiteMaps(matrix: FloatArray, source: FloatArray, target: FloatArray) {
        val mapped = multiply(matrix, source)
        assertEquals(target[0], mapped[0], 0.000001f)
        assertEquals(target[1], mapped[1], 0.000001f)
        assertEquals(target[2], mapped[2], 0.000001f)
    }

    private fun multiply(matrix: FloatArray, vector: FloatArray): FloatArray =
        floatArrayOf(
            matrix[0] * vector[0] + matrix[1] * vector[1] + matrix[2] * vector[2],
            matrix[3] * vector[0] + matrix[4] * vector[1] + matrix[5] * vector[2],
            matrix[6] * vector[0] + matrix[7] * vector[1] + matrix[8] * vector[2]
        )

    private fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray =
        FloatArray(9) { index ->
            val row = index / 3
            val col = index % 3
            a[row * 3] * b[col] +
                a[row * 3 + 1] * b[col + 3] +
                a[row * 3 + 2] * b[col + 6]
        }

    private fun xy(xyz: FloatArray): Pair<Float, Float> {
        val sum = xyz[0] + xyz[1] + xyz[2]
        return xyz[0] / sum to xyz[1] / sum
    }

    private fun oklabHue(rgb: AcesRgb): Float {
        val lab = NeoWideGamut2.oklabFromLinearRgb(rgb)
        val hue = Math.toDegrees(atan2(lab.b.toDouble(), lab.a.toDouble())).toFloat()
        return if (hue < 0f) hue + 360f else hue
    }

    private fun chroma(rgb: AcesRgb): Float {
        val lab = NeoWideGamut2.oklabFromLinearRgb(rgb)
        return sqrt((lab.a * lab.a + lab.b * lab.b).toDouble()).toFloat()
    }
}
