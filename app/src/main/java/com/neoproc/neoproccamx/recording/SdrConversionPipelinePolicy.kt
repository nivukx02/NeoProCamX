package com.neoproc.neoproccamx.recording

/**
 * Limits the user-facing conversion preference to ordinary processed SDR video.
 *
 * RAW, Log and proprietary dynamic-range modes have format-specific capture and conversion
 * contracts. They must never be redirected by an SDR destination preference.
 */
internal object SdrConversionPipelinePolicy {
    enum class PlainSdrDestination {
        PRIVATE_CONVERSION_MASTER,
        DIRECT_GALLERY
    }

    fun isPlainSdrVideo(
        cinemaMode: CinemaVideoMode,
        rawEnabled: Boolean,
        logEnabled: Boolean,
        hdrMode: String
    ): Boolean =
        cinemaMode == CinemaVideoMode.PRO &&
            !rawEnabled &&
            !logEnabled &&
            hdrMode.trim().equals("OFF", ignoreCase = true)

    fun allowsPrivateConversionMaster(
        sdrConversionPipelineEnabled: Boolean,
        cinemaMode: CinemaVideoMode,
        rawEnabled: Boolean,
        logEnabled: Boolean,
        hdrMode: String
    ): Boolean = resolvePlainSdrDestination(
        sdrConversionPipelineEnabled = sdrConversionPipelineEnabled,
        cinemaMode = cinemaMode,
        rawEnabled = rawEnabled,
        logEnabled = logEnabled,
        hdrMode = hdrMode
    ) != PlainSdrDestination.DIRECT_GALLERY

    fun usesDirectGalleryDelivery(
        sdrConversionPipelineEnabled: Boolean,
        cinemaMode: CinemaVideoMode,
        rawEnabled: Boolean,
        logEnabled: Boolean,
        hdrMode: String
    ): Boolean = resolvePlainSdrDestination(
        sdrConversionPipelineEnabled = sdrConversionPipelineEnabled,
        cinemaMode = cinemaMode,
        rawEnabled = rawEnabled,
        logEnabled = logEnabled,
        hdrMode = hdrMode
    ) == PlainSdrDestination.DIRECT_GALLERY

    fun resolvePlainSdrDestination(
        sdrConversionPipelineEnabled: Boolean,
        cinemaMode: CinemaVideoMode,
        rawEnabled: Boolean,
        logEnabled: Boolean,
        hdrMode: String
    ): PlainSdrDestination? {
        if (!isPlainSdrVideo(
            cinemaMode = cinemaMode,
            rawEnabled = rawEnabled,
            logEnabled = logEnabled,
            hdrMode = hdrMode
        )) {
            return null
        }
        return if (sdrConversionPipelineEnabled) {
            PlainSdrDestination.PRIVATE_CONVERSION_MASTER
        } else {
            PlainSdrDestination.DIRECT_GALLERY
        }
    }
}
