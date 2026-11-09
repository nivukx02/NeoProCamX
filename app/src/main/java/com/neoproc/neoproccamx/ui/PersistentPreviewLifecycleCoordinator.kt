package com.neoproc.neoproccamx.ui

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the persistent SDR/HDR handoff lifecycle and the stricter sensor-linear NDR readiness proof.
 * Texture acceptance may start an NDR warmup, but only a successful project-owned child swap can
 * advance its warm-frame count or authorize HDR visibility for the current RAW epoch/surface pair.
 */
internal class PersistentPreviewLifecycleCoordinator<I : Any>(
    private val scope: CoroutineScope,
    private val runOnMain: (() -> Unit) -> Unit,
    private val pipelineActive: () -> Boolean,
    private val sensorLinearNdrMode: () -> Boolean,
    private val currentPresentationEpoch: () -> Long,
    private val presentationEpochCurrent: (Long) -> Boolean,
    private val inputForOwner: (PersistentPreviewOwner) -> I?,
    private val beginCameraHandoff: (I) -> Boolean,
    private val cancelCameraHandoff: () -> Unit,
    private val completeCameraHandoff: (I) -> Boolean,
    private val selectOutput: (PersistentPreviewOwner) -> Boolean,
    private val updatePresentation: (PersistentPreviewOwner?, PersistentPreviewOwner?) -> Unit,
    private val onHandoffCommitted: (PersistentPreviewOwner) -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime
) {
    @Volatile var owner: PersistentPreviewOwner? = null
        private set
    @Volatile var targetOwner: PersistentPreviewOwner? = null
        private set
    @Volatile private var surfaceGeneration = 0L
    @Volatile private var candidateEpoch = Long.MIN_VALUE
    @Volatile private var candidateSurfaceGeneration = Long.MIN_VALUE
    @Volatile private var readyEpoch = Long.MIN_VALUE
    @Volatile private var readySurfaceGeneration = Long.MIN_VALUE
    private var handoffGeneration = 0L
    private var warmFrameCount = 0
    private var lastWarmTimestampNs = 0L
    private var retryJob: Job? = null
    private var timeoutJob: Job? = null

    fun readyOwner(desired: PersistentPreviewOwner): PersistentPreviewOwner {
        if (desired != PersistentPreviewOwner.HDR || !sensorLinearNdrMode()) return desired
        val epoch = currentPresentationEpoch()
        if (epoch > 0L && !presentationEpochCurrent(epoch)) return PersistentPreviewOwner.SDR
        val currentEpochReady = epoch > 0L && presentationEpochCurrent(epoch) &&
            readyEpoch == epoch && readySurfaceGeneration == surfaceGeneration
        if (currentEpochReady) return PersistentPreviewOwner.HDR

        // Never publish a live Camera2 SDR proxy on a cold sensor-linear launch. If a project-owned
        // HDR frame is already visible, retain that last valid buffer while the new epoch warms.
        return if (owner == null || owner == PersistentPreviewOwner.HDR) {
            PersistentPreviewOwner.HDR
        } else {
            PersistentPreviewOwner.SDR
        }
    }

    fun activateOwner(initialOwner: PersistentPreviewOwner) {
        owner = initialOwner
        targetOwner = null
        resetWarmFrames()
    }

    fun adoptOwner(newOwner: PersistentPreviewOwner) {
        owner = newOwner
    }

    fun invalidateSurface() {
        surfaceGeneration += 1L
        resetNdrProofForEpoch()
        if (targetOwner != null) {
            handoffGeneration += 1L
            clearJobs()
            cancelCameraHandoff()
            targetOwner = null
            resetWarmFrames()
            updatePresentation(owner, null)
        }
    }

    fun reset(releaseOwner: Boolean) {
        handoffGeneration += 1L
        clearJobs()
        if (targetOwner != null) cancelCameraHandoff()
        targetOwner = null
        resetWarmFrames()
        if (releaseOwner) {
            invalidateSurface()
            owner = null
        }
    }

    fun cancelPendingHandoff() {
        handoffGeneration += 1L
        clearJobs()
        cancelCameraHandoff()
        targetOwner = null
        resetWarmFrames()
    }

    /** Retain the last project-owned NDR output while a new RAW epoch is validated. */
    fun onPresentationEpochBegan(epoch: Long, reason: String) {
        runOnMain {
            if (epoch <= 0L || epoch != currentPresentationEpoch()) return@runOnMain
            resetNdrProofForEpoch()
            if (!pipelineActive() || !sensorLinearNdrMode()) return@runOnMain
            when {
                owner == PersistentPreviewOwner.HDR -> {
                    handoffGeneration += 1L
                    clearJobs()
                    if (targetOwner != null) cancelCameraHandoff()
                    targetOwner = null
                    resetWarmFrames()
                    updatePresentation(PersistentPreviewOwner.HDR, null)
                    selectOutput(PersistentPreviewOwner.HDR)
                    Log.i(TAG, "RAW preview epoch=$epoch retained project-owned HDR output; reason=$reason")
                }
                targetOwner == PersistentPreviewOwner.HDR -> {
                    handoffGeneration += 1L
                    clearJobs()
                    cancelCameraHandoff()
                    targetOwner = null
                    resetWarmFrames()
                    updatePresentation(PersistentPreviewOwner.SDR, null)
                    selectOutput(PersistentPreviewOwner.SDR)
                    Log.i(TAG, "RAW preview epoch=$epoch cancelled stale HDR warmup; SDR output retained; reason=$reason")
                }
            }
        }
    }

    fun beginHandoff(incomingOwner: PersistentPreviewOwner) {
        if (targetOwner == incomingOwner && (retryJob?.isActive == true || timeoutJob?.isActive == true)) return
        handoffGeneration += 1L
        val generation = handoffGeneration
        val replacingPendingHandoff = targetOwner != null
        clearJobs()
        if (replacingPendingHandoff) cancelCameraHandoff()
        targetOwner = incomingOwner
        resetWarmFrames()

        fun armWarmupIfConfigured(): Boolean {
            if (generation != handoffGeneration || targetOwner != incomingOwner) return false
            val incoming = inputForOwner(incomingOwner) ?: return false
            // Freeze the outgoing SDR child while the project-owned RAW/NDR result warms. Its
            // SurfaceTexture keeps the prior mode's last buffer instead of showing an SDR proxy.
            if (incomingOwner == PersistentPreviewOwner.HDR && sensorLinearNdrMode()) {
                updatePresentation(null, incomingOwner)
            } else {
                updatePresentation(owner, incomingOwner)
            }
            if (!beginCameraHandoff(incoming)) return false
            timeoutJob = scope.launch(mainDispatcher) {
                delay(WARMUP_TIMEOUT_MS)
                if (generation != handoffGeneration || targetOwner != incomingOwner) return@launch
                cancelCameraHandoff()
                targetOwner = null
                warmFrameCount = 0
                updatePresentation(owner, null)
                Log.w(TAG, "Persistent preview warmup timed out for owner=$incomingOwner; outgoing preview retained")
            }
            return true
        }

        if (armWarmupIfConfigured()) return
        retryJob = scope.launch(mainDispatcher) {
            val deadline = elapsedRealtimeMs() + CONFIG_WAIT_MS
            while (isActive && elapsedRealtimeMs() < deadline) {
                if (generation != handoffGeneration || targetOwner != incomingOwner) return@launch
                if (armWarmupIfConfigured()) {
                    retryJob = null
                    return@launch
                }
                delay(CONFIG_POLL_MS)
            }
            if (generation == handoffGeneration && targetOwner == incomingOwner) {
                targetOwner = null
                updatePresentation(owner, null)
                Log.w(TAG, "Persistent preview output was not configured in time; outgoing preview retained")
            }
            retryJob = null
        }
    }

    fun onInputFrame(
        inputOwner: PersistentPreviewOwner,
        timestampNs: Long,
        projectOwnedEpoch: Long? = null
    ) {
        if (timestampNs <= 0L || !pipelineActive()) return
        if (inputOwner == PersistentPreviewOwner.HDR && sensorLinearNdrMode()) {
            val completedEpoch = projectOwnedEpoch ?: return
            val observedSurfaceGeneration = surfaceGeneration
            if (!validEpoch(completedEpoch)) return
            runOnMain {
                if (!pipelineActive() || !sensorLinearNdrMode() ||
                    observedSurfaceGeneration != surfaceGeneration || !validEpoch(completedEpoch)
                ) return@runOnMain
                // This is a candidate only; readiness and warm counting require a successful EGL swap.
                candidateEpoch = completedEpoch
                candidateSurfaceGeneration = observedSurfaceGeneration
                if (owner == PersistentPreviewOwner.SDR && targetOwner == null) {
                    beginHandoff(PersistentPreviewOwner.HDR)
                }
            }
            return
        }
        if (targetOwner != inputOwner) return
        runOnMain {
            if (!pipelineActive() || targetOwner != inputOwner) return@runOnMain
            if (recordDistinctWarmFrame(timestampNs)) completeHandoff(inputOwner)
        }
    }

    /** Successful HDR child-surface swap proof; texture arrival alone never makes RAW/NDR visible. */
    fun onProjectOwnedFramePresented(presentationEpoch: Long, timestampNs: Long) {
        if (presentationEpoch <= 0L || timestampNs <= 0L) return
        val observedSurfaceGeneration = surfaceGeneration
        runOnMain {
            if (!pipelineActive() || !sensorLinearNdrMode() ||
                observedSurfaceGeneration != surfaceGeneration ||
                presentationEpoch != candidateEpoch ||
                observedSurfaceGeneration != candidateSurfaceGeneration ||
                !validEpoch(presentationEpoch)
            ) return@runOnMain
            if ((targetOwner == PersistentPreviewOwner.HDR ||
                    (targetOwner == null && owner == PersistentPreviewOwner.HDR)) &&
                recordDistinctWarmFrame(timestampNs)
            ) {
                // Readiness means the complete warm-frame contract, not merely the first successful
                // child swap. This prevents readyOwner() from selecting HDR one frame too early.
                readyEpoch = presentationEpoch
                readySurfaceGeneration = observedSurfaceGeneration
                if (targetOwner == PersistentPreviewOwner.HDR) {
                    completeHandoff(PersistentPreviewOwner.HDR)
                }
            }
        }
    }

    fun close() {
        handoffGeneration += 1L
        clearJobs()
        if (targetOwner != null) cancelCameraHandoff()
        targetOwner = null
        resetWarmFrames()
    }

    private fun completeHandoff(completedOwner: PersistentPreviewOwner) {
        if (targetOwner != completedOwner) return
        if (completedOwner == PersistentPreviewOwner.HDR && sensorLinearNdrMode()) {
            val epoch = currentPresentationEpoch()
            if (epoch <= 0L || readyEpoch != epoch ||
                readySurfaceGeneration != surfaceGeneration || !presentationEpochCurrent(epoch)
            ) {
                Log.w(TAG, "Rejected RAW/NDR preview handoff without current epoch/surface swap proof")
                return
            }
        }
        val generation = handoffGeneration
        clearJobs()
        val outgoingOwner = owner ?: return
        if (generation != handoffGeneration || !selectOutput(completedOwner)) {
            cancelCameraHandoff()
            if (generation == handoffGeneration) targetOwner = null
            updatePresentation(owner, null)
            Log.w(TAG, "Persistent preview source selection failed/staled; outgoing preview retained")
            return
        }
        val incoming = inputForOwner(completedOwner)
        if (incoming == null || !completeCameraHandoff(incoming)) {
            selectOutput(outgoingOwner)
            cancelCameraHandoff()
            targetOwner = null
            updatePresentation(outgoingOwner, null)
            return
        }
        owner = completedOwner
        targetOwner = null
        warmFrameCount = 0
        updatePresentation(completedOwner, null)
        onHandoffCommitted(completedOwner)
        Log.i(TAG, "Persistent preview handoff committed by stable output selection owner=$completedOwner " +
            "frames=$REQUIRED_WARM_FRAMES")
    }

    private fun validEpoch(epoch: Long): Boolean =
        epoch > 0L && epoch == currentPresentationEpoch() && presentationEpochCurrent(epoch)

    private fun recordDistinctWarmFrame(timestampNs: Long): Boolean {
        if (timestampNs == lastWarmTimestampNs) return false
        lastWarmTimestampNs = timestampNs
        warmFrameCount += 1
        return warmFrameCount >= REQUIRED_WARM_FRAMES
    }

    private fun resetNdrProofForEpoch() {
        candidateEpoch = Long.MIN_VALUE
        candidateSurfaceGeneration = Long.MIN_VALUE
        readyEpoch = Long.MIN_VALUE
        readySurfaceGeneration = Long.MIN_VALUE
    }

    private fun resetWarmFrames() {
        warmFrameCount = 0
        lastWarmTimestampNs = 0L
    }

    private fun clearJobs() {
        retryJob?.cancel()
        retryJob = null
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private companion object {
        const val TAG = "CameraViewModel"
        const val REQUIRED_WARM_FRAMES = 2
        const val WARMUP_TIMEOUT_MS = 1_600L
        const val CONFIG_WAIT_MS = 2_000L
        const val CONFIG_POLL_MS = 25L
    }
}
