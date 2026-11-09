package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.quality.measure.ImageQualityMetrics
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CPU golden for the bounded reconstruction block embedded in [PreviewGlCompositor]. It is not
 * physical-camera proof; it guards the mathematical properties required before device validation:
 * a blurred edge is tightened, no new local extrema/halos are created, flat noise is not amplified,
 * reconstruction is luma-only, and the same rule applies across the complete frame.
 */
class Niv99PreviewFullFrameDetailGoldenTest {
    @Test
    fun `full frame reconstruction raises slanted edge MTF without overshoot`() {
        val width = 96
        val height = 80
        val sigma = 1.45
        val source = grayImage(width, height) { x, y ->
            val edge = width * 0.5 + y * 0.071
            val spread = 0.5 * (1.0 + erf((x - edge) / (sigma * sqrt(2.0))))
            (0.20 + 0.60 * spread).toFloat()
        }
        val restored = enhance(source, width, height, spatialDenoise = 0.18f,
            sharpeningCeiling = 0.72f, confidence = 0.92f)
        val sourceLuma = lumaPlane(source, width, height)
        val restoredLuma = lumaPlane(restored, width, height)
        val sourceMtf50 = ImageQualityMetrics.slantedEdgeMtf50(sourceLuma, width, height)
        val restoredMtf50 = ImageQualityMetrics.slantedEdgeMtf50(restoredLuma, width, height)

        assertTrue("restoration must measurably raise MTF50 ($sourceMtf50 -> $restoredMtf50)",
            restoredMtf50 > sourceMtf50 * 1.01)
        assertTrue("strict local envelope must prevent bright halos",
            restoredLuma.maxOrNull()!! <= 0.800001f)
        assertTrue("strict local envelope must prevent dark halos",
            restoredLuma.minOrNull()!! >= 0.199999f)
    }

    @Test
    fun `noise evidence gate cleans flat grain instead of sharpening it`() {
        val width = 72
        val height = 56
        val random = Random(9917L)
        val noisy = grayImage(width, height) { _, _ ->
            (0.50 + random.nextGaussian() * 0.014).toFloat()
        }
        val restored = enhance(noisy, width, height, spatialDenoise = 0.52f,
            sharpeningCeiling = 0.72f, confidence = 0.82f)

        val inputDeviation = standardDeviation(lumaPlane(noisy, width, height))
        val outputDeviation = standardDeviation(lumaPlane(restored, width, height))
        assertTrue("flat-field noise must fall ($inputDeviation -> $outputDeviation)",
            outputDeviation < inputDeviation)
    }

    @Test
    fun `luma reconstruction preserves chroma differences`() {
        val width = 64
        val height = 48
        val source = rgbImage(width, height) { x, y ->
            val wave = 0.035f * sin(x * 0.73 + y * 0.19).toFloat()
            floatArrayOf(0.52f + wave, 0.39f + wave, 0.27f + wave)
        }
        val restored = enhance(source, width, height, spatialDenoise = 0f,
            sharpeningCeiling = 0.72f, confidence = 0.95f)

        for (index in source.indices step 3) {
            val sourceRedGreen = source[index] - source[index + 1]
            val sourceBlueGreen = source[index + 2] - source[index + 1]
            val outputRedGreen = restored[index] - restored[index + 1]
            val outputBlueGreen = restored[index + 2] - restored[index + 1]
            assertTrue(abs(sourceRedGreen - outputRedGreen) <= 2e-6f)
            assertTrue(abs(sourceBlueGreen - outputBlueGreen) <= 2e-6f)
        }
    }

    @Test
    fun `detail rule changes supported texture in every frame quadrant`() {
        val width = 80
        val height = 64
        val source = grayImage(width, height) { x, y ->
            0.48f + 0.055f * sin(x * 1.31 + y * 0.37).toFloat()
        }
        val restored = enhance(source, width, height, spatialDenoise = 0.10f,
            sharpeningCeiling = 0.72f, confidence = 0.95f)

        val changedByQuadrant = IntArray(4)
        for (y in 2 until height - 2) for (x in 2 until width - 2) {
            val index = (y * width + x) * 3
            if (abs(restored[index] - source[index]) > 1e-5f) {
                changedByQuadrant[(if (y >= height / 2) 2 else 0) + if (x >= width / 2) 1 else 0]++
            }
        }
        changedByQuadrant.forEachIndexed { quadrant, changed ->
            assertTrue("quadrant $quadrant must receive evidence-gated detail work", changed > 100)
        }
    }

    /** Shader-faithful RGB reference for the nine already-fetched samples in enhance(). */
    private fun enhance(
        source: FloatArray,
        width: Int,
        height: Int,
        spatialDenoise: Float,
        sharpeningCeiling: Float,
        confidence: Float
    ): FloatArray {
        val output = FloatArray(source.size)
        val weights = floatArrayOf(4f, 2f, 2f, 2f, 2f, 1f, 1f, 1f, 1f)
        val offsets = arrayOf(
            0 to 0, 0 to 1, 0 to -1, 1 to 0, -1 to 0,
            1 to 1, -1 to 1, 1 to -1, -1 to -1
        )
        for (y in 0 until height) for (x in 0 until width) {
            val samples = Array(9) { sample(source, width, height, x + offsets[it].first, y + offsets[it].second) }
            val luma = FloatArray(9) { luma(samples[it]) }
            val center = samples[0]
            val centerLuma = luma[0]
            val bilateral = FloatArray(3)
            val gaussian = FloatArray(3)
            var bilateralWeight = 0f
            var gaussianWeight = 0f
            for (sampleIndex in samples.indices) {
                val difference = luma[sampleIndex] - centerLuma
                val rangeWeight = if (sampleIndex == 0) 1f else
                    exp((-difference * difference / (2f * 0.05f * 0.05f)).toDouble()).toFloat()
                val weightedRange = weights[sampleIndex] * rangeWeight
                for (channel in 0..2) {
                    bilateral[channel] += samples[sampleIndex][channel] * weightedRange
                    gaussian[channel] += samples[sampleIndex][channel] * weights[sampleIndex]
                }
                bilateralWeight += weightedRange
                gaussianWeight += weights[sampleIndex]
            }
            for (channel in 0..2) {
                bilateral[channel] /= bilateralWeight
                gaussian[channel] /= gaussianWeight
            }

            val gradientX = 0.5f * (luma[3] - luma[4])
            val gradientY = 0.5f * (luma[1] - luma[2])
            val edgeEnergy = sqrt(gradientX * gradientX + gradientY * gradientY)
            val laplacian = abs(centerLuma - 0.25f * (luma[1] + luma[2] + luma[3] + luma[4]))
            val edgeProtect = smoothstep(0.012f, 0.095f, edgeEnergy + laplacian)
            val diagonalSpread = max(
                rgbDistance(samples[5], samples[8]),
                rgbDistance(samples[6], samples[7])
            )
            val fineTexture = smoothstep(0.010f, 0.090f, laplacian + 0.25f * diagonalSpread) *
                (1f - smoothstep(0.10f, 0.28f, edgeEnergy))
            val lumaPolicy = spatialDenoise.coerceIn(0f, 0.62f) * confidence.coerceIn(0f, 1f)
            val lumaBlend = lumaPolicy * (1f - 0.90f * edgeProtect) * (1f - 0.70f * fineTexture)
            val bilateralLuma = luma(bilateral)
            val filteredLuma = mix(centerLuma, bilateralLuma, lumaBlend.coerceIn(0f, 0.70f))
            val denoised = FloatArray(3) { channel ->
                center[channel] + (filteredLuma - centerLuma)
            }

            val axialMean = 0.25f * (luma[1] + luma[2] + luma[3] + luma[4])
            val frequencyResidual = mix(centerLuma - luma(gaussian), centerLuma - axialMean, 0.38f)
            val detailLuma = abs(frequencyResidual)
            val noiseFloor = 0.0025f + 0.0100f * lumaPolicy * (1f - 0.45f * confidence)
            val noiseConfidence = smoothstep(noiseFloor, noiseFloor * 3.8f + 1e-6f, detailLuma)
            val measuredStructure = noiseConfidence * max(edgeProtect, fineTexture)
            val reconstructionPolicy = 0.24f + 0.66f * sharpeningCeiling.coerceIn(0f, 1f)
            val haloGuard = 1f - smoothstep(0.055f, 0.18f, detailLuma)
            val localMin = luma.minOrNull()!!
            val localMax = luma.maxOrNull()!!
            val localRange = max(localMax - localMin, 0f)
            val localContrastGuard = 1f - smoothstep(0.18f, 0.62f, localRange)
            val reconstructionAmount = reconstructionPolicy * confidence * measuredStructure *
                haloGuard * mix(0.72f, 1f, localContrastGuard)
            val policyBound = 0.025f + 0.045f * sharpeningCeiling.coerceIn(0f, 1f)
            val reconstructionBound = min(policyBound, 0.22f * localRange + 0.002f)
            val lumaBoost = (frequencyResidual * reconstructionAmount)
                .coerceIn(-reconstructionBound, reconstructionBound)
            val reconstructedLuma = (filteredLuma + lumaBoost).coerceIn(localMin, localMax)
            val offset = (y * width + x) * 3
            for (channel in 0..2) {
                output[offset + channel] =
                    (denoised[channel] + reconstructedLuma - filteredLuma).coerceIn(0f, 1f)
            }
        }
        return output
    }

    private fun sample(source: FloatArray, width: Int, height: Int, x: Int, y: Int): FloatArray {
        val boundedX = x.coerceIn(0, width - 1)
        val boundedY = y.coerceIn(0, height - 1)
        val offset = (boundedY * width + boundedX) * 3
        return floatArrayOf(source[offset], source[offset + 1], source[offset + 2])
    }

    private fun luma(rgb: FloatArray): Float =
        rgb[0] * 0.2126f + rgb[1] * 0.7152f + rgb[2] * 0.0722f

    private fun lumaPlane(rgb: FloatArray, width: Int, height: Int): FloatArray =
        FloatArray(width * height) { index ->
            val offset = index * 3
            rgb[offset] * 0.2126f + rgb[offset + 1] * 0.7152f + rgb[offset + 2] * 0.0722f
        }

    private fun rgbDistance(first: FloatArray, second: FloatArray): Float = sqrt(
        (first[0] - second[0]) * (first[0] - second[0]) +
            (first[1] - second[1]) * (first[1] - second[1]) +
            (first[2] - second[2]) * (first[2] - second[2])
    )

    private fun grayImage(width: Int, height: Int, value: (Int, Int) -> Float): FloatArray =
        rgbImage(width, height) { x, y -> FloatArray(3) { value(x, y) } }

    private fun rgbImage(
        width: Int,
        height: Int,
        value: (Int, Int) -> FloatArray
    ): FloatArray = FloatArray(width * height * 3).also { output ->
        for (y in 0 until height) for (x in 0 until width) {
            val rgb = value(x, y)
            val offset = (y * width + x) * 3
            output[offset] = rgb[0].coerceIn(0f, 1f)
            output[offset + 1] = rgb[1].coerceIn(0f, 1f)
            output[offset + 2] = rgb[2].coerceIn(0f, 1f)
        }
    }

    private fun standardDeviation(values: FloatArray): Double {
        val mean = values.sumOf(Float::toDouble) / values.size
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun smoothstep(lower: Float, upper: Float, value: Float): Float {
        val t = ((value - lower) / (upper - lower)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun mix(first: Float, second: Float, amount: Float): Float =
        first + (second - first) * amount.coerceIn(0f, 1f)

    /** Abramowitz-Stegun 7.1.26, sufficient for deterministic synthetic edge generation. */
    private fun erf(value: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(value))
        val approximation = 1.0 -
            (((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t +
                0.254829592) * t * exp(-value * value))
        return sign(value) * approximation
    }
}
