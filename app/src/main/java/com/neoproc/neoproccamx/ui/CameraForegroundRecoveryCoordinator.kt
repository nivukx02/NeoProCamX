package com.neoproc.neoproccamx.ui

import android.os.SystemClock
import android.util.Log
import com.neoproc.neoproccamx.camera.CameraState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Owns the bounded stale-preview verification used after a foreground return. */
internal class CameraForegroundRecoveryCoordinator(
    private val scope: CoroutineScope,
    private val cameraState: StateFlow<CameraState>,
    private val hasPreviewSurface: () -> Boolean,
    private val closeInFlight: () -> Boolean,
    private val recordingOrStopping: () -> Boolean,
    private val recoverStalePreview: suspend () -> Unit
) {
    @Volatile private var lastPreviewFrameElapsedMs = 0L
    private var healthCheckJob: Job? = null

    fun onPreviewFrame(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        lastPreviewFrameElapsedMs = nowElapsedMs
    }

    fun isPreviewFresh(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean =
        lastPreviewFrameElapsedMs > 0L &&
            nowElapsedMs - lastPreviewFrameElapsedMs <= PREVIEW_FRESHNESS_MS

    fun scheduleHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch(Dispatchers.Default) {
            delay(HEALTH_CHECK_DELAY_MS)
            if (recordingOrStopping() || isPreviewFresh()) return@launch
            val state = cameraState.value
            if (!hasPreviewSurface() || closeInFlight() ||
                (state != CameraState.Open && state != CameraState.Streaming)
            ) return@launch
            Log.w(TAG, "Foreground preview became stale; rebuilding camera session once")
            recoverStalePreview()
        }
    }

    fun cancel() {
        healthCheckJob?.cancel()
        healthCheckJob = null
    }

    private companion object {
        const val PREVIEW_FRESHNESS_MS = 650L
        const val HEALTH_CHECK_DELAY_MS = 1_100L
        const val TAG = "CameraViewModel"
    }
}
