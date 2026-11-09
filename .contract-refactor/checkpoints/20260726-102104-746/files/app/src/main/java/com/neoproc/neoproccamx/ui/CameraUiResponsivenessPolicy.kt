package com.neoproc.neoproccamx.ui

/**
 * Cross-mode UI responsiveness limits for camera control work.
 *
 * The UI action queue is bounded so rapid settings, mode, and gesture bursts
 * cannot build an unbounded backlog of stale camera operations.
 */
internal object CameraUiResponsivenessPolicy {
    const val UI_ACTION_BUFFER_CAPACITY = 128

    const val MANUAL_ISO_KEY = "manual_iso"
    const val MANUAL_EXPOSURE_KEY = "manual_exposure"
    const val MANUAL_WHITE_BALANCE_KEY = "manual_white_balance"
    const val MANUAL_FOCUS_DISTANCE_KEY = "manual_focus_distance"
    const val MANUAL_EV_KEY = "manual_ev"
    const val MANUAL_ZOOM_KEY = "manual_zoom"

    fun shouldDropWhenActionQueueFull(action: UiAction): Boolean =
        action is UiAction.SetPeakingIntensity ||
            action is UiAction.AdjustZoom
}
