package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.GpuRecordingState

internal object VideoRenderProfileQualityHintPolicy {
    fun isVideoSurface(appMode: AppMode): Boolean =
        appMode == AppMode.VIDEO || appMode == AppMode.PRO

    fun useRecordingGradeRenderProfile(
        appMode: AppMode,
        recordingState: GpuRecordingState
    ): Boolean =
        isVideoSurface(appMode) ||
            recordingState is GpuRecordingState.Recording ||
            recordingState is GpuRecordingState.Paused ||
            recordingState is GpuRecordingState.Stopping
}
