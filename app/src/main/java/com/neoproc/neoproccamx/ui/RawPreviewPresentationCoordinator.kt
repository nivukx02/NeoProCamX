package com.neoproc.neoproccamx.ui

import android.media.ImageReader
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.pipeline.RawVideoManager
import com.neoproc.neoproccamx.pipeline.IdleRawPreviewSubmissionIdentity
import com.neoproc.neoproccamx.pipeline.SensorLinearNdrRecordingCompletion
import com.neoproc.neoproccamx.pipeline.SensorLinearNdrRecordingCompletionStatus
import com.neoproc.neoproccamx.pipeline.SensorLinearNdrRecordingRetryCause
import com.neoproc.neoproccamx.pipeline.SensorLinearNdrRecordingSubmission
import com.neoproc.neoproccamx.pipeline.SensorLinearNdrRecordingSubmissionStatus
import com.neoproc.neoproccamx.pipeline.SensorLinearNdrSessionGenerationPolicy
import com.neoproc.neoproccamx.rendering.Hdr10PqPreviewCompositor
import com.neoproc.neoproccamx.rendering.NdrPeakCapabilityPolicy
import com.neoproc.neoproccamx.rendering.PreviewGlCompositor
import com.neoproc.neoproccamx.rendering.RawDebayerPreviewPresenter
import com.neoproc.neoproccamx.rendering.RawDemosaicFrameIdentity
import com.neoproc.neoproccamx.rendering.SensorLinearNdrDemosaicCompletionStatus
import com.neoproc.neoproccamx.rendering.SensorLinearNdrDemosaicRetryCause
import com.neoproc.neoproccamx.rendering.SensorLinearNdrDemosaicSubmissionStatus
import com.neoproc.neoproccamx.rendering.SensorLinearNdrPreviewPresenter
import com.neoproc.neoproccamx.rendering.SensorLinearNdrPreviewPreparation
import com.neoproc.neoproccamx.rendering.SensorLinearNdrRecordingPreviewSubmission
import com.neoproc.neoproccamx.rendering.VulkanRuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Owns Camera2/RAW callback lineage and the two-phase visible debayer presentation epoch. */
internal class RawPreviewPresentationCoordinator(
    private val scope: CoroutineScope,
    private val camera: CameraController,
    private val rawVideoManager: RawVideoManager,
    private val vulkanRuntime: VulkanRuntimeManager,
    private val compositor: PreviewGlCompositor,
    private val ndrCompositor: Hdr10PqPreviewCompositor,
    private val stillCaptureExpectsRaw: () -> Boolean,
    private val onStillRawImageAvailable: (ImageReader) -> Unit,
    private val sensorDebayerRequested: () -> Boolean,
    private val sensorLinearNdrRequested: () -> Boolean,
    private val ndrPeakPlan: () -> NdrPeakCapabilityPolicy.Plan,
    private val identityForReason: (String) -> String,
    private val awaitRenderConfiguration: suspend () -> Unit,
    private val onEpochBegan: (epoch: Long, reason: String) -> Unit = { _, _ -> }
) {
    private val cameraSessionGeneration = camera.sessionGeneration
    private val isCameraSessionGenerationCurrent: (Long) -> Boolean =
        cameraSessionGeneration.let { generationOwner ->
            { expectedGeneration ->
                SensorLinearNdrSessionGenerationPolicy.isCurrent(
                    frameSessionGeneration = expectedGeneration,
                    currentSessionGeneration = generationOwner.get()
                )
            }
        }
    private val presenter = RawDebayerPreviewPresenter(vulkanRuntime, compositor)
    private val ndrPresenter = SensorLinearNdrPreviewPresenter(
        vulkanRuntime = vulkanRuntime,
        compositor = ndrCompositor,
        isSessionGenerationCurrent = isCameraSessionGenerationCurrent
    )
    private val recordingNdrPreviewSink = SensorLinearNdrRecordingPreviewSink(
        presenter = ndrPresenter,
        isSessionGenerationCurrent = isCameraSessionGenerationCurrent
    )

    init {
        // The runtime retains only this AtomicLong-backed validator, not CameraController or the
        // ViewModel. Pending native completions can therefore prove their immutable Camera2 lineage
        // even after the UI coordinator has retired.
        vulkanRuntime.setSensorLinearNdrSessionGenerationValidator(
            isCameraSessionGenerationCurrent
        )
    }

    /**
     * Camera2 session callbacks arrive on the camera worker while mode/profile changes normally arrive
     * on the UI owner. Keep epoch publication, commit replacement, and session admission in one domain.
     * Render configuration and NDR surface preparation stay outside this monitor because neither may
     * delay a Camera2 session-advance invalidation.
     */
    private val transitionLock = Any()
    private var commitJob: Job? = null
    private var commitRequestId: Long = 0L
    private data class DeferredNdrRouteCommit(
        val expectedEpoch: Long,
        val sessionGeneration: Long,
        val reason: String
    )
    private val ndrRouteReadinessReplay =
        NdrRouteReadinessReplaySlot<DeferredNdrRouteCommit>()
    private var latestSessionGeneration: Long = camera.sessionGeneration.get()
    private var lastAdvancedSessionGeneration: Long = NO_SESSION_GENERATION
    @Volatile
    private var closed: Boolean = false

    @Volatile
    var callbacksActive: Boolean = false
        private set

    private val recordingResultRouteDiagnosticCount = AtomicInteger(0)

    @Volatile
    var pathActive: Boolean = false
        private set

    @Volatile
    private var sensorLinearPathActive: Boolean = false

    @Volatile
    var epoch: Long = 0L
        private set

    fun beginIfActive(reason: String): Long? = synchronized(transitionLock) {
        if (!closed && pathActive) beginLocked(reason) else null
    }

    fun begin(reason: String): Long = synchronized(transitionLock) {
        if (closed) epoch else beginLocked(reason)
    }

    private fun beginLocked(reason: String): Long {
        invalidatePendingCommitLocked()
        val nextEpoch = rawVideoManager.beginPreviewPresentationEpoch(identityForReason(reason))
        epoch = nextEpoch
        if (sensorLinearPathActive) {
            compositor.setDebayerPresentEnabled(false)
            compositor.clearDebayerPresent()
            ndrCompositor.setProjectOwnedNdrPresentEnabled(true)
            ndrCompositor.beginProjectOwnedNdrPresentationEpoch(nextEpoch)
        } else {
            ndrCompositor.setProjectOwnedNdrPresentEnabled(false)
            ndrCompositor.clearProjectOwnedNdrPresent()
            compositor.setDebayerPresentEnabled(true)
            compositor.beginDebayerPresentationEpoch(nextEpoch)
        }
        try {
            // The observer shares this serialized owner, so visible-route readiness is invalidated
            // before any matching commit can be scheduled by another thread.
            onEpochBegan(nextEpoch, reason)
        } catch (exception: Exception) {
            Log.e(TAG, "RAW monitor epoch-began observer failed epoch=$nextEpoch reason=$reason", exception)
        }
        return nextEpoch
    }

    private fun invalidatePendingCommitLocked() {
        commitRequestId = if (commitRequestId == Long.MAX_VALUE) 1L else commitRequestId + 1L
        commitJob?.cancel()
        commitJob = null
        ndrRouteReadinessReplay.invalidate()
    }

    private fun canCommitLocked(
        requestId: Long,
        expectedEpoch: Long,
        expectedSensorLinearPath: Boolean,
        sessionGeneration: Long
    ): Boolean =
        !closed &&
            requestId == commitRequestId &&
            pathActive &&
            expectedEpoch == epoch &&
            expectedSensorLinearPath == sensorLinearPathActive &&
            sessionGeneration == latestSessionGeneration &&
            sessionGeneration == camera.sessionGeneration.get() &&
            rawVideoManager.isPreviewPresentationEpochCurrent(expectedEpoch)

    private fun invalidateIfCameraAdvancedDuringCommitLocked(sessionGeneration: Long): Boolean {
        val currentGeneration = camera.sessionGeneration.get()
        if (currentGeneration == sessionGeneration) return false

        // CameraController publishes its AtomicLong before invoking its listener. If it changes while
        // this monitor owns the commit, invalidate now instead of exposing a short-lived stale route
        // while the session-advance callback waits for this lock.
        if (currentGeneration > latestSessionGeneration) latestSessionGeneration = currentGeneration
        if (currentGeneration > lastAdvancedSessionGeneration) {
            lastAdvancedSessionGeneration = currentGeneration
        }
        if (!closed && pathActive) {
            beginLocked("camera-session-raced-commit:$sessionGeneration->$currentGeneration")
        } else {
            invalidatePendingCommitLocked()
        }
        return true
    }

    fun updatePathActive(active: Boolean, reason: String): Boolean = synchronized(transitionLock) {
        if (closed) return@synchronized false
        val nextSensorLinear = active && sensorLinearNdrRequested()
        if (active == pathActive && nextSensorLinear == sensorLinearPathActive) {
            return@synchronized false
        }
        sensorLinearPathActive = nextSensorLinear
        if (active) pathActive = true
        beginLocked(reason)
        if (!active) {
            pathActive = false
            sensorLinearPathActive = false
            invalidatePendingCommitLocked()
            compositor.clearDebayerPresent()
            ndrCompositor.clearProjectOwnedNdrPresent()
        }
        true
    }

    fun scheduleCommit(
        expectedEpoch: Long = epoch,
        sessionGeneration: Long = camera.sessionGeneration.get(),
        reason: String
    ) {
        synchronized(transitionLock) {
            scheduleCommitLocked(expectedEpoch, sessionGeneration, reason)
        }
    }

    private fun scheduleCommitLocked(
        expectedEpoch: Long,
        sessionGeneration: Long,
        reason: String
    ) {
        // A configured callback from an older graph must not cancel the valid current request.
        if (
            closed || !pathActive || expectedEpoch != epoch ||
            sessionGeneration != latestSessionGeneration ||
            sessionGeneration != camera.sessionGeneration.get()
        ) return

        val expectedSensorLinearPath = sensorLinearPathActive
        invalidatePendingCommitLocked()
        val requestId = commitRequestId
        commitJob = scope.launch(Dispatchers.Default) {
            awaitRenderConfiguration()

            val prepareRequired = synchronized(transitionLock) {
                if (
                    !isActive || !canCommitLocked(
                        requestId = requestId,
                        expectedEpoch = expectedEpoch,
                        expectedSensorLinearPath = expectedSensorLinearPath,
                        sessionGeneration = sessionGeneration
                    )
                ) return@synchronized null
                expectedSensorLinearPath
            } ?: return@launch

            if (prepareRequired) {
                when (ndrPresenter.prepare(ndrPeakPlan())) {
                    SensorLinearNdrPreviewPreparation.PREPARED -> Unit
                    SensorLinearNdrPreviewPreparation.DEFERRED_UNREADY -> {
                        var retainedForReadinessReplay = false
                        synchronized(transitionLock) {
                            if (canCommitLocked(
                                    requestId = requestId,
                                    expectedEpoch = expectedEpoch,
                                    expectedSensorLinearPath = expectedSensorLinearPath,
                                    sessionGeneration = sessionGeneration
                                )
                            ) {
                                // Latest schedule owns commitRequestId, so this single slot naturally
                                // coalesces startup churn. No timer/retry loop and no warning/error:
                                // the actual compositor readiness edge replays it exactly once.
                                ndrRouteReadinessReplay.retainLatest(DeferredNdrRouteCommit(
                                    expectedEpoch = expectedEpoch,
                                    sessionGeneration = sessionGeneration,
                                    reason = reason
                                ))
                                // Close the missed-edge window: either readiness callback can run
                                // after prepare() observes UNREADY but before this request is retained.
                                // Both callbacks serialize on transitionLock, so checking the sticky
                                // compositor/runtime states *after* retention guarantees that the
                                // request is either consumed here or by the blocked readiness edge.
                                retainedForReadinessReplay = true
                                if (requestId == commitRequestId) commitJob = null
                            }
                        }
                        // Do not acquire compositor/Vulkan ownership while holding transitionLock.
                        // If an epoch, session, path, or lifecycle change wins during this probe,
                        // replayDeferredNdrRouteCommitLocked() revalidates it before scheduling.
                        if (retainedForReadinessReplay &&
                            currentNdrPreparationDependenciesReady()
                        ) {
                            synchronized(transitionLock) {
                                replayDeferredNdrRouteCommitLocked(
                                    "post-retention-readiness-recheck"
                                )
                            }
                        }
                        return@launch
                    }
                    SensorLinearNdrPreviewPreparation.FAILED -> {
                        Log.e(
                            TAG,
                            "Strict RAW-derived visible NDR route rejected ready native preparation; " +
                                "outgoing frame retained"
                        )
                        synchronized(transitionLock) {
                            if (requestId == commitRequestId) commitJob = null
                        }
                        return@launch
                    }
                }
            }

            synchronized(transitionLock) {
                if (
                    !isActive || !canCommitLocked(
                        requestId = requestId,
                        expectedEpoch = expectedEpoch,
                        expectedSensorLinearPath = expectedSensorLinearPath,
                        sessionGeneration = sessionGeneration
                    )
                ) return@synchronized

                // Prepare the visible consumer before opening producer admission. If the compositor
                // rejects this epoch, RawVideoManager must remain disarmed instead of feeding frames
                // into a route that can never present them.
                val compositorReady = if (expectedSensorLinearPath) {
                    ndrCompositor.commitProjectOwnedNdrPresentationEpoch(expectedEpoch)
                } else {
                    compositor.commitDebayerPresentationEpoch(expectedEpoch)
                }
                if (invalidateIfCameraAdvancedDuringCommitLocked(sessionGeneration)) {
                    return@synchronized
                }
                val managerReady = compositorReady && rawVideoManager.commitPreviewPresentationEpoch(
                    expectedEpoch,
                    sessionGeneration
                )
                if (invalidateIfCameraAdvancedDuringCommitLocked(sessionGeneration)) {
                    return@synchronized
                }
                if (compositorReady && !managerReady) {
                    // The producer gate rejected the exact pair after the visible consumer committed.
                    // Start a fresh uncommitted epoch immediately so the compositor cannot remain armed
                    // without a matching admission owner.
                    beginLocked("manager-commit-rejected:$reason")
                    return@synchronized
                }
                if (requestId == commitRequestId) commitJob = null
                if (managerReady && compositorReady) {
                    Log.i(
                        TAG,
                        "RAW monitor configured route=${if (expectedSensorLinearPath) "VISIBLE_NDR" else "VISIBLE_SDR"} " +
                            "epoch=$expectedEpoch session=$sessionGeneration " +
                            "reason=$reason; exact-frame admission active"
                    )
                }
            }
        }
    }

    /**
     * Sticky, side-effect-free counterpart of [SensorLinearNdrPreviewPresenter.prepare]. Native
     * surface binding remains outside [transitionLock]; this probe exists only to recover a
     * readiness edge that completed immediately before the deferred request was retained.
     */
    private fun currentNdrPreparationDependenciesReady(): Boolean {
        if (!ndrCompositor.isRunning()) return false
        if (ndrCompositor.projectOwnedNdrInputSurface() == null) return false
        if (ndrCompositor.projectOwnedNdrInputWidth() <= 0 ||
            ndrCompositor.projectOwnedNdrInputHeight() <= 0
        ) return false
        return vulkanRuntime.isCurrentNdrRenderProfilePacketReady()
    }

    /** Replays only the latest still-current deferred route after the compositor is actually live. */
    fun onNdrCompositorReady() {
        synchronized(transitionLock) {
            replayDeferredNdrRouteCommitLocked("ndr-compositor-ready")
        }
    }

    /** Symmetric second edge for the ordering where the compositor becomes ready first. */
    fun onNdrRenderProfileReady(profileGeneration: Long) {
        if (profileGeneration <= 0L) return
        synchronized(transitionLock) {
            replayDeferredNdrRouteCommitLocked("ndr-render-profile-ready:$profileGeneration")
        }
    }

    private fun replayDeferredNdrRouteCommitLocked(trigger: String) {
        val deferred = ndrRouteReadinessReplay.takeForReadinessEdge() ?: return
        if (closed || !pathActive || !sensorLinearPathActive ||
            deferred.expectedEpoch != epoch ||
            deferred.sessionGeneration != latestSessionGeneration ||
            deferred.sessionGeneration != camera.sessionGeneration.get() ||
            !rawVideoManager.isPreviewPresentationEpochCurrent(deferred.expectedEpoch)
        ) return
        scheduleCommitLocked(
            expectedEpoch = deferred.expectedEpoch,
            sessionGeneration = deferred.sessionGeneration,
            reason = "${deferred.reason}:$trigger"
        )
    }

    fun restartIfActive(reason: String) {
        synchronized(transitionLock) {
            if (closed || !pathActive) return
            val nextEpoch = beginLocked(reason)
            scheduleCommitLocked(nextEpoch, camera.sessionGeneration.get(), reason)
        }
    }

    fun onSessionGenerationAdvanced(generation: Long) {
        synchronized(transitionLock) {
            if (closed || generation < latestSessionGeneration) return
            val controllerGeneration = camera.sessionGeneration.get()
            if (generation != controllerGeneration) return
            latestSessionGeneration = generation
            if (generation == lastAdvancedSessionGeneration) return
            lastAdvancedSessionGeneration = generation
            if (pathActive) beginLocked("camera-session-advanced:$generation")
        }
    }

    fun onSessionGenerationConfigured(generation: Long) {
        synchronized(transitionLock) {
            if (closed || !pathActive) return
            val controllerGeneration = camera.sessionGeneration.get()
            if (generation != controllerGeneration || generation < latestSessionGeneration) return
            // Recover if listeners were installed between CameraController's advance and configured
            // notifications: commit is never allowed without first disarming a fresh epoch.
            if (generation != lastAdvancedSessionGeneration) {
                latestSessionGeneration = generation
                lastAdvancedSessionGeneration = generation
                beginLocked("camera-session-observed-configured:$generation")
            }
            scheduleCommitLocked(
                expectedEpoch = epoch,
                sessionGeneration = generation,
                reason = "camera-session-configured"
            )
        }
    }

    fun installCallbacks() = synchronized(transitionLock) {
        if (closed || !camera.supportsRaw) return@synchronized
        installCallbacksLocked()
    }

    private fun installCallbacksLocked() {
        if (!callbacksActive) {
            // Publish processors before CameraController can invoke the RAW callback.
            configureProcessors()
            camera.onRawReady = { reader ->
                if (stillCaptureExpectsRaw()) onStillRawImageAvailable(reader)
                else rawVideoManager.onRawFrameAvailable(reader)
            }
            callbacksActive = true
        } else {
            configureProcessors()
        }
    }

    fun restoreCallbacks() = synchronized(transitionLock) {
        restoreCallbacksLocked()
    }

    private fun restoreCallbacksLocked() {
        camera.onRawReady = null
        rawVideoManager.rawPreviewProcessor = null
        rawVideoManager.recordingPreviewBytesProcessor = null
        if (!rawVideoManager.isSensorLinearNdrRecordingIngressActive()) {
            rawVideoManager.sensorLinearNdrRecordingProcessor = null
            rawVideoManager.sensorLinearNdrRecordingCompletionPoller = null
            vulkanRuntime.setSensorLinearNdrRecordingPreviewListener(null)
        }
        compositor.setDebayerPresentEnabled(false)
        ndrCompositor.setProjectOwnedNdrPresentEnabled(false)
        callbacksActive = false
    }

    fun reconcileCallbacks(callbacksNeeded: Boolean) = synchronized(transitionLock) {
        if (closed) return@synchronized
        if (callbacksNeeded) installCallbacksLocked() else {
            restoreCallbacksLocked()
            compositor.clearDebayerPresent()
        }
    }

    /**
     * Invoked by CameraViewModel's dedicated early RAW result lane. This is a Camera2 capture-result
     * hot path: it must not wait behind general result observers or an epoch transition that can close
     * Images, reconfigure an EGL route, or prepare a compositor. The volatile flags are admission hints
     * only; RawVideoManager's exact timestamp/session-generation checks remain the authority if
     * close/restore races this call.
     */
    fun onCaptureResult(result: TotalCaptureResult, sessionGeneration: Long) {
        val recordingIngressActive = rawVideoManager.isSensorLinearNdrRecordingIngressActive()
        if (!recordingIngressActive) recordingResultRouteDiagnosticCount.set(0)
        if (closed || !callbacksActive ||
            !isCameraSessionGenerationCurrent(sessionGeneration)
        ) {
            if (recordingIngressActive) {
                val index = recordingResultRouteDiagnosticCount.getAndIncrement()
                if (index < RESULT_ROUTE_DIAGNOSTIC_LIMIT) {
                    Log.i(
                        TAG,
                        "NDR_RESULT_CALLBACK index=$index disposition=DISCARDED " +
                            "closed=$closed callbacksActive=$callbacksActive " +
                            "callbackSession=$sessionGeneration " +
                            "currentSession=${camera.sessionGeneration.get()}"
                    )
                }
            }
            return
        }
        if (recordingIngressActive) {
            val index = recordingResultRouteDiagnosticCount.getAndIncrement()
            if (index < RESULT_ROUTE_DIAGNOSTIC_LIMIT) {
                Log.i(
                    TAG,
                    "NDR_RESULT_CALLBACK index=$index disposition=FORWARDED " +
                        "closed=$closed callbacksActive=$callbacksActive " +
                        "callbackSession=$sessionGeneration " +
                        "currentSession=${camera.sessionGeneration.get()}"
                )
            }
        }
        rawVideoManager.onCaptureResult(result, sessionGeneration)
    }

    private fun configureProcessors() {
        val sensorDebayer = sensorDebayerRequested()
        val previewRuntime = vulkanRuntime
        val completedFrameSink = CompletedRawFramePresentationSink(
            rawVideoManager = rawVideoManager,
            sensorLinearPath = sensorLinearPathActive,
            rawPresenter = presenter,
            ndrPresenter = ndrPresenter,
            isSessionGenerationCurrent = isCameraSessionGenerationCurrent
        )
        rawVideoManager.rawPreviewProcessor = if (sensorDebayer) {
            { image, descriptor, submissionIdentity ->
                if (!isCameraSessionGenerationCurrent(submissionIdentity.sessionGeneration)) {
                    false
                } else {
                    previewRuntime.submitRawSensorImage(
                        image = image,
                        descriptor = descriptor,
                        presentationEpoch = submissionIdentity.presentationEpoch,
                        cameraSessionGeneration = submissionIdentity.sessionGeneration
                    ) { completed -> completedFrameSink.present(completed, submissionIdentity) }
                }
            }
        } else null
        if (sensorDebayer && sensorLinearPathActive) {
            // These callbacks can outlive the visible preview coordinator while an admitted recording
            // frame retires. Capture the runtime directly so a quarantined native call does not retain
            // CameraViewModel through this coordinator and its UI-facing providers.
            val recordingRuntime = vulkanRuntime
            recordingRuntime.setSensorLinearNdrRecordingPreviewListener(
                recordingNdrPreviewSink::present
            )
            rawVideoManager.sensorLinearNdrRecordingProcessor =
                { image, descriptor, presentationEpoch, recordingIdentity ->
                    val submission = recordingRuntime.submitSensorLinearNdrRecordingImage(
                        image = image,
                        descriptor = descriptor,
                        presentationEpoch = presentationEpoch,
                        recordingIdentity = recordingIdentity
                    )
                    SensorLinearNdrRecordingSubmission(
                        status = when (submission.status) {
                            SensorLinearNdrDemosaicSubmissionStatus.ACCEPTED ->
                                SensorLinearNdrRecordingSubmissionStatus.ACCEPTED
                            SensorLinearNdrDemosaicSubmissionStatus.RETRY_PENDING ->
                                SensorLinearNdrRecordingSubmissionStatus.RETRY_PENDING
                            SensorLinearNdrDemosaicSubmissionStatus.FAILED ->
                                SensorLinearNdrRecordingSubmissionStatus.FAILED
                        },
                        completionPending = submission.completionPending,
                        retryCause = when (submission.retryCause) {
                            SensorLinearNdrDemosaicRetryCause.NATIVE_OUTPUT ->
                                SensorLinearNdrRecordingRetryCause.NATIVE_OUTPUT
                            SensorLinearNdrDemosaicRetryCause.PREVIEW_TRANSPORT ->
                                SensorLinearNdrRecordingRetryCause.PREVIEW_TRANSPORT
                            SensorLinearNdrDemosaicRetryCause.RENDER_PROFILE_TRANSITION ->
                                SensorLinearNdrRecordingRetryCause.RENDER_PROFILE_TRANSITION
                            null -> null
                        },
                        reason = submission.reason
                    )
                }
            rawVideoManager.sensorLinearNdrRecordingCompletionPoller = {
                val completion = recordingRuntime.pollSensorLinearNdrRecordingCompletion()
                SensorLinearNdrRecordingCompletion(
                    status = when (completion.status) {
                        SensorLinearNdrDemosaicCompletionStatus.IDLE ->
                            SensorLinearNdrRecordingCompletionStatus.IDLE
                        SensorLinearNdrDemosaicCompletionStatus.COMPLETED ->
                            SensorLinearNdrRecordingCompletionStatus.COMPLETED
                        SensorLinearNdrDemosaicCompletionStatus.PENDING ->
                            SensorLinearNdrRecordingCompletionStatus.PENDING
                        SensorLinearNdrDemosaicCompletionStatus.FAILED ->
                            SensorLinearNdrRecordingCompletionStatus.FAILED
                    },
                    reason = completion.reason
                )
            }
        } else if (!rawVideoManager.isSensorLinearNdrRecordingIngressActive()) {
            rawVideoManager.sensorLinearNdrRecordingProcessor = null
            rawVideoManager.sensorLinearNdrRecordingCompletionPoller = null
            vulkanRuntime.setSensorLinearNdrRecordingPreviewListener(null)
        }
        rawVideoManager.recordingPreviewBytesProcessor = null
        compositor.setDebayerPresentEnabled(sensorDebayer && !sensorLinearPathActive)
        ndrCompositor.setProjectOwnedNdrPresentEnabled(sensorDebayer && sensorLinearPathActive)
        if (!sensorDebayer || sensorLinearPathActive) compositor.clearDebayerPresent()
        if (!sensorDebayer || !sensorLinearPathActive) ndrCompositor.clearProjectOwnedNdrPresent()
    }

    /**
     * Retires Camera2/UI admission synchronously and transfers only the native preview-surface close
     * operation to the caller. The returned action captures the compositor and Vulkan runtime directly,
     * not this coordinator or its ViewModel-facing providers, and is safe to run on a teardown worker.
     */
    fun retireCallbacksForTeardown(): () -> Boolean {
        synchronized(transitionLock) {
            if (!closed) {
                closed = true
                pathActive = false
                sensorLinearPathActive = false
                invalidatePendingCommitLocked()
                ndrRouteReadinessReplay.close()
                epoch = rawVideoManager.beginPreviewPresentationEpoch(
                    identityForReason("coordinator-close")
                )
                // Callback/processors are retired under the same owner that prevents any late install.
                restoreCallbacksLocked()
                compositor.clearDebayerPresent()
                ndrCompositor.clearProjectOwnedNdrPresent()
            }
        }
        val runtimeToRetire = vulkanRuntime
        val compositorToRetire = ndrCompositor
        return {
            compositorToRetire.clearProjectOwnedNdrPresent()
            runtimeToRetire.releaseSensorLinearNdrPreviewSurface()
        }
    }

    fun close(): Boolean = retireCallbacksForTeardown().invoke()

    private companion object {
        const val TAG = "RawPreviewEpoch"
        const val NO_SESSION_GENERATION = -1L
        const val RESULT_ROUTE_DIAGNOSTIC_LIMIT = 16
    }
}

/**
 * Recording-time fan-out detached from coordinator/ViewModel lifetime. The runtime invokes this only
 * after MediaCodec accepted the exact completed frame and before its native export slot is acknowledged.
 */
private class SensorLinearNdrRecordingPreviewSink(
    private val presenter: SensorLinearNdrPreviewPresenter,
    private val isSessionGenerationCurrent: (Long) -> Boolean
) {
    fun present(submission: SensorLinearNdrRecordingPreviewSubmission): Boolean {
        val completed = submission.frame
        if (!isSessionGenerationCurrent(completed.cameraSessionGeneration)) return false
        return presenter.present(submission)
    }
}

/** Immutable sink detached from CameraViewModel/coordinator lifetime for in-flight native completion. */
private class CompletedRawFramePresentationSink(
    private val rawVideoManager: RawVideoManager,
    private val sensorLinearPath: Boolean,
    private val rawPresenter: RawDebayerPreviewPresenter,
    private val ndrPresenter: SensorLinearNdrPreviewPresenter,
    private val isSessionGenerationCurrent: (Long) -> Boolean
) {
    fun present(
        completed: RawDemosaicFrameIdentity,
        submissionIdentity: IdleRawPreviewSubmissionIdentity
    ) {
        if (completed.presentationEpoch != submissionIdentity.presentationEpoch ||
            completed.sensorTimestampNs != submissionIdentity.sensorTimestampNs ||
            completed.cameraSessionGeneration != submissionIdentity.sessionGeneration ||
            !isSessionGenerationCurrent(submissionIdentity.sessionGeneration)
        ) return
        rawVideoManager.presentIdleRawPreviewCompletionIfCurrent(submissionIdentity) {
            if (!isSessionGenerationCurrent(submissionIdentity.sessionGeneration)) {
                return@presentIdleRawPreviewCompletionIfCurrent
            }
            if (sensorLinearPath) {
                val colorProof = completed.projectOwnedNdrColorProof ?: return@presentIdleRawPreviewCompletionIfCurrent
                ndrPresenter.present(
                    presentationEpoch = completed.presentationEpoch,
                    width = completed.width,
                    height = completed.height,
                    sensorTimestampNs = completed.sensorTimestampNs,
                    sessionGeneration = completed.cameraSessionGeneration,
                    colorProof = colorProof
                )
            } else {
                rawPresenter.present(completed.presentationEpoch, completed.sensorTimestampNs)
            }
        }
    }
}
