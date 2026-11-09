package com.neoproc.neoproccamx.niv99

import kotlin.math.max

enum class Niv99MetadataStatus {
    VALID,
    CAMERA_ID_MISMATCH,
    UNSUPPORTED_CFA,
    MISSING_BLACK_LEVEL,
    MISSING_WHITE_LEVEL,
    MISSING_COLOR_TRANSFORMS,
    INVALID_MATRIX,
    INVALID_NOISE_CALIBRATION,
    INVALID_DYNAMIC_METADATA,
    INVALID_CAPTURE_IDENTITY,
    STALE_CAPTURE_METADATA,
    LENS_SHADING_CAPACITY_EXCEEDED
}

/**
 * Platform-neutral snapshot of the static Camera2 sensor metadata. Arrays are fixed and owned by
 * this object so the Android adapter can refresh it without producing temporary collections.
 */
class Niv99StaticCameraMetadata {
    var cameraId: String = ""
    var cfaPattern: Niv99CfaPattern = Niv99CfaPattern.UNKNOWN
    val blackLevelPhysicalTile: FloatArray = FloatArray(Niv99SensorCalibration.CHANNEL_COUNT)
    var blackLevelAvailable: Boolean = false
    var whiteLevel: Float = 0f
    var whiteLevelAvailable: Boolean = false
    var referenceIlluminant1Kelvin: Float = 0f
    var referenceIlluminant2Kelvin: Float = 0f
    val calibrationTransform1: FloatArray = FloatArray(9)
    val calibrationTransform2: FloatArray = FloatArray(9)
    val colorTransform1: FloatArray = FloatArray(9)
    val colorTransform2: FloatArray = FloatArray(9)
    val forwardMatrix1: FloatArray = FloatArray(9)
    val forwardMatrix2: FloatArray = FloatArray(9)
    var calibrationTransformPairAvailable: Boolean = false
    var colorTransformPairAvailable: Boolean = false
    var forwardMatrixPairAvailable: Boolean = false
    val fallbackShotNoise: FloatArray = FloatArray(Niv99SensorCalibration.CHANNEL_COUNT)
    val fallbackReadNoise: FloatArray = FloatArray(Niv99SensorCalibration.CHANNEL_COUNT)
    var fallbackNoiseSource: Niv99NoiseCalibrationSource = Niv99NoiseCalibrationSource.UNAVAILABLE
    var referenceIso: Int = 100
    var referenceTemperatureCelsius: Float = 25f

    init {
        Niv99Math.setIdentity3(calibrationTransform1)
        Niv99Math.setIdentity3(calibrationTransform2)
        Niv99Math.setIdentity3(colorTransform1)
        Niv99Math.setIdentity3(colorTransform2)
        Niv99Math.setIdentity3(forwardMatrix1)
        Niv99Math.setIdentity3(forwardMatrix2)
    }

    fun clear() {
        cameraId = ""
        cfaPattern = Niv99CfaPattern.UNKNOWN
        blackLevelPhysicalTile.fill(0f)
        blackLevelAvailable = false
        whiteLevel = 0f
        whiteLevelAvailable = false
        referenceIlluminant1Kelvin = 0f
        referenceIlluminant2Kelvin = 0f
        Niv99Math.setIdentity3(calibrationTransform1)
        Niv99Math.setIdentity3(calibrationTransform2)
        Niv99Math.setIdentity3(colorTransform1)
        Niv99Math.setIdentity3(colorTransform2)
        Niv99Math.setIdentity3(forwardMatrix1)
        Niv99Math.setIdentity3(forwardMatrix2)
        calibrationTransformPairAvailable = false
        colorTransformPairAvailable = false
        forwardMatrixPairAvailable = false
        fallbackShotNoise.fill(0f)
        fallbackReadNoise.fill(0f)
        fallbackNoiseSource = Niv99NoiseCalibrationSource.UNAVAILABLE
        referenceIso = 100
        referenceTemperatureCelsius = 25f
    }
}

/** Same-frame metadata. Dynamic black and noise arrays are semantic R, Gr, Gb, B. */
class Niv99DynamicSensorMetadata(lensShadingCapacity: Int = DEFAULT_LENS_SHADING_CAPACITY) {
    val identity: Niv99FrameIdentity = Niv99FrameIdentity()
    val dynamicBlackLevelRggb: FloatArray = FloatArray(Niv99SensorCalibration.CHANNEL_COUNT)
    var dynamicBlackLevelAvailable: Boolean = false
    var dynamicWhiteLevel: Float = 0f
    var dynamicWhiteLevelAvailable: Boolean = false
    val noiseShotRggb: FloatArray = FloatArray(Niv99SensorCalibration.CHANNEL_COUNT)
    val noiseReadRggb: FloatArray = FloatArray(Niv99SensorCalibration.CHANNEL_COUNT)
    var noiseProfileAvailable: Boolean = false
    var noiseCoefficientDomain: Niv99NoiseCoefficientDomain =
        Niv99NoiseCoefficientDomain.NORMALIZED_SENSOR_SIGNAL
    val lensShadingRggb: FloatArray = FloatArray(lensShadingCapacity)
    var lensShadingColumns: Int = 0
    var lensShadingRows: Int = 0
    var lensShadingAvailable: Boolean = false

    fun clearFramePayload() {
        dynamicBlackLevelAvailable = false
        dynamicWhiteLevel = 0f
        dynamicWhiteLevelAvailable = false
        noiseProfileAvailable = false
        lensShadingColumns = 0
        lensShadingRows = 0
        lensShadingAvailable = false
    }

    fun lensShadingElementCount(): Int =
        lensShadingColumns * lensShadingRows * Niv99SensorCalibration.CHANNEL_COUNT

    fun copyFrom(other: Niv99DynamicSensorMetadata): Niv99DynamicSensorMetadata {
        identity.copyFrom(other.identity)
        other.dynamicBlackLevelRggb.copyInto(dynamicBlackLevelRggb)
        dynamicBlackLevelAvailable = other.dynamicBlackLevelAvailable
        dynamicWhiteLevel = other.dynamicWhiteLevel
        dynamicWhiteLevelAvailable = other.dynamicWhiteLevelAvailable
        other.noiseShotRggb.copyInto(noiseShotRggb)
        other.noiseReadRggb.copyInto(noiseReadRggb)
        noiseProfileAvailable = other.noiseProfileAvailable
        noiseCoefficientDomain = other.noiseCoefficientDomain
        val count = other.lensShadingElementCount()
        check(count <= lensShadingRggb.size) { "destination lens-shading capacity is too small" }
        if (count > 0) other.lensShadingRggb.copyInto(lensShadingRggb, endIndex = count)
        lensShadingColumns = other.lensShadingColumns
        lensShadingRows = other.lensShadingRows
        lensShadingAvailable = other.lensShadingAvailable
        return this
    }

    companion object {
        const val DEFAULT_LENS_SHADING_CAPACITY: Int = 4 * 64 * 64
    }
}

/** Preallocated user-control snapshot copied atomically into a frame context. */
class Niv99UserControlMetadata {
    var manualKelvin: Int = 0
    var manualTint: Int = 0
    var manualRedGain: Float = 1f
    var manualGreenGain: Float = 1f
    var manualBlueGain: Float = 1f
    var manualMatrixAvailable: Boolean = false
    val manualColorMatrix: FloatArray = FloatArray(9)
    var awbLockRequested: Boolean = false

    init {
        Niv99Math.setIdentity3(manualColorMatrix)
    }

    fun copyTo(output: Niv99WhiteBalanceIntent) {
        output.manualKelvin = manualKelvin
        output.manualTint = manualTint
        output.manualRedGain = manualRedGain
        output.manualGreenGain = manualGreenGain
        output.manualBlueGain = manualBlueGain
        output.manualMatrixValid = manualMatrixAvailable && finiteMatrix(manualColorMatrix)
        if (output.manualMatrixValid) {
            manualColorMatrix.copyInto(output.manualColorMatrix)
        } else {
            Niv99Math.setIdentity3(output.manualColorMatrix)
        }
        output.awbLockRequested = awbLockRequested
    }

    private fun finiteMatrix(matrix: FloatArray): Boolean {
        for (index in 0 until 9) if (!matrix[index].isFinite()) return false
        return true
    }
}

/**
 * Platform-neutral same-frame capture metadata. The Android adapter fills this object directly from
 * one TotalCaptureResult; gains and matrix therefore cannot be accidentally mixed across frames.
 */
class Niv99CaptureMetadata(
    lensShadingCapacity: Int = Niv99DynamicSensorMetadata.DEFAULT_LENS_SHADING_CAPACITY
) {
    val identity: Niv99FrameIdentity = Niv99FrameIdentity()
    var iso: Int = 100
    var exposureTimeNs: Long = 0L
    var frameDurationNs: Long = 0L
    var rollingShutterSkewNs: Long = 0L
    var flashFired: Boolean = false
    var halAwbConverged: Boolean = false
    var halAwbLocked: Boolean = false
    var halGainsAvailable: Boolean = false
    var halRedGain: Float = 1f
    var halGreenEvenGain: Float = 1f
    var halGreenOddGain: Float = 1f
    var halBlueGain: Float = 1f
    var halMatrixAvailable: Boolean = false
    val halColorMatrix: FloatArray = FloatArray(9)
    val sensor: Niv99DynamicSensorMetadata = Niv99DynamicSensorMetadata(lensShadingCapacity)

    init {
        Niv99Math.setIdentity3(halColorMatrix)
    }

    fun copyFrom(other: Niv99CaptureMetadata): Niv99CaptureMetadata {
        identity.copyFrom(other.identity)
        iso = other.iso
        exposureTimeNs = other.exposureTimeNs
        frameDurationNs = other.frameDurationNs
        rollingShutterSkewNs = other.rollingShutterSkewNs
        flashFired = other.flashFired
        halAwbConverged = other.halAwbConverged
        halAwbLocked = other.halAwbLocked
        halGainsAvailable = other.halGainsAvailable
        halRedGain = other.halRedGain
        halGreenEvenGain = other.halGreenEvenGain
        halGreenOddGain = other.halGreenOddGain
        halBlueGain = other.halBlueGain
        halMatrixAvailable = other.halMatrixAvailable
        other.halColorMatrix.copyInto(halColorMatrix)
        sensor.copyFrom(other.sensor)
        return this
    }

    fun applyTo(
        context: Niv99FrameContext,
        userControls: Niv99UserControlMetadata,
        mode: Niv99OperatingMode,
        signalDomain: Niv99SignalDomain,
        detailProfile: Niv99DetailProfile,
        width: Int,
        height: Int,
        targetFps: Int,
        colorProfileId: Long,
        surfaceGeneration: Long,
        codecGeneration: Long
    ): Niv99ValidationResult {
        context.identity.copyFrom(identity)
        context.operatingMode = mode
        context.signalDomain = signalDomain
        context.detailProfile = detailProfile
        context.width = width
        context.height = height
        context.targetFps = targetFps
        context.colorProfileId = colorProfileId
        context.surfaceGeneration = surfaceGeneration
        context.codecGeneration = codecGeneration
        context.iso = iso
        context.exposureTimeNs = exposureTimeNs
        context.frameDurationNs = frameDurationNs
        context.rollingShutterSkewNs = rollingShutterSkewNs
        context.flashFired = flashFired
        val intent = context.whiteBalanceIntent
        intent.clear()
        userControls.copyTo(intent)
        // CONTROL_AWB_LOCK is also used internally by the cinema stability policy. It is user
        // authority only when the operator explicitly requested a lock; otherwise NIV99 may still
        // estimate a bounded residual correction over the stable HAL solution.
        intent.halAwbHeld = halAwbLocked
        intent.awbLockActive = intent.awbLockRequested && halAwbLocked
        intent.halAwbConverged = halAwbConverged
        intent.halGainsAvailable = halGainsAvailable && validGains()
        if (intent.halGainsAvailable) {
            intent.halRedGain = halRedGain
            intent.halGreenEvenGain = halGreenEvenGain
            intent.halGreenOddGain = halGreenOddGain
            intent.halBlueGain = halBlueGain
        }
        intent.halMatrixValid = halMatrixAvailable && finiteMatrix(halColorMatrix)
        if (intent.halMatrixValid) {
            halColorMatrix.copyInto(intent.halColorMatrix)
        }
        return context.validate()
    }

    private fun validGains(): Boolean =
        halRedGain.isFinite() && halRedGain > 0f &&
            halGreenEvenGain.isFinite() && halGreenEvenGain > 0f &&
            halGreenOddGain.isFinite() && halGreenOddGain > 0f &&
            halBlueGain.isFinite() && halBlueGain > 0f

    private fun finiteMatrix(matrix: FloatArray): Boolean {
        for (index in 0 until 9) if (!matrix[index].isFinite()) return false
        return true
    }
}

object Niv99CfaChannelMapper {
    const val POSITION_TOP_LEFT: Int = 0
    const val POSITION_TOP_RIGHT: Int = 1
    const val POSITION_BOTTOM_LEFT: Int = 2
    const val POSITION_BOTTOM_RIGHT: Int = 3

    fun physicalPositionToSemanticChannel(pattern: Niv99CfaPattern, position: Int): Int =
        when (pattern) {
            Niv99CfaPattern.RGGB -> when (position) {
                POSITION_TOP_LEFT -> Niv99SensorCalibration.CHANNEL_RED
                POSITION_TOP_RIGHT -> Niv99SensorCalibration.CHANNEL_GREEN_EVEN
                POSITION_BOTTOM_LEFT -> Niv99SensorCalibration.CHANNEL_GREEN_ODD
                POSITION_BOTTOM_RIGHT -> Niv99SensorCalibration.CHANNEL_BLUE
                else -> -1
            }
            Niv99CfaPattern.GRBG -> when (position) {
                POSITION_TOP_LEFT -> Niv99SensorCalibration.CHANNEL_GREEN_EVEN
                POSITION_TOP_RIGHT -> Niv99SensorCalibration.CHANNEL_RED
                POSITION_BOTTOM_LEFT -> Niv99SensorCalibration.CHANNEL_BLUE
                POSITION_BOTTOM_RIGHT -> Niv99SensorCalibration.CHANNEL_GREEN_ODD
                else -> -1
            }
            Niv99CfaPattern.GBRG -> when (position) {
                POSITION_TOP_LEFT -> Niv99SensorCalibration.CHANNEL_GREEN_EVEN
                POSITION_TOP_RIGHT -> Niv99SensorCalibration.CHANNEL_BLUE
                POSITION_BOTTOM_LEFT -> Niv99SensorCalibration.CHANNEL_RED
                POSITION_BOTTOM_RIGHT -> Niv99SensorCalibration.CHANNEL_GREEN_ODD
                else -> -1
            }
            Niv99CfaPattern.BGGR -> when (position) {
                POSITION_TOP_LEFT -> Niv99SensorCalibration.CHANNEL_BLUE
                POSITION_TOP_RIGHT -> Niv99SensorCalibration.CHANNEL_GREEN_EVEN
                POSITION_BOTTOM_LEFT -> Niv99SensorCalibration.CHANNEL_GREEN_ODD
                POSITION_BOTTOM_RIGHT -> Niv99SensorCalibration.CHANNEL_RED
                else -> -1
            }
            Niv99CfaPattern.MONO -> Niv99SensorCalibration.CHANNEL_GREEN_EVEN
            Niv99CfaPattern.UNKNOWN -> -1
        }

    fun copyPhysicalTileToSemantic(
        physical: FloatArray,
        pattern: Niv99CfaPattern,
        semantic: FloatArray
    ): Boolean {
        if (physical.size < 4 || semantic.size < 4 ||
            pattern == Niv99CfaPattern.UNKNOWN || pattern == Niv99CfaPattern.MONO
        ) {
            return false
        }
        for (position in 0 until 4) {
            val channel = physicalPositionToSemanticChannel(pattern, position)
            if (channel !in 0 until 4) return false
            semantic[channel] = physical[position]
        }
        return true
    }
}

/**
 * Converts validated platform-neutral metadata into the calibration consumed by NIV99. Missing
 * required data is reported; no generic identity/color calibration is silently advertised.
 */
object Niv99CalibrationAssembler {
    fun populate(
        staticMetadata: Niv99StaticCameraMetadata,
        dynamicMetadata: Niv99DynamicSensorMetadata?,
        output: Niv99SensorCalibration
    ): Niv99MetadataStatus {
        val staticStatus = validateStatic(staticMetadata)
        if (staticStatus != Niv99MetadataStatus.VALID) return staticStatus
        if (dynamicMetadata != null && dynamicMetadata.identity.isComplete() &&
            dynamicMetadata.identity.cameraId != staticMetadata.cameraId
        ) {
            return Niv99MetadataStatus.CAMERA_ID_MISMATCH
        }

        val whiteLevel = if (dynamicMetadata?.dynamicWhiteLevelAvailable == true) {
            dynamicMetadata.dynamicWhiteLevel
        } else {
            staticMetadata.whiteLevel
        }
        if (!whiteLevel.isFinite() || whiteLevel <= 0f) return Niv99MetadataStatus.MISSING_WHITE_LEVEL

        val dynamicBlackAvailable = dynamicMetadata?.dynamicBlackLevelAvailable == true
        if (dynamicBlackAvailable) {
            if (!validBlackLevels(dynamicMetadata.dynamicBlackLevelRggb, whiteLevel)) {
                return Niv99MetadataStatus.INVALID_DYNAMIC_METADATA
            }
        } else if (!validBlackLevels(staticMetadata.blackLevelPhysicalTile, whiteLevel)) {
            return Niv99MetadataStatus.MISSING_BLACK_LEVEL
        }

        if (dynamicMetadata?.lensShadingAvailable == true) {
            val count = dynamicMetadata.lensShadingElementCount()
            if (count <= 0 || count > dynamicMetadata.lensShadingRggb.size) {
                return Niv99MetadataStatus.LENS_SHADING_CAPACITY_EXCEEDED
            }
            for (index in 0 until count) {
                val gain = dynamicMetadata.lensShadingRggb[index]
                if (!gain.isFinite() || gain <= 0f) {
                    return Niv99MetadataStatus.INVALID_DYNAMIC_METADATA
                }
            }
        }

        output.cameraId = staticMetadata.cameraId
        output.cfaPattern = staticMetadata.cfaPattern
        output.whiteLevel = whiteLevel
        if (dynamicBlackAvailable) {
            dynamicMetadata.dynamicBlackLevelRggb.copyInto(output.blackLevel)
        } else if (!Niv99CfaChannelMapper.copyPhysicalTileToSemantic(
                staticMetadata.blackLevelPhysicalTile,
                staticMetadata.cfaPattern,
                output.blackLevel
            )
        ) {
            return Niv99MetadataStatus.UNSUPPORTED_CFA
        }
        output.referenceIlluminant1Kelvin = staticMetadata.referenceIlluminant1Kelvin
        output.referenceIlluminant2Kelvin = staticMetadata.referenceIlluminant2Kelvin
        staticMetadata.colorTransform1.copyInto(output.colorTransform1)
        staticMetadata.colorTransform2.copyInto(output.colorTransform2)
        output.colorTransformPairAvailable = staticMetadata.colorTransformPairAvailable
        if (staticMetadata.calibrationTransformPairAvailable) {
            staticMetadata.calibrationTransform1.copyInto(output.calibrationTransform1)
            staticMetadata.calibrationTransform2.copyInto(output.calibrationTransform2)
        } else {
            Niv99Math.setIdentity3(output.calibrationTransform1)
            Niv99Math.setIdentity3(output.calibrationTransform2)
        }
        output.calibrationTransformPairAvailable = staticMetadata.calibrationTransformPairAvailable
        if (staticMetadata.forwardMatrixPairAvailable) {
            staticMetadata.forwardMatrix1.copyInto(output.forwardMatrix1)
            staticMetadata.forwardMatrix2.copyInto(output.forwardMatrix2)
        } else {
            Niv99Math.setIdentity3(output.forwardMatrix1)
            Niv99Math.setIdentity3(output.forwardMatrix2)
        }
        output.forwardMatrixPairAvailable = staticMetadata.forwardMatrixPairAvailable
        staticMetadata.fallbackShotNoise.copyInto(output.baseShotNoise)
        staticMetadata.fallbackReadNoise.copyInto(output.baseReadNoise)
        output.baseNoiseSource = staticMetadata.fallbackNoiseSource
        output.referenceIso = max(staticMetadata.referenceIso, 1)
        output.referenceTemperatureCelsius = staticMetadata.referenceTemperatureCelsius
        if (dynamicMetadata?.lensShadingAvailable == true) {
            if (!output.setLensShadingMap(
                    dynamicMetadata.lensShadingRggb,
                    dynamicMetadata.lensShadingColumns,
                    dynamicMetadata.lensShadingRows
                )
            ) {
                return Niv99MetadataStatus.LENS_SHADING_CAPACITY_EXCEEDED
            }
        } else {
            output.clearLensShadingMap()
        }
        output.markUpdated()
        return if (output.validate() == Niv99ValidationResult.VALID) {
            Niv99MetadataStatus.VALID
        } else {
            Niv99MetadataStatus.INVALID_MATRIX
        }
    }

    fun populateMeasuredNoise(
        metadata: Niv99DynamicSensorMetadata,
        output: Niv99MeasuredNoiseProfile
    ): Niv99MetadataStatus {
        output.valid = false
        if (!metadata.noiseProfileAvailable) return Niv99MetadataStatus.INVALID_NOISE_CALIBRATION
        for (channel in 0 until Niv99SensorCalibration.CHANNEL_COUNT) {
            val shot = metadata.noiseShotRggb[channel]
            val read = metadata.noiseReadRggb[channel]
            if (!shot.isFinite() || !read.isFinite() || shot < 0f || read < 0f) {
                return Niv99MetadataStatus.INVALID_NOISE_CALIBRATION
            }
            output.shotCoefficient[channel] = shot
            output.readCoefficient[channel] = read
        }
        output.coefficientDomain = metadata.noiseCoefficientDomain
        output.valid = true
        output.valid = output.validate()
        return if (output.valid) {
            Niv99MetadataStatus.VALID
        } else {
            Niv99MetadataStatus.INVALID_NOISE_CALIBRATION
        }
    }

    private fun validateStatic(metadata: Niv99StaticCameraMetadata): Niv99MetadataStatus {
        if (metadata.cameraId.isBlank()) return Niv99MetadataStatus.CAMERA_ID_MISMATCH
        if (metadata.cfaPattern == Niv99CfaPattern.UNKNOWN ||
            metadata.cfaPattern == Niv99CfaPattern.MONO
        ) {
            return Niv99MetadataStatus.UNSUPPORTED_CFA
        }
        if (!metadata.blackLevelAvailable) return Niv99MetadataStatus.MISSING_BLACK_LEVEL
        if (!metadata.whiteLevelAvailable) return Niv99MetadataStatus.MISSING_WHITE_LEVEL
        if (!metadata.colorTransformPairAvailable) return Niv99MetadataStatus.MISSING_COLOR_TRANSFORMS
        if (!metadata.referenceIlluminant1Kelvin.isFinite() ||
            !metadata.referenceIlluminant2Kelvin.isFinite() ||
            metadata.referenceIlluminant1Kelvin <= 0f || metadata.referenceIlluminant2Kelvin <= 0f
        ) {
            return Niv99MetadataStatus.INVALID_MATRIX
        }
        if (!finiteMatrix(metadata.colorTransform1) || !finiteMatrix(metadata.colorTransform2) ||
            !finiteMatrix(metadata.calibrationTransform1) ||
            !finiteMatrix(metadata.calibrationTransform2) ||
            !finiteMatrix(metadata.forwardMatrix1) || !finiteMatrix(metadata.forwardMatrix2)
        ) {
            return Niv99MetadataStatus.INVALID_MATRIX
        }
        if (metadata.fallbackNoiseSource != Niv99NoiseCalibrationSource.UNAVAILABLE) {
            for (channel in 0 until Niv99SensorCalibration.CHANNEL_COUNT) {
                val shot = metadata.fallbackShotNoise[channel]
                val read = metadata.fallbackReadNoise[channel]
                if (!shot.isFinite() || !read.isFinite() || shot < 0f || read < 0f ||
                    shot + read <= 0f
                ) {
                    return Niv99MetadataStatus.INVALID_NOISE_CALIBRATION
                }
            }
        }
        return Niv99MetadataStatus.VALID
    }

    private fun validBlackLevels(values: FloatArray, whiteLevel: Float): Boolean {
        if (values.size < Niv99SensorCalibration.CHANNEL_COUNT) return false
        for (channel in 0 until Niv99SensorCalibration.CHANNEL_COUNT) {
            val value = values[channel]
            if (!value.isFinite() || value < 0f || value >= whiteLevel) return false
        }
        return true
    }

    private fun finiteMatrix(matrix: FloatArray): Boolean {
        if (matrix.size < 9) return false
        for (index in 0 until 9) if (!matrix[index].isFinite()) return false
        return true
    }
}

/** Public EXIF/DNG illuminant constants converted to a stable reference temperature. */
object Niv99ReferenceIlluminant {
    fun kelvin(code: Int, fallback: Float = 0f): Float = when (code) {
        1 -> 5_500f // Daylight
        2 -> 4_150f // Fluorescent (broad legacy class)
        3 -> 2_850f // Tungsten
        4 -> 5_500f // Flash
        9 -> 5_500f // Fine weather
        10 -> 6_500f // Cloudy
        11 -> 7_500f // Shade
        12 -> 6_500f
        13 -> 5_000f
        14 -> 4_150f
        15 -> 3_500f
        17 -> 2_856f // Standard Light A
        18 -> 4_874f // Standard Light B
        19 -> 6_774f // Standard Light C
        20 -> 5_500f // D55
        21 -> 6_500f
        22 -> 7_500f
        23 -> 5_000f
        24 -> 3_200f
        else -> fallback
    }
}
