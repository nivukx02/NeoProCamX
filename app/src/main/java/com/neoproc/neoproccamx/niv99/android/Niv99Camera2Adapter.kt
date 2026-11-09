package com.neoproc.neoproccamx.niv99.android

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import com.neoproc.neoproccamx.camera.CameraSensorNoiseModelPolicy
import com.neoproc.neoproccamx.camera.SensorNoiseModel
import com.neoproc.neoproccamx.niv99.Niv99CalibrationAssembler
import com.neoproc.neoproccamx.niv99.Niv99CaptureMetadataDepth
import com.neoproc.neoproccamx.niv99.Niv99CaptureMetadata
import com.neoproc.neoproccamx.niv99.Niv99CfaPattern
import com.neoproc.neoproccamx.niv99.Niv99DetailProfile
import com.neoproc.neoproccamx.niv99.Niv99FrameContext
import com.neoproc.neoproccamx.niv99.Niv99FrameProvenanceGate
import com.neoproc.neoproccamx.niv99.Niv99MeasuredNoiseProfile
import com.neoproc.neoproccamx.niv99.Niv99MetadataStatus
import com.neoproc.neoproccamx.niv99.Niv99NoiseCalibrationSource
import com.neoproc.neoproccamx.niv99.Niv99NoiseCoefficientDomain
import com.neoproc.neoproccamx.niv99.Niv99OperatingMode
import com.neoproc.neoproccamx.niv99.Niv99ReferenceIlluminant
import com.neoproc.neoproccamx.niv99.Niv99SensorCalibration
import com.neoproc.neoproccamx.niv99.Niv99SignalDomain
import com.neoproc.neoproccamx.niv99.Niv99StaticCameraMetadata
import com.neoproc.neoproccamx.niv99.Niv99UserControlMetadata
import com.neoproc.neoproccamx.niv99.Niv99ValidationResult

/**
 * Allocation-bounded Camera2 bridge. A single instance belongs to one active camera timeline and
 * reuses every mutable payload. It never builds a Camera2 request and therefore cannot override
 * manual WB or AWB-lock intent.
 */
class Niv99Camera2Adapter(
    lensShadingCapacity: Int = DEFAULT_LENS_SHADING_CAPACITY
) {
    val staticMetadata: Niv99StaticCameraMetadata = Niv99StaticCameraMetadata()
    val captureMetadata: Niv99CaptureMetadata = Niv99CaptureMetadata(lensShadingCapacity)
    val calibration: Niv99SensorCalibration = Niv99SensorCalibration(lensShadingCapacity)
    val measuredNoiseProfile: Niv99MeasuredNoiseProfile = Niv99MeasuredNoiseProfile()
    /** Immutable-for-the-session ISP-domain calibration, isolated from RAW dynamic metadata. */
    val processedCalibration: Niv99SensorCalibration = Niv99SensorCalibration(lensShadingCapacity)
    /** Processed frames use the validated static fallback model; RAW measured noise never crosses domains. */
    val processedMeasuredNoiseProfile: Niv99MeasuredNoiseProfile = Niv99MeasuredNoiseProfile()

    private val provenanceGate = Niv99FrameProvenanceGate()
    private var staticReady = false

    @Synchronized
    fun readStatic(
        cameraId: String,
        characteristics: CameraCharacteristics
    ): Niv99MetadataStatus {
        staticReady = false
        provenanceGate.reset()
        staticMetadata.clear()
        staticMetadata.cameraId = cameraId
        staticMetadata.cfaPattern = mapCfa(
            characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        )
        if (staticMetadata.cfaPattern == Niv99CfaPattern.UNKNOWN ||
            staticMetadata.cfaPattern == Niv99CfaPattern.MONO
        ) {
            return Niv99MetadataStatus.UNSUPPORTED_CFA
        }

        val blackPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        if (blackPattern != null) {
            staticMetadata.blackLevelPhysicalTile[0] = blackPattern.getOffsetForIndex(0, 0).toFloat()
            staticMetadata.blackLevelPhysicalTile[1] = blackPattern.getOffsetForIndex(1, 0).toFloat()
            staticMetadata.blackLevelPhysicalTile[2] = blackPattern.getOffsetForIndex(0, 1).toFloat()
            staticMetadata.blackLevelPhysicalTile[3] = blackPattern.getOffsetForIndex(1, 1).toFloat()
            staticMetadata.blackLevelAvailable = true
        }
        val whiteLevel = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        if (whiteLevel != null && whiteLevel > 0) {
            staticMetadata.whiteLevel = whiteLevel.toFloat()
            staticMetadata.whiteLevelAvailable = true
        }

        val reference1 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
        val reference2 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)
        staticMetadata.referenceIlluminant1Kelvin =
            Niv99ReferenceIlluminant.kelvin(reference1 ?: -1)
        staticMetadata.referenceIlluminant2Kelvin =
            Niv99ReferenceIlluminant.kelvin(reference2?.toInt() ?: -1)

        staticMetadata.colorTransformPairAvailable = copyTransformPair(
            characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1),
            characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2),
            staticMetadata.colorTransform1,
            staticMetadata.colorTransform2
        )
        staticMetadata.calibrationTransformPairAvailable = copyTransformPair(
            characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1),
            characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2),
            staticMetadata.calibrationTransform1,
            staticMetadata.calibrationTransform2
        )
        staticMetadata.forwardMatrixPairAvailable = copyTransformPair(
            characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1),
            characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2),
            staticMetadata.forwardMatrix1,
            staticMetadata.forwardMatrix2
        )

        val sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        staticMetadata.referenceIso = sensitivityRange?.lower?.coerceAtLeast(1) ?: 100
        val fallbackNoise = CameraSensorNoiseModelPolicy.fromCharacteristics(characteristics)
        staticMetadata.fallbackShotNoise.fill(fallbackNoise.shotNoiseCoefficient)
        staticMetadata.fallbackReadNoise.fill(fallbackNoise.readNoiseCoefficient)
        staticMetadata.fallbackNoiseSource = if (
            fallbackNoise.source == SensorNoiseModel.CAPTURE_RESULT_SOURCE
        ) {
            Niv99NoiseCalibrationSource.CAPTURE_RESULT_NOISE_PROFILE
        } else if (sensitivityRange != null) {
            Niv99NoiseCalibrationSource.SENSITIVITY_RANGE_ESTIMATE
        } else {
            Niv99NoiseCalibrationSource.VALIDATED_CONSERVATIVE_FALLBACK
        }

        val status = Niv99CalibrationAssembler.populate(staticMetadata, null, processedCalibration)
        if (status == Niv99MetadataStatus.VALID) {
            calibration.copyFrom(processedCalibration)
            processedMeasuredNoiseProfile.valid = false
            measuredNoiseProfile.valid = false
        }
        staticReady = status == Niv99MetadataStatus.VALID
        return status
    }

    /**
     * Copies one TotalCaptureResult atomically. The provenance gate commits only after every field
     * has passed validation, so corrupt metadata cannot retire a later valid retry.
     */
    @Synchronized
    internal fun readCapture(
        result: TotalCaptureResult,
        sessionGeneration: Long,
        metadataDepth: Niv99CaptureMetadataDepth = Niv99CaptureMetadataDepth.SENSOR_LINEAR
    ): Niv99MetadataStatus {
        if (!staticReady) return Niv99MetadataStatus.MISSING_COLOR_TRANSFORMS
        val sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
        captureMetadata.identity.set(
            staticMetadata.cameraId,
            sessionGeneration,
            result.frameNumber,
            sensorTimestamp
        )
        captureMetadata.sensor.identity.copyFrom(captureMetadata.identity)
        val identityValidation = provenanceGate.validate(captureMetadata.identity)
        if (identityValidation == Niv99ValidationResult.INVALID_IDENTITY) {
            return Niv99MetadataStatus.INVALID_CAPTURE_IDENTITY
        }
        if (identityValidation == Niv99ValidationResult.STALE_METADATA) {
            return Niv99MetadataStatus.STALE_CAPTURE_METADATA
        }

        captureMetadata.iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        captureMetadata.exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        captureMetadata.frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
        captureMetadata.rollingShutterSkewNs =
            result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L
        val flashState = result.get(CaptureResult.FLASH_STATE)
        captureMetadata.flashFired = flashState == CaptureResult.FLASH_STATE_FIRED ||
            flashState == CaptureResult.FLASH_STATE_PARTIAL
        val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
        captureMetadata.halAwbConverged = awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
            awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED
        captureMetadata.halAwbLocked =
            result.get(CaptureResult.CONTROL_AWB_LOCK) == true ||
                awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED

        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        captureMetadata.halGainsAvailable = gains != null
        if (gains != null) {
            captureMetadata.halRedGain = gains.red
            captureMetadata.halGreenEvenGain = gains.greenEven
            captureMetadata.halGreenOddGain = gains.greenOdd
            captureMetadata.halBlueGain = gains.blue
        }
        captureMetadata.halMatrixAvailable = copyTransform(
            result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM),
            captureMetadata.halColorMatrix
        )

        val sensor = captureMetadata.sensor
        sensor.clearFramePayload()
        if (metadataDepth == Niv99CaptureMetadataDepth.PROCESSED) {
            // The ISP-domain route needs exposure/timing/AWB metadata above, but must not consume or
            // inherit RAW-only dynamic calibration. Static calibration and its validated fallback
            // noise model were sealed when this camera timeline started.
            processedMeasuredNoiseProfile.valid = false
            if (!provenanceGate.commit(captureMetadata.identity)) {
                return Niv99MetadataStatus.STALE_CAPTURE_METADATA
            }
            return Niv99MetadataStatus.VALID
        }
        val dynamicBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        if (dynamicBlack != null) {
            if (dynamicBlack.size < CHANNEL_COUNT ||
                !copyFiniteNonNegative(dynamicBlack, sensor.dynamicBlackLevelRggb)
            ) {
                return Niv99MetadataStatus.INVALID_DYNAMIC_METADATA
            }
            sensor.dynamicBlackLevelAvailable = true
        }
        val dynamicWhite = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
        if (dynamicWhite != null) {
            if (dynamicWhite <= 0) return Niv99MetadataStatus.INVALID_DYNAMIC_METADATA
            sensor.dynamicWhiteLevel = dynamicWhite.toFloat()
            sensor.dynamicWhiteLevelAvailable = true
        }

        val noiseProfile = result.get(CaptureResult.SENSOR_NOISE_PROFILE)
        if (noiseProfile != null) {
            if (noiseProfile.size < CHANNEL_COUNT) return Niv99MetadataStatus.INVALID_NOISE_CALIBRATION
            for (channel in 0 until CHANNEL_COUNT) {
                val pair = noiseProfile[channel]
                val shot = pair.first?.toFloat()
                val read = pair.second?.toFloat()
                if (shot == null || read == null || !shot.isFinite() || !read.isFinite() ||
                    shot < 0f || read < 0f
                ) {
                    return Niv99MetadataStatus.INVALID_NOISE_CALIBRATION
                }
                sensor.noiseShotRggb[channel] = shot
                sensor.noiseReadRggb[channel] = read
            }
            sensor.noiseCoefficientDomain = Niv99NoiseCoefficientDomain.NORMALIZED_SENSOR_SIGNAL
            sensor.noiseProfileAvailable = true
        }

        val shadingMap = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        if (shadingMap != null) {
            val columns = shadingMap.columnCount
            val rows = shadingMap.rowCount
            val required = Niv99LensShadingMapPolicy.requiredGainCount(
                columns = columns,
                rows = rows,
                capacity = sensor.lensShadingRggb.size
            )
            if (required == Niv99LensShadingMapPolicy.INVALID_COUNT) {
                return Niv99MetadataStatus.LENS_SHADING_CAPACITY_EXCEEDED
            }
            shadingMap.copyGainFactors(sensor.lensShadingRggb, 0)
            if (!Niv99LensShadingMapPolicy.containsOnlyPhysicalGains(
                    gains = sensor.lensShadingRggb,
                    count = required
                )
            ) {
                return Niv99MetadataStatus.INVALID_DYNAMIC_METADATA
            }
            sensor.lensShadingColumns = columns
            sensor.lensShadingRows = rows
            sensor.lensShadingAvailable = true
        }

        val calibrationStatus = Niv99CalibrationAssembler.populate(
            staticMetadata,
            sensor,
            calibration
        )
        if (calibrationStatus != Niv99MetadataStatus.VALID) return calibrationStatus
        measuredNoiseProfile.valid = false
        if (sensor.noiseProfileAvailable) {
            val noiseStatus = Niv99CalibrationAssembler.populateMeasuredNoise(
                sensor,
                measuredNoiseProfile
            )
            if (noiseStatus != Niv99MetadataStatus.VALID) return noiseStatus
        }
        if (!provenanceGate.commit(captureMetadata.identity)) {
            return Niv99MetadataStatus.STALE_CAPTURE_METADATA
        }
        return Niv99MetadataStatus.VALID
    }

    @Synchronized
    fun applyFrameContext(
        output: Niv99FrameContext,
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
    ): Niv99ValidationResult = captureMetadata.applyTo(
        output,
        userControls,
        mode,
        signalDomain,
        detailProfile,
        width,
        height,
        targetFps,
        colorProfileId,
        surfaceGeneration,
        codecGeneration
    )

    @Synchronized
    fun resetTimeline() {
        provenanceGate.reset()
        measuredNoiseProfile.valid = false
        processedMeasuredNoiseProfile.valid = false
    }

    internal fun calibrationFor(metadataDepth: Niv99CaptureMetadataDepth): Niv99SensorCalibration =
        if (metadataDepth == Niv99CaptureMetadataDepth.SENSOR_LINEAR) calibration else processedCalibration

    internal fun measuredNoiseFor(metadataDepth: Niv99CaptureMetadataDepth): Niv99MeasuredNoiseProfile =
        if (metadataDepth == Niv99CaptureMetadataDepth.SENSOR_LINEAR) {
            measuredNoiseProfile
        } else {
            processedMeasuredNoiseProfile
        }

    private fun mapCfa(value: Int?): Niv99CfaPattern = when (value) {
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> Niv99CfaPattern.RGGB
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> Niv99CfaPattern.GRBG
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> Niv99CfaPattern.GBRG
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> Niv99CfaPattern.BGGR
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> Niv99CfaPattern.MONO
        else -> Niv99CfaPattern.UNKNOWN
    }

    private fun copyTransformPair(
        first: ColorSpaceTransform?,
        second: ColorSpaceTransform?,
        firstOutput: FloatArray,
        secondOutput: FloatArray
    ): Boolean = copyTransform(first, firstOutput) && copyTransform(second, secondOutput)

    private fun copyTransform(transform: ColorSpaceTransform?, output: FloatArray): Boolean {
        if (transform == null || output.size < 9) return false
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                val value = transform.getElement(column, row).toFloat()
                if (!value.isFinite()) return false
                output[row * 3 + column] = value
            }
        }
        return true
    }

    private fun copyFiniteNonNegative(source: FloatArray, destination: FloatArray): Boolean {
        for (index in 0 until CHANNEL_COUNT) {
            val value = source[index]
            if (!value.isFinite() || value < 0f) return false
            destination[index] = value
        }
        return true
    }

    companion object {
        private const val CHANNEL_COUNT = Niv99SensorCalibration.CHANNEL_COUNT
        private const val DEFAULT_LENS_SHADING_CAPACITY = 4 * 64 * 64
    }
}
