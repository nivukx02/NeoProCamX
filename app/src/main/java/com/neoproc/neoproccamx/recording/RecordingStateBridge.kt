package com.neoproc.neoproccamx.recording

object RecordingStateBridge {
    fun toRecordingState(state: GpuRecordingState): RecordingState =
        when (state) {
            GpuRecordingState.Idle -> RecordingState.Idle
            // Encoder prearm is an internal background-ready state. Public recording state must
            // remain idle until the recorder actually starts accepting the clip.
            GpuRecordingState.Ready -> RecordingState.Idle
            GpuRecordingState.Starting -> RecordingState.Idle
            GpuRecordingState.Recording -> RecordingState.Recording
            GpuRecordingState.Paused -> RecordingState.Paused
            GpuRecordingState.Stopping -> RecordingState.Paused
            GpuRecordingState.Finalizing -> RecordingState.Idle
            is GpuRecordingState.Error -> RecordingState.Error(state.message)
        }
}
