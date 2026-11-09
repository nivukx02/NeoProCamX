package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest

internal data class CameraAutofocusRequestPlan(
    val afMode: Int,
    val lensFocusDistance: Float?,
    val applyAfRegions: Boolean
)

internal object CameraAutofocusRequestPolicy {
    fun resolve(
        manualFocusDistance: Float,
        aiDrivenFocusDistance: Float,
        selectedAfMode: Int,
        maxAfRegions: Int,
        maxFocusDistance: Float = Float.MAX_VALUE,
        manualFocusInfinityRequested: Boolean = false
    ): CameraAutofocusRequestPlan {
        val lensDistanceSupported = maxFocusDistance > 0f
        // 0f is the LENS_FOCUS_DISTANCE encoding for INFINITY. It is honored as a manual lock ONLY when
        // the caller EXPLICITLY flags an infinity request (the user dragged manual focus to the infinity
        // endpoint) — this enables landscape/astro infinity lock (#22). A bare 0f WITHOUT the flag stays
        // "stale/none" and keeps autofocus, preserving the dead-AF protection on lenses that report 0
        // diopters at infinity.
        if (manualFocusInfinityRequested && lensDistanceSupported) {
            return CameraAutofocusRequestPlan(
                afMode = CaptureRequest.CONTROL_AF_MODE_OFF,
                lensFocusDistance = 0f,
                applyAfRegions = false
            )
        }
        val validManualFocusDistance = manualFocusDistance
            .takeIf { it.isFinite() && it > 0f && lensDistanceSupported }
        val validAiFocusDistance = aiDrivenFocusDistance
            .takeIf { it.isFinite() && it >= 0f && lensDistanceSupported }
        if (validManualFocusDistance != null) {
            return CameraAutofocusRequestPlan(
                afMode = CaptureRequest.CONTROL_AF_MODE_OFF,
                lensFocusDistance = validManualFocusDistance.coerceIn(0f, maxFocusDistance),
                applyAfRegions = false
            )
        }

        val hardwareRegionAfAvailable =
            selectedAfMode != CaptureRequest.CONTROL_AF_MODE_OFF && maxAfRegions > 0
        if (validAiFocusDistance != null && selectedAfMode == CaptureRequest.CONTROL_AF_MODE_OFF) {
            return CameraAutofocusRequestPlan(
                afMode = CaptureRequest.CONTROL_AF_MODE_OFF,
                lensFocusDistance = validAiFocusDistance.coerceIn(0f, maxFocusDistance),
                applyAfRegions = false
            )
        }

        return CameraAutofocusRequestPlan(
            afMode = selectedAfMode,
            lensFocusDistance = null,
            applyAfRegions = hardwareRegionAfAvailable
        )
    }
}
