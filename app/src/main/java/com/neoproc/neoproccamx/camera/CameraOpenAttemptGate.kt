package com.neoproc.neoproccamx.camera

import java.util.concurrent.atomic.AtomicLong

/**
 * Generation and semaphore-ownership gate for asynchronous CameraDevice opens.
 *
 * Camera2 may deliver a callback after an intentional close, a same-camera reopen, or a newer retry.
 * Camera id equality cannot distinguish those lifecycles. Each public open/close boundary therefore
 * advances a generation, while every physical open attempt receives a unique permit-owner id.
 */
internal class CameraOpenAttemptGate {
    @ConsistentCopyVisibility
    data class Attempt internal constructor(
        val generation: Long,
        val id: Long
    )

    private val activeGeneration = AtomicLong(0L)
    private val nextAttemptId = AtomicLong(0L)
    private val permitOwnerAttemptId = AtomicLong(NO_ATTEMPT)

    fun beginOpenLifecycle(): Long = activeGeneration.incrementAndGet()

    fun invalidateLifecycle(): Long = activeGeneration.incrementAndGet()

    fun newAttempt(generation: Long): Attempt? {
        if (generation != activeGeneration.get()) return null
        val attempt = Attempt(generation = generation, id = nextAttemptId.incrementAndGet())
        return attempt.takeIf { isCurrent(it) }
    }

    fun isCurrent(generation: Long): Boolean = generation == activeGeneration.get()

    fun isCurrent(attempt: Attempt): Boolean = isCurrent(attempt.generation)

    /** Claims ownership only for the current lifecycle and only when no other attempt owns it. */
    fun claimPermit(attempt: Attempt): Boolean =
        isCurrent(attempt) && permitOwnerAttemptId.compareAndSet(NO_ATTEMPT, attempt.id)

    /**
     * Returns true exactly once for the owning attempt. A delayed callback from an older attempt can
     * never release the semaphore currently owned by a newer open.
     */
    fun releasePermit(attempt: Attempt): Boolean =
        permitOwnerAttemptId.compareAndSet(attempt.id, NO_ATTEMPT)

    /**
     * Clears an orphaned owner after close has invalidated the lifecycle and timed out waiting for the
     * vendor callback. getAndSet makes this mutually exclusive with a late callback's [releasePermit].
     */
    fun releaseOrphanedPermit(): Boolean =
        permitOwnerAttemptId.getAndSet(NO_ATTEMPT) != NO_ATTEMPT

    private companion object {
        const val NO_ATTEMPT = 0L
    }
}
