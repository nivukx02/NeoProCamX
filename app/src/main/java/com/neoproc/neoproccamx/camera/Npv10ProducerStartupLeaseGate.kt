package com.neoproc.neoproccamx.camera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class Npv10ProducerStartupLeaseCommitResult(
    val committed: Boolean,
    val reason: String = ""
)

/**
 * Exact ownership gate for the short NPV10 interval between recording-request publication and the
 * first durably written P010 frame. CameraController evaluates this gate while holding its Camera2
 * submission lock, so a control refresh can neither slip between lease acquisition and the initial
 * request nor replace the proven request before writer/sink ownership is established.
 */
internal class Npv10ProducerStartupLeaseGate {
    internal class Lease internal constructor(
        val sessionGeneration: Long
    ) {
        internal val deferredRefresh = AtomicBoolean(false)
        internal val releaseRequested = AtomicBoolean(false)
        internal val revoked = AtomicBoolean(false)
        internal val commitCompletion = CompletableDeferred<Npv10ProducerStartupLeaseCommitResult>()
    }

    internal enum class SubmissionAdmission {
        ALLOWED,
        DEFERRED,
        STALE_AUTHORIZATION
    }

    internal data class ReleaseResult(
        val released: Boolean,
        val replayDeferredRefresh: Boolean,
        val duplicate: Boolean = false
    )

    private val active = AtomicReference<Lease?>(null)

    fun acquire(sessionGeneration: Long): Lease {
        require(sessionGeneration > 0L) { "NPV10 startup lease requires a configured session" }
        val lease = Lease(sessionGeneration)
        check(active.compareAndSet(null, lease)) { "An NPV10 producer startup lease is already active" }
        return lease
    }

    fun admitSubmission(
        sessionGeneration: Long,
        authorization: Lease?
    ): SubmissionAdmission {
        val current = active.get()
        if (authorization != null) {
            return if (current === authorization &&
                authorization.sessionGeneration == sessionGeneration &&
                !authorization.revoked.get()
            ) {
                SubmissionAdmission.ALLOWED
            } else {
                SubmissionAdmission.STALE_AUTHORIZATION
            }
        }
        if (current == null) return SubmissionAdmission.ALLOWED
        if (current.sessionGeneration != sessionGeneration) {
            return SubmissionAdmission.STALE_AUTHORIZATION
        }
        current.deferredRefresh.set(true)
        return SubmissionAdmission.DEFERRED
    }

    fun release(lease: Lease): ReleaseResult {
        if (!lease.releaseRequested.compareAndSet(false, true)) {
            return ReleaseResult(released = false, replayDeferredRefresh = false, duplicate = true)
        }
        if (active.get() !== lease || lease.revoked.get()) {
            failCommit(lease, "NPV10 producer startup lease was revoked before commit")
            return ReleaseResult(released = false, replayDeferredRefresh = false)
        }
        if (lease.deferredRefresh.get()) {
            // Keep the gate active. Only the exact authorized replay may retire it, atomically with
            // setRepeatingRequest, so no ordinary control submission can overtake the replay.
            return ReleaseResult(released = false, replayDeferredRefresh = true)
        }
        val released = active.compareAndSet(lease, null)
        if (released) completeCommit(lease)
        else failCommit(lease, "NPV10 producer startup lease ownership changed before commit")
        return ReleaseResult(released = released, replayDeferredRefresh = false)
    }

    /**
     * Claims one coalesced control refresh while retaining the startup lease.
     *
     * The caller publishes the refreshed request with this lease as its authorization, waits for
     * that exact producer to prove stable cadence, and checks again. Ordinary request publishers
     * remain excluded throughout, so a control update cannot slip between cadence proof and writer
     * attachment. A refresh arriving while the authorized replay is in flight simply sets the flag
     * again and is consumed by the next bounded stabilization pass.
     */
    fun claimDeferredReplayWhileRetained(lease: Lease): Boolean {
        if (active.get() !== lease || lease.releaseRequested.get() || lease.revoked.get()) {
            return false
        }
        return lease.deferredRefresh.compareAndSet(true, false)
    }

    /**
     * Commits a producer boundary only when no control refresh accumulated after cadence proof.
     *
     * Replaying a deferred Camera2 request after the first durable video frame can create a physical
     * one-frame HAL cadence hole. This strict commit therefore fails closed and releases the gate for
     * an ordinary replay; the caller must abort the unpublished startup rather than preserve a clip
     * whose timeline is already known to be unstable.
     */
    fun releaseAfterCadenceStabilization(lease: Lease): ReleaseResult {
        if (!lease.releaseRequested.compareAndSet(false, true)) {
            return ReleaseResult(released = false, replayDeferredRefresh = false, duplicate = true)
        }
        if (active.get() !== lease || lease.revoked.get()) {
            failCommit(lease, "NPV10 producer startup lease was revoked before stable commit")
            return ReleaseResult(released = false, replayDeferredRefresh = false)
        }
        if (lease.deferredRefresh.get()) {
            lease.revoked.set(true)
            val retired = active.compareAndSet(lease, null)
            failCommit(
                lease,
                "Camera2 controls changed after NPV10 cadence stabilization; startup must retry"
            )
            return ReleaseResult(
                released = false,
                replayDeferredRefresh = retired
            )
        }
        val released = active.compareAndSet(lease, null)
        if (released) completeCommit(lease)
        else failCommit(lease, "NPV10 producer startup lease ownership changed before stable commit")
        return ReleaseResult(released = released, replayDeferredRefresh = false)
    }

    fun completeDeferredReplay(lease: Lease): Boolean {
        val completed = lease.releaseRequested.get() && active.compareAndSet(lease, null)
        if (completed) completeCommit(lease)
        return completed
    }

    fun deferredReplayPending(lease: Lease): Boolean =
        active.get() === lease && lease.releaseRequested.get() && lease.deferredRefresh.get()

    fun revoke(lease: Lease, reason: String): Boolean {
        lease.revoked.set(true)
        val revoked = active.compareAndSet(lease, null)
        failCommit(lease, reason)
        return revoked
    }

    fun invalidate(reason: String = "NPV10 producer startup lease invalidated"): Boolean {
        while (true) {
            val invalidated = active.get() ?: return false
            invalidated.revoked.set(true)
            if (active.compareAndSet(invalidated, null)) {
                failCommit(invalidated, reason)
                return true
            }
        }
    }

    private fun completeCommit(lease: Lease) {
        lease.commitCompletion.complete(Npv10ProducerStartupLeaseCommitResult(committed = true))
    }

    private fun failCommit(lease: Lease, reason: String) {
        lease.commitCompletion.complete(
            Npv10ProducerStartupLeaseCommitResult(committed = false, reason = reason)
        )
    }
}

/** Caller-owned lease: commit is acknowledged atomically; close is an idempotent startup abort. */
internal class Npv10ProducerStartupLeaseHandle(
    internal val lease: Npv10ProducerStartupLeaseGate.Lease,
    private val requestCommit: (Npv10ProducerStartupLeaseGate.Lease) -> Unit,
    private val requestStableCommit: (Npv10ProducerStartupLeaseGate.Lease) -> Unit,
    private val replayDeferredRefresh: (Npv10ProducerStartupLeaseGate.Lease) -> Boolean,
    private val cancel: (Npv10ProducerStartupLeaseGate.Lease, String) -> Unit
) : AutoCloseable {
    private val terminalRequested = AtomicBoolean(false)

    val isRevoked: Boolean
        get() = lease.revoked.get()

    fun replayDeferredRefreshIfPending(): Boolean {
        check(!terminalRequested.get()) {
            "NPV10 producer startup lease is already terminal"
        }
        return replayDeferredRefresh(lease)
    }

    suspend fun commit(): Npv10ProducerStartupLeaseCommitResult {
        if (terminalRequested.compareAndSet(false, true)) {
            try {
                requestCommit(lease)
            } catch (failure: Throwable) {
                cancel(lease, "NPV10 producer startup lease commit request failed")
                throw failure
            }
        }
        return try {
            lease.commitCompletion.await()
        } catch (cancelled: CancellationException) {
            cancel(lease, "NPV10 producer startup lease commit wait was cancelled")
            throw cancelled
        }
    }

    suspend fun commitAfterCadenceStabilization(): Npv10ProducerStartupLeaseCommitResult {
        if (terminalRequested.compareAndSet(false, true)) {
            try {
                requestStableCommit(lease)
            } catch (failure: Throwable) {
                cancel(lease, "NPV10 stable producer startup lease commit request failed")
                throw failure
            }
        }
        return try {
            lease.commitCompletion.await()
        } catch (cancelled: CancellationException) {
            cancel(lease, "NPV10 stable producer startup lease commit wait was cancelled")
            throw cancelled
        }
    }

    override fun close() {
        if (terminalRequested.compareAndSet(false, true)) {
            cancel(lease, "NPV10 producer startup lease closed before commit")
        }
    }
}
