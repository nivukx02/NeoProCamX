package com.neoproc.neoproccamx.niv99

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Sensor calibration copied from CameraCharacteristics into a long-lived object. Matrix semantics
 * are explicit: color matrices map XYZ to camera RGB; forward matrices map white-balanced camera RGB
 * to XYZ. NIV99 never silently substitutes a generic matrix when a required sensor matrix is absent.
 */
class Niv99SensorCalibration(
    lensShadingCapacity: Int = DEFAULT_LENS_SHADING_CAPACITY
) {
    var cameraId: String = ""
    var cfaPattern: Niv99CfaPattern = Niv99CfaPattern.UNKNOWN
    val blackLevel: FloatArray = FloatArray(4)
    var whiteLevel: Float = 1f
    var referenceIlluminant1Kelvin: Float = 2_850f
    var referenceIlluminant2Kelvin: Float = 6_500f
    val calibrationTransform1: FloatArray = FloatArray(9)
    val calibrationTransform2: FloatArray = FloatArray(9)
    val colorTransform1: FloatArray = FloatArray(9)
    val colorTransform2: FloatArray = FloatArray(9)
    val forwardMatrix1: FloatArray = FloatArray(9)
    val forwardMatrix2: FloatArray = FloatArray(9)
    var colorTransformPairAvailable: Boolean = false
    var calibrationTransformPairAvailable: Boolean = false
    var forwardMatrixPairAvailable: Boolean = false
    val baseShotNoise: FloatArray = FloatArray(4)
    val baseReadNoise: FloatArray = FloatArray(4)
    var baseNoiseSource: Niv99NoiseCalibrationSource = Niv99NoiseCalibrationSource.UNAVAILABLE
    var referenceIso: Int = 100
    var referenceTemperatureCelsius: Float = 25f
    var lensShadingColumns: Int = 0
        private set
    var lensShadingRows: Int = 0
        private set
    private val lensShading: FloatArray = FloatArray(lensShadingCapacity)
    var revision: Long = 0L
        private set

    init {
        Niv99Math.setIdentity3(calibrationTransform1)
        Niv99Math.setIdentity3(calibrationTransform2)
        Niv99Math.setIdentity3(colorTransform1)
        Niv99Math.setIdentity3(colorTransform2)
        Niv99Math.setIdentity3(forwardMatrix1)
        Niv99Math.setIdentity3(forwardMatrix2)
        baseShotNoise.fill(DEFAULT_SHOT_NOISE)
        baseReadNoise.fill(DEFAULT_READ_NOISE)
    }

    fun setLensShadingMap(
        gains: FloatArray,
        columns: Int,
        rows: Int
    ): Boolean {
        if (columns <= 0 || rows <= 0) return false
        val required = columns.toLong() * rows * CHANNEL_COUNT
        if (required > lensShading.size || required > gains.size) return false
        for (index in 0 until required.toInt()) {
            val gain = gains[index]
            if (!gain.isFinite() || gain <= 0f) return false
        }
        gains.copyInto(lensShading, endIndex = required.toInt())
        lensShadingColumns = columns
        lensShadingRows = rows
        revision += 1L
        return true
    }

    fun clearLensShadingMap() {
        lensShadingColumns = 0
        lensShadingRows = 0
        revision += 1L
    }

    /** Copies a complete calibration snapshot without exposing the private shading storage. */
    fun copyFrom(other: Niv99SensorCalibration): Niv99SensorCalibration {
        cameraId = other.cameraId
        cfaPattern = other.cfaPattern
        other.blackLevel.copyInto(blackLevel)
        whiteLevel = other.whiteLevel
        referenceIlluminant1Kelvin = other.referenceIlluminant1Kelvin
        referenceIlluminant2Kelvin = other.referenceIlluminant2Kelvin
        other.calibrationTransform1.copyInto(calibrationTransform1)
        other.calibrationTransform2.copyInto(calibrationTransform2)
        other.colorTransform1.copyInto(colorTransform1)
        other.colorTransform2.copyInto(colorTransform2)
        other.forwardMatrix1.copyInto(forwardMatrix1)
        other.forwardMatrix2.copyInto(forwardMatrix2)
        colorTransformPairAvailable = other.colorTransformPairAvailable
        calibrationTransformPairAvailable = other.calibrationTransformPairAvailable
        forwardMatrixPairAvailable = other.forwardMatrixPairAvailable
        other.baseShotNoise.copyInto(baseShotNoise)
        other.baseReadNoise.copyInto(baseReadNoise)
        baseNoiseSource = other.baseNoiseSource
        referenceIso = other.referenceIso
        referenceTemperatureCelsius = other.referenceTemperatureCelsius
        val shadingCount = other.lensShadingColumns * other.lensShadingRows * CHANNEL_COUNT
        if (shadingCount > 0) {
            check(shadingCount <= lensShading.size) { "destination lens-shading capacity is too small" }
            other.lensShading.copyInto(lensShading, endIndex = shadingCount)
            lensShadingColumns = other.lensShadingColumns
            lensShadingRows = other.lensShadingRows
        } else {
            lensShadingColumns = 0
            lensShadingRows = 0
        }
        revision = other.revision
        return this
    }

    fun lensShadingGain(channel: Int, normalizedX: Float, normalizedY: Float): Float {
        val columns = lensShadingColumns
        val rows = lensShadingRows
        if (channel !in 0 until CHANNEL_COUNT || columns <= 0 || rows <= 0) return 1f
        val x = Niv99Math.saturateFinite(normalizedX) * (columns - 1)
        val y = Niv99Math.saturateFinite(normalizedY) * (rows - 1)
        val x0 = x.toInt().coerceIn(0, columns - 1)
        val y0 = y.toInt().coerceIn(0, rows - 1)
        val x1 = min(x0 + 1, columns - 1)
        val y1 = min(y0 + 1, rows - 1)
        val tx = x - x0
        val ty = y - y0
        val g00 = lensShading[indexOfLensGain(channel, x0, y0, columns)]
        val g10 = lensShading[indexOfLensGain(channel, x1, y0, columns)]
        val g01 = lensShading[indexOfLensGain(channel, x0, y1, columns)]
        val g11 = lensShading[indexOfLensGain(channel, x1, y1, columns)]
        val upper = Niv99Math.lerp(g00, g10, tx)
        val lower = Niv99Math.lerp(g01, g11, tx)
        return Niv99Math.lerp(upper, lower, ty).coerceIn(MIN_LENS_GAIN, MAX_LENS_GAIN)
    }

    fun blackLevelForCfaChannel(channel: Int): Float = blackLevel[channel.coerceIn(0, 3)]

    fun validate(): Niv99ValidationResult {
        if (cameraId.isBlank()) return Niv99ValidationResult.INVALID_CALIBRATION
        if (cfaPattern == Niv99CfaPattern.UNKNOWN) return Niv99ValidationResult.INVALID_CALIBRATION
        if (!whiteLevel.isFinite() || whiteLevel <= 0f) return Niv99ValidationResult.INVALID_CALIBRATION
        for (channel in 0 until CHANNEL_COUNT) {
            if (!blackLevel[channel].isFinite() || blackLevel[channel] < 0f ||
                blackLevel[channel] >= whiteLevel ||
                !baseShotNoise[channel].isFinite() || baseShotNoise[channel] < 0f ||
                !baseReadNoise[channel].isFinite() || baseReadNoise[channel] < 0f
            ) {
                return Niv99ValidationResult.INVALID_CALIBRATION
            }
        }
        if (!referenceIlluminant1Kelvin.isFinite() || !referenceIlluminant2Kelvin.isFinite() ||
            referenceIlluminant1Kelvin <= 0f || referenceIlluminant2Kelvin <= 0f
        ) {
            return Niv99ValidationResult.INVALID_CALIBRATION
        }
        if (!isFiniteMatrix(calibrationTransform1) || !isFiniteMatrix(calibrationTransform2) ||
            !isFiniteMatrix(colorTransform1) || !isFiniteMatrix(colorTransform2) ||
            !isFiniteMatrix(forwardMatrix1) || !isFiniteMatrix(forwardMatrix2)
        ) {
            return Niv99ValidationResult.INVALID_CALIBRATION
        }
        if (!colorTransformPairAvailable) return Niv99ValidationResult.INVALID_CALIBRATION
        if (abs(Niv99Math.determinant3(colorTransform1)) < MIN_MATRIX_DETERMINANT ||
            abs(Niv99Math.determinant3(colorTransform2)) < MIN_MATRIX_DETERMINANT
        ) {
            return Niv99ValidationResult.INVALID_CALIBRATION
        }
        if (calibrationTransformPairAvailable &&
            (abs(Niv99Math.determinant3(calibrationTransform1)) < MIN_MATRIX_DETERMINANT ||
                abs(Niv99Math.determinant3(calibrationTransform2)) < MIN_MATRIX_DETERMINANT)
        ) {
            return Niv99ValidationResult.INVALID_CALIBRATION
        }
        return Niv99ValidationResult.VALID
    }

    fun markUpdated() {
        revision += 1L
    }

    private fun isFiniteMatrix(matrix: FloatArray): Boolean {
        for (index in 0 until 9) if (!matrix[index].isFinite()) return false
        return true
    }

    private fun indexOfLensGain(channel: Int, x: Int, y: Int, columns: Int): Int =
        (y * columns + x) * CHANNEL_COUNT + channel

    companion object {
        const val CHANNEL_RED = 0
        const val CHANNEL_GREEN_EVEN = 1
        const val CHANNEL_GREEN_ODD = 2
        const val CHANNEL_BLUE = 3
        const val CHANNEL_COUNT = 4
        private const val DEFAULT_LENS_SHADING_CAPACITY = 4 * 64 * 64
        private const val DEFAULT_SHOT_NOISE = 0.0025f
        private const val DEFAULT_READ_NOISE = 0.00008f
        private const val MIN_LENS_GAIN = 0.25f
        private const val MAX_LENS_GAIN = 16f
        private const val MIN_MATRIX_DETERMINANT = 1e-8f
    }
}

class Niv99CalibrationSolution {
    val xyzToSensor: FloatArray = FloatArray(9)
    val sensorToXyz: FloatArray = FloatArray(9)
    val forwardMatrix: FloatArray = FloatArray(9)
    val calibrationTransform: FloatArray = FloatArray(9)
    val sensorToOutput: FloatArray = FloatArray(9)
    var interpolationWeight: Float = 0f
    var valid: Boolean = false
    var matrixSource: Niv99CalibrationMatrixSource = Niv99CalibrationMatrixSource.NONE
    var calibrationTransformIdentityFallback: Boolean = false

    init {
        Niv99Math.setIdentity3(xyzToSensor)
        Niv99Math.setIdentity3(sensorToXyz)
        Niv99Math.setIdentity3(forwardMatrix)
        Niv99Math.setIdentity3(calibrationTransform)
        Niv99Math.setIdentity3(sensorToOutput)
    }
}

/** Dual-illuminant interpolation in reciprocal-temperature (mired) space. */
class Niv99DualIlluminantInterpolator {
    private val calibratedColor = FloatArray(9)
    private val inverseCalibration = FloatArray(9)
    private val cameraToXyz = FloatArray(9)

    fun resolve(
        calibration: Niv99SensorCalibration,
        cctKelvin: Float,
        output: Niv99CalibrationSolution
    ): Boolean {
        if (calibration.validate() != Niv99ValidationResult.VALID ||
            !cctKelvin.isFinite() || cctKelvin <= 0f
        ) {
            output.valid = false
            return false
        }
        val firstMired = kelvinToMired(calibration.referenceIlluminant1Kelvin)
        val secondMired = kelvinToMired(calibration.referenceIlluminant2Kelvin)
        val targetMired = kelvinToMired(cctKelvin)
        val denominator = secondMired - firstMired
        val weight = if (abs(denominator) < MIRED_EPSILON) {
            0f
        } else {
            ((targetMired - firstMired) / denominator).coerceIn(0f, 1f)
        }
        Niv99Math.interpolateMatrix3(
            calibration.colorTransform1,
            calibration.colorTransform2,
            weight,
            output.xyzToSensor
        )
        if (calibration.calibrationTransformPairAvailable) {
            Niv99Math.interpolateMatrix3(
                calibration.calibrationTransform1,
                calibration.calibrationTransform2,
                weight,
                output.calibrationTransform
            )
            output.calibrationTransformIdentityFallback = false
        } else {
            Niv99Math.setIdentity3(output.calibrationTransform)
            output.calibrationTransformIdentityFallback = true
        }
        if (calibration.forwardMatrixPairAvailable) {
            Niv99Math.interpolateMatrix3(
                calibration.forwardMatrix1,
                calibration.forwardMatrix2,
                weight,
                output.forwardMatrix
            )
        } else {
            Niv99Math.setIdentity3(output.forwardMatrix)
        }
        Niv99Math.multiply3(output.calibrationTransform, output.xyzToSensor, calibratedColor)
        if (!Niv99Math.invert3(output.calibrationTransform, inverseCalibration) ||
            !Niv99Math.invert3(calibratedColor, output.sensorToXyz)
        ) {
            output.valid = false
            return false
        }
        // Prefer the interpolated DNG forward matrix for rendering; retain the inverse color matrix
        // as a fully testable sensor-space estimate and as a fallback only when the forward matrix is singular.
        if (calibration.forwardMatrixPairAvailable &&
            abs(Niv99Math.determinant3(output.forwardMatrix)) >= MATRIX_EPSILON
        ) {
            Niv99Math.multiply3(output.forwardMatrix, inverseCalibration, cameraToXyz)
            Niv99Math.copy3(cameraToXyz, output.sensorToOutput)
            output.matrixSource = Niv99CalibrationMatrixSource.DUAL_ILLUMINANT_FORWARD_MATRIX
        } else {
            Niv99Math.copy3(output.sensorToXyz, output.sensorToOutput)
            output.matrixSource = Niv99CalibrationMatrixSource.INVERSE_DUAL_ILLUMINANT_COLOR_MATRIX
        }
        output.interpolationWeight = weight
        output.valid = matrixFinite(output.sensorToOutput)
        return output.valid
    }

    private fun matrixFinite(matrix: FloatArray): Boolean {
        for (index in 0 until 9) if (!matrix[index].isFinite()) return false
        return true
    }

    private fun kelvinToMired(kelvin: Float): Float = 1_000_000f / max(kelvin, 1f)

    companion object {
        private const val MIRED_EPSILON = 1e-6f
        private const val MATRIX_EPSILON = 1e-8f
    }
}

/** CAT16 chromatic adaptation with caller-owned output storage. */
class Niv99Cat16Adaptation {
    private val sourceLms = FloatArray(3)
    private val targetLms = FloatArray(3)
    private val scale = FloatArray(9)
    private val scaledCone = FloatArray(9)

    fun compute(
        sourceX: Float,
        sourceY: Float,
        sourceZ: Float,
        targetX: Float,
        targetY: Float,
        targetZ: Float,
        degreeOfAdaptation: Float,
        output: FloatArray
    ): Boolean {
        require(output.size >= 9)
        if (!sourceX.isFinite() || !sourceY.isFinite() || !sourceZ.isFinite() ||
            !targetX.isFinite() || !targetY.isFinite() || !targetZ.isFinite()
        ) {
            return false
        }
        Niv99Math.multiply3Vector(CAT16, sourceX, sourceY, sourceZ, sourceLms)
        Niv99Math.multiply3Vector(CAT16, targetX, targetY, targetZ, targetLms)
        for (index in 0 until 3) {
            if (abs(sourceLms[index]) < LMS_EPSILON) return false
        }
        val adaptation = Niv99Math.saturateFinite(degreeOfAdaptation)
        val scaleL = Niv99Math.lerp(1f, targetLms[0] / sourceLms[0], adaptation)
        val scaleM = Niv99Math.lerp(1f, targetLms[1] / sourceLms[1], adaptation)
        val scaleS = Niv99Math.lerp(1f, targetLms[2] / sourceLms[2], adaptation)
        Niv99Math.diagonal3(scaleL, scaleM, scaleS, scale)
        Niv99Math.multiply3(scale, CAT16, scaledCone)
        Niv99Math.multiply3(CAT16_INVERSE, scaledCone, output)
        return true
    }

    companion object {
        private const val LMS_EPSILON = 1e-7f
        private val CAT16 = floatArrayOf(
            0.401288f, 0.650173f, -0.051461f,
            -0.250268f, 1.204414f, 0.045854f,
            -0.002079f, 0.048952f, 0.953127f
        )
        private val CAT16_INVERSE = floatArrayOf(
            1.86206786f, -1.01125463f, 0.14918677f,
            0.38752654f, 0.62144744f, -0.00897398f,
            -0.01584150f, -0.03412294f, 1.04996444f
        )
    }
}
