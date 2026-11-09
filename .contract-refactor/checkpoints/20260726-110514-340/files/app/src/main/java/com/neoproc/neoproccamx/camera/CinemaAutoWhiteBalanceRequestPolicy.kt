package com.neoproc.neoproccamx.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest

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

    @SuppressLint("InlinedApi")
    fun resolve(
        manualKelvin: Int,
        manualTint: Int,
        kelvinRange: CameraIntRange,
        availableAwbModes: IntArray,
        supportsCctWhiteBalance: Boolean,
        awbLocked: Boolean,
        forceAutoForHdr: Boolean = false,
        sdkInt: Int = 36,
        requireLockableAutoWhiteBalance: Boolean = false,
        shotStableFallbackKelvin: Int = 5_500,
        shotStableFallbackTint: Int = 0
    ): CinemaAutoWhiteBalanceRequestPlan {
        if (manualKelvin > 0 && !forceAutoForHdr) {
            val kelvin = manualKelvin.coerceIn(kelvinRange.lower, kelvinRange.upper)
            val tint = manualTint.coerceIn(TINT_MIN, TINT_MAX)
            val correctionMode = if (supportsCctWhiteBalance && sdkInt >= 36) {
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
            !forceAutoForHdr &&
                awbLocked &&
                autoMode == CaptureRequest.CONTROL_AWB_MODE_AUTO
        if (requireLockableAutoWhiteBalance && awbLocked && !autoAwbLockable && canDisableAwb(availableAwbModes)) {
            val kelvin = shotStableFallbackKelvin.coerceIn(kelvinRange.lower, kelvinRange.upper)
            val tint = shotStableFallbackTint.coerceIn(TINT_MIN, TINT_MAX)
            val correctionMode = if (supportsCctWhiteBalance && sdkInt >= 36) {
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
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
            CaptureRequest.CONTROL_AWB_MODE_OFF
        )
        return fallbackOrder.firstOrNull { it in availableAwbModes }
            ?: CaptureRequest.CONTROL_AWB_MODE_AUTO
    }

    private fun canDisableAwb(availableAwbModes: IntArray): Boolean =
        availableAwbModes.isEmpty() ||
            availableAwbModes.contains(CaptureRequest.CONTROL_AWB_MODE_OFF)
}
