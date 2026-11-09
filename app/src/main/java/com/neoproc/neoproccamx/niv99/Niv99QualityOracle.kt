package com.neoproc.neoproccamx.niv99

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class Niv99LabColor {
    var l: Double = 0.0
    var a: Double = 0.0
    var b: Double = 0.0
}

class Niv99WhiteBalanceMetrics {
    var neutralDeltaE2000: Double = 0.0
    var skinDeltaE2000: Double = 0.0
    var skinHueErrorDegrees: Double = 0.0
    var cctErrorKelvin: Double = 0.0
    var duvError: Double = 0.0
    var frameChromaticityVariance: Double = 0.0
    var convergenceFrames: Int = 0
    var lockMaximumDriftUv: Double = 0.0
    var introducedClippingFraction: Double = 0.0
}

class Niv99DenoiseMetrics {
    var lumaSnrImprovementDb: Double = 0.0
    var chromaSnrImprovementDb: Double = 0.0
    var psnrDb: Double = 0.0
    var ssim: Double = 0.0
    var mtf50Retention: Double = 0.0
    var fineTextureRetention: Double = 0.0
    var edgeOvershoot: Double = 0.0
    var haloWidthPixels: Double = 0.0
    var temporalFlicker: Double = 0.0
    var ghostingRate: Double = 0.0
    var residualLumaNoise: Double = 0.0
    var residualChromaNoise: Double = 0.0
    var skinTextureRetention: Double = 0.0
    var colorShiftDeltaE2000: Double = 0.0
    var tileBoundaryDiscontinuity: Double = 0.0
}

class Niv99QualityVerdict {
    var clippingPass: Boolean = false
    var mtfPass: Boolean = false
    var tileSeamPass: Boolean = false
    var historyIsolationPass: Boolean = false
    var timestampPass: Boolean = false
    var frameCountPass: Boolean = false
    var qualityImproved: Boolean = false
    var vendorSuperiorityProven: Boolean = false

    fun allStartingTargetsPass(): Boolean =
        clippingPass && mtfPass && tileSeamPass && historyIsolationPass &&
            timestampPass && frameCountPass && qualityImproved
}

/** Deterministic offline metrics. These methods evaluate evidence; they never tune production output. */
class Niv99QualityOracle(maxEdgeSamples: Int = MAX_EDGE_SAMPLES) {
    private val edgeDerivativeReference = DoubleArray(maxEdgeSamples)
    private val edgeDerivativeCandidate = DoubleArray(maxEdgeSamples)

    fun linearRgbToLab(red: Float, green: Float, blue: Float, output: Niv99LabColor) {
        val r = red.coerceAtLeast(0f).toDouble()
        val g = green.coerceAtLeast(0f).toDouble()
        val b = blue.coerceAtLeast(0f).toDouble()
        val x = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b
        xyzToLab(x, y, z, output)
    }

    fun xyzToLab(x: Double, y: Double, z: Double, output: Niv99LabColor) {
        val fx = labPivot(x / D65_X)
        val fy = labPivot(y / D65_Y)
        val fz = labPivot(z / D65_Z)
        output.l = 116.0 * fy - 16.0
        output.a = 500.0 * (fx - fy)
        output.b = 200.0 * (fy - fz)
    }

    /** CIEDE2000 implementation following Sharma, Wu, and Dalal (2005). */
    fun deltaE2000(first: Niv99LabColor, second: Niv99LabColor): Double {
        val l1 = first.l
        val a1 = first.a
        val b1 = first.b
        val l2 = second.l
        val a2 = second.a
        val b2 = second.b
        val c1 = sqrt(a1 * a1 + b1 * b1)
        val c2 = sqrt(a2 * a2 + b2 * b2)
        val meanC = (c1 + c2) * 0.5
        val meanC7 = meanC.pow(7.0)
        val g = 0.5 * (1.0 - sqrt(meanC7 / (meanC7 + 25.0.pow(7.0))))
        val adjustedA1 = (1.0 + g) * a1
        val adjustedA2 = (1.0 + g) * a2
        val adjustedC1 = sqrt(adjustedA1 * adjustedA1 + b1 * b1)
        val adjustedC2 = sqrt(adjustedA2 * adjustedA2 + b2 * b2)
        val hue1 = positiveDegrees(atan2(b1, adjustedA1) * RAD_TO_DEG)
        val hue2 = positiveDegrees(atan2(b2, adjustedA2) * RAD_TO_DEG)
        val deltaL = l2 - l1
        val deltaC = adjustedC2 - adjustedC1
        val deltaHueDegrees = when {
            adjustedC1 * adjustedC2 == 0.0 -> 0.0
            abs(hue2 - hue1) <= 180.0 -> hue2 - hue1
            hue2 <= hue1 -> hue2 - hue1 + 360.0
            else -> hue2 - hue1 - 360.0
        }
        val deltaH = 2.0 * sqrt(adjustedC1 * adjustedC2) *
            sin(degreesToRadians(deltaHueDegrees * 0.5))
        val meanL = (l1 + l2) * 0.5
        val meanAdjustedC = (adjustedC1 + adjustedC2) * 0.5
        val meanHue = when {
            adjustedC1 * adjustedC2 == 0.0 -> hue1 + hue2
            abs(hue1 - hue2) <= 180.0 -> (hue1 + hue2) * 0.5
            hue1 + hue2 < 360.0 -> (hue1 + hue2 + 360.0) * 0.5
            else -> (hue1 + hue2 - 360.0) * 0.5
        }
        val t = 1.0 - 0.17 * cos(degreesToRadians(meanHue - 30.0)) +
            0.24 * cos(degreesToRadians(2.0 * meanHue)) +
            0.32 * cos(degreesToRadians(3.0 * meanHue + 6.0)) -
            0.20 * cos(degreesToRadians(4.0 * meanHue - 63.0))
        val deltaTheta = 30.0 * exp(-((meanHue - 275.0) / 25.0).pow(2.0))
        val meanAdjustedC7 = meanAdjustedC.pow(7.0)
        val rc = 2.0 * sqrt(meanAdjustedC7 / (meanAdjustedC7 + 25.0.pow(7.0)))
        val lightnessTerm = (meanL - 50.0).pow(2.0)
        val sl = 1.0 + 0.015 * lightnessTerm / sqrt(20.0 + lightnessTerm)
        val sc = 1.0 + 0.045 * meanAdjustedC
        val sh = 1.0 + 0.015 * meanAdjustedC * t
        val rt = -sin(degreesToRadians(2.0 * deltaTheta)) * rc
        val normalizedL = deltaL / sl
        val normalizedC = deltaC / sc
        val normalizedH = deltaH / sh
        return sqrt(
            normalizedL * normalizedL + normalizedC * normalizedC + normalizedH * normalizedH +
                rt * normalizedC * normalizedH
        )
    }

    fun evaluateDenoise(
        referenceRgb: FloatArray,
        noisyRgb: FloatArray,
        processedRgb: FloatArray,
        width: Int,
        height: Int,
        rowStrideFloats: Int,
        tileWidth: Int,
        tileHeight: Int,
        output: Niv99DenoiseMetrics
    ): Niv99ValidationResult {
        if (!validRgbPlane(referenceRgb, width, height, rowStrideFloats) ||
            !validRgbPlane(noisyRgb, width, height, rowStrideFloats) ||
            !validRgbPlane(processedRgb, width, height, rowStrideFloats) ||
            tileWidth <= 0 || tileHeight <= 0
        ) {
            return Niv99ValidationResult.INVALID_PLANE
        }
        var noisyLumaError = 0.0
        var processedLumaError = 0.0
        var noisyChromaError = 0.0
        var processedChromaError = 0.0
        var referenceLumaEnergy = 0.0
        var referenceChromaEnergy = 0.0
        var referenceMean = 0.0
        var processedMean = 0.0
        var sampleCount = 0L
        for (y in 0 until height) {
            val row = y * rowStrideFloats
            for (x in 0 until width) {
                val index = row + x * 3
                val referenceLuma = luma(referenceRgb, index)
                val noisyLuma = luma(noisyRgb, index)
                val processedLuma = luma(processedRgb, index)
                val referenceCb = blueDifference(referenceRgb, index, referenceLuma)
                val referenceCr = redDifference(referenceRgb, index, referenceLuma)
                val noisyCb = blueDifference(noisyRgb, index, noisyLuma)
                val noisyCr = redDifference(noisyRgb, index, noisyLuma)
                val processedCb = blueDifference(processedRgb, index, processedLuma)
                val processedCr = redDifference(processedRgb, index, processedLuma)
                noisyLumaError += square(noisyLuma - referenceLuma)
                processedLumaError += square(processedLuma - referenceLuma)
                noisyChromaError += square(noisyCb - referenceCb) + square(noisyCr - referenceCr)
                processedChromaError += square(processedCb - referenceCb) +
                    square(processedCr - referenceCr)
                referenceLumaEnergy += square(referenceLuma)
                referenceChromaEnergy += square(referenceCb) + square(referenceCr)
                referenceMean += referenceLuma
                processedMean += processedLuma
                sampleCount += 1L
            }
        }
        val count = max(sampleCount, 1L).toDouble()
        val noisyLumaMse = noisyLumaError / count
        val processedLumaMse = processedLumaError / count
        val noisyChromaMse = noisyChromaError / (count * 2.0)
        val processedChromaMse = processedChromaError / (count * 2.0)
        output.lumaSnrImprovementDb = ratioDb(noisyLumaMse, processedLumaMse)
        output.chromaSnrImprovementDb = ratioDb(noisyChromaMse, processedChromaMse)
        output.psnrDb = if (processedLumaMse <= EPSILON) {
            120.0
        } else {
            10.0 * log10(1.0 / processedLumaMse)
        }
        output.residualLumaNoise = sqrt(processedLumaMse)
        output.residualChromaNoise = sqrt(processedChromaMse)
        output.ssim = globalSsim(
            referenceRgb,
            processedRgb,
            width,
            height,
            rowStrideFloats,
            referenceMean / count,
            processedMean / count
        )
        output.fineTextureRetention = textureRetention(
            referenceRgb,
            processedRgb,
            width,
            height,
            rowStrideFloats
        )
        output.edgeOvershoot = edgeOvershoot(
            referenceRgb,
            processedRgb,
            width,
            height,
            rowStrideFloats
        )
        output.haloWidthPixels = haloWidth(
            referenceRgb,
            processedRgb,
            width,
            height,
            rowStrideFloats
        )
        output.tileBoundaryDiscontinuity = tileBoundaryDiscontinuity(
            referenceRgb,
            processedRgb,
            width,
            height,
            rowStrideFloats,
            tileWidth,
            tileHeight
        )
        output.mtf50Retention = output.fineTextureRetention.coerceIn(0.0, 1.5)
        output.skinTextureRetention = output.fineTextureRetention
        output.colorShiftDeltaE2000 = meanColorDeltaE(
            referenceRgb,
            processedRgb,
            width,
            height,
            rowStrideFloats
        )
        // Keep the independently accumulated energies observable to static analysis and protect
        // zero-signal scenes from misleading positive SNR claims.
        if (referenceLumaEnergy <= EPSILON) output.lumaSnrImprovementDb = 0.0
        if (referenceChromaEnergy <= EPSILON) output.chromaSnrImprovementDb = 0.0
        return Niv99ValidationResult.VALID
    }

    fun mtf50FromEdgeSpread(edgeSpread: FloatArray, count: Int): Double {
        if (count < MIN_EDGE_SAMPLES || count > edgeDerivativeReference.size || count > edgeSpread.size) {
            return 0.0
        }
        val derivative = edgeDerivativeReference
        var derivativeSum = 0.0
        for (index in 0 until count - 1) {
            val value = edgeSpread[index + 1] - edgeSpread[index]
            derivative[index] = value.toDouble()
            derivativeSum += value
        }
        val derivativeCount = count - 1
        if (abs(derivativeSum) <= EPSILON) return 0.0
        var previousMtf = 1.0
        var previousFrequency = 0.0
        for (frequencyBin in 1..derivativeCount / 2) {
            var real = 0.0
            var imaginary = 0.0
            for (sample in 0 until derivativeCount) {
                val angle = -2.0 * PI * frequencyBin * sample / derivativeCount
                real += derivative[sample] * cos(angle)
                imaginary += derivative[sample] * sin(angle)
            }
            val mtf = sqrt(real * real + imaginary * imaginary) / abs(derivativeSum)
            val frequency = frequencyBin.toDouble() / derivativeCount
            if (mtf <= 0.5 && previousMtf > 0.5) {
                val span = previousMtf - mtf
                val interpolation = if (span > EPSILON) (previousMtf - 0.5) / span else 0.0
                return previousFrequency + (frequency - previousFrequency) * interpolation
            }
            previousMtf = mtf
            previousFrequency = frequency
        }
        return 0.5
    }

    fun mtf50Retention(referenceEdge: FloatArray, candidateEdge: FloatArray, count: Int): Double {
        val reference = mtf50FromEdgeSpread(referenceEdge, count)
        if (reference <= EPSILON) return 0.0
        // Preserve the first derivative before reusing the workspace on the candidate.
        for (index in 0 until min(count - 1, edgeDerivativeCandidate.size)) {
            edgeDerivativeCandidate[index] = edgeDerivativeReference[index]
        }
        val candidate = mtf50FromEdgeSpread(candidateEdge, count)
        return candidate / reference
    }

    fun temporalFlicker(
        frameMeanRed: FloatArray,
        frameMeanGreen: FloatArray,
        frameMeanBlue: FloatArray,
        frameCount: Int
    ): Double {
        if (frameCount < 2 || frameCount > frameMeanRed.size ||
            frameCount > frameMeanGreen.size || frameCount > frameMeanBlue.size
        ) {
            return 0.0
        }
        var sumSquared = 0.0
        var transitions = 0
        for (index in 1 until frameCount) {
            val previousGreen = max(frameMeanGreen[index - 1], 1e-6f)
            val currentGreen = max(frameMeanGreen[index], 1e-6f)
            val previousRed = frameMeanRed[index - 1] / previousGreen
            val previousBlue = frameMeanBlue[index - 1] / previousGreen
            val currentRed = frameMeanRed[index] / currentGreen
            val currentBlue = frameMeanBlue[index] / currentGreen
            val distance = Niv99Math.logChromaDistance(
                previousRed,
                previousBlue,
                currentRed,
                currentBlue
            )
            sumSquared += distance * distance
            transitions += 1
        }
        return sqrt(sumSquared / max(transitions, 1))
    }

    fun ghostingRate(
        reference: FloatArray,
        candidate: FloatArray,
        motionMask: FloatArray,
        count: Int,
        errorThreshold: Float
    ): Double {
        if (count <= 0 || count > reference.size || count > candidate.size || count > motionMask.size) {
            return 1.0
        }
        var motionSamples = 0
        var ghosted = 0
        for (index in 0 until count) {
            if (motionMask[index] < 0.5f) continue
            motionSamples += 1
            if (abs(candidate[index] - reference[index]) > errorThreshold) ghosted += 1
        }
        return if (motionSamples > 0) ghosted.toDouble() / motionSamples else 0.0
    }

    fun verdict(
        baseline: Niv99DenoiseMetrics,
        candidate: Niv99DenoiseMetrics,
        introducedClippingFraction: Double,
        historyIsolationPass: Boolean,
        timestampPass: Boolean,
        frameCountPass: Boolean,
        output: Niv99QualityVerdict
    ) {
        output.clippingPass = introducedClippingFraction <= CLIPPING_TOLERANCE
        output.mtfPass = candidate.mtf50Retention >= MIN_MTF50_RETENTION
        output.tileSeamPass = candidate.tileBoundaryDiscontinuity <= MAX_TILE_SEAM
        output.historyIsolationPass = historyIsolationPass
        output.timestampPass = timestampPass
        output.frameCountPass = frameCountPass
        output.qualityImproved =
            candidate.psnrDb > baseline.psnrDb &&
                candidate.ssim >= baseline.ssim &&
                candidate.residualChromaNoise < baseline.residualChromaNoise &&
                candidate.ghostingRate <= baseline.ghostingRate &&
                candidate.edgeOvershoot <= MAX_EDGE_OVERSHOOT
        output.vendorSuperiorityProven = false
    }

    private fun globalSsim(
        reference: FloatArray,
        candidate: FloatArray,
        width: Int,
        height: Int,
        stride: Int,
        referenceMean: Double,
        candidateMean: Double
    ): Double {
        var referenceVariance = 0.0
        var candidateVariance = 0.0
        var covariance = 0.0
        var count = 0L
        for (y in 0 until height) {
            val row = y * stride
            for (x in 0 until width) {
                val index = row + x * 3
                val referenceDelta = luma(reference, index) - referenceMean
                val candidateDelta = luma(candidate, index) - candidateMean
                referenceVariance += referenceDelta * referenceDelta
                candidateVariance += candidateDelta * candidateDelta
                covariance += referenceDelta * candidateDelta
                count += 1L
            }
        }
        val denominator = max(count - 1L, 1L).toDouble()
        referenceVariance /= denominator
        candidateVariance /= denominator
        covariance /= denominator
        val numerator = (2.0 * referenceMean * candidateMean + SSIM_C1) *
            (2.0 * covariance + SSIM_C2)
        val divisor = (referenceMean * referenceMean + candidateMean * candidateMean + SSIM_C1) *
            (referenceVariance + candidateVariance + SSIM_C2)
        return if (divisor > EPSILON) (numerator / divisor).coerceIn(-1.0, 1.0) else 1.0
    }

    private fun textureRetention(
        reference: FloatArray,
        candidate: FloatArray,
        width: Int,
        height: Int,
        stride: Int
    ): Double {
        if (width < 3 || height < 3) return 0.0
        var referenceEnergy = 0.0
        var candidateEnergy = 0.0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = y * stride + x * 3
                val left = center - 3
                val right = center + 3
                val up = center - stride
                val down = center + stride
                val referenceLaplacian = 4.0 * luma(reference, center) - luma(reference, left) -
                    luma(reference, right) - luma(reference, up) - luma(reference, down)
                val candidateLaplacian = 4.0 * luma(candidate, center) - luma(candidate, left) -
                    luma(candidate, right) - luma(candidate, up) - luma(candidate, down)
                referenceEnergy += referenceLaplacian * referenceLaplacian
                candidateEnergy += candidateLaplacian * candidateLaplacian
            }
        }
        return if (referenceEnergy > EPSILON) sqrt(candidateEnergy / referenceEnergy) else 1.0
    }

    private fun edgeOvershoot(
        reference: FloatArray,
        candidate: FloatArray,
        width: Int,
        height: Int,
        stride: Int
    ): Double {
        if (width < 3 || height < 3) return 0.0
        var maximumOvershoot = 0.0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * stride + x * 3
                val referenceValue = luma(reference, index)
                val candidateValue = luma(candidate, index)
                val localMin = min(
                    referenceValue,
                    min(luma(reference, index - 3), luma(reference, index + 3))
                )
                val localMax = max(
                    referenceValue,
                    max(luma(reference, index - 3), luma(reference, index + 3))
                )
                val overshoot = max(candidateValue - localMax, localMin - candidateValue)
                maximumOvershoot = max(maximumOvershoot, overshoot)
            }
        }
        return maximumOvershoot
    }

    private fun haloWidth(
        reference: FloatArray,
        candidate: FloatArray,
        width: Int,
        height: Int,
        stride: Int
    ): Double {
        if (width < 5 || height < 1) return 0.0
        var widest = 0
        for (y in 0 until height) {
            var run = 0
            for (x in 0 until width) {
                val index = y * stride + x * 3
                val excess = abs(luma(candidate, index) - luma(reference, index))
                if (excess > HALO_ERROR_THRESHOLD) {
                    run += 1
                    widest = max(widest, run)
                } else {
                    run = 0
                }
            }
        }
        return widest.toDouble()
    }

    private fun tileBoundaryDiscontinuity(
        reference: FloatArray,
        candidate: FloatArray,
        width: Int,
        height: Int,
        stride: Int,
        tileWidth: Int,
        tileHeight: Int
    ): Double {
        var boundaryGradient = 0.0
        var interiorGradient = 0.0
        var boundaryCount = 0L
        var interiorCount = 0L
        for (y in 0 until height) {
            for (x in 1 until width) {
                val index = y * stride + x * 3
                val currentError = luma(candidate, index) - luma(reference, index)
                val previousError = luma(candidate, index - 3) - luma(reference, index - 3)
                val gradient = abs(currentError - previousError)
                if (x % tileWidth == 0) {
                    boundaryGradient += gradient
                    boundaryCount += 1L
                } else {
                    interiorGradient += gradient
                    interiorCount += 1L
                }
            }
        }
        for (y in 1 until height) {
            for (x in 0 until width) {
                val index = y * stride + x * 3
                val currentError = luma(candidate, index) - luma(reference, index)
                val previousError = luma(candidate, index - stride) - luma(reference, index - stride)
                val gradient = abs(currentError - previousError)
                if (y % tileHeight == 0) {
                    boundaryGradient += gradient
                    boundaryCount += 1L
                } else {
                    interiorGradient += gradient
                    interiorCount += 1L
                }
            }
        }
        val boundaryMean = boundaryGradient / max(boundaryCount, 1L)
        val interiorMean = interiorGradient / max(interiorCount, 1L)
        return max(boundaryMean - interiorMean, 0.0)
    }

    private fun meanColorDeltaE(
        reference: FloatArray,
        candidate: FloatArray,
        width: Int,
        height: Int,
        stride: Int
    ): Double {
        val referenceLab = Niv99LabColor()
        val candidateLab = Niv99LabColor()
        var sum = 0.0
        var count = 0L
        val stepX = max(width / 32, 1)
        val stepY = max(height / 32, 1)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * stride + x * 3
                linearRgbToLab(reference[index], reference[index + 1], reference[index + 2], referenceLab)
                linearRgbToLab(candidate[index], candidate[index + 1], candidate[index + 2], candidateLab)
                sum += deltaE2000(referenceLab, candidateLab)
                count += 1L
                x += stepX
            }
            y += stepY
        }
        return sum / max(count, 1L)
    }

    private fun validRgbPlane(samples: FloatArray, width: Int, height: Int, stride: Int): Boolean {
        if (width <= 0 || height <= 0 || stride < width * 3) return false
        return (height - 1L) * stride + width.toLong() * 3L <= samples.size
    }

    private fun luma(samples: FloatArray, index: Int): Double =
        samples[index] * 0.2126 + samples[index + 1] * 0.7152 + samples[index + 2] * 0.0722

    private fun blueDifference(samples: FloatArray, index: Int, luma: Double): Double =
        (samples[index + 2] - luma) * 0.5389

    private fun redDifference(samples: FloatArray, index: Int, luma: Double): Double =
        (samples[index] - luma) * 0.6350

    private fun labPivot(value: Double): Double = if (value > LAB_EPSILON) {
        value.pow(1.0 / 3.0)
    } else {
        (LAB_KAPPA * value + 16.0) / 116.0
    }

    private fun positiveDegrees(value: Double): Double = if (value < 0.0) value + 360.0 else value

    private fun degreesToRadians(value: Double): Double = value / RAD_TO_DEG

    private fun ratioDb(numerator: Double, denominator: Double): Double = when {
        numerator <= EPSILON -> 0.0
        denominator <= EPSILON -> 120.0
        else -> 10.0 * log10(numerator / denominator)
    }

    private fun log10(value: Double): Double = ln(value) / LN_10

    private fun square(value: Double): Double = value * value

    companion object {
        private const val MAX_EDGE_SAMPLES = 512
        private const val MIN_EDGE_SAMPLES = 16
        private const val D65_X = 0.95047
        private const val D65_Y = 1.0
        private const val D65_Z = 1.08883
        private const val LAB_EPSILON = 216.0 / 24_389.0
        private const val LAB_KAPPA = 24_389.0 / 27.0
        private const val RAD_TO_DEG = 180.0 / PI
        private const val LN_10 = 2.302585092994046
        private const val EPSILON = 1e-12
        private const val SSIM_C1 = 0.0001
        private const val SSIM_C2 = 0.0009
        private const val HALO_ERROR_THRESHOLD = 0.025
        private const val CLIPPING_TOLERANCE = 0.0005
        private const val MIN_MTF50_RETENTION = 0.90
        private const val MAX_TILE_SEAM = 0.005
        private const val MAX_EDGE_OVERSHOOT = 0.04
    }
}
