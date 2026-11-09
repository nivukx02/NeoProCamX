package com.neoproc.neoproccamx.ui

import android.view.Window
import android.view.WindowManager
import com.neoproc.neoproccamx.recording.GpuRecordingState

/**
 * Owns the screen-awake decision for the camera window.
 *
 * A live foreground camera preview must not inherit an almost-expired vendor user-activity timer:
 * doing so can turn the display off during permission completion, session startup, or an ADB/UI test
 * even though Camera2 is still streaming. Recording lifecycle states retain the historical guarantee
 * independently so an in-flight stop/finalization is never weakened.
 */
internal object CameraScreenAwakePolicy {
    fun shouldKeepScreenOn(
        foregroundPreviewActive: Boolean,
        recordingState: GpuRecordingState
    ): Boolean = foregroundPreviewActive || when (recordingState) {
        is GpuRecordingState.Starting,
        is GpuRecordingState.Recording,
        is GpuRecordingState.Paused,
        is GpuRecordingState.Stopping,
        is GpuRecordingState.Finalizing -> true
        else -> false
    }
}

internal fun Window.applyCameraScreenAwakePolicy(
    foregroundPreviewActive: Boolean,
    recordingState: GpuRecordingState
) {
    if (CameraScreenAwakePolicy.shouldKeepScreenOn(foregroundPreviewActive, recordingState)) {
        addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
