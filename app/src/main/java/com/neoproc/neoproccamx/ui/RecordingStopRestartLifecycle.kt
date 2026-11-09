package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.RecordingPublicationIoGate

import android.util.Log
import com.neoproc.neoproccamx.recording.GpuRecordingState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Serializes a user restart request against the complete stop/finalization owner.
 *
 * A stop lease is opaque and generation-bound. At most one restart intent is retained for that
 * lease, duplicate shutter requests coalesce, and only the exact release result can claim the
 * replay. The external stop flag is changed through the reservation/release callbacks while this
 * monitor is held, so callers can never observe an unfenced gap between that flag and the
 * authoritative generation.
 */
internal class RecordingRestartAfterStopCoordinator {
    internal class StopLease internal constructor(val generation: Long)
    internal class ReplayPermit internal constructor(val stoppedGeneration: Long)

    internal sealed class StartDecision {
        object Claimed : StartDecision()
        data class Deferred(val stopGeneration: Long, val newlyRetained: Boolean) : StartDecision()
        object RejectedBusy : StartDecision()
        object RejectedTeardown : StartDecision()
    }

    internal sealed class StopReleaseDecision {
        object Released : StopReleaseDecision()
        data class ReplayReady(val permit: ReplayPermit) : StopReleaseDecision()
        object StaleLease : StopReleaseDecision()
    }

    internal enum class ReplayClaimDecision {
        CLAIMED,
        DEFERRED_TO_NEWER_STOP,
        CANCELLED_BY_TEARDOWN,
        STALE_PERMIT
    }

    private var nextStopGeneration = 0L
    private var activeStop: StopLease? = null
    private var retainedRestartGeneration: Long? = null
    private var outstandingReplay: ReplayPermit? = null
    private var teardown = false

    @Synchronized
    fun requestStart(): StartDecision {
        if (teardown) return StartDecision.RejectedTeardown
        activeStop?.let { stop ->
            val newlyRetained = retainedRestartGeneration != stop.generation
            retainedRestartGeneration = stop.generation
            return StartDecision.Deferred(stop.generation, newlyRetained)
        }
        outstandingReplay?.let { replay ->
            return StartDecision.Deferred(replay.stoppedGeneration, newlyRetained = false)
        }
        return StartDecision.Claimed
    }

    @Synchronized
    fun tryBeginStop(onOwnerReserved: () -> Unit = {}): StopLease? {
        if (teardown || activeStop != null || outstandingReplay != null) return null
        check(nextStopGeneration < Long.MAX_VALUE) { "Recording stop generation exhausted" }
        val lease = StopLease(++nextStopGeneration)
        activeStop = lease
        try {
            onOwnerReserved()
        } catch (failure: Throwable) {
            activeStop = null
            throw failure
        }
        return lease
    }

    @Synchronized
    fun activeStopLeaseForOwner(): StopLease? = activeStop

    @Synchronized
    fun releaseStop(
        lease: StopLease,
        onOwnerReleased: () -> Unit = {}
    ): StopReleaseDecision {
        if (activeStop !== lease) return StopReleaseDecision.StaleLease
        val replay = if (!teardown && retainedRestartGeneration == lease.generation) {
            ReplayPermit(lease.generation)
        } else {
            null
        }
        // Retire the externally visible owner while this monitor still excludes both a new stop
        // generation and direct start admission.
        onOwnerReleased()
        activeStop = null
        retainedRestartGeneration = null
        outstandingReplay = replay
        return if (replay != null) {
            StopReleaseDecision.ReplayReady(replay)
        } else {
            StopReleaseDecision.Released
        }
    }

    @Synchronized
    fun claimReplay(permit: ReplayPermit): ReplayClaimDecision {
        if (outstandingReplay !== permit) return ReplayClaimDecision.STALE_PERMIT
        if (teardown) {
            outstandingReplay = null
            return ReplayClaimDecision.CANCELLED_BY_TEARDOWN
        }
        activeStop?.let { newerStop ->
            retainedRestartGeneration = newerStop.generation
            outstandingReplay = null
            return ReplayClaimDecision.DEFERRED_TO_NEWER_STOP
        }
        outstandingReplay = null
        return ReplayClaimDecision.CLAIMED
    }

    @Synchronized
    fun cancelForTeardown(): Boolean {
        teardown = true
        val cancelled = retainedRestartGeneration != null || outstandingReplay != null
        retainedRestartGeneration = null
        // Keep an issued permit long enough for its queued claimant to observe explicit teardown
        // cancellation. It can never claim a start because teardown remains latched.
        return cancelled
    }
}

/**
 * Owns the complete start/cancel/stop/finalize/restart transaction for [CameraViewModel].
 *
 * Lock order is always startup handoff -> generation coordinator. A restart arriving while startup
 * cancellation still owns the handoff is therefore retained against the already-published stop
 * generation instead of being mistaken for a duplicate start and lost.
 */
internal class RecordingStopRestartLifecycle(
    private val scope: CoroutineScope,
    private val claimStartAdmission: () -> Boolean,
    private val beginClaimedStart: () -> Unit,
    private val hasActiveBackend: () -> Boolean,
    private val requestBackendStopAdmission: () -> Boolean,
    private val beginBackendStop: () -> Unit,
    private val setTransitionState: (GpuRecordingState) -> Unit,
    private val replayDeferredLifecycleRefreshIfSafe: () -> Unit,
    private val publishWarning: (String) -> Unit,
    private val teardownStarted: () -> Boolean
) {
    private val startupHandoff = RecordingStartupHandoff()
    private val stopInProgress = AtomicBoolean(false)
    private val restartAfterStop = RecordingRestartAfterStopCoordinator()
    private val startupCancellationStopLease =
        AtomicReference<RecordingRestartAfterStopCoordinator.StopLease?>(null)
    private val publicationCaptureLease = AtomicReference<RecordingPublicationIoGate.CaptureLease?>(null)

    val isStarting: Boolean get() = startupHandoff.isStarting
    val isCancellationRequested: Boolean get() = startupHandoff.isCancellationRequested
    val isStopInProgress: Boolean get() = stopInProgress.get()

    fun requestStart() {
        var decision: RecordingRestartAfterStopCoordinator.StartDecision? = null
        val startupClaimed = startupHandoff.tryBeginStart(
            retainRestartDuringPendingStop = {
                // requestStopDuringStart publishes the stop lease before stopRequested under the same
                // handoff lock, so this always observes that exact generation (or teardown).
                decision = restartAfterStop.requestStart()
            }
        ) {
            val coordinatorDecision = restartAfterStop.requestStart()
            decision = if (
                coordinatorDecision is RecordingRestartAfterStopCoordinator.StartDecision.Claimed &&
                !claimStartAdmission()
            ) {
                RecordingRestartAfterStopCoordinator.StartDecision.RejectedBusy
            } else {
                coordinatorDecision
            }
            decision is RecordingRestartAfterStopCoordinator.StartDecision.Claimed
        }
        when (val resolved = decision ?: RecordingRestartAfterStopCoordinator.StartDecision.RejectedBusy) {
            RecordingRestartAfterStopCoordinator.StartDecision.Claimed ->
                if (startupClaimed) beginStartWithPublicationIsolation()
            is RecordingRestartAfterStopCoordinator.StartDecision.Deferred -> Log.i(
                TAG,
                "Recording restart retained for stopGeneration=${resolved.stopGeneration} " +
                    "new=${resolved.newlyRetained}"
            )
            RecordingRestartAfterStopCoordinator.StartDecision.RejectedBusy ->
                Log.i(TAG, "startRecording ignored: another recording start/backend owns admission")
            RecordingRestartAfterStopCoordinator.StartDecision.RejectedTeardown ->
                Log.i(TAG, "startRecording rejected: ViewModel teardown has begun")
        }
    }

    fun registerStartupCancellationAction(action: () -> Unit) {
        startupHandoff.registerCancellationAction(action)
    }

    /** Called only by the off-main startup coroutine, before storage/encoder/RAW preparation. */
    fun awaitPublicationIdleBeforeStart() {
        val lease = checkNotNull(publicationCaptureLease.get()) {
            "Recording start has no publication-isolation owner"
        }
        check(lease.awaitBackgroundIdle(RecordingPublicationIoGate.START_HANDOFF_TIMEOUT_MS)) {
            "Recording storage handoff timed out; background publication has not quiesced"
        }
        Log.i(TAG, "RECORDING_PUBLICATION_IO isolated=true snapshot=${RecordingPublicationIoGate.process.snapshot()}")
    }

    /** Native retirement calls this only after every camera/recorder resource has been released. */
    fun releasePublicationIsolationAfterRetirement() = releasePublicationCaptureLease()

    private fun beginStartWithPublicationIsolation() {
        val lease = RecordingPublicationIoGate.process.acquireCapture()
        if (!publicationCaptureLease.compareAndSet(null, lease)) {
            lease.close()
            error("Recording publication isolation already has an owner")
        }
        try {
            beginClaimedStart()
        } catch (failure: Throwable) {
            if (!hasActiveBackend()) releasePublicationCaptureLease()
            throw failure
        }
    }

    private fun releasePublicationCaptureLease() {
        publicationCaptureLease.getAndSet(null)?.close()
    }

    /** Completes startup, then transfers or releases the exact pre-reserved stop owner. */
    fun completeStart(
        recordingSessionStarted: Boolean,
        beforeOwnerTransfer: () -> Unit
    ) {
        var completionStopLease: RecordingRestartAfterStopCoordinator.StopLease? = null
        val backendActive = recordingSessionStarted || hasActiveBackend()
        // A failed start still owns startupHandoff here. Retire its storage lease BEFORE that
        // handoff reopens admission; otherwise a rapid retry can see the stale lease, or the old
        // completion can close the retry's newer lease. Active sessions keep ownership until stop.
        if (!backendActive) releasePublicationCaptureLease()
        val handoffCompletion = startupHandoff.completeStart(
            backendActive = backendActive,
            reserveBackendStop = {
                completionStopLease = checkNotNull(startupCancellationStopLease.get()) {
                    "Recording startup stop was requested without a pre-reserved generation"
                }
                true
            }
        )
        if (handoffCompletion.stopWasRequested && completionStopLease == null) {
            completionStopLease = checkNotNull(startupCancellationStopLease.get()) {
                "Cancelled recording startup lost its pre-reserved stop generation"
            }
        }

        try {
            beforeOwnerTransfer()
        } finally {
            when {
                handoffCompletion.backendStopReserved -> {
                    val stopLease = checkNotNull(completionStopLease) {
                        "Recording startup reserved backend stop without a generation lease"
                    }
                    check(startupCancellationStopLease.compareAndSet(stopLease, null)) {
                        "Recording startup stop lease was not transferred exactly once"
                    }
                    setTransitionState(GpuRecordingState.Stopping)
                    Log.i(TAG, "Recording startup handed pending stop to active backend")
                    beginBackendStop()
                }
                handoffCompletion.stopWasRequested -> {
                    val cancelledStartLease = checkNotNull(completionStopLease)
                    check(startupCancellationStopLease.compareAndSet(cancelledStartLease, null)) {
                        "Cancelled recording startup stop lease was not released exactly once"
                    }
                    releaseStopOwnerWithoutBackend(cancelledStartLease)
                }
                !stopInProgress.get() && !startupHandoff.isStarting -> {
                    setTransitionState(GpuRecordingState.Idle)
                    if (!recordingSessionStarted && !hasActiveBackend()) {
                        replayDeferredLifecycleRefreshIfSafe()
                    }
                }
            }
        }
    }

    fun requestStop() {
        val startupStop = startupHandoff.requestStopDuringStart {
            val startupStopLease = checkNotNull(
                restartAfterStop.tryBeginStop {
                    check(stopInProgress.compareAndSet(false, true)) {
                        "Recording stop flag was already owned without a stop generation"
                    }
                }
            ) { "Recording startup stop could not reserve a generation" }
            if (!startupCancellationStopLease.compareAndSet(null, startupStopLease)) {
                val rollback = restartAfterStop.releaseStop(startupStopLease) {
                    stopInProgress.set(false)
                }
                check(rollback === RecordingRestartAfterStopCoordinator.StopReleaseDecision.Released) {
                    "Recording startup stop lease rollback lost generation ownership"
                }
                error("Recording startup cancellation lease already has an owner")
            }
        }
        if (startupStop.handledDuringStartup) {
            if (startupStop.newlyRequested) {
                setTransitionState(GpuRecordingState.Stopping)
                Log.i(TAG, "Shutter stop accepted during recording startup")
            }
            return
        }

        val stopLease = restartAfterStop.tryBeginStop {
            check(stopInProgress.compareAndSet(false, true)) {
                "Recording stop flag was already owned without a stop generation"
            }
        } ?: return
        val backendActive = hasActiveBackend()
        if (!requestBackendStopAdmission() && !backendActive) {
            releaseStopOwnerWithoutBackend(stopLease)
            return
        }
        setTransitionState(GpuRecordingState.Stopping)
        beginBackendStop()
    }

    fun releaseActiveStopOwnerWithoutBackend() {
        val stopLease = checkNotNull(restartAfterStop.activeStopLeaseForOwner()) {
            "Inactive recording backend had no stop generation to release"
        }
        releaseStopOwnerWithoutBackend(stopLease)
    }

    /** Installs exact stop ownership before any backend work can dispatch. */
    fun launchStopOwner(stopWork: suspend () -> Unit) {
        val stopLease = checkNotNull(restartAfterStop.activeStopLeaseForOwner()) {
            "Recording backend stop launched without a generation lease"
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) { stopWork() }
            } finally {
                releaseStopOwner(stopLease)
            }
        }
    }

    fun cancelForTeardown(): Boolean = restartAfterStop.cancelForTeardown()

    private fun releaseStopOwnerWithoutBackend(
        stopLease: RecordingRestartAfterStopCoordinator.StopLease
    ) {
        releaseStopOwner(stopLease)
    }

    private fun releaseStopOwner(stopLease: RecordingRestartAfterStopCoordinator.StopLease) {
        when (val release = restartAfterStop.releaseStop(stopLease) {
            stopInProgress.set(false)
            setTransitionState(GpuRecordingState.Idle)
            if (!hasActiveBackend()) releasePublicationCaptureLease()
        }) {
            is RecordingRestartAfterStopCoordinator.StopReleaseDecision.ReplayReady ->
                scheduleRetainedRestart(release.permit)
            RecordingRestartAfterStopCoordinator.StopReleaseDecision.Released ->
                if (!teardownStarted()) replayDeferredLifecycleRefreshIfSafe()
            RecordingRestartAfterStopCoordinator.StopReleaseDecision.StaleLease ->
                Log.e(TAG, "Recording stop owner attempted a stale generation release")
        }
    }

    private fun scheduleRetainedRestart(
        permit: RecordingRestartAfterStopCoordinator.ReplayPermit
    ) {
        // Queue on Main so the old stop owner returns completely before replacement startup executes.
        scope.launch(Dispatchers.Main, start = CoroutineStart.DEFAULT) {
            var backendAdmissionPassed = false
            var replayDecision = RecordingRestartAfterStopCoordinator.ReplayClaimDecision.STALE_PERMIT
            val startupClaimed = startupHandoff.tryBeginStart {
                replayDecision = restartAfterStop.claimReplay(permit)
                if (replayDecision == RecordingRestartAfterStopCoordinator.ReplayClaimDecision.CLAIMED) {
                    backendAdmissionPassed = claimStartAdmission()
                }
                replayDecision == RecordingRestartAfterStopCoordinator.ReplayClaimDecision.CLAIMED &&
                    backendAdmissionPassed
            }
            when (replayDecision) {
                RecordingRestartAfterStopCoordinator.ReplayClaimDecision.CLAIMED -> {
                    if (startupClaimed && backendAdmissionPassed) {
                        Log.i(TAG, "Replaying retained recording start after stopGeneration=${permit.stoppedGeneration}")
                        beginStartWithPublicationIsolation()
                    } else {
                        val message =
                            "Recording restart was replayed after finalization but admission is not ready"
                        Log.w(TAG, message)
                        publishWarning(message)
                        replayDeferredLifecycleRefreshIfSafe()
                    }
                }
                RecordingRestartAfterStopCoordinator.ReplayClaimDecision.DEFERRED_TO_NEWER_STOP ->
                    Log.i(TAG, "Retained recording restart moved to a newer stop generation")
                RecordingRestartAfterStopCoordinator.ReplayClaimDecision.CANCELLED_BY_TEARDOWN ->
                    Log.i(TAG, "Retained recording restart cancelled by ViewModel teardown")
                RecordingRestartAfterStopCoordinator.ReplayClaimDecision.STALE_PERMIT ->
                    Log.e(TAG, "Refused stale or duplicate retained recording restart permit")
            }
        }
    }

    private companion object {
        const val TAG = "CameraViewModel"
    }
}
