package com.neoproc.neoproccamx.niv99

/**
 * Compatibility authority for the former live NIV99 pixel routes.
 *
 * NIV99 now executes only at the offline conversion boundary. Keeping the route validation here is
 * useful for deterministic conversion tests and prevents an accidental future live reactivation,
 * but [isEligible] must remain false while the offline-isolation contract is active.
 */
object Niv99VideoPixelApplicationPolicy {
    const val DEFAULT_ENABLED: Boolean = Niv99ExecutionBoundaryPolicy.ANY_LIVE_PROCESSING_ENABLED

    fun isEligible(configuration: Niv99LiveFrameConfiguration): Boolean =
        DEFAULT_ENABLED &&
            supportsLiveMode(configuration.mode) &&
            configuration.width > 0 &&
            configuration.height > 0 &&
            configuration.targetFps > 0 &&
            configuration.colorProfileId >= 0L &&
            configuration.surfaceGeneration >= 0L &&
            configuration.codecGeneration >= 0L &&
            supportsDomain(configuration.domain)

    fun supportsDomain(domain: Niv99SignalDomain): Boolean = when (domain) {
        Niv99SignalDomain.DISPLAY_RGB,
        Niv99SignalDomain.LINEAR_RGB,
        Niv99SignalDomain.P010_YUV -> true

        Niv99SignalDomain.SENSOR_LINEAR_RAW,
        Niv99SignalDomain.SENSOR_LINEAR_DEMOSAICED -> false
    }

    fun supportsLiveMode(mode: Niv99OperatingMode): Boolean = when (mode) {
        Niv99OperatingMode.VIDEO,
        Niv99OperatingMode.PRO -> true

        Niv99OperatingMode.RAW_PREVIEW,
        Niv99OperatingMode.PHOTO,
        Niv99OperatingMode.CONVERSION_RAW_TO_LOG,
        Niv99OperatingMode.CONVERSION_RAW_TO_RAW_DEVELOPED,
        Niv99OperatingMode.CONVERSION_RAW_TO_XDR10,
        Niv99OperatingMode.CONVERSION_RAW_TO_NIVUKX -> false
    }
}
