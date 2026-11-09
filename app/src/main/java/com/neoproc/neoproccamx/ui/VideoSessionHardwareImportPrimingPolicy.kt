package com.neoproc.neoproccamx.ui

/**
 * Pure admission policy for publishing an offscreen P010 source before Camera2 builds the final
 * cold-open or mode-switch session. Every input is fail-closed so unsupported devices keep the
 * established session path without a resolution, cadence, depth, or quality fallback.
 */
internal object VideoSessionHardwareImportPrimingPolicy {
    fun shouldPrime(
        featureEnabled: Boolean,
        videoModeActive: Boolean,
        idlePrearmEligible: Boolean,
        offscreenHardwareImportFeed: Boolean,
        sensorLinearNdr: Boolean,
        nativeP010SourceSupported: Boolean
    ): Boolean =
        featureEnabled &&
            videoModeActive &&
            idlePrearmEligible &&
            offscreenHardwareImportFeed &&
            !sensorLinearNdr &&
            nativeP010SourceSupported
}
