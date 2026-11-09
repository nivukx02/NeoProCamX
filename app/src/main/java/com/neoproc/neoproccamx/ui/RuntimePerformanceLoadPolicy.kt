package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.GpuRecordingState

internal data class RuntimePerformanceLoadClassification(
    val recordingCritical: Boolean,
    val highLoadOrRecording: Boolean
)

/** Separates real recording protection from expensive but discardable idle preview work. */
internal object RuntimePerformanceLoadPolicy {
    /**
     * Camera capture can stop before the encoder, container and quality certificate finish.
     * Finalizing must retain the existing recording evidence window across preview rebuilds;
     * treating it as idle clears real source counters before the terminal certificate reads them.
     * This does not reopen encoder ingress or extend the separately sealed clip-loss window.
     */
    fun isRecordingCritical(state: GpuRecordingState): Boolean = when (state) {
        GpuRecordingState.Starting,
        GpuRecordingState.Recording,
        GpuRecordingState.Paused,
        GpuRecordingState.Stopping,
        GpuRecordingState.Finalizing -> true
        GpuRecordingState.Idle,
        GpuRecordingState.Ready,
        is GpuRecordingState.Error -> false
    }

    fun resolve(
        recordingGraphPrearmed: Boolean,
        recordingStarting: Boolean,
        gpuRecordingCritical: Boolean,
        cameraRecording: Boolean,
        highLoadPreview: Boolean
    ): RuntimePerformanceLoadClassification {
        val recordingCritical = recordingStarting || gpuRecordingCritical || cameraRecording
        return RuntimePerformanceLoadClassification(
            recordingCritical = recordingCritical,
            highLoadOrRecording = recordingCritical || recordingGraphPrearmed || highLoadPreview
        )
    }
}
