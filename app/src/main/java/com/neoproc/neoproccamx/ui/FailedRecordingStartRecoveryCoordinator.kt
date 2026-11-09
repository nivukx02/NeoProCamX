package com.neoproc.neoproccamx.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

internal data class FailedRecordingStartRecoveryActions(
    val backendFailureMessage: () -> String?,
    val publishTransitionFailure: (String) -> Unit,
    val stopForegroundProtection: () -> Unit,
    val reportRecorderFailure: (String) -> Unit,
    val discardPreparedSession: () -> Unit,
    val releasePersistentInputSurface: () -> Unit,
    val refreshCameraSession: () -> Unit,
    val restorePreviewBinding: () -> Unit,
    val syncCameraPreviewLook: () -> Unit,
    val releaseCinemaAutoLock: () -> Unit,
    val reconcileRawPreview: () -> Unit,
    val syncPreviewFrameRate: () -> Unit,
    val syncPreviewPerformance: () -> Unit,
    val reportCleanupError: (Throwable, String, String) -> Unit
)

/**
 * Owns failed-recording-start terminalization and the trailing viewfinder recovery.
 *
 * The transition state masks recorder state while startup is active. Terminalization therefore
 * publishes the recorder's exact failure before any vendor Camera2 operation. Critical recorder
 * ownership is then released synchronously, while potentially blocking session restoration runs as
 * latest-only scope-owned work and cannot strand the startup handoff in `Starting`.
 */
internal class FailedRecordingStartRecoveryCoordinator(
    private val scope: CoroutineScope,
    private val actions: FailedRecordingStartRecoveryActions,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    private val previewRecoveryLock = Any()
    private val previewRecoveryGeneration = AtomicLong(0L)

    @Volatile
    private var previewRecoveryJob: Job? = null

    fun terminalize(fallbackMessage: String, reportError: Boolean): String? {
        if (!reportError) return null
        return (actions.backendFailureMessage() ?: fallbackMessage).also {
            actions.publishTransitionFailure(it)
        }
    }

    fun releaseRecorder(terminalFailureMessage: String?) {
        actions.stopForegroundProtection()
        try {
            if (terminalFailureMessage != null) {
                actions.reportRecorderFailure(terminalFailureMessage)
            } else {
                actions.discardPreparedSession()
            }
        } catch (failure: Throwable) {
            actions.reportCleanupError(
                failure,
                "gpuRecording.failedStartRelease",
                "Video encoder cleanup failed during recording recovery."
            )
            runCatching(actions.discardPreparedSession)
        } finally {
            runCatching(actions.releasePersistentInputSurface)
        }
    }

    fun runStep(operation: String, userMessage: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            actions.reportCleanupError(failure, operation, userMessage)
        }
    }

    suspend fun runSuspendingStep(
        operation: String,
        userMessage: String,
        block: suspend () -> Unit
    ) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            actions.reportCleanupError(failure, operation, userMessage)
        }
    }

    fun schedulePreviewRecovery(refreshSession: Boolean) {
        val recoveryJob: Job
        synchronized(previewRecoveryLock) {
            val generation = previewRecoveryGeneration.incrementAndGet()
            previewRecoveryJob?.cancel(
                CancellationException("Superseded failed-start preview recovery")
            )
            recoveryJob = scope.launch(mainDispatcher, start = CoroutineStart.LAZY) {
                fun ensureLatestRecovery() {
                    coroutineContext.ensureActive()
                    if (previewRecoveryGeneration.get() != generation) {
                        throw CancellationException("Stale failed-start preview recovery generation")
                    }
                }

                try {
                    ensureLatestRecovery()
                    if (refreshSession) {
                        actions.refreshCameraSession()
                        ensureLatestRecovery()
                    }
                    actions.restorePreviewBinding()
                    ensureLatestRecovery()
                    actions.syncCameraPreviewLook()
                    ensureLatestRecovery()
                    actions.releaseCinemaAutoLock()
                    ensureLatestRecovery()
                    actions.reconcileRawPreview()
                    ensureLatestRecovery()
                    actions.syncPreviewFrameRate()
                    ensureLatestRecovery()
                    actions.syncPreviewPerformance()
                    ensureLatestRecovery()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    ensureLatestRecovery()
                    actions.reportCleanupError(
                        failure,
                        "recordingStart.previewRecovery",
                        "Preview session reset failed during recording recovery."
                    )
                }
            }
            previewRecoveryJob = recoveryJob
        }
        recoveryJob.start()
    }
}
