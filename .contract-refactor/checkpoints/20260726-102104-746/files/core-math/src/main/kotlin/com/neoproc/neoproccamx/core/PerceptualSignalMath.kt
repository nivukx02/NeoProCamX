package com.neoproc.neoproccamx.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class PerceptualSignalSample(
    val normalizedNoiseFloor: Float,
    val flatRegionStdDev: Float,
    val flatRegionCoverage: Float,
    val perceptualSharpness: Float,
    val laplacianEnergy: Float,
    val realIsoNoiseFloor: Float
) {
    companion object {
        val ZERO = PerceptualSignalSample(
            normalizedNoiseFloor = 0f,
            flatRegionStdDev = 0f,
            flatRegionCoverage = 0f,
            perceptualSharpness = 0f,
            laplacianEnergy = 0f,
            realIsoNoiseFloor = 100f
        )
    }
}

object PerceptualSignalMath {

    fun analyzeLuma(luma: ByteArray, width: Int, height: Int): PerceptualSignalSample {
        if (width < 3 || height < 3 || luma.size < width * height) {
            return PerceptualSignalSample.ZERO
        }
        val stepX = max(1, width / 128)
        val stepY = max(1, height / 128)
        var flatSum = 0.0
        var flatSumSquares = 0.0
        var flatCount = 0
        var laplacianSum = 0.0
        var texturedCount = 0
        var totalSamples = 0

        for (y in 1 until height - 1 step stepY) {
            val row = y * width
            for (x in 1 until width - 1 step stepX) {
                val idx = row + x
                val center = luma[idx].toInt() and 0xFF
                val left = luma[idx - 1].toInt() and 0xFF
                val right = luma[idx + 1].toInt() and 0xFF
                val up = luma[idx - width].toInt() and 0xFF
                val down = luma[idx + width].toInt() and 0xFF
                val gradient = abs(right - left) + abs(down - up)
                val laplacian = abs((4 * center) - left - right - up - down)

                if (gradient <= 22) {
                    flatSum += center.toDouble()
                    flatSumSquares += center.toDouble() * center.toDouble()
                    flatCount++
                } else if (gradient >= 40) {
                    texturedCount++
                }

                laplacianSum += laplacian.toDouble()
                totalSamples++
            }
        }

        if (totalSamples == 0) {
            return PerceptualSignalSample.ZERO
        }

        val flatMean = if (flatCount > 0) flatSum / flatCount else 0.0
        val variance = if (flatCount > 0) {
            (flatSumSquares / flatCount) - (flatMean * flatMean)
        } else {
            0.0
        }
        val flatStdDev = sqrt(variance.coerceAtLeast(0.0)).toFloat()
        val flatCoverage = flatCount.toFloat() / totalSamples.toFloat()
        val laplacianEnergy = (laplacianSum / totalSamples.toDouble()).toFloat()
        val normalizedNoiseFloor =
            ((flatStdDev - 1.25f) / 17.5f).coerceIn(0f, 1f)
        val edgeOccupancy = texturedCount.toFloat() / totalSamples.toFloat()
        val perceptualSharpness =
            (
                (laplacianEnergy / 96f) * 0.72f +
                    edgeOccupancy * 0.38f -
                    normalizedNoiseFloor * 0.14f
                ).coerceIn(0f, 1f)
        val realIsoNoiseFloor =
            (
                100f +
                    normalizedNoiseFloor * 1500f +
                    (1f - flatCoverage).coerceIn(0f, 1f) * 140f +
                    perceptualSharpness * 60f
                ).coerceIn(100f, 3200f)

        return PerceptualSignalSample(
            normalizedNoiseFloor = normalizedNoiseFloor,
            flatRegionStdDev = flatStdDev,
            flatRegionCoverage = flatCoverage,
            perceptualSharpness = perceptualSharpness,
            laplacianEnergy = laplacianEnergy,
            realIsoNoiseFloor = realIsoNoiseFloor
        )
    }

    fun analyzeArgb(pixels: IntArray, width: Int, height: Int): PerceptualSignalSample {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return PerceptualSignalSample.ZERO
        }
        val luma = ByteArray(width * height)
        for (index in 0 until width * height) {
            val pixel = pixels[index]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val y = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            luma[index] = y.toByte()
        }
        return analyzeLuma(luma, width, height)
    }

    fun dynamicBlueNoiseTemporalDitherFloor(
        baseFloor: Float,
        signal: PerceptualSignalSample,
        motionScore: Float,
        hdrActive: Boolean
    ): Float {
        if (baseFloor <= 0f) return 0f
        val motionBoost = motionScore.coerceIn(0f, 1f) * 0.003f
        val noiseBoost = signal.normalizedNoiseFloor * 0.004f
        val flatBoost = signal.flatRegionCoverage.coerceIn(0f, 1f) * 0.002f
        val hdrBoost = if (hdrActive) 0.002f else 0f
        return (baseFloor + motionBoost + noiseBoost + flatBoost + hdrBoost)
            .coerceIn(0f, 0.018f)
    }
}
