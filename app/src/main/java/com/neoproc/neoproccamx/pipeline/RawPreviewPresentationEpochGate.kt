package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.atomic.AtomicReference

/**
 * Atomic ownership gate for the visible RAW/NDR monitor.
 *
 * A transition first [begin]s an uncommitted epoch, which makes every frame from the previous
 * Camera2/profile configuration stale immediately. The owner [commit]s that exact epoch only after
 * the matching Camera2 session and Vulkan render configuration are ready. Frames are accepted only
 * when both the presentation epoch and Camera2 session generation match the committed pair.
 */
internal class RawPreviewPresentationEpochGate {
    internal enum class CommitOutcome {
        REJECTED,
        UNCHANGED,
        COMMITTED
    }

    internal data class Snapshot(
        val epoch: Long,
        val committedEpoch: Long,
        val sessionGeneration: Long,
        val identity: String
    ) {
        val committed: Boolean get() = committedEpoch == epoch && epoch > 0L
    }

    private val state = AtomicReference(
        Snapshot(
            epoch = 0L,
            committedEpoch = UNCOMMITTED_EPOCH,
            sessionGeneration = NO_SESSION_GENERATION,
            identity = "uninitialized"
        )
    )

    fun begin(identity: String): Long {
        while (true) {
            val previous = state.get()
            val nextEpoch = if (previous.epoch == Long.MAX_VALUE) 1L else previous.epoch + 1L
            val next = Snapshot(
                epoch = nextEpoch,
                committedEpoch = UNCOMMITTED_EPOCH,
                sessionGeneration = NO_SESSION_GENERATION,
                identity = identity
            )
            if (state.compareAndSet(previous, next)) return nextEpoch
        }
    }

    fun commitOutcome(epoch: Long, sessionGeneration: Long): CommitOutcome {
        if (epoch <= 0L || sessionGeneration < 0L) return CommitOutcome.REJECTED
        while (true) {
            val previous = state.get()
            if (previous.epoch != epoch) return CommitOutcome.REJECTED
            if (
                previous.committedEpoch == epoch &&
                previous.sessionGeneration == sessionGeneration
            ) return CommitOutcome.UNCHANGED
            val committed = previous.copy(
                committedEpoch = epoch,
                sessionGeneration = sessionGeneration
            )
            if (state.compareAndSet(previous, committed)) return CommitOutcome.COMMITTED
        }
    }

    fun commit(epoch: Long, sessionGeneration: Long): Boolean =
        commitOutcome(epoch, sessionGeneration) != CommitOutcome.REJECTED

    fun accepts(epoch: Long, sessionGeneration: Long): Boolean {
        val current = state.get()
        return current.committed &&
            current.epoch == epoch &&
            current.sessionGeneration == sessionGeneration
    }

    fun acceptsSession(sessionGeneration: Long): Boolean {
        val current = state.get()
        return current.committed && current.sessionGeneration == sessionGeneration
    }

    fun isCurrent(epoch: Long): Boolean = state.get().epoch == epoch

    fun snapshot(): Snapshot = state.get()

    private companion object {
        const val UNCOMMITTED_EPOCH = -1L
        const val NO_SESSION_GENERATION = -1L
    }
}
