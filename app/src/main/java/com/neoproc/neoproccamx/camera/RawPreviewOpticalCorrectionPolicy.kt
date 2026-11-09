package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import android.os.Build

/**
 * Optical correction for the processed live monitor that accompanies a RAW_SENSOR stream.
 *
 * Camera2 RAW pixels are not rewritten by SHADING_MODE or COLOR_CORRECTION_ABERRATION_MODE; those
 * controls affect processed outputs in the same request. Enabling the best advertised correction on
 * the preview therefore removes lens colour shading/CA without altering the RAW master.
 */
internal data class RawPreviewOpticalCorrectionPlan(
    val shadingMode: Int?,
    val aberrationMode: Int?
)

internal object RawPreviewOpticalCorrectionPolicy {
    fun resolve(
        rawSensorRequestActive: Boolean,
        forStillCapture: Boolean,
        availability: IspBypassAvailability?,
        androidApiLevel: Int = Build.VERSION.SDK_INT
    ): RawPreviewOpticalCorrectionPlan? {
        if (!rawSensorRequestActive || forStillCapture) return null

        val shadingMode = bestCorrectionMode(
            preferred = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
            fallback = CaptureRequest.SHADING_MODE_FAST,
            available = availability?.shadingModes
        )
        val aberrationMode = if (androidApiLevel >= Build.VERSION_CODES.M) {
            bestCorrectionMode(
                preferred = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
                fallback = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST,
                available = availability?.colorAberrationModes
            )
        } else {
            null
        }
        return RawPreviewOpticalCorrectionPlan(shadingMode, aberrationMode)
    }

    private fun bestCorrectionMode(preferred: Int, fallback: Int, available: List<Int>?): Int? {
        if (available == null || available.isEmpty()) return preferred
        return when {
            preferred in available -> preferred
            fallback in available -> fallback
            else -> null
        }
    }
}
