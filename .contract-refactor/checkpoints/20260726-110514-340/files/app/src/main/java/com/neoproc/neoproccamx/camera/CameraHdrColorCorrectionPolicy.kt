package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest

internal object CameraHdrColorCorrectionPolicy {
    fun resolve(
        hdrDynamicRangeMode: HdrDynamicRangeMode,
        availableColorCorrectionModes: IntArray,
        strictProcessedVideo: Boolean = false,
        rawVideoCaptureEnabled: Boolean = false,
        manualColorTransformActive: Boolean = false,
        preferFastForHdr: Boolean = false
    ): Int? {
        val shouldForceHighQuality =
            !manualColorTransformActive && (
                hdrDynamicRangeMode != HdrDynamicRangeMode.SDR ||
                    (strictProcessedVideo && !rawVideoCaptureEnabled)
                )
        if (!shouldForceHighQuality) return null
        val modes = if (availableColorCorrectionModes.isEmpty()) {
            // Empty on SDK < 36 (COLOR_CORRECTION_AVAILABLE_MODES unread). Include FAST so the HDR
            // warm-red fix (preferFastForHdr) can still select it there; HIGH_QUALITY stays default.
            intArrayOf(
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY,
                CaptureRequest.COLOR_CORRECTION_MODE_FAST
            )
        } else {
            availableColorCorrectionModes
        }
        // NDR10+/NIVUKX warm-red fix (CameraHdrColorFastModeFlag, default ON): the vendor HIGH_QUALITY
        // CCM over-boosts saturated warm/red hues (baked into the single shared HDR capture → shows on
        // preview + record + convert). For HDR only, prefer the lighter stock FAST CCM. SDR (incl.
        // strict-processed video) keeps HIGH_QUALITY — out of the bug's scope.
        val isHdr = hdrDynamicRangeMode != HdrDynamicRangeMode.SDR
        if (preferFastForHdr && isHdr && modes.contains(CaptureRequest.COLOR_CORRECTION_MODE_FAST)) {
            return CaptureRequest.COLOR_CORRECTION_MODE_FAST
        }
        return when {
            modes.contains(CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY) ->
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
            modes.contains(CaptureRequest.COLOR_CORRECTION_MODE_FAST) ->
                CaptureRequest.COLOR_CORRECTION_MODE_FAST
            else -> null
        }
    }
}
