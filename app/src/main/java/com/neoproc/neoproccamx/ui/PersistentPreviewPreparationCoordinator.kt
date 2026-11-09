package com.neoproc.neoproccamx.ui

import android.util.Log
import android.util.Size
import android.view.Surface
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.rendering.Hdr10PqPreviewCompositor
import com.neoproc.neoproccamx.rendering.NdrPeakCapabilityPolicy
import com.neoproc.neoproccamx.rendering.PersistentPreviewOutputRouter
import com.neoproc.neoproccamx.rendering.PreviewGlCompositor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the cancellable, off-main EGL preparation transaction for the persistent SDR/HDR preview.
 * CameraViewModel supplies only current policy state and receives the committed owner, keeping
 * blocking EGL startup and partial-initialization cleanup outside the UI god object.
 */
internal class PersistentPreviewPreparationCoordinator(
    private val scope: CoroutineScope,
    private val camera: CameraController,
    private val router: PersistentPreviewOutputRouter,
    private val sdr: PreviewGlCompositor,
    private val hdr: Hdr10PqPreviewCompositor,
    private val presentation: PersistentPreviewPresentationCoordinator,
    private val runtimeFailure: StrictPreviewRuntimeFailureCoordinator
) {
    private enum class ActivationDisposition {
        ACTIVATED,
        RETRYABLE_STALE,
        TERMINAL_FAILURE
    }

    private data class ActivationResult(
        val disposition: ActivationDisposition,
        val detail: String,
        val failure: PersistentPreviewOutputRouter.StartResult? = null
    ) {
        companion object {
            fun activated(): ActivationResult = ActivationResult(
                disposition = ActivationDisposition.ACTIVATED,
                detail = "Current strict preview route activated"
            )

            fun retryable(detail: String): ActivationResult = ActivationResult(
                disposition = ActivationDisposition.RETRYABLE_STALE,
                detail = detail
            )

            fun terminal(failure: PersistentPreviewOutputRouter.StartResult): ActivationResult =
                ActivationResult(
                    disposition = ActivationDisposition.TERMINAL_FAILURE,
                    detail = failure.detail,
                    failure = failure
                )
        }
    }

    data class RenderState(
        val owner: PersistentPreviewOwner,
        val gradeCode: Int,
        val hdrProfile: Long?,
        val pqMode: Int,
        val peakPlan: NdrPeakCapabilityPolicy.Plan
    )

    data class Request(
        val visibleSurface: Surface,
        val previewSize: Size,
        val initialState: RenderState
    )

    data class Activation(
        val owner: PersistentPreviewOwner,
        val sdrInput: Surface,
        val hdrInput: Surface,
        val visibleSurface: Surface,
        val previewSize: Size
    )

    @Volatile var inProgress: Boolean = false
        private set
    @Volatile var failed: Boolean = false
        private set
    @Volatile private var generation = 0L
    private var job: Job? = null
    @Volatile private var retiringJob: Job? = null

    val jobActive: Boolean get() = job?.isActive == true

    fun invalidate(resetFailure: Boolean) {
        generation += 1L
        job?.let { invalidatedJob ->
            invalidatedJob.cancel()
            retiringJob = invalidatedJob
        }
        job = null
        inProgress = false
        if (resetFailure) failed = false
    }

    /** Permanently rejects the current route generation until the normal camera-close reset. */
    fun rejectRuntimeFailure(): Long {
        failed = true
        invalidate(resetFailure = false)
        return generation
    }

    fun isRuntimeFailureRetirementCurrent(retirementGeneration: Long): Boolean =
        failed && generation == retirementGeneration

    /**
     * Unregisters Camera2 outputs immediately and, when requested, returns the bounded retirement
     * job that releases their EGL producers before the visible router. Callers transferring the
     * SurfaceView to another producer must join the returned job before acquiring that surface.
     */
    fun detach(releaseInputs: Boolean): Job? {
        if (jobActive) invalidate(resetFailure = false)
        camera.cancelPersistentPreviewHandoff()
        camera.setPersistentPreviewOutputs(null, null)
        if (!releaseInputs) return retiringJob
        val predecessor = retiringJob
        if (predecessor?.isActive == true) {
            // Serialize another release after the retiring transaction. An activation-window runtime
            // failure can cancel a job whose `activated` flag is already true, so that job does not
            // own cleanup in its finally block; returning here would leak every EGL producer.
            return scheduleResourceRelease(predecessor)
        }
        if (!sdr.isRunning() && !hdr.isRunning() && !router.isRunning() &&
            !sdr.isReleasing() && !hdr.isReleasing() && !router.isReleasing()
        ) return predecessor

        return scheduleResourceRelease(predecessor = null)
    }

    fun resourcesFullyRetired(): Boolean =
        !sdr.isRunning() &&
            !hdr.isRunning() &&
            !router.isRunning() &&
            !sdr.isReleasing() &&
            !hdr.isReleasing() &&
            !router.isReleasing()

    private fun scheduleResourceRelease(predecessor: Job?): Job {
        val cleanup = scope.launch(Dispatchers.Default) {
            withContext(NonCancellable) {
                predecessor?.join()
                releaseResources()
            }
        }
        retiringJob = cleanup
        cleanup.invokeOnCompletion {
            if (retiringJob === cleanup) retiringJob = null
        }
        return cleanup
    }

    fun schedule(
        request: Request,
        isCurrent: () -> Boolean,
        currentState: () -> RenderState,
        onActivated: (Activation) -> Boolean,
        onStrictOutputRejected: (PersistentPreviewOutputRouter.StartResult) -> Unit,
        onFinished: () -> Unit
    ) {
        if (inProgress || failed || jobActive) return
        generation += 1L
        val requestGeneration = generation
        val predecessor = retiringJob
        inProgress = true
        job = scope.launch(Dispatchers.Default) {
            var activated = false
            var resourcesTouched = false
            var startResult = PersistentPreviewOutputRouter.StartResult.failure(
                PersistentPreviewOutputRouter.StartFailure.UNKNOWN,
                "Strict preview preparation did not reach router initialization"
            )
            var activationResult = ActivationResult.terminal(startResult)
            try {
                // A new surface can arrive before the cancelled EGL transaction has finished its
                // NonCancellable cleanup. Preserve a strict single-owner chain across rapid resumes.
                withContext(NonCancellable) { predecessor?.join() }
                if (retiringJob === predecessor) retiringJob = null
                if (!awaitResourceRelease() ||
                    requestGeneration != generation ||
                    !runCatching { request.visibleSurface.isValid }.getOrDefault(false)
                ) {
                    activationResult = ActivationResult.retryable(
                        "Strict preview request was superseded before EGL initialization"
                    )
                    Log.w(TAG, "Persistent preview preparation cancelled before EGL initialization")
                    return@launch
                }
                val initial = request.initialState
                val size = request.previewSize
                resourcesTouched = true
                startResult = router.startWithResult(
                    output = request.visibleSurface,
                    width = size.width,
                    height = size.height,
                    initialSource = initial.owner.routerSource
                )
                if (!startResult.started) {
                    activationResult = ActivationResult.terminal(startResult)
                }
                val sdrOutput = router.sdrOutputSurface()
                val hdrOutput = router.hdrOutputSurface()
                val childrenStarted = if (startResult.started && sdrOutput != null && hdrOutput != null) {
                    coroutineScope {
                        val sdrStarted = async {
                            sdr.start(sdrOutput, size.width, size.height, initial.gradeCode)
                        }
                        val hdrStarted = async {
                            hdr.start(
                                output = hdrOutput,
                                width = size.width,
                                height = size.height,
                                mode = initial.pqMode,
                                appliedProfile = initial.hdrProfile,
                                peakPlan = initial.peakPlan,
                                publishInitialBlack = false
                            )
                        }
                        sdrStarted.await() && hdrStarted.await()
                    }
                } else {
                    false
                }
                if (startResult.started && !childrenStarted) {
                    startResult = PersistentPreviewOutputRouter.StartResult.failure(
                        PersistentPreviewOutputRouter.StartFailure.UNKNOWN,
                        "Strict output started, but an SDR/HDR child compositor failed to initialize"
                    )
                    activationResult = ActivationResult.terminal(startResult)
                }
                val sdrInput = sdr.inputSurface()
                val hdrInput = hdr.inputSurface()
                if (childrenStarted && sdrInput != null && hdrInput != null) {
                    // Runtime callbacks become observable before setGrade/selectSource can enqueue
                    // any render work. Admission remains PENDING until activateIfCurrent commits.
                    runtimeFailure.activatePersistentRoute()
                    activationResult = withContext(Dispatchers.Main.immediate) {
                        activateIfCurrent(
                            request = request,
                            requestGeneration = requestGeneration,
                            sdrInput = sdrInput,
                            hdrInput = hdrInput,
                            isCurrent = isCurrent,
                            currentState = currentState,
                            onActivated = onActivated
                        )
                    }
                    activated = activationResult.disposition == ActivationDisposition.ACTIVATED
                    activationResult.failure?.let { failure ->
                        startResult = failure
                    }
                }
            } finally {
                if (!activated && resourcesTouched) {
                    runtimeFailure.deactivateRoute()
                    // Camera2 output ownership must be withdrawn before destroying the EGL producers.
                    // The cold-open path has not configured a session yet; hot retries still clear the
                    // controller's pending targets before their BufferQueues are retired.
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        camera.cancelPersistentPreviewHandoff()
                        camera.setPersistentPreviewOutputs(null, null)
                    }
                    withContext(NonCancellable) { releaseResources() }
                }
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (requestGeneration == generation) {
                        when (activationResult.disposition) {
                            ActivationDisposition.ACTIVATED -> Unit
                            ActivationDisposition.RETRYABLE_STALE -> Log.w(
                                TAG,
                                "Persistent strict preview activation superseded; latest request may retry: " +
                                    activationResult.detail
                            )
                            ActivationDisposition.TERMINAL_FAILURE -> {
                                failed = true
                                if (isCurrent() &&
                                    runCatching { request.visibleSurface.isValid }.getOrDefault(false)
                                ) {
                                    // A direct Camera2 surface is commonly RGBA8888/SDR and would disguise
                                    // this strict failure as a working NDR preview. Fail closed and let the
                                    // UI expose the typed capability error while the last validated buffer
                                    // remains owned by SurfaceFlinger.
                                    camera.setPreviewSurface(null)
                                    onStrictOutputRejected(startResult)
                                }
                                Log.e(
                                    TAG,
                                    "Persistent strict preview rejected failure=${startResult.failure} " +
                                        "detail=${startResult.detail}; direct fallback blocked"
                                )
                            }
                        }
                        inProgress = false
                        job = null
                        onFinished()
                    }
                }
            }
        }
    }

    private fun activateIfCurrent(
        request: Request,
        requestGeneration: Long,
        sdrInput: Surface,
        hdrInput: Surface,
        isCurrent: () -> Boolean,
        currentState: () -> RenderState,
        onActivated: (Activation) -> Boolean
    ): ActivationResult {
        if (requestGeneration != generation) {
            return ActivationResult.retryable("Preparation generation was superseded")
        }
        if (!isCurrent()) {
            return ActivationResult.retryable("Visible preview request was superseded")
        }
        if (!runCatching { request.visibleSurface.isValid }.getOrDefault(false)) {
            return ActivationResult.retryable("Visible preview surface became invalid")
        }

        // A harmless PENDING/rebind notification may have deactivated the route while this coroutine
        // waited for the main thread. Re-arm after freshness is proved. A claimed render failure stays
        // active-but-unhealthy and therefore cannot be erased by this call.
        runtimeFailure.activatePersistentRoute()
        if (!runtimeFailure.isPersistentRouteHealthy()) {
            return ActivationResult.terminal(
                PersistentPreviewOutputRouter.StartResult.failure(
                    PersistentPreviewOutputRouter.StartFailure.RUNTIME_EGL_FAILURE,
                    "Strict preview route became unhealthy before current-generation activation"
                )
            )
        }
        if (!camera.setPersistentPreviewOutputs(sdrInput, hdrInput)) {
            return ActivationResult.terminal(
                PersistentPreviewOutputRouter.StartResult.failure(
                    PersistentPreviewOutputRouter.StartFailure.UNKNOWN,
                    "Camera2 rejected the persistent SDR/HDR preview output pair"
                )
            )
        }

        val current = currentState()
        sdr.setGrade(current.gradeCode)
        hdr.setInputProfile(current.hdrProfile)
        hdr.setMode(current.pqMode)
        hdr.setPeakPlan(current.peakPlan)
        presentation.update(current.owner)
        if (!router.selectSource(current.owner.routerSource)) {
            camera.setPersistentPreviewOutputs(null, null)
            return ActivationResult.terminal(
                PersistentPreviewOutputRouter.StartResult.failure(
                    PersistentPreviewOutputRouter.StartFailure.UNKNOWN,
                    "Strict preview router rejected the current visible source"
                )
            )
        }
        if (!runtimeFailure.isPersistentRouteHealthy() ||
            !router.isRunning() || !sdr.isRunning() || !hdr.isRunning()
        ) {
            camera.setPersistentPreviewOutputs(null, null)
            return ActivationResult.terminal(
                PersistentPreviewOutputRouter.StartResult.failure(
                    PersistentPreviewOutputRouter.StartFailure.RUNTIME_EGL_FAILURE,
                    "Strict preview route or child compositor stopped during activation"
                )
            )
        }
        val accepted = onActivated(
            Activation(
                owner = current.owner,
                sdrInput = sdrInput,
                hdrInput = hdrInput,
                visibleSurface = request.visibleSurface,
                previewSize = request.previewSize
            )
        )
        val routeHealthyAfterOwnerCommit = runtimeFailure.isPersistentRouteHealthy()
        if (!accepted || !routeHealthyAfterOwnerCommit) {
            camera.setPersistentPreviewOutputs(null, null)
            return ActivationResult.terminal(
                PersistentPreviewOutputRouter.StartResult.failure(
                    if (routeHealthyAfterOwnerCommit) {
                        PersistentPreviewOutputRouter.StartFailure.UNKNOWN
                    } else {
                        PersistentPreviewOutputRouter.StartFailure.RUNTIME_EGL_FAILURE
                    },
                    if (accepted) {
                        "Strict preview route became unhealthy while committing activation"
                    } else {
                        "Current strict preview owner rejected activation"
                    }
                )
            )
        }
        camera.setPreviewSurface(if (current.owner == PersistentPreviewOwner.SDR) sdrInput else hdrInput)
        Log.i(TAG, "Persistent preview pipeline prepared off-main initial owner=${current.owner}")
        return ActivationResult.activated()
    }

    private suspend fun awaitResourceRelease(): Boolean {
        repeat(200) {
            if (!sdr.isReleasing() && !hdr.isReleasing() && !router.isReleasing()) return true
            delay(10L)
        }
        return !sdr.isReleasing() && !hdr.isReleasing() && !router.isReleasing()
    }

    private suspend fun releaseResources() {
        sdr.releaseAsync()
        hdr.releaseAsync()
        repeat(CHILD_RELEASE_WAIT_ATTEMPTS) {
            if (!sdr.isReleasing() && !hdr.isReleasing()) {
                // The router owns both child output queues. Releasing it before the children finish
                // can make an in-flight eglSwapBuffers target an abandoned BufferQueue.
                router.release()
                return
            }
            delay(RELEASE_POLL_MS)
        }
        Log.w(TAG, "Preview child teardown timed out; retaining router queues for safe retry")
    }

    private companion object {
        const val TAG = "CameraViewModel"
        const val CHILD_RELEASE_WAIT_ATTEMPTS = 300
        const val RELEASE_POLL_MS = 10L
    }
}
