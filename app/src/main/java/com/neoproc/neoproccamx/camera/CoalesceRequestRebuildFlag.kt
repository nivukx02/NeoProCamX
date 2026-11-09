package com.neoproc.neoproccamx.camera

/**
 * CoalesceRequestRebuildFlag — reversible coalescing of redundant non-immediate preview-request
 * rebuilds in [CameraController.updatePreview].
 *
 * Every interactive setter (setZoom / setEv / setWb / setFocusRegion / setAeAwbLock /
 * setNoiseReductionEnabled / setVideoDetailProfile / setFaceDetectionEnabled / setStabilizationMode …)
 * calls updatePreview(), which runs a FULL applyAll rebuild + setRepeatingRequest on the camera thread.
 * During a pinch-zoom or an EV/WB drag those setters fire per touch-move event, so dozens of identical
 * full rebuilds queue up and the camera thread thrashes → visible gesture lag.
 *
 * When enabled, a burst of non-immediate rebuilds collapses to a single queued rebuild: each rebuild's
 * applyAll reads the CURRENT mutable state at execution time, so the coalesced final CaptureRequest is
 * byte-identical to running every intermediate rebuild (last-write-wins). The pending flag is cleared
 * at the START of the queued runnable, so a setter firing mid-rebuild re-arms exactly one trailing
 * rebuild — no update is ever lost. An immediate=true rebuild is never coalesced. The sessionGeneration
 * guard already drops any stale/reordered post.
 *
 * NO-DOWNGRADE / NOT-A-BATTERY-OPT: identical final request, just fewer redundant rebuilds of it — it
 * removes wasted camera-thread work (a smoothness win), it does not save power by doing less real work.
 * Default ON to deliver the gesture smoothness live; reversible for on-device A/B:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.coalesce_request_rebuild false
 */
object CoalesceRequestRebuildFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
