package com.neoproc.neoproccamx.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.neoproc.neoproccamx.core.RuntimeStageTelemetry
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * CameraDeviceOrchestrator — Manages the physical camera device lifecycle.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * EXTRACTED FROM CameraController — Phase 2/3 God-Class Decomposition
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Responsibilities:
 * - Opening the camera device with appropriate retry backoff policies.
 * - Closing the camera cleanly, acquiring necessary locks.
 * - Handling unexpected device disconnections or errors.
 * - Scheduling camera reopens after failures.
 */
class CameraDeviceOrchestrator(
    private val host: CameraControllerHost,
    private val openLock: Semaphore,
    private val tag: String = "CameraDeviceOrchestrator"
) {

    // Internal timing state
    @Volatile private var pendingCameraOpenStartElapsedMs = 0L
    private val openAttemptGate = CameraOpenAttemptGate()

    @SuppressLint("MissingPermission")
    fun openCamera(
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        host.lastOpenReadyCallback = onReady
        host.lastOpenErrorCallback = onError
        
        if (host.cameraDevice != null) {
            Log.d(tag, "openCamera ignored: camera device already active state=${host.stateFlow.value}")
            val state = host.stateFlow.value
            if (state == CameraState.Open || state == CameraState.Streaming || state == CameraState.Recording) {
                onReady()
            }
            return
        }

        val lifecycleGeneration = openAttemptGate.beginOpenLifecycle()
        host.initializationAttempts = 0
        host.stopPreviewWatchdog() // Instead of reset, which is internal. The host will manage it.

        openCameraWithRetry(onReady, onError, attempt = 0, lifecycleGeneration = lifecycleGeneration)
    }

    @SuppressLint("MissingPermission")
    private fun openCameraWithRetry(
        onReady: () -> Unit,
        onError: (String) -> Unit,
        attempt: Int,
        lifecycleGeneration: Long
    ) {
        if (!openAttemptGate.isCurrent(lifecycleGeneration)) return
        val maxAttempts = 3 // Hardcoding CameraControllerConfig.Retry.INIT_RETRY_MAX_ATTEMPTS which is 3
        if (attempt >= maxAttempts) {
            reportTerminalOpenFailure(
                message = "Camera initialization failed after $maxAttempts attempts",
                onError = onError,
                lifecycleGeneration = lifecycleGeneration
            )
            return
        }
        
        host.initializationAttempts = attempt + 1

        val openAttempt = openAttemptGate.newAttempt(lifecycleGeneration) ?: return
        val lockAcquired = try {
            openLock.tryAcquire(2000L, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            reportTerminalOpenFailure(
                message = "Camera open interrupted while waiting for lifecycle ownership",
                onError = onError,
                lifecycleGeneration = lifecycleGeneration
            )
            return
        }
        if (lockAcquired && !openAttemptGate.claimPermit(openAttempt)) {
            openLock.release()
            return
        }
        if (lockAcquired) {
            if (host.currentCameraId.isNotBlank() && !isCameraIdOpenable(host.currentCameraId)) {
                Log.w(tag, "Current camera id ${host.currentCameraId} is no longer openable; reselecting")
                host.currentCameraId = ""
                host.selectCamera()
            }
            if (host.currentCameraId.isBlank()) {
                Log.w(tag, "No selected camera id before open; selecting camera for facing=${host.facing}")
                host.selectCamera()
            }
        }
        
        val precheck = CameraSessionLifecycleCoordinator.validateOpenPreconditions(
            hasCameraPermission = true, // Permission check happens in the caller or here via DI, assuming true for now
            lockAcquired = lockAcquired,
            currentCameraId = host.currentCameraId
        )

        when (precheck) {
            CameraLifecycleOutcome.Proceed -> Unit
            is CameraLifecycleOutcome.Blocked -> {
                releaseOpenLockIfHeld(openAttempt)
                val message = "${precheck.reason} (attempt=${host.initializationAttempts})"
                Log.w(tag, message)
                if (precheck.retryable && CameraSessionLifecycleCoordinator.shouldRetryAttempt(attempt, maxAttempts)) {
                    scheduleRetry(
                        onReady,
                        onError,
                        attempt,
                        exponentialBackoffMs(attempt),
                        lifecycleGeneration
                    )
                } else {
                    if (precheck.retryable) {
                        reportTerminalOpenFailure(precheck.reason, onError, lifecycleGeneration)
                    } else {
                        onError(precheck.reason)
                    }
                }
                return
            }
            else -> {
                releaseOpenLockIfHeld(openAttempt)
                val retryable = precheck is CameraLifecycleOutcome.RetryableFailure ||
                        precheck == CameraLifecycleOutcome.Timeout ||
                        precheck == CameraLifecycleOutcome.HardwareUnavailable
                val message = "Camera precondition failed: ${precheck::class.simpleName}"
                Log.w(tag, "$message (attempt=${host.initializationAttempts})")
                if (retryable && CameraSessionLifecycleCoordinator.shouldRetryAttempt(attempt, maxAttempts)) {
                    scheduleRetry(
                        onReady,
                        onError,
                        attempt,
                        exponentialBackoffMs(attempt),
                        lifecycleGeneration
                    )
                } else {
                    if (retryable) {
                        reportTerminalOpenFailure(message, onError, lifecycleGeneration)
                    } else {
                        onError(message)
                    }
                }
                return
            }
        }
        
        val bgHandler = host.backgroundHandler
        if (bgHandler == null) {
            releaseOpenLockIfHeld(openAttempt)
            if (openAttemptGate.isCurrent(lifecycleGeneration)) {
                onError("Background handler not available")
            }
            return
        }

        try {
            host.setupReaders()
        } catch (failure: Throwable) {
            releaseOpenLockIfHeld(openAttempt)
            pendingCameraOpenStartElapsedMs = 0L
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            val message = "Camera output setup failed: ${failure.message ?: failure.javaClass.simpleName}"
            Log.e(tag, message, failure)
            reportTerminalOpenFailure(message, onError, lifecycleGeneration)
            return
        }
        if (!openAttemptGate.isCurrent(lifecycleGeneration)) {
            releaseOpenLockIfHeld(openAttempt)
            return
        }
        host.transitionCameraState(CameraState.Opening, "open-camera")
        pendingCameraOpenStartElapsedMs = SystemClock.elapsedRealtime()
        val requestedCameraId = host.currentCameraId
        
        Log.i(tag, "Opening camera $requestedCameraId (attempt=${host.initializationAttempts})")

        try {
            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    if (isStaleCameraCallback(cam, requestedCameraId, openAttempt)) {
                        ignoreStaleCameraCallback(cam, requestedCameraId, openAttempt, "onOpened")
                        return
                    }
                    releaseOpenLockIfHeld(openAttempt)
                    if (host.stateFlow.value == CameraState.Closing) {
                        pendingCameraOpenStartElapsedMs = 0L
                        Log.w(tag, "Ignoring onOpened callback while camera is closing")
                        runCatching { cam.close() }
                        return
                    }
                    
                    host.cameraDevice = cam
                    host.reopenAttempts = 0
                    host.initializationAttempts = 0
                    host.sessionRecoveryPending = false
                    host.clearCameraAvailabilityRecovery()
                    
                    val openStart = pendingCameraOpenStartElapsedMs
                    if (openStart > 0L) {
                        RuntimeStageTelemetry.recordDuration(
                            tag = tag,
                            stage = "openCamera",
                            durationMs = SystemClock.elapsedRealtime() - openStart,
                            slowThresholdMs = 1500L
                        )
                    }
                    pendingCameraOpenStartElapsedMs = 0L
                    host.transitionCameraState(CameraState.Open, "device-opened")
                    Log.i(tag, "Camera device opened successfully")
                    
                    host.onDeviceOpened(cam, onReady, onError)
                    host.startPreviewWatchdog()
                }

                override fun onDisconnected(cam: CameraDevice) {
                    if (isStaleCameraCallback(cam, requestedCameraId, openAttempt)) {
                        ignoreStaleCameraCallback(cam, requestedCameraId, openAttempt, "onDisconnected")
                        return
                    }
                    pendingCameraOpenStartElapsedMs = 0L
                    Log.w(tag, "Camera disconnected during initialization")
                    handleCameraDeviceLoss(
                        cam,
                        "Camera disconnected",
                        allowRetry = true,
                        openAttempt = openAttempt
                    )
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    if (isStaleCameraCallback(cam, requestedCameraId, openAttempt)) {
                        ignoreStaleCameraCallback(cam, requestedCameraId, openAttempt, "onError($error)")
                        return
                    }
                    pendingCameraOpenStartElapsedMs = 0L
                    Log.e(tag, "Camera device error $error during initialization")
                    handleCameraDeviceLoss(
                        cam = cam,
                        message = "Camera error $error",
                        allowRetry = CameraLifecyclePolicy.shouldRetryOnError(error),
                        externalOnError = onError,
                        openAttempt = openAttempt
                    )
                }

                override fun onClosed(cam: CameraDevice) {
                    host.acknowledgeCameraDeviceClosed(cam)
                    Log.i(tag, "Camera device close acknowledged by Camera2")
                }
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                CameraLifecyclePolicy.shouldUseExecutorCameraOpen(Build.VERSION.SDK_INT)
            ) {
                host.cameraManager.openCamera(
                    requestedCameraId,
                    host.cameraCallbackExecutor,
                    stateCallback
                )
            } else {
                @Suppress("DEPRECATION")
                host.cameraManager.openCamera(requestedCameraId, stateCallback, bgHandler)
            }
        } catch (e: CameraAccessException) {
            releaseOpenLockIfHeld(openAttempt)
            pendingCameraOpenStartElapsedMs = 0L
            if (!openAttemptGate.isCurrent(lifecycleGeneration)) return
            val errorMsg = "CameraAccessException: ${e.message ?: "unknown error"}"
            Log.e(tag, "Camera access exception during open: $errorMsg", e)
            host.transitionCameraState(CameraState.Error(errorMsg), "camera-access-exception")
            
            if (CameraSessionLifecycleCoordinator.shouldRetryAttempt(attempt, maxAttempts)) {
                scheduleRetry(
                    onReady,
                    onError,
                    attempt,
                    exponentialBackoffMs(attempt),
                    lifecycleGeneration
                )
            } else {
                reportTerminalOpenFailure(errorMsg, onError, lifecycleGeneration)
            }
        } catch (e: IllegalArgumentException) {
            releaseOpenLockIfHeld(openAttempt)
            pendingCameraOpenStartElapsedMs = 0L
            if (!openAttemptGate.isCurrent(lifecycleGeneration)) return
            val errorMsg = "IllegalArgument: ${e.message ?: "invalid camera ID"}"
            Log.e(tag, "Invalid camera configuration: $errorMsg", e)
            host.transitionCameraState(CameraState.Error(errorMsg), "invalid-camera-configuration")
            host.currentCameraId = ""
            if (CameraSessionLifecycleCoordinator.shouldRetryAttempt(attempt, maxAttempts)) {
                scheduleRetry(
                    onReady,
                    onError,
                    attempt,
                    exponentialBackoffMs(attempt),
                    lifecycleGeneration
                )
            } else {
                reportTerminalOpenFailure(errorMsg, onError, lifecycleGeneration)
            }
        } catch (e: Exception) {
            releaseOpenLockIfHeld(openAttempt)
            pendingCameraOpenStartElapsedMs = 0L
            if (!openAttemptGate.isCurrent(lifecycleGeneration)) return
            val errorMsg = "Unexpected error during camera open: ${e.message ?: "unknown"}"
            Log.e(tag, errorMsg, e)
            host.transitionCameraState(CameraState.Error(errorMsg), "unexpected-open-error")
            
            if (CameraSessionLifecycleCoordinator.shouldRetryAttempt(attempt, maxAttempts)) {
                scheduleRetry(
                    onReady,
                    onError,
                    attempt,
                    exponentialBackoffMs(attempt),
                    lifecycleGeneration
                )
            } else {
                reportTerminalOpenFailure(errorMsg, onError, lifecycleGeneration)
            }
        }
    }

    fun closeCamera() {
        // Invalidate delayed retries and same-camera callbacks before waiting for the physical permit.
        // The owning open callback can still release its own permit, but it can no longer publish state.
        openAttemptGate.invalidateLifecycle()
        pendingCameraOpenStartElapsedMs = 0L
        val acquired = try {
            openLock.tryAcquire(2000L, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) {
            Log.w(tag, "closeCamera: timed out waiting for camera lock, forcing best-effort close")
            if (openAttemptGate.releaseOrphanedPermit()) {
                // The lifecycle was invalidated before the wait. A late Camera2 callback now fails its
                // attempt-id CAS and cannot release this semaphore a second time.
                openLock.release()
                Log.w(tag, "Released orphaned camera-open permit after close timeout")
            }
        }
        try {
            val releaseTicket = host.beginCameraRelease()
            Log.i(
                tag,
                "Camera2 release requested generation=${releaseTicket.generation} " +
                    host.pendingCameraReleaseDescription(releaseTicket)
            )
            host.transitionCameraState(CameraState.Closing, "close-camera")
            host.clearCameraAvailabilityRecovery()
            host.reopenAttempts = 0
            host.sessionRecoveryPending = false
            host.recordingSafeSessionFallback = false
            host.conservativeSessionFallback = false
            host.clearPendingInFlightRefresh()
            host.stillCaptureJpegEnabled = false
            host.stillCaptureRawEnabled = false
            host.lastOpenReadyCallback = null
            host.lastOpenErrorCallback = null
            host.stopPreviewWatchdog()
            
            host.releaseCameraResources(clearPreviewSurface = true)

            val releaseAcknowledged = try {
                host.awaitCameraRelease(releaseTicket, CAMERA_RELEASE_TIMEOUT_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            } catch (failure: RuntimeException) {
                Log.w(tag, "Camera2 close acknowledgement failed", failure)
                false
            }
            if (!releaseAcknowledged) {
                // CameraDevice.close() and every owned ImageReader close have already been issued. A
                // missing vendor onClosed callback must not strand the app in Closing or crash the
                // settings/foreground resume path. The next open remains generation-gated and uses the
                // normal CameraManager retry/availability recovery if the service is still releasing.
                Log.w(
                    tag,
                    "Camera2 close acknowledgement timed out; release remains best-effort: " +
                        host.pendingCameraReleaseDescription(releaseTicket)
                )
            }
            if (!host.isCameraReleaseFullyAcknowledged(releaseTicket)) {
                Log.w(
                    tag,
                    "Camera device release is definitive; vendor omitted session callback: " +
                        host.pendingCameraReleaseDescription(releaseTicket)
                )
            }
            Log.i(tag, "Camera2 release acknowledged generation=${releaseTicket.generation}")
            host.setCinemaFidelityOverridesEnabled(false)
            host.frameGeometryFlow.value = null
            host.transitionCameraState(CameraState.Closed, "close-camera-complete")
        } finally {
            if (acquired) {
                openLock.release()
            }
        }
    }

    private fun handleCameraDeviceLoss(
        cam: CameraDevice?,
        message: String,
        allowRetry: Boolean,
        externalOnError: ((String) -> Unit)? = null,
        openAttempt: CameraOpenAttemptGate.Attempt? = null
    ) {
        val deviceToClose = cam ?: host.cameraDevice
        deviceToClose?.close()
        host.cameraDevice = null
        host.prepareCameraSessionForDeviceRecovery()
        openAttempt?.let(::releaseOpenLockIfHeld)

        val isExpectedClose = host.stateFlow.value == CameraState.Closing || host.stateFlow.value == CameraState.Closed
        if (isExpectedClose) {
            Log.i(tag, "Camera closed normally ($message)")
            return
        }

        if (allowRetry && scheduleCameraReopen(openAttempt?.generation)) {
            Log.w(tag, "$message - scheduling camera reopen")
            host.transitionCameraState(CameraState.Opening, "camera-loss-reopen")
            return
        }
        
        host.transitionCameraState(CameraState.Error(message), "camera-device-loss")
        if (allowRetry) {
            host.armCameraAvailabilityRecovery(message)
        }
        externalOnError?.invoke(message)
    }

    private fun reportTerminalOpenFailure(
        message: String,
        onError: (String) -> Unit,
        lifecycleGeneration: Long
    ) {
        if (!openAttemptGate.isCurrent(lifecycleGeneration)) return
        host.transitionCameraState(CameraState.Error(message), "camera-open-terminal")
        host.armCameraAvailabilityRecovery(message)
        onError(message)
    }

    private fun isCameraIdOpenable(cameraId: String): Boolean {
        if (cameraId.isBlank()) return false
        return runCatching {
            val characteristics = host.cameraManager.getCameraCharacteristics(cameraId)
            val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ||
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        }.getOrElse { 
            Log.e(tag, "CRITICAL_HW_FAILURE: isCameraIdOpenable(cameraId=$cameraId)", it)
            false
        }
    }

    private fun isStaleCameraCallback(
        cam: CameraDevice,
        requestedCameraId: String,
        openAttempt: CameraOpenAttemptGate.Attempt
    ): Boolean =
        !openAttemptGate.isCurrent(openAttempt) ||
            cam.id != requestedCameraId ||
            host.currentCameraId != requestedCameraId

    private fun ignoreStaleCameraCallback(
        cam: CameraDevice,
        requestedCameraId: String,
        openAttempt: CameraOpenAttemptGate.Attempt,
        event: String
    ) {
        releaseOpenLockIfHeld(openAttempt)
        Log.w(
            tag,
            "Ignoring stale camera $event requested=$requestedCameraId " +
                "callback=${cam.id} current=${host.currentCameraId} " +
                "generation=${openAttempt.generation}"
        )
        runCatching { cam.close() }.onFailure {
            Log.w(tag, "Failed to close stale camera callback device ${cam.id}", it)
        }
    }

    private fun scheduleCameraReopen(expectedLifecycleGeneration: Long?): Boolean {
        val handler = host.backgroundHandler ?: return false
        val onReady = host.lastOpenReadyCallback ?: return false
        val onError = host.lastOpenErrorCallback ?: return false
        
        val reopenPlan = CameraLifecyclePolicy.resolveReopenPlan(
            currentAttempts = host.reopenAttempts,
            maxAttempts = 5, // CameraControllerConfig.MAX_CAMERA_REOPEN_ATTEMPTS
            hasPreviewSurface = host.getPreviewSurface()?.let { surface ->
                try {
                    surface.isValid
                } catch (e: RuntimeException) {
                    Log.w(tag, "Failed to query preview surface validity for reopen", e)
                    true
                }
            } ?: true,
            hasReadyCallback = true,
            hasErrorCallback = true,
            isClosing = host.stateFlow.value == CameraState.Closing,
            hasOpenDevice = host.cameraDevice != null
        )
        
        if (!reopenPlan.shouldSchedule) return false
        
        host.reopenAttempts = reopenPlan.nextAttemptCount
        Log.i(tag, "Scheduling camera reopen attempt=${host.reopenAttempts} delayMs=${reopenPlan.delayMs}")
        
        handler.postDelayed({
            if (expectedLifecycleGeneration != null &&
                !openAttemptGate.isCurrent(expectedLifecycleGeneration)
            ) {
                return@postDelayed
            }
            if (host.stateFlow.value == CameraState.Closing) return@postDelayed
            if (host.cameraDevice != null) return@postDelayed
            openCamera(onReady, onError)
        }, reopenPlan.delayMs)
        
        return true
    }

    private companion object {
        const val CAMERA_RELEASE_TIMEOUT_MS = 6_000L
    }

    private fun exponentialBackoffMs(attemptIndex: Int): Long {
        val delay = CameraSessionLifecycleCoordinator.exponentialBackoffMs(attemptIndex)
        Log.d(tag, "Exponential backoff: attempt=$attemptIndex delay=${delay}ms")
        return delay
    }

    private fun scheduleRetry(
        onReady: () -> Unit,
        onError: (String) -> Unit,
        currentAttempt: Int,
        delayMs: Long,
        lifecycleGeneration: Long
    ) {
        val handler = host.backgroundHandler ?: run {
            Log.e(tag, "Cannot schedule retry: bgHandler is null")
            onError("Background handler not available")
            return
        }
        
        handler.postDelayed({
            if (!openAttemptGate.isCurrent(lifecycleGeneration)) {
                Log.d(tag, "Discarding stale camera retry generation=$lifecycleGeneration")
                return@postDelayed
            }
            if (host.stateFlow.value == CameraState.Closing) {
                Log.d(tag, "Camera closing, aborting retry")
                return@postDelayed
            }
            Log.d(tag, "Retrying camera initialization (attempt=${currentAttempt + 2})")
            openCameraWithRetry(onReady, onError, currentAttempt + 1, lifecycleGeneration)
        }, delayMs)
    }

    private fun releaseOpenLockIfHeld(openAttempt: CameraOpenAttemptGate.Attempt) {
        if (openAttemptGate.releasePermit(openAttempt)) {
            openLock.release()
        }
    }
}
