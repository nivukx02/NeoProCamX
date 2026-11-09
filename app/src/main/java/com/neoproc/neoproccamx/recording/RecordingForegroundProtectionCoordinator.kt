package com.neoproc.neoproccamx.recording

import android.app.Application
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Owns foreground-service registration and converts platform failures into a main-thread stop. */
internal class RecordingForegroundProtectionCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val onProtectionFailure: () -> Unit
) {
    fun start() {
        RecordingForegroundService.setProtectionFailureListener(::reportFailure)
        try {
            ContextCompat.startForegroundService(
                application,
                RecordingForegroundService.buildIntent(application)
            )
        } catch (error: RuntimeException) {
            reportFailure(
                "Unable to start foreground recording protection: " +
                    (error.message ?: error.javaClass.simpleName)
            )
        }
    }

    fun stop() {
        try {
            RecordingForegroundService.stop(application)
        } catch (error: SecurityException) {
            Log.w(TAG, "Failed to stop recording foreground service: permission denied", error)
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Failed to stop recording foreground service: illegal state", error)
        } finally {
            RecordingForegroundService.setProtectionFailureListener(null)
        }
    }

    private fun reportFailure(reason: String) {
        Log.e(TAG, reason)
        scope.launch(Dispatchers.Main.immediate) { onProtectionFailure() }
    }

    private companion object {
        const val TAG = "CameraViewModel"
    }
}
