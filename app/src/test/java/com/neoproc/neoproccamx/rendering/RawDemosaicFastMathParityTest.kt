package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/** Numeric lock for the algebra moved from every RAW pixel into the per-frame UBO builder. */
class RawDemosaicFastMathParityTest {

    @Test
    fun precomputedCfaCalibrationMatchesLegacyOrderingWithinFp32Rounding() {
        val random = Random(0x4E4452)
        repeat(20_000) {
            val white = random.nextInt(1_023, 16_384).toFloat()
            val black = random.nextDouble(0.0, (white - 1f).toDouble()).toFloat()
            val wb = random.nextDouble(0.20, 8.0).toFloat()
            val raw = random.nextDouble(0.0, white.toDouble()).toFloat()
            val denominator = maxOf(white - black, 1f)
            val legacy = (((raw - black) / denominator).coerceIn(0f, 1f)) * wb
            val scale = wb / denominator
            val optimized = ((raw - black) * scale).coerceIn(0f, maxOf(wb, 0f))
            assertTrue(
                "CFA calibration delta=${abs(legacy - optimized)}",
                abs(legacy - optimized) <= 2e-6f,
            )
        }
    }

    @Test
    fun precomposedSensorToAcesMatrixMatchesTwoLegFp32Path() {
        val random = Random(0x41434553)
        repeat(10_000) {
            val sensorToXyz = FloatArray(9) { random.nextDouble(-0.25, 1.25).toFloat() }
            val xyzToAces = FloatArray(9) { random.nextDouble(-0.25, 1.25).toFloat() }
            val sensor = FloatArray(3) { random.nextDouble(0.0, 16.0).toFloat() }
            val twoLeg = multiplyVector(xyzToAces, multiplyVector(sensorToXyz, sensor))
            val combined = multiply(xyzToAces, sensorToXyz)
            val oneLeg = multiplyVector(combined, sensor)
            for (channel in 0..2) {
                assertTrue(
                    "matrix channel=$channel delta=${abs(twoLeg[channel] - oneLeg[channel])}",
                    abs(twoLeg[channel] - oneLeg[channel]) <= 3e-5f,
                )
            }
        }
    }

    @Test
    fun nativeAndShaderUseThePrecomputedFastFieldsWithLegacyInputsRetained() {
        val shader = File("src/main/cpp/shaders/vulkan/demosaic_vulkan.comp").readText()
        val native = File("src/main/cpp/vulkan_bridge.cpp").readText()
        assertTrue(shader.contains("vec4 calibrationScaleRggb"))
        assertTrue(shader.contains("vec4 calibrationOffsetRggb"))
        assertTrue(shader.contains("mat3 M_SENSOR_TO_ACESCG_FAST"))
        assertTrue(shader.contains("(raw - blackForChannel(channel)) * uniforms.calibrationScaleRggb[channel]"))
        assertTrue(shader.contains("uniforms.M_SENSOR_TO_ACESCG_FAST * sensorRgb"))
        assertTrue(native.contains("multiplyRawDemosaicMatrices"))
        assertTrue(
            native.countOccurrences(
                "ubo.calibrationScaleRggb[i] = whiteBalanceRggb[i] / denominator",
            ) >= 2,
        )
        assertTrue(
            native.countOccurrences(
                "packRawDemosaicMatrixColumnMajor(sensorToAcescgFast",
            ) >= 2,
        )
        // Original diagnostic/calibration inputs stay in the ABI; this optimization does not weaken it.
        assertTrue(shader.contains("mat3 M_SENSOR_TO_XYZ"))
        assertTrue(shader.contains("mat3 M_XYZ_TO_ACESCG"))
    }

    private fun multiply(lhs: FloatArray, rhs: FloatArray): FloatArray =
        FloatArray(9) { index ->
            val row = index / 3
            val column = index % 3
            var value = 0f
            for (inner in 0..2) value += lhs[row * 3 + inner] * rhs[inner * 3 + column]
            value
        }

    private fun multiplyVector(matrix: FloatArray, vector: FloatArray): FloatArray = floatArrayOf(
        matrix[0] * vector[0] + matrix[1] * vector[1] + matrix[2] * vector[2],
        matrix[3] * vector[0] + matrix[4] * vector[1] + matrix[5] * vector[2],
        matrix[6] * vector[0] + matrix[7] * vector[1] + matrix[8] * vector[2],
    )

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var offset = 0
        while (true) {
            val found = indexOf(needle, offset)
            if (found < 0) return count
            count += 1
            offset = found + needle.length
        }
    }
}
