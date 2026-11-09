package com.neoproc.neoproccamx.ui

/**
 * RecordStopSingleSessionRebuildFlag — reversible A/B gate (DEFAULT ON) that collapses the
 * OFFSCREEN_AHB_EGL record-stop cleanup to a single Camera2 session rebuild.
 *
 * WHY: on the offscreen-EGL stop path the finally-cleanup issues TWO non-forced refreshOutputsAndSession()
 * calls — refreshRecordingOutputsSafely() (a bare session refresh) immediately followed by
 * refreshPreviewPipelineIfNeeded(forceSessionRefresh = true) (preview re-bind + session refresh). They
 * are meant to coalesce via CameraController.sessionRefreshPending (compareAndSet), but the IO->main
 * thread hop between them can let the first session finish configuring (which resets the pending flag)
 * before the second posts, producing two createSession() rebuilds per stop.
 *
 * When ON, the offscreen stop path SKIPS the redundant refreshRecordingOutputsSafely() and lets the
 * trailing refreshPreviewPipelineIfNeeded(forceSessionRefresh = true) perform the one rebuild that both
 * removes the hardware-import reader and restores the preview pipeline. No pixels, resolution, fps,
 * bit-depth, or look change; only the number of session reconfigurations.
 *
 * DEFAULT ON after the trailing preview refresh became the canonical restore point. A live previewSurface
 * and rebuildable camera state are still required; if unavailable the surrounding stop path falls back to
 * the explicit restore path. For A/B:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.record_stop_single_rebuild true
 */
object RecordStopSingleSessionRebuildFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
