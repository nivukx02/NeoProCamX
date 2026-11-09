package com.neoproc.neoproccamx.niv99

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Reusable per-frame Poisson-Gaussian model in normalized sensor-linear units. */
class Niv99NoiseModelSnapshot {
    val identity: Niv99FrameIdentity = Niv99FrameIdentity()
    val shotCoefficient: FloatArray = FloatArray(Niv99SensorCalibration.CHANNEL_COUNT)
    val readCoefficient: FloatArray = FloatArray(Niv99SensorCalibration.CHANNEL_COUNT)
    val blackLevel: FloatArray = FloatArray(Niv99SensorCalibration.CHANNEL_COUNT)
    var whiteLevel: Float = 1f
    var iso: Int = 100
    var temperatureCelsius: Float = 25f
    var measuredProfile: Boolean = false
    var coefficientDomain: Niv99NoiseCoefficientDomain =
        Niv99NoiseCoefficientDomain.NORMALIZED_SENSOR_SIGNAL
    var calibrationSource: Niv99NoiseCalibrationSource = Niv99NoiseCalibrationSource.UNAVAILABLE
    var calibrationRevision: Long = -1L

    fun variance(channel: Int, normalizedSignal: Float): Float {
        val index = channel.coerceIn(0, Niv99SensorCalibration.CHANNEL_COUNT - 1)
        val signal = max(normalizedSignal - blackLevel[index], 0f)
        return max(shotCoefficient[index] * signal + readCoefficient[index], MIN_VARIANCE)
    }

    fun standardDeviation(channel: Int, normalizedSignal: Float): Float =
        sqrt(variance(channel, normalizedSignal))

    fun meanVariance(red: Float, green: Float, blue: Float): Float =
        (
            variance(Niv99SensorCalibration.CHANNEL_RED, red) +
                variance(Niv99SensorCalibration.CHANNEL_GREEN_EVEN, green) +
                variance(Niv99SensorCalibration.CHANNEL_GREEN_ODD, green) +
                variance(Niv99SensorCalibration.CHANNEL_BLUE, blue)
            ) * 0.25f

    fun copyFrom(other: Niv99NoiseModelSnapshot): Niv99NoiseModelSnapshot {
        identity.copyFrom(other.identity)
        other.shotCoefficient.copyInto(shotCoefficient)
        other.readCoefficient.copyInto(readCoefficient)
        other.blackLevel.copyInto(blackLevel)
        whiteLevel = other.whiteLevel
        iso = other.iso
        temperatureCelsius = other.temperatureCelsius
        measuredProfile = other.measuredProfile
        coefficientDomain = other.coefficientDomain
        calibrationSource = other.calibrationSource
        calibrationRevision = other.calibrationRevision
        return this
    }

    companion object {
        private const val MIN_VARIANCE = 1e-12f
    }
}

/** Caller-owned optional Camera2 noise coefficients ordered by CFA channel. */
class Niv99MeasuredNoiseProfile {
    val shotCoefficient: FloatArray = FloatArray(Niv99SensorCalibration.CHANNEL_COUNT)
    val readCoefficient: FloatArray = FloatArray(Niv99SensorCalibration.CHANNEL_COUNT)
    var valid: Boolean = false
    var coefficientDomain: Niv99NoiseCoefficientDomain =
        Niv99NoiseCoefficientDomain.NORMALIZED_SENSOR_SIGNAL

    fun validate(): Boolean {
        if (!valid) return false
        var positiveEvidence = false
        for (channel in 0 until Niv99SensorCalibration.CHANNEL_COUNT) {
            val shot = shotCoefficient[channel]
            val read = readCoefficient[channel]
            if (!shot.isFinite() || !read.isFinite() || shot < 0f || read < 0f) return false
            positiveEvidence = positiveEvidence || shot > 0f || read > 0f
        }
        return positiveEvidence
    }

    fun copyFrom(other: Niv99MeasuredNoiseProfile): Niv99MeasuredNoiseProfile {
        other.shotCoefficient.copyInto(shotCoefficient)
        other.readCoefficient.copyInto(readCoefficient)
        coefficientDomain = other.coefficientDomain
        valid = other.valid
        return this
    }
}

/**
 * Calibrated heteroscedastic model. Measured same-frame Camera2 coefficients win; otherwise sensor
 * calibration is scaled by analog gain and temperature without inventing a noiseless fallback.
 */
class Niv99NoiseModel {
    fun resolve(
        context: Niv99FrameContext,
        calibration: Niv99SensorCalibration,
        measured: Niv99MeasuredNoiseProfile?,
        output: Niv99NoiseModelSnapshot
    ): Niv99ValidationResult {
        if (context.validate() != Niv99ValidationResult.VALID) return context.validate()
        if (calibration.validate() != Niv99ValidationResult.VALID ||
            calibration.cameraId != context.identity.cameraId
        ) {
            return Niv99ValidationResult.INVALID_CALIBRATION
        }
        output.identity.copyFrom(context.identity)
        output.iso = context.iso
        output.temperatureCelsius = if (context.sensorTemperatureCelsius.isFinite()) {
            context.sensorTemperatureCelsius
        } else {
            calibration.referenceTemperatureCelsius
        }
        output.whiteLevel = calibration.whiteLevel
        output.calibrationRevision = calibration.revision
        output.measuredProfile = measured?.validate() == true
        if (!output.measuredProfile &&
            calibration.baseNoiseSource == Niv99NoiseCalibrationSource.UNAVAILABLE
        ) {
            return Niv99ValidationResult.INVALID_CALIBRATION
        }
        output.coefficientDomain = if (output.measuredProfile) {
            measured?.coefficientDomain ?: Niv99NoiseCoefficientDomain.NORMALIZED_SENSOR_SIGNAL
        } else {
            Niv99NoiseCoefficientDomain.NORMALIZED_SENSOR_SIGNAL
        }
        output.calibrationSource = if (output.measuredProfile) {
            Niv99NoiseCalibrationSource.CAPTURE_RESULT_NOISE_PROFILE
        } else {
            calibration.baseNoiseSource
        }
        val isoRatio = context.iso.toFloat() / max(calibration.referenceIso, 1)
        val temperatureDelta = output.temperatureCelsius - calibration.referenceTemperatureCelsius
        val thermalReadScale = exp(
            (temperatureDelta * READ_NOISE_TEMPERATURE_SLOPE).coerceIn(
                MIN_THERMAL_EXPONENT,
                MAX_THERMAL_EXPONENT
            )
        )
        for (channel in 0 until Niv99SensorCalibration.CHANNEL_COUNT) {
            val black = calibration.blackLevel[channel]
            val range = max(calibration.whiteLevel - black, MIN_SENSOR_RANGE)
            output.blackLevel[channel] = 0f
            if (output.measuredProfile) {
                val measuredShot = measured?.shotCoefficient?.get(channel) ?: 0f
                val measuredRead = measured?.readCoefficient?.get(channel) ?: 0f
                if (output.coefficientDomain == Niv99NoiseCoefficientDomain.SENSOR_CODE_VALUE) {
                    output.shotCoefficient[channel] =
                        (measuredShot / range).coerceIn(MIN_SHOT, MAX_SHOT)
                    output.readCoefficient[channel] =
                        (measuredRead / (range * range)).coerceIn(MIN_READ, MAX_READ)
                } else {
                    output.shotCoefficient[channel] = measuredShot.coerceIn(MIN_SHOT, MAX_SHOT)
                    output.readCoefficient[channel] = measuredRead.coerceIn(MIN_READ, MAX_READ)
                }
            } else {
                val baseShot = max(calibration.baseShotNoise[channel], MIN_SHOT)
                val baseRead = max(calibration.baseReadNoise[channel], MIN_READ)
                output.shotCoefficient[channel] =
                    (baseShot * isoRatio).coerceIn(MIN_SHOT, MAX_SHOT)
                output.readCoefficient[channel] =
                    (baseRead * isoRatio.pow(READ_NOISE_ISO_EXPONENT) * thermalReadScale)
                        .coerceIn(MIN_READ, MAX_READ)
            }
        }
        return Niv99ValidationResult.VALID
    }

    companion object {
        private const val MIN_SENSOR_RANGE = 1e-6f
        private const val MIN_SHOT = 1e-8f
        private const val MAX_SHOT = 0.5f
        private const val MIN_READ = 1e-12f
        private const val MAX_READ = 0.25f
        private const val READ_NOISE_ISO_EXPONENT = 1.72f
        private const val READ_NOISE_TEMPERATURE_SLOPE = 0.025f
        private const val MIN_THERMAL_EXPONENT = -2f
        private const val MAX_THERMAL_EXPONENT = 2f
    }
}

class Niv99NoisePropagationWorkspace {
    val rawCovariance: FloatArray = FloatArray(9)
    val outputCovariance: FloatArray = FloatArray(9)
    val colorMatrix: FloatArray = FloatArray(9)
    var outputLumaVariance: Float = 0f
    var outputChromaVariance: Float = 0f

    init {
        Niv99Math.setIdentity3(colorMatrix)
    }
}

/** Propagates RAW variance through WB and CCM: `CCM × WB × Cov × WBᵀ × CCMᵀ`. */
object Niv99NoiseCovariancePropagation {
    fun propagate(
        model: Niv99NoiseModelSnapshot,
        redSignal: Float,
        greenSignal: Float,
        blueSignal: Float,
        redGain: Float,
        greenGain: Float,
        blueGain: Float,
        colorMatrix: FloatArray,
        workspace: Niv99NoisePropagationWorkspace
    ): Boolean {
        if (colorMatrix.size < 9 || !redGain.isFinite() || !greenGain.isFinite() ||
            !blueGain.isFinite() || redGain <= 0f || greenGain <= 0f || blueGain <= 0f
        ) {
            return false
        }
        val redVariance = model.variance(Niv99SensorCalibration.CHANNEL_RED, redSignal)
        val greenVariance = (
            model.variance(Niv99SensorCalibration.CHANNEL_GREEN_EVEN, greenSignal) +
                model.variance(Niv99SensorCalibration.CHANNEL_GREEN_ODD, greenSignal)
            ) * 0.5f
        val blueVariance = model.variance(Niv99SensorCalibration.CHANNEL_BLUE, blueSignal)
        Niv99Math.propagateDiagonalCovariance3(
            covarianceR = redVariance,
            covarianceG = greenVariance,
            covarianceB = blueVariance,
            redGain = redGain,
            greenGain = greenGain,
            blueGain = blueGain,
            colorMatrix = colorMatrix,
            output = workspace.outputCovariance
        )
        Niv99Math.copy3(colorMatrix, workspace.colorMatrix)
        workspace.rawCovariance.fill(0f)
        workspace.rawCovariance[0] = redVariance
        workspace.rawCovariance[4] = greenVariance
        workspace.rawCovariance[8] = blueVariance
        val covariance = workspace.outputCovariance
        // Rec.709 luma covariance and two opponent chroma axes.
        workspace.outputLumaVariance = quadraticForm(
            covariance,
            LUMA_RED,
            LUMA_GREEN,
            LUMA_BLUE
        )
        val redGreenVariance = quadraticForm(covariance, 1f, -1f, 0f)
        val blueGreenVariance = quadraticForm(covariance, 0f, -1f, 1f)
        workspace.outputChromaVariance = max(redGreenVariance, blueGreenVariance)
        return workspace.outputLumaVariance.isFinite() && workspace.outputChromaVariance.isFinite()
    }

    private fun quadraticForm(matrix: FloatArray, x: Float, y: Float, z: Float): Float {
        val mx = matrix[0] * x + matrix[1] * y + matrix[2] * z
        val my = matrix[3] * x + matrix[4] * y + matrix[5] * z
        val mz = matrix[6] * x + matrix[7] * y + matrix[8] * z
        return max(x * mx + y * my + z * mz, 0f)
    }

    private const val LUMA_RED = 0.2126f
    private const val LUMA_GREEN = 0.7152f
    private const val LUMA_BLUE = 0.0722f
}

/** Noise-normalized defect decision used before demosaic. */
object Niv99DefectPixelPolicy {
    fun isDefective(
        center: Float,
        sameColorMedian: Float,
        localGradient: Float,
        variance: Float,
        repeatedAcrossFrames: Boolean
    ): Boolean {
        if (!center.isFinite() || !sameColorMedian.isFinite() || !localGradient.isFinite() ||
            !variance.isFinite()
        ) {
            return true
        }
        val residual = kotlin.math.abs(center - sameColorMedian)
        val sigma = sqrt(max(variance, MIN_VARIANCE))
        val edgeAllowance = localGradient.coerceAtLeast(0f) * EDGE_ALLOWANCE
        val threshold = sigma * if (repeatedAcrossFrames) PERSISTENT_SIGMA else TRANSIENT_SIGMA
        return residual > threshold + edgeAllowance + ABSOLUTE_FLOOR
    }

    private const val MIN_VARIANCE = 1e-12f
    private const val EDGE_ALLOWANCE = 1.8f
    private const val PERSISTENT_SIGMA = 5.5f
    private const val TRANSIENT_SIGMA = 8f
    private const val ABSOLUTE_FLOOR = 1e-5f
}

/** Row/column fixed-pattern suppression is limited by scene structure and measured noise. */
object Niv99FixedPatternNoisePolicy {
    fun correctionWeight(
        rowOrColumnBias: Float,
        localVariance: Float,
        orthogonalGradient: Float,
        temporalPersistence: Float
    ): Float {
        if (!rowOrColumnBias.isFinite() || !localVariance.isFinite() ||
            !orthogonalGradient.isFinite() || !temporalPersistence.isFinite()
        ) {
            return 0f
        }
        val sigma = sqrt(max(localVariance, 1e-12f))
        val detectability = Niv99Math.smoothstep(1.5f, 5f, kotlin.math.abs(rowOrColumnBias) / sigma)
        val structureGuard = 1f - Niv99Math.smoothstep(0.01f, 0.10f, orthogonalGradient)
        return detectability * structureGuard * Niv99Math.saturateFinite(temporalPersistence)
    }
}
