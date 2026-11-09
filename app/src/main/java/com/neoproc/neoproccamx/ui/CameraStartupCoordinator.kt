package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.core.StartupSmoothnessPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal interface CameraStartupActions {
    fun startRecordingCapabilityWarmup()
    fun selectCamera()
    fun applyStartupPreferences()
    fun completeStartupFastPath()
    fun releaseStartupNativePreviewGate()
    fun refreshPreviewPipelineIfNeeded()
    fun isStartupFeatureGateActive(): Boolean
    fun completeStartupAiRamp()
    fun applyAiTrackingPreferences()
    fun scheduleAiWarmUp(delayMs: Long, allowDuringStartupAiRamp: Boolean)
}

internal class CameraStartupCoordinator(
    private val scope: CoroutineScope,
    private val cameraState: StateFlow<CameraState>,
    private val actions: CameraStartupActions
) {
    private var startupBootstrapJob: Job? = null
    private var startupOpenAwaitJob: Job? = null
    private var startupNativePreviewReleaseJob: Job? = null
    private var startupAiRampJob: Job? = null

    fun startBootstrap() {
        if (startupBootstrapJob != null) return
        actions.startRecordingCapabilityWarmup()
        startupBootstrapJob = scope.launch(Dispatchers.Default) {
            actions.selectCamera()
            actions.applyStartupPreferences()
        }
    }

    fun scheduleOpenAwait() {
        startupOpenAwaitJob?.cancel()
        startupOpenAwaitJob = scope.launch {
            withTimeoutOrNull(StartupSmoothnessPolicy.featureGateTimeoutMs()) {
                cameraState.first { state ->
                    state == CameraState.Streaming ||
                        state == CameraState.Recording ||
                        state is CameraState.Error
                }
            }
            withContext(Dispatchers.Main.immediate) {
                actions.completeStartupFastPath()
            }
        }
    }

    fun prepareOpen(fastResume: Boolean) {
        cancelSessionJobs()
        if (!fastResume) scheduleOpenAwait()
    }

    fun scheduleNativePreviewRelease() {
        startupNativePreviewReleaseJob?.cancel()
        startupNativePreviewReleaseJob = scope.launch {
            delay(StartupSmoothnessPolicy.nativePreviewReleaseDelayMs())
            actions.releaseStartupNativePreviewGate()
            withContext(Dispatchers.Main.immediate) {
                actions.refreshPreviewPipelineIfNeeded()
            }
        }
    }

    fun scheduleAiRamp(delayMs: Long) {
        startupAiRampJob?.cancel()
        startupAiRampJob = scope.launch {
            delay(delayMs)
            if (actions.isStartupFeatureGateActive()) return@launch
            actions.completeStartupAiRamp()
            actions.applyAiTrackingPreferences()
            actions.scheduleAiWarmUp(delayMs = 0L, allowDuringStartupAiRamp = false)
        }
    }

    fun cancelSessionJobs() {
        startupOpenAwaitJob?.cancel()
        startupOpenAwaitJob = null
        startupNativePreviewReleaseJob?.cancel()
        startupNativePreviewReleaseJob = null
        startupAiRampJob?.cancel()
        startupAiRampJob = null
    }

    fun cancelAll() {
        startupBootstrapJob?.cancel()
        startupBootstrapJob = null
        cancelSessionJobs()
    }
}
