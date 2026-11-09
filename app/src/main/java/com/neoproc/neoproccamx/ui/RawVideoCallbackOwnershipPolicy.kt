package com.neoproc.neoproccamx.ui

/**
 * Keeps the RAW ImageReader consumer installed for the whole RAW-mode lifetime.
 *
 * Idle request prewarming targets RAW_SENSOR even when the visible monitor uses the cheaper Camera2
 * preview. Those unused sensor buffers must still be acquired and closed or the HAL stalls as soon as
 * ImageReader.maxImages is reached.
 */
internal object RawVideoCallbackOwnershipPolicy {
    fun callbacksNeeded(videoUiActive: Boolean, rawModeActive: Boolean): Boolean =
        videoUiActive && rawModeActive
}
