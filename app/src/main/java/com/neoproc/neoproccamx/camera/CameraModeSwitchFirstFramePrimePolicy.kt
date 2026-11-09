package com.neoproc.neoproccamx.camera

/**
 * Decides whether a replacement Camera2 session should submit one visible-preview request before
 * publishing its complete repeating graph.
 *
 * RAW_SENSOR and processed P010/YUV cannot share one Camera2 session on affected Qualcomm/Vivo
 * devices, so crossing that boundary necessarily reconfigures the HAL.  The first request does not
 * need to target an idle RAW/P010 pre-arm surface to make the new mode visible.  Priming only the
 * already-configured preview outputs lets the HAL present the first current-generation frame while
 * the immediately-following repeating request retains every full-quality target.
 */
internal object CameraModeSwitchFirstFramePrimePolicy {
    fun shouldPrime(
        refreshReason: String,
        hasPreviousSession: Boolean,
        recordingActive: Boolean,
        videoPreviewActive: Boolean,
        maximumResolutionStillCaptureActive: Boolean,
        previousRawSurfaceActive: Boolean,
        nextRawSurfaceActive: Boolean
    ): Boolean {
        if (!refreshReason.startsWith("configuration-change")) return false
        if (!hasPreviousSession || recordingActive || !videoPreviewActive) return false
        if (maximumResolutionStillCaptureActive) return false
        return previousRawSurfaceActive != nextRawSurfaceActive
    }
}
