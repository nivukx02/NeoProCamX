package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.os.Build
import android.util.SizeF
import android.util.Log
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.abs

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * HARDWARE ABSTRACTION LAYER — BEYOND-IMAX HARDWARE OVERRIDES
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Extracted from CameraController to modularize all hardware-level camera
 * configuration. Enforces professional-grade hardware overrides with
 * zero-latency state transitions:
 *
 *   • No-Edge-Mode:   Disable hardware sharpening for RAW/LOG workflows
 *   • Minimal-NR:     Minimal noise reduction to preserve fine detail
 *   • ISP Bypass:     Direct sensor data for cinema debayer pipeline
 *   • OIS/EIS Guard:  Intelligent stabilization policy per recording mode
 *   • Tone-Map Guard: Bypass or override Android ISP tone-mapping
 *
 * The HAL ensures that hardware overrides are applied atomically — no
 * intermediate states are visible to the capture pipeline.
 *
 * Thread safety: All methods are safe for concurrent access.
 * Volatile fields ensure lock-free reads with immediate visibility.
 */

/**
 * Hardware override profile — a complete set of hardware-level camera parameters
 * that are applied atomically to a CaptureRequest.Builder.
 */
data class HardwareOverrideProfile(
    /** Human-readable profile name for diagnostics */
    val name: String,

    /** Edge mode: OFF, FAST, or HIGH_QUALITY */
    val edgeMode: Int,

    /** Noise reduction mode: OFF, MINIMAL, FAST, HIGH_QUALITY, ZERO_SHUTTER_LAG */
    val noiseReductionMode: Int,

    /** Hot pixel correction mode */
    val hotPixelMode: Int,

    /** Lens shading mode */
    val shadingMode: Int,

    /** Tone-map mode */
    val tonemapMode: Int,

    /** Distortion correction mode (null if not supported) */
    val distortionCorrectionMode: Int?,

    /** Chromatic aberration correction mode (null if not supported) */
    val aberrationCorrectionMode: Int?,

    /** Whether to force OIS on */
    val forceOis: Boolean,

    /** Whether to force EIS on */
    val forceEis: Boolean,

    /** Whether to disable OIS explicitly */
    val disableOis: Boolean = false,

    /** Whether to disable EIS explicitly */
    val disableEis: Boolean = false,

    /** Priority level (higher = takes precedence when multiple profiles conflict) */
    val priority: Int = 0,

    /** Estimated processing overhead (0.0 = minimal, 1.0 = maximum) */
    val estimatedOverhead: Float = 0f,

    /** Internal request quality tier; IMAX_CINEMA is a NeoProCamX tier, not a certification claim. */
    val qualityTier: QualityTier = QualityTier.CINEMA,

    /** Capture-mode hint for full-frame/open-gate policies. */
    val captureMode: HalCaptureMode? = null,

    /** Physical optics seed used by shader profiles before live CaptureResult updates arrive. */
    val physicalOpticsModel: CameraPhysicalImagingTraits = CameraPhysicalImagingTraits.DEFAULT,

    /** Public-metadata wavefront seed used by Vulkan deconvolution shaders. */
    val wavefrontCalibration: CameraWavefrontCalibration = CameraWavefrontCalibration.PUBLIC_CAMERA2_BASELINE,

    /** Public Camera2 color-matrix set used for dynamic sensor-to-ACEScg IDT interpolation. */
    val dynamicAcesIdtCalibration: DynamicAcesIdtCalibration = DynamicAcesIdtCalibration.PUBLIC_CAMERA2_REQUIRED,

    /** Maximum allowed LCG/HCG start-of-exposure skew before optical-flow pre-warp fallback. */
    val dualGainSyncGuardNs: Long = 1_000L,

    /** Lock-free telemetry ring buffer capacity for OIS/gyro samples. */
    val motionTelemetryRingBufferCapacity: Int = 0,

    /** Whether this profile requires async native/Vulkan compute for advertised pixel stages. */
    val asyncVulkanComputeRequired: Boolean = false,

    /** Pipelines covered by this HAL profile contract. */
    val pipelineCoverage: Set<HalPipelineCoverage> = emptySet(),

    /** Request public Camera2 OIS samples so the render path can align frames without private APIs. */
    val enableOisTelemetry: Boolean = false,

    /**
     * When true, forces the camera to use the native sensor aspect ratio (4:3 open gate).
     * This locks out user-selected aspect ratios and ensures full-MP sensor readout.
     * Used by BEYOND_IMAX_RAW and BEYOND_IMAX_CINEMA for maximum image quality.
     */
    val enforceOpenGate: Boolean = false,

    /**
     * Section 2.4 mandate: when true and tonemapMode == TONEMAP_MODE_CONTRAST_CURVE,
     * apply() will inject a linear 1:1 RGB tonemap curve so the Android ISP cannot
     * crush the 10/12-bit dynamic range with a vendor gamma curve before our pipeline
     * sees the data. Used by every Total-ISP-Bypass profile.
     */
    val applyLinearTonemapCurve: Boolean = false
) {
    /**
     * Apply this hardware override profile to a CaptureRequest.Builder.
     * This is the SINGLE AUTHORITY for EDGE_MODE, NOISE_REDUCTION_MODE,
     * TONEMAP_MODE, and all ISP-level processing parameters.
     * Returns the number of parameters actually modified.
     */
    fun apply(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics?
    ): Int {
        var modified = 0
        val availability = characteristics?.toIspBypassAvailability()

        CameraHalRequestModePolicy.resolveSupportedMode(edgeMode, availability?.edgeModes)?.let {
            builder[CaptureRequest.EDGE_MODE] = it
            modified++
        }

        CameraHalRequestModePolicy.resolveSupportedMode(
            noiseReductionMode,
            availability?.noiseReductionModes
        )?.let {
            builder[CaptureRequest.NOISE_REDUCTION_MODE] = it
            modified++
        }

        CameraHalRequestModePolicy.resolveSupportedMode(hotPixelMode, availability?.hotPixelModes)?.let {
            builder[CaptureRequest.HOT_PIXEL_MODE] = it
            modified++
        }

        CameraHalRequestModePolicy.resolveSupportedMode(shadingMode, availability?.shadingModes)?.let {
            builder[CaptureRequest.SHADING_MODE] = it
            modified++
        }

        val appliedTonemapMode = CameraHalRequestModePolicy.resolveSupportedMode(
            tonemapMode,
            availability?.tonemapModes
        )
        appliedTonemapMode?.let {
            builder[CaptureRequest.TONEMAP_MODE] = it
            modified++
        }

        // Section 2.4: When the profile demands CONTRAST_CURVE mode and asks for the linear
        // 1:1 curve, build a 65-point identity ramp so the ISP performs zero gamma transform.
        // Without this, CONTRAST_CURVE mode without a curve attached is an invalid request and
        // either gets ignored (vendor falls back) or rejected. Linear curve is the only way to
        // preserve every bit of the 10/12-bit sensor readout.
        if (applyLinearTonemapCurve && appliedTonemapMode == CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE) {
            try {
                val curveSize = 65
                val ramp = FloatArray(curveSize * 2)
                for (i in 0 until curveSize) {
                    val t = i / (curveSize - 1).toFloat()
                    ramp[i * 2] = t
                    ramp[i * 2 + 1] = t
                }
                builder[CaptureRequest.TONEMAP_CURVE] = TonemapCurve(ramp, ramp, ramp)
                modified++
            } catch (e: Exception) {
                // Some legacy hardware refuses arbitrary curves; fall back to FAST
                // rather than ship a broken request.
                builder[CaptureRequest.TONEMAP_MODE] = CaptureRequest.TONEMAP_MODE_FAST
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && distortionCorrectionMode != null) {
            CameraHalRequestModePolicy.resolveSupportedMode(
                requestedMode = distortionCorrectionMode,
                availableModes = availability?.distortionCorrectionModes
            )?.let {
                builder[CaptureRequest.DISTORTION_CORRECTION_MODE] = it
                modified++
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && aberrationCorrectionMode != null) {
            CameraHalRequestModePolicy.resolveSupportedMode(
                requestedMode = aberrationCorrectionMode,
                availableModes = availability?.colorAberrationModes
            )?.let {
                builder[CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE] = it
                modified++
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && enableOisTelemetry) {
            builder[CaptureRequest.STATISTICS_OIS_DATA_MODE] = CameraMetadata.STATISTICS_OIS_DATA_MODE_ON
            modified++
        }

        return modified
    }
}

internal object CameraHalRequestModePolicy {
    fun resolveSupportedMode(
        requestedMode: Int,
        availableModes: List<Int>?
    ): Int? {
        if (availableModes == null || availableModes.isEmpty()) return requestedMode
        return requestedMode.takeIf { it in availableModes }
    }
}

/**
 * Stabilization override decision from the HAL.
 */
data class StabilizationOverride(
    val oisEnabled: Boolean,
    val eisEnabled: Boolean,
    val reason: String
)

enum class HalPipelineCoverage {
    PREVIEW,
    RECORDING,
    NPV10_EXPORT,
    RAW_MONITOR
}

enum class HalCaptureMode {
    IMAX_FULL_FRAME,
    PLANCK_ZENITH_FULL_SENSOR,
    COSMIC_SINGULARITY_FULL_SENSOR,
    OMNIPOTENT_FULL_SENSOR
}

data class CameraWavefrontCalibration(
    val zernikeCoefficients: FloatArray,
    val source: String,
    val truthful: Boolean
) {
    val signature: String
        get() = "wavefront=$source|zernikeTerms=${zernikeCoefficients.size}|truthful=$truthful"

    companion object {
        val PUBLIC_CAMERA2_BASELINE = fromPublicOpticsTraits(
            focalLengthMm = 0f,
            aperture = 0f,
            focusDistanceDiopters = 0f,
            rollingShutterSkewNs = 0L
        )

        fun fromCharacteristics(characteristics: CameraCharacteristics?): CameraWavefrontCalibration {
            if (characteristics == null) return PUBLIC_CAMERA2_BASELINE
            val focalLength = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: 0f
            val aperture = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.firstOrNull() ?: 0f
            val focusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            return fromPublicOpticsTraits(
                focalLengthMm = focalLength,
                aperture = aperture,
                focusDistanceDiopters = focusDistance,
                rollingShutterSkewNs = 0L
            )
        }

        fun fromPublicOpticsTraits(
            focalLengthMm: Float,
            aperture: Float,
            focusDistanceDiopters: Float,
            rollingShutterSkewNs: Long
        ): CameraWavefrontCalibration {
            val safeFocal = focalLengthMm.coerceAtLeast(0f)
            val safeAperture = aperture.coerceAtLeast(0f)
            val safeFocus = focusDistanceDiopters.coerceAtLeast(0f)
            val safeSkew = rollingShutterSkewNs.coerceAtLeast(0L).toFloat() / 1_000_000_000f
            val coefficients = FloatArray(37) { index ->
                when (index) {
                    0 -> 1f
                    1 -> safeFocal / 100f
                    2 -> safeAperture / 32f
                    3 -> safeFocus / 64f
                    4 -> safeSkew.coerceIn(0f, 1f)
                    else -> 0f
                }
            }
            return CameraWavefrontCalibration(
                zernikeCoefficients = coefficients,
                source = "PUBLIC_CAMERA2_OPTICS_DERIVED_BASELINE",
                truthful = true
            )
        }
    }
}

data class DynamicAcesIdtCalibration(
    val transform1: FloatArray,
    val transform2: FloatArray,
    val forward1: FloatArray,
    val forward2: FloatArray,
    val calibration1: FloatArray,
    val calibration2: FloatArray,
    val illuminant1Kelvin: Float,
    val illuminant2Kelvin: Float,
    val usesPublicCamera2Matrices: Boolean,
    val source: String
) {
    val signature: String
        get() = "idt=$source|SENSOR_COLOR_TRANSFORM1|SENSOR_FORWARD_MATRIX1|SENSOR_CALIBRATION_TRANSFORM1|public=$usesPublicCamera2Matrices"

    fun interpolatedSensorToAcesCg(whiteBalanceKelvin: Float): FloatArray {
        val low = illuminant1Kelvin.coerceAtMost(illuminant2Kelvin)
        val high = illuminant1Kelvin.coerceAtLeast(illuminant2Kelvin)
        val t = if (abs(high - low) < 1e-3f) {
            0f
        } else {
            ((whiteBalanceKelvin.coerceIn(low, high) - low) / (high - low)).coerceIn(0f, 1f)
        }
        return FloatArray(9) { index -> transform1[index] * (1f - t) + transform2[index] * t }
    }

    companion object {
        private val IDENTITY_3X3 = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )

        val PUBLIC_CAMERA2_REQUIRED = fromPublicMatrices(
            transform1 = IDENTITY_3X3,
            transform2 = IDENTITY_3X3,
            forward1 = IDENTITY_3X3,
            forward2 = IDENTITY_3X3,
            calibration1 = IDENTITY_3X3,
            calibration2 = IDENTITY_3X3,
            illuminant1Kelvin = 2_850f,
            illuminant2Kelvin = 6_500f,
            source = "PUBLIC_CAMERA2_MATRIX_KEYS_REQUIRED"
        )

        val UNAVAILABLE = fromPublicMatrices(
            transform1 = IDENTITY_3X3,
            transform2 = IDENTITY_3X3,
            forward1 = IDENTITY_3X3,
            forward2 = IDENTITY_3X3,
            calibration1 = IDENTITY_3X3,
            calibration2 = IDENTITY_3X3,
            illuminant1Kelvin = 2_850f,
            illuminant2Kelvin = 6_500f,
            usesPublicCamera2Matrices = false,
            source = "PUBLIC_CAMERA2_COLOR_MATRICES_UNAVAILABLE"
        )

        fun fromCharacteristics(characteristics: CameraCharacteristics?): DynamicAcesIdtCalibration {
            if (characteristics == null) return UNAVAILABLE
            val transform1 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1).toMatrixArray()
            val transform2 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2).toMatrixArray()
            val forward1 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1).toMatrixArray()
            val forward2 = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2).toMatrixArray()
            val calibration1 = characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1).toMatrixArray()
            val calibration2 = characteristics.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2).toMatrixArray()
            val hasPublicMatrices = listOf(transform1, transform2, forward1, forward2, calibration1, calibration2).all { it != null }
            if (!hasPublicMatrices) return UNAVAILABLE
            return fromPublicMatrices(
                transform1 = transform1 ?: IDENTITY_3X3,
                transform2 = transform2 ?: IDENTITY_3X3,
                forward1 = forward1 ?: IDENTITY_3X3,
                forward2 = forward2 ?: IDENTITY_3X3,
                calibration1 = calibration1 ?: IDENTITY_3X3,
                calibration2 = calibration2 ?: IDENTITY_3X3,
                illuminant1Kelvin = illuminantKelvin(characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1), 2_850f),
                illuminant2Kelvin = illuminantKelvin(characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2), 6_500f),
                source = "PUBLIC_CAMERA2_SENSOR_MATRIX_INTERPOLATION"
            )
        }

        fun fromPublicMatrices(
            transform1: FloatArray,
            transform2: FloatArray,
            forward1: FloatArray,
            forward2: FloatArray,
            calibration1: FloatArray,
            calibration2: FloatArray,
            illuminant1Kelvin: Float,
            illuminant2Kelvin: Float,
            usesPublicCamera2Matrices: Boolean = true,
            source: String = "PUBLIC_CAMERA2_SENSOR_MATRIX_INTERPOLATION"
        ): DynamicAcesIdtCalibration =
            DynamicAcesIdtCalibration(
                transform1 = transform1.normalized3x3(),
                transform2 = transform2.normalized3x3(),
                forward1 = forward1.normalized3x3(),
                forward2 = forward2.normalized3x3(),
                calibration1 = calibration1.normalized3x3(),
                calibration2 = calibration2.normalized3x3(),
                illuminant1Kelvin = illuminant1Kelvin.coerceIn(1_500f, 20_000f),
                illuminant2Kelvin = illuminant2Kelvin.coerceIn(1_500f, 20_000f),
                usesPublicCamera2Matrices = usesPublicCamera2Matrices,
                source = source
            )

        fun estimateWhiteBalanceKelvin(gains: RggbChannelVector?): Float? {
            if (gains == null) return null
            val redBlueRatio = (gains.red / gains.blue.coerceAtLeast(0.001f)).coerceIn(0.25f, 4.0f)
            val normalizedWarmth = ((redBlueRatio - 0.25f) / 3.75f).coerceIn(0f, 1f)
            return (7_000f - normalizedWarmth * 5_000f).coerceIn(2_000f, 12_000f)
        }

        private fun ColorSpaceTransform?.toMatrixArray(): FloatArray? =
            this?.let { transform ->
                FloatArray(9) { index ->
                    val row = index / 3
                    val column = index % 3
                    transform.getElement(column, row).toFloat()
                }
            }

        private fun FloatArray.normalized3x3(): FloatArray =
            if (size == 9) copyOf() else IDENTITY_3X3.copyOf()

        private fun illuminantKelvin(illuminant: Number?, fallback: Float): Float =
            when (illuminant?.toInt()) {
                1 -> 6_500f
                2 -> 7_500f
                3 -> 5_000f
                4 -> 5_500f
                9 -> 2_856f
                10 -> 4_150f
                11 -> 5_000f
                12 -> 6_500f
                13 -> 7_500f
                14 -> 6_500f
                15 -> 5_500f
                17 -> 2_856f
                18 -> 4_000f
                19 -> 4_500f
                20 -> 6_500f
                21 -> 6_500f
                22 -> 6_500f
                23 -> 5_000f
                24 -> 5_000f
                else -> fallback
            }
    }
}

data class CameraPhysicalImagingTraits(
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val focalLengthMm: Float,
    val aperture: Float,
    val focusDistanceDiopters: Float,
    val rollingShutterSkewNs: Long,
    val whiteBalanceKelvin: Float = 6_500f,
    val wavefrontCalibration: CameraWavefrontCalibration = CameraWavefrontCalibration.PUBLIC_CAMERA2_BASELINE,
    val dynamicAcesIdtCalibration: DynamicAcesIdtCalibration = DynamicAcesIdtCalibration.UNAVAILABLE
) {
    fun updateFromCaptureResult(
        result: CaptureResult?,
        colorCorrectionGains: RggbChannelVector? = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)
    ): CameraPhysicalImagingTraits {
        if (result == null) return this
        val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: focusDistanceDiopters
        val rollingSkew = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: rollingShutterSkewNs
        return copy(
            focusDistanceDiopters = focusDistance,
            rollingShutterSkewNs = rollingSkew,
            whiteBalanceKelvin = DynamicAcesIdtCalibration.estimateWhiteBalanceKelvin(colorCorrectionGains) ?: whiteBalanceKelvin,
            wavefrontCalibration = CameraWavefrontCalibration.fromPublicOpticsTraits(
                focalLengthMm = focalLengthMm,
                aperture = aperture,
                focusDistanceDiopters = focusDistance,
                rollingShutterSkewNs = rollingSkew
            )
        )
    }

    companion object {
        val DEFAULT = CameraPhysicalImagingTraits(
            sensorWidthMm = 0f,
            sensorHeightMm = 0f,
            focalLengthMm = 0f,
            aperture = 0f,
            focusDistanceDiopters = 0f,
            rollingShutterSkewNs = 0L,
            whiteBalanceKelvin = 6_500f,
            wavefrontCalibration = CameraWavefrontCalibration.PUBLIC_CAMERA2_BASELINE,
            dynamicAcesIdtCalibration = DynamicAcesIdtCalibration.UNAVAILABLE
        )

        fun fromCharacteristics(characteristics: CameraCharacteristics?): CameraPhysicalImagingTraits {
            if (characteristics == null) return DEFAULT
            val physicalSize: SizeF? = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            return CameraPhysicalImagingTraits(
                sensorWidthMm = physicalSize?.width ?: 0f,
                sensorHeightMm = physicalSize?.height ?: 0f,
                focalLengthMm = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull() ?: 0f,
                aperture = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    ?.firstOrNull() ?: 0f,
                focusDistanceDiopters = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                rollingShutterSkewNs = 0L,
                whiteBalanceKelvin = 6_500f,
                wavefrontCalibration = CameraWavefrontCalibration.fromCharacteristics(characteristics),
                dynamicAcesIdtCalibration = DynamicAcesIdtCalibration.fromCharacteristics(characteristics)
            )
        }

        fun updateFromCaptureResult(
            base: CameraPhysicalImagingTraits,
            result: CaptureResult?,
            colorCorrectionGains: RggbChannelVector? = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        ): CameraPhysicalImagingTraits = base.updateFromCaptureResult(result, colorCorrectionGains)
    }
}

data class CameraMotionTelemetry(
    val timestampNs: Long,
    val oisShiftXPixels: Float,
    val oisShiftYPixels: Float,
    val gyroXRadPerSec: Float,
    val gyroYRadPerSec: Float,
    val gyroZRadPerSec: Float,
    /**
     * Which capture source last stamped this sample's [timestampNs]. The motion ring multiplexes two
     * clock domains: [Source.GYRO] samples carry the `TYPE_GYROSCOPE` `SensorEvent` clock, while
     * [Source.OIS_RESULT] samples carry the camera `SENSOR_TIMESTAMP` clock (`STATISTICS_OIS_SAMPLES`).
     * Orientation integration is only valid over a SINGLE clock domain, so a reconciler must consume
     * only [Source.GYRO] samples (see [CameraMotionTelemetryRingBuffer.snapshotGyroOldestFirst]).
     *
     * Defaults to [Source.UNKNOWN] and is read by NO live pixel path, so the existing numeric capture
     * (timestamps, OIS shift, angular velocity) is bit-for-bit unchanged — this tag is pure metadata
     * that makes the already-captured-but-unconsumed stream self-describing for the dormant consumers.
     */
    val source: Source = Source.UNKNOWN
) {
    /** The capture path that produced a telemetry sample (and therefore its clock domain). */
    enum class Source { UNKNOWN, GYRO, OIS_RESULT }

    companion object {
        val DEFAULT = CameraMotionTelemetry(
            timestampNs = 0L,
            oisShiftXPixels = 0f,
            oisShiftYPixels = 0f,
            gyroXRadPerSec = 0f,
            gyroYRadPerSec = 0f,
            gyroZRadPerSec = 0f,
            source = Source.UNKNOWN
        )

        fun fromCaptureResult(
            base: CameraMotionTelemetry,
            result: CaptureResult?
        ): CameraMotionTelemetry {
            if (result == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return base
            val oisSamples = result.get(CaptureResult.STATISTICS_OIS_SAMPLES)
            val latest = oisSamples?.lastOrNull() ?: return base
            return base.copy(
                timestampNs = latest.timestamp,
                oisShiftXPixels = latest.xshift,
                oisShiftYPixels = latest.yshift,
                source = Source.OIS_RESULT
            )
        }

        fun fromGyroscope(
            base: CameraMotionTelemetry,
            timestampNs: Long,
            gyroXRadPerSec: Float,
            gyroYRadPerSec: Float,
            gyroZRadPerSec: Float
        ): CameraMotionTelemetry =
            base.copy(
                timestampNs = timestampNs,
                gyroXRadPerSec = gyroXRadPerSec,
                gyroYRadPerSec = gyroYRadPerSec,
                gyroZRadPerSec = gyroZRadPerSec,
                source = Source.GYRO
            )
    }
}

class CameraMotionTelemetryRingBuffer(capacity: Int) {
    private val safeCapacity = capacity.coerceAtLeast(1)
    private val samples = AtomicReferenceArray<CameraMotionTelemetry>(safeCapacity)
    private val writeIndex = AtomicLong(0L)

    val signature: String
        get() = "lockFreeRingBuffer=true|capacity=$safeCapacity|source=STATISTICS_OIS_SAMPLES+Sensor.TYPE_GYROSCOPE"

    fun push(sample: CameraMotionTelemetry) {
        val index = writeIndex.getAndIncrement()
        samples.set((index % safeCapacity).toInt(), sample)
    }

    fun latest(): CameraMotionTelemetry? {
        val latestIndex = writeIndex.get() - 1L
        if (latestIndex < 0L) return null
        return samples.get((latestIndex % safeCapacity).toInt())
    }

    fun snapshotNewestFirst(maxSamples: Int): List<CameraMotionTelemetry> {
        val end = writeIndex.get() - 1L
        if (end < 0L || maxSamples <= 0) return emptyList()
        val count = minOf(maxSamples, safeCapacity, end.toInt() + 1)
        return List(count) { offset ->
            samples.get(((end - offset) % safeCapacity).toInt())
        }.filterNotNull()
    }

    /**
     * Oldest-first snapshot of up to [maxSamples] most-recent GYRO-domain samples only — the order and
     * single clock domain [com.neoproc.neoproccamx.stabilization.GyroOrientationIntegrator] requires.
     * The ring multiplexes the gyro clock and the camera SENSOR_TIMESTAMP (OIS) clock; orientation
     * integration is only valid within ONE clock domain, so a reconciler consumes THIS gyro-filtered
     * view, never the raw mixed ring. Pure read.
     */
    fun snapshotGyroOldestFirst(maxSamples: Int): List<CameraMotionTelemetry> =
        snapshotNewestFirst(maxSamples).asReversed()
            .filter { it.source == CameraMotionTelemetry.Source.GYRO }

    /**
     * Most-recent OIS-domain sample (carries the live `STATISTICS_OIS_SAMPLES` displacement) within the
     * last [maxScan] samples, or null if none. Used to subtract OIS-absorbed motion in OIS/EIS fusion.
     */
    fun latestOisSample(maxScan: Int = safeCapacity): CameraMotionTelemetry? =
        snapshotNewestFirst(maxScan).firstOrNull { it.source == CameraMotionTelemetry.Source.OIS_RESULT }
}

/**
 * The Hardware Abstraction Layer for camera hardware overrides.
 */
object HardwareAbstractionLayer {

    private const val TAG = "HardwareAbstraction"
    private val FULL_PIPELINE_COVERAGE = setOf(
        HalPipelineCoverage.PREVIEW,
        HalPipelineCoverage.RECORDING,
        HalPipelineCoverage.NPV10_EXPORT,
        HalPipelineCoverage.RAW_MONITOR
    )
    private val PROCESSED_VIDEO_PIPELINE_COVERAGE = setOf(
        HalPipelineCoverage.PREVIEW,
        HalPipelineCoverage.RECORDING,
        HalPipelineCoverage.NPV10_EXPORT
    )
    private val ELITE_WAVEFRONT_CALIBRATION = CameraWavefrontCalibration.PUBLIC_CAMERA2_BASELINE
    private val ELITE_ACES_IDT_CALIBRATION = DynamicAcesIdtCalibration.PUBLIC_CAMERA2_REQUIRED

    val ZENITH_COSMIC_PLANCK_SIGNATURE: String
        get() = listOf(
            "standard=ZENITH_COSMIC_PLANCK",
            "publicCamera2=true",
            "physicalLimitClaim=false",
            "profiles=PLANCK_ZENITH,COSMIC_SINGULARITY,OMNIPOTENT_CINEMA_ENGINE",
            "preview=ZENITH_COSMIC_PLANCK_PREVIEW_PIPELINE",
            "recording=ZENITH_COSMIC_PLANCK_DENOISE_PIPELINE",
            "wavefront=ZENITH_COSMIC_PLANCK_WAVEFRONT_PIPELINE",
            "warp=ZENITH_COSMIC_PLANCK_WARP_PIPELINE",
            "dualGain=ZENITH_COSMIC_PLANCK_DUAL_GAIN_PIPELINE",
            "dualGainGuardNs=${DualGainHdrFusionPolicy.MAX_TIMESTAMP_SKEW_NS}",
            "motionTelemetry=STATISTICS_OIS_SAMPLES+Sensor.TYPE_GYROSCOPE",
            "dynamicAcesIdt=PUBLIC_CAMERA2_SENSOR_MATRIX_INTERPOLATION",
            "npv10=ZENITH_COSMIC_PLANCK_NPV10_EXPORT_PIPELINE",
            "processedSdrHal=OMNIPOTENT_PUBLIC_CAMERA2_ISP_PIPELINE",
            "openGate=${PLANCK_ZENITH.enforceOpenGate && COSMIC_SINGULARITY.enforceOpenGate}",
            "rawBypass=${SUPREME_FIDELITY_BYPASS.name}",
            "vendor=false"
        ).joinToString(separator = "|")

    fun strictBypassDistortionCorrectionMode(androidApiLevel: Int = Build.VERSION.SDK_INT): Int? =
        if (androidApiLevel >= Build.VERSION_CODES.P) {
            CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
        } else {
            null
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // BEYOND-IMAX HARDWARE OVERRIDE PROFILES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Beyond-IMAX RAW profile — strict low-processing request (Section 2.4 mandate).
     *
     * SECTION 2.4 ESCALATION: every exposed ISP stage is requested off; tonemap is
     * requested as a linear 1:1 ramp. Camera2 cannot prove that vendor-private ISP
     * stages are fully bypassed, so this profile treats low-processing capture as a
     * capability request that must still be validated per device/session.
     *
     * Sharpness and noise control happen in our pipeline (Section 17), not in the HAL.
     */
    val BEYOND_IMAX_RAW = HardwareOverrideProfile(
        name = "BEYOND_IMAX_RAW",
        edgeMode = CaptureRequest.EDGE_MODE_OFF,
        noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_OFF,
        hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_OFF,
        shadingMode = CaptureRequest.SHADING_MODE_OFF,
        tonemapMode = CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE,
        distortionCorrectionMode = strictBypassDistortionCorrectionMode(),
        aberrationCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF else null,
        forceOis = false,
        forceEis = false,
        disableOis = true,
        disableEis = true,
        priority = 100,
        estimatedOverhead = 0.95f,
        enforceOpenGate = true,
        applyLinearTonemapCurve = true
    )

    /**
     * Consumer Auto Video profile.
     *
     * Requests the public Camera2 high-quality ISP controls for a ready-to-share
     * video result. Whether a device implements those hints is verified through
     * recording evidence, not assumed from the request alone.
     */
    val CONSUMER_AUTO_VIDEO = HardwareOverrideProfile(
        name = "CONSUMER_AUTO_VIDEO",
        edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
        noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
        shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
        tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
        distortionCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        } else null,
        aberrationCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        } else null,
        forceOis = false,
        forceEis = false,
        priority = 125,
        estimatedOverhead = 1.0f,
        qualityTier = QualityTier.OMNIPOTENT_CINEMA_ENGINE,
        physicalOpticsModel = CameraPhysicalImagingTraits.DEFAULT.copy(
            wavefrontCalibration = ELITE_WAVEFRONT_CALIBRATION,
            dynamicAcesIdtCalibration = ELITE_ACES_IDT_CALIBRATION
        ),
        wavefrontCalibration = ELITE_WAVEFRONT_CALIBRATION,
        dynamicAcesIdtCalibration = ELITE_ACES_IDT_CALIBRATION,
        dualGainSyncGuardNs = 1_000L,
        motionTelemetryRingBufferCapacity = 1024,
        asyncVulkanComputeRequired = true,
        pipelineCoverage = PROCESSED_VIDEO_PIPELINE_COVERAGE,
        enableOisTelemetry = true,
        enforceOpenGate = false,
        applyLinearTonemapCurve = false
    )

    /**
     * Cinema/Pro encoded profile.
     *
     * Processed Cinema/Pro SDR profile.
     *
     * Requests every public Camera2 processed-video quality stage at HIGH_QUALITY
     * so SDR recording does not enter the encoder with elevated noise, grain, or
     * weak edge detail. RAW remains the only path that requests ISP-off behavior.
     */
    val BEYOND_IMAX_CINEMA = HardwareOverrideProfile(
        name = "BEYOND_IMAX_CINEMA",
        edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
        noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
        shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
        tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
        distortionCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        } else null,
        aberrationCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        } else null,
        forceOis = true,
        forceEis = false,
        priority = 135,
        estimatedOverhead = 1.0f,
        qualityTier = QualityTier.OMNIPOTENT_CINEMA_ENGINE,
        physicalOpticsModel = CameraPhysicalImagingTraits.DEFAULT.copy(
            wavefrontCalibration = ELITE_WAVEFRONT_CALIBRATION,
            dynamicAcesIdtCalibration = ELITE_ACES_IDT_CALIBRATION
        ),
        wavefrontCalibration = ELITE_WAVEFRONT_CALIBRATION,
        dynamicAcesIdtCalibration = ELITE_ACES_IDT_CALIBRATION,
        dualGainSyncGuardNs = 1_000L,
        motionTelemetryRingBufferCapacity = 1024,
        asyncVulkanComputeRequired = true,
        pipelineCoverage = PROCESSED_VIDEO_PIPELINE_COVERAGE,
        enableOisTelemetry = true,
        enforceOpenGate = false,
        applyLinearTonemapCurve = false
    )

    val IMAX_FULL_FRAME = HalCaptureMode.IMAX_FULL_FRAME
    val PLANCK_ZENITH_FULL_SENSOR = HalCaptureMode.PLANCK_ZENITH_FULL_SENSOR
    val COSMIC_SINGULARITY_FULL_SENSOR = HalCaptureMode.COSMIC_SINGULARITY_FULL_SENSOR
    val OMNIPOTENT_FULL_SENSOR = HalCaptureMode.OMNIPOTENT_FULL_SENSOR

    val IMAX_CINEMA = HardwareOverrideProfile(
        name = "IMAX_CINEMA",
        edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
        noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
        shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
        tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
        distortionCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        } else null,
        aberrationCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        } else null,
        forceOis = true,
        forceEis = false,
        priority = 120,
        estimatedOverhead = 0.98f,
        qualityTier = QualityTier.IMAX_CINEMA,
        captureMode = IMAX_FULL_FRAME,
        enforceOpenGate = true,
        applyLinearTonemapCurve = false
    )

    val COSMIC_SINGULARITY = HardwareOverrideProfile(
        name = "COSMIC_SINGULARITY",
        edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
        noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
        shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
        tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
        distortionCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        } else null,
        aberrationCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        } else null,
        forceOis = true,
        forceEis = false,
        priority = 140,
        estimatedOverhead = 1.0f,
        qualityTier = QualityTier.COSMIC_SINGULARITY,
        captureMode = COSMIC_SINGULARITY_FULL_SENSOR,
        physicalOpticsModel = CameraPhysicalImagingTraits.DEFAULT.copy(
            wavefrontCalibration = ELITE_WAVEFRONT_CALIBRATION,
            dynamicAcesIdtCalibration = ELITE_ACES_IDT_CALIBRATION
        ),
        wavefrontCalibration = ELITE_WAVEFRONT_CALIBRATION,
        dynamicAcesIdtCalibration = ELITE_ACES_IDT_CALIBRATION,
        dualGainSyncGuardNs = 1_000L,
        motionTelemetryRingBufferCapacity = 1024,
        asyncVulkanComputeRequired = true,
        pipelineCoverage = FULL_PIPELINE_COVERAGE,
        enableOisTelemetry = true,
        enforceOpenGate = true,
        applyLinearTonemapCurve = false
    )

    val PLANCK_ZENITH = HardwareOverrideProfile(
        name = "PLANCK_ZENITH",
        edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
        noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
        shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
        tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
        distortionCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        } else null,
        aberrationCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        } else null,
        forceOis = true,
        forceEis = false,
        priority = 130,
        estimatedOverhead = 1.0f,
        qualityTier = QualityTier.PLANCK_ZENITH,
        captureMode = PLANCK_ZENITH_FULL_SENSOR,
        physicalOpticsModel = CameraPhysicalImagingTraits.DEFAULT.copy(
            wavefrontCalibration = ELITE_WAVEFRONT_CALIBRATION,
            dynamicAcesIdtCalibration = ELITE_ACES_IDT_CALIBRATION
        ),
        wavefrontCalibration = ELITE_WAVEFRONT_CALIBRATION,
        dynamicAcesIdtCalibration = ELITE_ACES_IDT_CALIBRATION,
        dualGainSyncGuardNs = 1_000L,
        motionTelemetryRingBufferCapacity = 1024,
        asyncVulkanComputeRequired = true,
        pipelineCoverage = FULL_PIPELINE_COVERAGE,
        enableOisTelemetry = true,
        enforceOpenGate = true,
        applyLinearTonemapCurve = false
    )

    val OMNIPOTENT_CINEMA_ENGINE = HardwareOverrideProfile(
        name = "OMNIPOTENT_CINEMA_ENGINE",
        edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
        noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
        shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
        tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
        distortionCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        } else null,
        aberrationCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        } else null,
        forceOis = true,
        forceEis = false,
        priority = 150,
        estimatedOverhead = 1.0f,
        qualityTier = QualityTier.OMNIPOTENT_CINEMA_ENGINE,
        captureMode = OMNIPOTENT_FULL_SENSOR,
        physicalOpticsModel = CameraPhysicalImagingTraits.DEFAULT.copy(
            wavefrontCalibration = ELITE_WAVEFRONT_CALIBRATION,
            dynamicAcesIdtCalibration = ELITE_ACES_IDT_CALIBRATION
        ),
        wavefrontCalibration = ELITE_WAVEFRONT_CALIBRATION,
        dynamicAcesIdtCalibration = ELITE_ACES_IDT_CALIBRATION,
        dualGainSyncGuardNs = 1_000L,
        motionTelemetryRingBufferCapacity = 1024,
        asyncVulkanComputeRequired = true,
        pipelineCoverage = FULL_PIPELINE_COVERAGE,
        enableOisTelemetry = true,
        enforceOpenGate = true,
        applyLinearTonemapCurve = false
    )

    /**
     * N-Log profile using the same elevated processed-video request as Cinema.
     * RAW bypass remains separate; Log SDR stays on the high-quality Camera2 ISP
     * stack before the app-owned render path applies its look.
     */
    val BEYOND_IMAX_LOG = HardwareOverrideProfile(
        name = "BEYOND_IMAX_LOG",
        edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
        noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
        shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
        tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
        distortionCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        } else null,
        aberrationCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        } else null,
        forceOis = true,
        forceEis = false,
        priority = 136,
        estimatedOverhead = 1.0f,
        qualityTier = QualityTier.OMNIPOTENT_CINEMA_ENGINE,
        physicalOpticsModel = CameraPhysicalImagingTraits.DEFAULT.copy(
            wavefrontCalibration = ELITE_WAVEFRONT_CALIBRATION,
            dynamicAcesIdtCalibration = ELITE_ACES_IDT_CALIBRATION
        ),
        wavefrontCalibration = ELITE_WAVEFRONT_CALIBRATION,
        dynamicAcesIdtCalibration = ELITE_ACES_IDT_CALIBRATION,
        dualGainSyncGuardNs = 1_000L,
        motionTelemetryRingBufferCapacity = 1024,
        asyncVulkanComputeRequired = true,
        pipelineCoverage = PROCESSED_VIDEO_PIPELINE_COVERAGE,
        enableOisTelemetry = true
    )

    /**
     * SUPREME FIDELITY BYPASS — strict low-processing request for RAW.
     * Processed SDR/Log/Pro video uses the elevated BEYOND_IMAX_CINEMA or
     * BEYOND_IMAX_LOG public Camera2 quality stack instead.
     * 
     * - EDGE_MODE_OFF: Disable edge enhancement (preserves RAW detail)
     * - NOISE_REDUCTION_MODE_OFF: Disable ISP NR (custom Laplacian pass in pipeline)
     * - HOT_PIXEL_MODE_OFF: Request no hot-pixel cleanup from the HAL
     * - SHADING_MODE_OFF: Request no lens-shading correction from the HAL
     * - COLOR_CORRECTION_ABERRATION_MODE_OFF: Request no CA correction from the HAL
     * - TONEMAP_MODE_CONTRAST_CURVE: Request a linear 1:1 app-visible curve
     * 
     * Actual fidelity still depends on device capability, HAL compliance, and session state.
     */
    val SUPREME_FIDELITY_BYPASS = HardwareOverrideProfile(
        name = "SUPREME_FIDELITY_BYPASS",
        edgeMode = CaptureRequest.EDGE_MODE_OFF,
        noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_OFF,
        hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_OFF,
        shadingMode = CaptureRequest.SHADING_MODE_OFF,
        tonemapMode = CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE,
        applyLinearTonemapCurve = true,
        distortionCorrectionMode = strictBypassDistortionCorrectionMode(),
        aberrationCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
        } else null,
        forceOis = false,
        forceEis = false,
        disableOis = true,
        disableEis = true,
        priority = 110,  // Highest priority
        estimatedOverhead = 0.1f,  // Minimal processing (direct sensor)
        enforceOpenGate = true
    )

    /**
     * ELITE CINEMA HDR profile — maximum ISP fidelity for HDR10+/HLG/PQ workflows.
     * Forces every ISP stage to HIGH_QUALITY with OIS lock for handheld cinema.
     */
    val ELITE_CINEMA_HDR = HardwareOverrideProfile(
        name = "ELITE_CINEMA_HDR",
        edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
        noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
        shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
        tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
        distortionCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        } else null,
        aberrationCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        } else null,
        forceOis = true,
        forceEis = false,
        priority = 92,
        estimatedOverhead = 0.92f,
        enforceOpenGate = false
    )

    /**
     * ELITE CINEMA STILL profile — maximum single-frame fidelity for photo capture.
     * Every ISP stage at peak quality; OIS forced for handheld stability.
     */
    val ELITE_CINEMA_STILL = HardwareOverrideProfile(
        name = "ELITE_CINEMA_STILL",
        edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
        noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
        shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
        tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
        distortionCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        } else null,
        aberrationCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        } else null,
        forceOis = true,
        forceEis = false,
        priority = 88,
        estimatedOverhead = 0.95f,
        enforceOpenGate = true
    )

    /**
     * ELITE CINEMA HIGHSPEED profile — cinema-grade quality even at 60/120fps.
     * Maintains HIGH_QUALITY edge/NR/hotpixel; only shading/distortion go FAST
     * to keep the frame budget achievable at high frame rates.
     */
    val ELITE_CINEMA_HIGHSPEED = HardwareOverrideProfile(
        name = "ELITE_CINEMA_HIGHSPEED",
        edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
        noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
        shadingMode = CaptureRequest.SHADING_MODE_FAST,
        tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
        distortionCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            CaptureRequest.DISTORTION_CORRECTION_MODE_FAST
        } else null,
        aberrationCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        } else null,
        forceOis = true,
        forceEis = false,
        priority = 80,
        estimatedOverhead = 0.78f
    )

    /**
     * Balanced preview profile — ELITE CINEMA GRADE.
     * Full HIGH_QUALITY ISP across all stages with forced OIS.
     * Cinema cameras never compromise preview quality.
     */
    val BALANCED_PREVIEW = HardwareOverrideProfile(
        name = "BALANCED_PREVIEW",
        edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
        noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
        shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
        tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
        distortionCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        } else null,
        aberrationCorrectionMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
        } else null,
        forceOis = true,
        forceEis = false,
        priority = 70,
        estimatedOverhead = 0.90f
    )

    // ── State transition tracking for zero-latency enforcement ────────────────
    @Volatile private var activeProfileName: String = "NONE"
    @Volatile private var lastProfileTransitionMs: Long = 0L
    private val profileTransitionCount = AtomicLong(0L)

    // ── Per-frame cache: pre-resolved profile + stabilization ─────────────────
    // Eliminates redundant calculations in the hot capture loop.
    @Volatile var cachedProfile: HardwareOverrideProfile = BALANCED_PREVIEW
        private set
    @Volatile var cachedStabilization: StabilizationOverride = StabilizationOverride(
        oisEnabled = false, eisEnabled = false, reason = "NONE"
    )
        private set
    @Volatile private var cacheInputHash: Long = 0L

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    /**
     * Recovery profile — ELITE CINEMA GRADE thermal management.
     * Cinema cameras maintain full HIGH_QUALITY ISP even under thermal pressure.
     * Only distortion correction is dropped; edge/NR/hotpixel/shading/tonemap
     * stay at maximum. OIS stays active to preserve handheld stability.
     */
    val THERMAL_RECOVERY = HardwareOverrideProfile(
        name = "THERMAL_RECOVERY",
        edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
        noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
        shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
        tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
        distortionCorrectionMode = null,
        aberrationCorrectionMode = null,
        forceOis = true,
        forceEis = false,
        priority = 10,
        estimatedOverhead = 0.75f
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // PROFILE RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Resolve the optimal hardware override profile for the current state.
     *
     * The resolution follows a strict priority hierarchy:
     * 1. RAW capture → SUPREME_FIDELITY_BYPASS (public Camera2 low-processing request)
     * 2. LOG capture → BEYOND_IMAX_LOG (high-quality processed SDR request)
     * 3. Cinema/Pro capture → BEYOND_IMAX_CINEMA (high-quality processed SDR request)
     * 4. Consumer Auto Video → CONSUMER_AUTO_VIDEO (device ISP cleanup)
     * 5. Preview fallback → BALANCED_PREVIEW
     *
     * In strict high-end mode, all profiles are escalated to their
     * maximum capability settings.
     */
    /**
     * Resolve and cache the optimal hardware override profile.
     * This is the SINGLE AUTHORITY for all ISP-level processing.
     * No fallback to CameraCineProcessingPolicy — HAL owns this entirely.
     *
     * Call this once per state change (not per-frame). Use [cachedProfile]
     * for zero-cost per-frame access in the capture loop.
     */
    fun resolveProfile(
        isRecording: Boolean,
        isRawCapture: Boolean,
        isLogMode: Boolean,
        isCinemaFidelity: Boolean,
        noiseReductionEnabled: Boolean,
        isHdrMode: Boolean = false,
        isStillCapture: Boolean = false,
        isHighSpeed: Boolean = false,
        isConsumerMode: Boolean = false
    ): HardwareOverrideProfile {
        // ── Fast cache check: avoid redundant resolution ──────────────────
        val inputHash = computeInputHash(
            isRecording = isRecording,
            isRawCapture = isRawCapture,
            isLogMode = isLogMode,
            isCinemaFidelity = isCinemaFidelity,
            noiseReductionEnabled = noiseReductionEnabled,
            isHdrMode = isHdrMode,
            isStillCapture = isStillCapture,
            isHighSpeed = isHighSpeed,
            isConsumerMode = isConsumerMode
        )
        if (inputHash == cacheInputHash) return cachedProfile

        val strictHighEnd = strictHighEndEnabled()

        // ═══════════════════════════════════════════════════════════════════
        // ELITE CINEMA CAMERA ROUTING — strict priority hierarchy
        // ═══════════════════════════════════════════════════════════════════
        // 1. RAW capture         → SUPREME_FIDELITY_BYPASS (total public ISP bypass request)
        // 2. LOG mode            → BEYOND_IMAX_LOG (high-quality processed SDR)
        // 3. HDR mode            → ELITE_CINEMA_HDR (max HDR ISP fidelity)
        // 4. Still capture       → ELITE_CINEMA_STILL (max single-frame quality)
        // 5. Cinema/Pro          → BEYOND_IMAX_CINEMA (high-quality processed SDR)
        // 6. Auto Video          → CONSUMER_AUTO_VIDEO (device ISP cleanup)
        // 7. High-speed fallback → ELITE_CINEMA_HIGHSPEED (quality at speed)
        // 8. Unclassified record → CONSUMER_AUTO_VIDEO (do not silently enter Pro)
        // 9. Strict preview      → BEYOND_IMAX_CINEMA
        // 10. Preview fallback   → BALANCED_PREVIEW
        // ═══════════════════════════════════════════════════════════════════
        val profile = when {
            isRawCapture -> SUPREME_FIDELITY_BYPASS
            isLogMode -> BEYOND_IMAX_LOG
            isStillCapture -> ELITE_CINEMA_STILL
            isHdrMode -> ELITE_CINEMA_HDR
            isCinemaFidelity -> BEYOND_IMAX_CINEMA
            isConsumerMode -> CONSUMER_AUTO_VIDEO
            isHighSpeed && isRecording -> ELITE_CINEMA_HIGHSPEED
            isRecording -> CONSUMER_AUTO_VIDEO
            strictHighEnd -> BEYOND_IMAX_CINEMA
            else -> BALANCED_PREVIEW
        }

        // An EXPLICIT user Noise Reduction OFF is honored on every mode (this block is
        // only entered when !noiseReductionEnabled, i.e. the user deliberately turned it
        // off). The ON default keeps HIGH_QUALITY, so the best-quality default is unchanged;
        // the record-path processed-video pass now honors the same explicit OFF too.
        val noiseToggleCanDisableIsp = true
        val finalProfile = if (
            !noiseReductionEnabled &&
            noiseToggleCanDisableIsp &&
            profile.noiseReductionMode != CaptureRequest.NOISE_REDUCTION_MODE_OFF
        ) {
            profile.copy(
                noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_OFF,
                hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_OFF
            )
        } else {
            profile
        }

        // Track state transition
        if (activeProfileName != finalProfile.name) {
            val transitionNumber = profileTransitionCount.incrementAndGet()
            val now = System.currentTimeMillis()
            val latency = if (lastProfileTransitionMs > 0) now - lastProfileTransitionMs else 0L
            Log.i(TAG, "Profile transition #$transitionNumber: $activeProfileName → ${finalProfile.name}" +
                " (latency=${latency}ms, priority=${finalProfile.priority})")
            activeProfileName = finalProfile.name
            lastProfileTransitionMs = now
        }

        // Update cache
        cachedProfile = finalProfile
        cacheInputHash = inputHash

        return finalProfile
    }

    /**
     * Resolve and cache stabilization for the current profile.
     * Call this alongside [resolveProfile] on state changes.
     * Use [cachedStabilization] for zero-cost per-frame access.
     */
    fun resolveAndCacheStabilization(
        profile: HardwareOverrideProfile,
        supportsOis: Boolean,
        supportsEis: Boolean,
        requestedOis: Boolean,
        requestedEis: Boolean
    ): StabilizationOverride {
        val result = resolveStabilization(
            profile, supportsOis, supportsEis, requestedOis, requestedEis
        )
        cachedStabilization = result
        return result
    }

    /**
     * Thermal-aware profile resolution.
     *
     * Dynamic Thermal Pipeline: When the device gets hot, instead of just
     * lowering bitrate, switch from 12-bit RAW to 10-bit LOG to save the
     * sensor from overheating while maintaining professional quality.
     */
    /**
     * ELITE CINEMA thermal-aware profile resolution.
     *
     * Cinema cameras NEVER degrade edge/NR/hotpixel quality under thermal pressure.
     * Instead, they shed non-visual-critical stages (distortion correction, shading)
     * and reduce stabilization overhead. Edge sharpness, noise reduction, and hot
     * pixel correction remain at HIGH_QUALITY unconditionally.
     */
    fun thermalAwareProfile(
        baseProfile: HardwareOverrideProfile,
        thermalResourceCeiling: Float
    ): HardwareOverrideProfile {
        // If thermal is healthy (≥ 72% ceiling), no changes needed
        if (thermalResourceCeiling >= 0.72f) return baseProfile
        if (HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly) {
            Log.w(
                TAG,
                "ELITE THERMAL: Strict high-end preserving full hardware profile " +
                    "(ceiling=$thermalResourceCeiling)"
            )
            return baseProfile
        }

        return when {
            // CRITICAL/EMERGENCY: Drop from RAW to LOG to save sensor
            thermalResourceCeiling < 0.40f &&
                (baseProfile.name == "BEYOND_IMAX_RAW" || baseProfile.name == "SUPREME_FIDELITY_BYPASS") -> {
                Log.w(TAG, "ELITE THERMAL: RAW → LOG (ceiling=$thermalResourceCeiling)")
                BEYOND_IMAX_LOG.copy(
                    name = "BEYOND_IMAX_LOG_THERMAL",
                    priority = baseProfile.priority
                )
            }
            // THROTTLED: shed optional corrections without changing the chosen
            // RAW-bypass versus processed-SDR quality intent.
            thermalResourceCeiling < 0.55f -> {
                Log.w(TAG, "ELITE THERMAL: Shedding optional corrections (ceiling=$thermalResourceCeiling)")
                baseProfile.copy(
                    name = "${baseProfile.name}_THERMAL",
                    shadingMode = CaptureRequest.SHADING_MODE_FAST,
                    distortionCorrectionMode = null,
                    aberrationCorrectionMode = null,
                    estimatedOverhead = baseProfile.estimatedOverhead * 0.75f
                )
            }
            // SUSTAINED: Only shed distortion correction
            thermalResourceCeiling < 0.72f -> {
                baseProfile.copy(
                    name = "${baseProfile.name}_SUSTAINED",
                    distortionCorrectionMode = null,
                    estimatedOverhead = baseProfile.estimatedOverhead * 0.88f
                )
            }
            else -> baseProfile
        }
    }

    private fun computeInputHash(
        isRecording: Boolean,
        isRawCapture: Boolean,
        isLogMode: Boolean,
        isCinemaFidelity: Boolean,
        noiseReductionEnabled: Boolean,
        isHdrMode: Boolean,
        isStillCapture: Boolean,
        isHighSpeed: Boolean,
        isConsumerMode: Boolean = false
    ): Long {
        var hash = 17L
        hash = hash * 31 + if (isRecording) 1L else 0L
        hash = hash * 31 + if (isRawCapture) 1L else 0L
        hash = hash * 31 + if (isLogMode) 1L else 0L
        hash = hash * 31 + if (isCinemaFidelity) 1L else 0L
        hash = hash * 31 + if (noiseReductionEnabled) 1L else 0L
        hash = hash * 31 + if (isHdrMode) 1L else 0L
        hash = hash * 31 + if (isStillCapture) 1L else 0L
        hash = hash * 31 + if (isHighSpeed) 1L else 0L
        hash = hash * 31 + if (strictHighEndEnabled()) 1L else 0L
        hash = hash * 31 + if (isConsumerMode) 1L else 0L
        return hash
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STABILIZATION GUARD
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Resolve stabilization settings based on the active hardware profile
     * and camera capabilities.
     */
    fun resolveStabilization(
        profile: HardwareOverrideProfile,
        supportsOis: Boolean,
        supportsEis: Boolean,
        requestedOis: Boolean,
        requestedEis: Boolean
    ): StabilizationOverride {
        val oisEnabled = when {
            profile.disableOis -> false
            profile.forceOis && supportsOis -> true
            requestedOis && supportsOis -> true
            else -> false
        }

        val eisEnabled = when {
            profile.disableEis -> false
            profile.forceEis && supportsEis -> true
            requestedEis && supportsEis -> true
            else -> false
        }

        val reason = buildString {
            append(profile.name)
            if (oisEnabled) append(" +OIS")
            if (eisEnabled) append(" +EIS")
            if (profile.disableEis) append(" (EIS-disabled:RAW)")
        }

        return StabilizationOverride(
            oisEnabled = oisEnabled,
            eisEnabled = eisEnabled,
            reason = reason
        )
    }

    /**
     * Apply stabilization override to a CaptureRequest.Builder.
     */
    fun applyStabilization(
        builder: CaptureRequest.Builder,
        override: StabilizationOverride,
        supportsOis: Boolean,
        supportsEis: Boolean
    ) {
        if (supportsOis) {
            builder[CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE] =
                if (override.oisEnabled) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        }

        if (supportsEis) {
            builder[CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE] =
                if (override.eisEnabled) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DIAGNOSTICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get current HAL state summary for diagnostics overlay.
     */
    fun diagnosticsSummary(): String {
        return buildString {
            append("HAL: profile=$activeProfileName")
            append(" transitions=${profileTransitionCount.get()}")
            if (strictHighEndEnabled()) append(" mode=STRICT_HIGH_END")
        }
    }

    /**
     * Get the current active profile name.
     */
    fun activeProfileName(): String = activeProfileName

    /**
     * Get the total number of profile transitions since app start.
     */
    fun transitionCount(): Long = profileTransitionCount.get()

    /**
     * Reset transition counters (e.g., on camera switch).
     */
    fun resetCounters() {
        activeProfileName = "NONE"
        lastProfileTransitionMs = 0L
        cacheInputHash = 0L
        cachedProfile = BALANCED_PREVIEW
        cachedStabilization = StabilizationOverride(
            oisEnabled = false, eisEnabled = false, reason = "NONE"
        )
        // Note: We don't reset profileTransitionCount — it's a lifetime counter
    }

    /**
     * Request a linear 1:1 tonemap curve for SUPREME FIDELITY mode.
     * This avoids app-requested gamma shaping, but vendor-private ISP and encoder
     * behavior can still affect the final dynamic range.
     *
     * Linear curve: Input [0.0...1.0] → Output [0.0...1.0] (no transformation)
     */
    fun applyLinearTonemapCurve(builder: CaptureRequest.Builder) {
        try {
            builder[CaptureRequest.TONEMAP_MODE] = CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE
            
            // Create linear curve: 65 points from (0,0) to (1,1)
            val curveSize = 65
            val inputCurve = FloatArray(curveSize) { i -> i / (curveSize - 1).toFloat() }
            val outputCurve = inputCurve.copyOf()  // 1:1 mapping, no transformation
            
            builder[CaptureRequest.TONEMAP_CURVE] = TonemapCurve(inputCurve, outputCurve, outputCurve)
            Log.d(TAG, "Applied linear 1:1 tonemap curve for high-bitrate quality-preserving recording")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply linear tonemap curve", e)
        }
    }
}
