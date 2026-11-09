package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.recording.RecordingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecordingDomainState(
    val gpuRecordState: GpuRecordingState = GpuRecordingState.Idle,
    val recDuration: Long = 0L,
    val savedPath: String? = null
)

internal class CameraRecordingController(
    private val recordingManager: RecordingManager
) {
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _domainState = MutableStateFlow(RecordingDomainState())
    val domainState: StateFlow<RecordingDomainState> = _domainState.asStateFlow()

    init {
        controllerScope.launch {
            combine(recordingManager.state, recordingManager.duration) { state, duration ->
                state to duration
            }.collect { (state, duration) ->
                _domainState.update {
                    it.copy(gpuRecordState = state, recDuration = duration)
                }
            }
        }
    }

    fun startRecording(): Boolean {
        if (domainState.value.gpuRecordState is GpuRecordingState.Starting ||
            domainState.value.gpuRecordState is GpuRecordingState.Recording ||
            domainState.value.gpuRecordState is GpuRecordingState.Paused ||
            domainState.value.gpuRecordState is GpuRecordingState.Stopping
        ) {
            return false
        }
        return true
    }

    fun stopRecording(): Boolean {
        return domainState.value.gpuRecordState is GpuRecordingState.Starting ||
            domainState.value.gpuRecordState is GpuRecordingState.Recording ||
            domainState.value.gpuRecordState is GpuRecordingState.Paused ||
            domainState.value.gpuRecordState is GpuRecordingState.Stopping
    }

    fun pauseRecording(): Boolean {
        return domainState.value.gpuRecordState is GpuRecordingState.Recording
    }

    fun resumeRecording(): Boolean {
        return domainState.value.gpuRecordState is GpuRecordingState.Paused
    }

    fun toggleRecording(): Boolean {
        return when (domainState.value.gpuRecordState) {
            is GpuRecordingState.Starting,
            is GpuRecordingState.Recording,
            is GpuRecordingState.Paused,
            is GpuRecordingState.Stopping -> stopRecording()
            is GpuRecordingState.Finalizing -> false
            else -> startRecording()
        }
    }

    fun syncExternalState(state: GpuRecordingState, duration: Long, savedPath: String? = domainState.value.savedPath) {
        _domainState.update {
            it.copy(gpuRecordState = state, recDuration = duration, savedPath = savedPath)
        }
    }

    fun close() {
        controllerScope.cancel()
    }
}
