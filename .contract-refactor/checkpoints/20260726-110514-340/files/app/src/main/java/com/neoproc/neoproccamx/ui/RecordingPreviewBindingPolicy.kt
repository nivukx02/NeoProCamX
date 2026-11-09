package com.neoproc.neoproccamx.ui

internal object RecordingPreviewBindingPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun resolvePreferNativePreviewOverride(
        fullSensorReadoutForced: Boolean
    ): Boolean = false
}
