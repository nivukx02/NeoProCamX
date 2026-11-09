package com.neoproc.neoproccamx.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import kotlin.math.roundToInt

internal data class CinemaAutoWhiteBalanceRequestPlan(
    val awbMode: Int,
    val awbLock: Boolean,
    val colorCorrectionMode: Int?,
    val colorTemperatureKelvin: Int?,
    val colorTint: Int?,
    val useTransformMatrix: Boolean
)

internal object CinemaAutoWhiteBalanceRequestPolicy {
    private const val TINT_MIN = -150
    private const val TINT_MAX = 150
    private const val PLATFORM_CCT_TINT_MIN = -50
    private const val PLATFORM_CCT_TINT_MAX = 50

    @SuppressLint("InlinedApi")
    fun resolve(
        manualKelvin: Int,
        manualTint: Int,
        kelvinRange: CameraIntRange,
        availableAwbModes: IntArray,
        supportsCctWhiteBalance: Boolean,
        manualTransformReady: Boolean = false,
        awbLocked: Boolean,
        awbLockAvailable: Boolean = true,
        forceAutoForHdr: Boolean = false,
        sdkInt: Int = 36,
        requireLockableAutoWhiteBalance: Boolean = false,
        shotStableFallbackKelvin: Int = 5_500,
        shotStableFallbackTint: Int = 0
    ): CinemaAutoWhiteBalanceRequestPlan {
        val cctPathSupported = supportsCctWhiteBalance && sdkInt >= 36
        val fixedWhiteBalanceSupported = canDisableAwb(availableAwbModes) &&
            (cctPathSupported || manualTransformReady)
        if (manualKelvin > 0 && !forceAutoForHdr && fixedWhiteBalanceSupported) {
            val kelvin = manualKelvin.coerceIn(kelvinRange.lower, kelvinRange.upper)
            val tint = if (cctPathSupported) {
                mapToPlatformCctTint(manualTint)
            } else {
                manualTint.coerceIn(TINT_MIN, TINT_MAX)
            }
            val correctionMode = if (cctPathSupported) {
                CameraMetadata.COLOR_CORRECTION_MODE_CCT
            } else {
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            }
            return CinemaAutoWhiteBalanceRequestPlan(
                awbMode = CaptureRequest.CONTROL_AWB_MODE_OFF,
                awbLock = false,
                colorCorrectionMode = correctionMode,
                colorTemperatureKelvin = kelvin,
                colorTint = tint,
                useTransformMatrix = correctionMode == CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
        }

        val autoMode = selectAutoAwbMode(availableAwbModes)
        val autoAwbLockable =
            awbLockAvailable &&
            !forceAutoForHdr &&
                awbLocked &&
                autoMode == CaptureRequest.CONTROL_AWB_MODE_AUTO
        if (requireLockableAutoWhiteBalance && awbLocked && !autoAwbLockable && fixedWhiteBalanceSupported) {
            val kelvin = shotStableFallbackKelvin.coerceIn(kelvinRange.lower, kelvinRange.upper)
            val tint = if (cctPathSupported) {
                mapToPlatformCctTint(shotStableFallbackTint)
            } else {
                shotStableFallbackTint.coerceIn(TINT_MIN, TINT_MAX)
            }
            val correctionMode = if (cctPathSupported) {
                CameraMetadata.COLOR_CORRECTION_MODE_CCT
            } else {
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            }
            return CinemaAutoWhiteBalanceRequestPlan(
                awbMode = CaptureRequest.CONTROL_AWB_MODE_OFF,
                awbLock = false,
                colorCorrectionMode = correctionMode,
                colorTemperatureKelvin = kelvin,
                colorTint = tint,
                useTransformMatrix = correctionMode == CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
        }
        return CinemaAutoWhiteBalanceRequestPlan(
            awbMode = autoMode,
            awbLock = autoAwbLockable,
            colorCorrectionMode = null,
            colorTemperatureKelvin = null,
            colorTint = null,
            useTransformMatrix = false
        )
    }

    fun tintToGreenMagentaScale(tint: Int): Float =
        (1.0f + tint.coerceIn(TINT_MIN, TINT_MAX) / 500f).coerceIn(0.70f, 1.30f)

    /** Maps the app's -150..150 tint control onto Camera2's API-36 -50..50 CCT-tint contract. */
    fun mapToPlatformCctTint(tint: Int): Int =
        (tint.coerceIn(TINT_MIN, TINT_MAX).toFloat() *
            PLATFORM_CCT_TINT_MAX / TINT_MAX.toFloat())
            .roundToInt()
            .coerceIn(PLATFORM_CCT_TINT_MIN, PLATFORM_CCT_TINT_MAX)

    fun shouldApplyStillCaptureAutoLock(
        requested: Boolean,
        awbLockAvailable: Boolean,
        resolvedAwbMode: Int?
    ): Boolean =
        requested &&
            awbLockAvailable &&
            resolvedAwbMode == CaptureRequest.CONTROL_AWB_MODE_AUTO

    private fun selectAutoAwbMode(availableAwbModes: IntArray): Int {
        if (availableAwbModes.isEmpty() ||
            availableAwbModes.contains(CaptureRequest.CONTROL_AWB_MODE_AUTO)
        ) {
            return CaptureRequest.CONTROL_AWB_MODE_AUTO
        }
        val fallbackOrder = intArrayOf(
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_SHADE,
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
        )
        return fallbackOrder.firstOrNull { it in availableAwbModes }
            ?: CaptureRequest.CONTROL_AWB_MODE_AUTO
    }

    private fun canDisableAwb(availableAwbModes: IntArray): Boolean =
        availableAwbModes.contains(CaptureRequest.CONTROL_AWB_MODE_OFF)
}

/**
 * Atomic pre-CCT manual-white-balance payload. A transform request is legal only when the sensor's
 * own calibration produced both finite gains and its matching finite 3x3 CCM. Keeping them together
 * prevents a gain solution from being paired with a stale transform harvested from another frame.
 */
internal data class CinemaManualWhiteBalanceTransform(
    val redGain: Float,
    val blueGain: Float,
    val colorCorrectionTransform: FloatArray
)

internal object CinemaManualWhiteBalanceTransformPolicy {
    private const val RATIONAL_DENOMINATOR = 1_000_000.0

    fun fromDeviceCalibration(
        solution: DngWhiteBalanceMath.DeviceWhiteBalance?
    ): CinemaManualWhiteBalanceTransform? {
        val resolved = solution ?: return null
        val matrix = resolved.colorCorrectionTransform ?: return null
        if (!resolved.rGain.isFinite() || resolved.rGain <= 0f ||
            !resolved.bGain.isFinite() || resolved.bGain <= 0f ||
            matrix.size != 9 ||
            matrix.any { element ->
                !element.isFinite() ||
                    kotlin.math.abs(element.toDouble() * RATIONAL_DENOMINATOR) > Int.MAX_VALUE.toDouble()
            }
        ) {
            return null
        }
        return CinemaManualWhiteBalanceTransform(
            redGain = resolved.rGain,
            blueGain = resolved.bGain,
            colorCorrectionTransform = matrix.copyOf()
        )
    }
}

/** Provenance carried by the single atomic capture-result color snapshot. */
internal data class WhiteBalanceMeasurementProvenance(
    val cameraId: String,
    val sessionGeneration: Long,
    val frameNumber: Long,
    val sensorTimestampNs: Long?
) {
    fun isCurrent(activeCameraId: String, activeSessionGeneration: Long): Boolean =
        cameraId == activeCameraId && sessionGeneration == activeSessionGeneration

    fun isNewerThan(previous: WhiteBalanceMeasurementProvenance): Boolean {
        if (cameraId != previous.cameraId || sessionGeneration != previous.sessionGeneration) return true
        if (frameNumber <= previous.frameNumber) return false
        val timestamp = sensorTimestampNs
        val previousTimestamp = previous.sensorTimestampNs
        return timestamp == null || previousTimestamp == null || timestamp >= previousTimestamp
    }
}
