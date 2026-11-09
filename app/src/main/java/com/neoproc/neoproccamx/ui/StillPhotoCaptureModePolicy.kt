package com.neoproc.neoproccamx.ui

/**
 * Converts the active video-delivery mode into an explicit still-capture contract.
 *
 * A photo taken from the video screen must not silently fall back to a neutral SDR JPEG. RAW keeps
 * the lossless DNG companion, NDR uses a bracketed high-dynamic-range still while idle, and LOG keeps
 * the active log colour pipeline. During recording NDR uses a single video snapshot so taking a photo
 * cannot stall the encoder with a multi-frame burst.
 */
internal object StillPhotoCaptureModePolicy {
    enum class Route {
        SDR_SINGLE,
        LOG_SINGLE,
        NDR_BRACKET,
        NDR_VIDEO_SNAPSHOT,
        RAW_DNG_COMPANION,
        USER_HDR_BRACKET
    }

    data class Plan(
        val route: Route,
        val hdrMode: String = "OFF",
        val requestRawCompanion: Boolean = false
    )

    fun resolve(
        rawVideoModeActive: Boolean,
        logVideoModeActive: Boolean,
        effectiveHdrMode: String,
        recordingActive: Boolean,
        rawSupported: Boolean,
        photoHdrMode: String
    ): Plan {
        if (rawVideoModeActive) {
            return Plan(
                route = Route.RAW_DNG_COMPANION,
                requestRawCompanion = rawSupported
            )
        }

        val normalizedVideoHdr = effectiveHdrMode.trim()
        if (!normalizedVideoHdr.equals("OFF", ignoreCase = true)) {
            return if (recordingActive) {
                Plan(Route.NDR_VIDEO_SNAPSHOT, hdrMode = normalizedVideoHdr)
            } else {
                Plan(Route.NDR_BRACKET, hdrMode = "${normalizedVideoHdr}_CINEMA")
            }
        }

        if (logVideoModeActive) return Plan(Route.LOG_SINGLE)

        val normalizedPhotoHdr = photoHdrMode.trim()
        if (normalizedPhotoHdr.isNotEmpty() && !normalizedPhotoHdr.equals("OFF", ignoreCase = true)) {
            return Plan(Route.USER_HDR_BRACKET, hdrMode = normalizedPhotoHdr)
        }

        return Plan(Route.SDR_SINGLE)
    }
}
