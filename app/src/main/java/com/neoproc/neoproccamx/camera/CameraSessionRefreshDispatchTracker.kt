package com.neoproc.neoproccamx.camera

import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Epoch-scoped ownership for a full Camera2 session-refresh dispatch.
 *
 * A refresh crosses two asynchronous queues before it reaches the camera thread. A plain counter
 * cannot distinguish an old lifecycle's completion from a new lifecycle's work, and a rejected
 * Handler post can otherwise leave the counter permanently positive. Tickets make completion
 * exact-once and make lifecycle invalidation immune to that ABA race.
 */
internal class CameraSessionRefreshDispatchTracker {
    class Ticket internal constructor(
        val epoch: Long,
        val sequence: Long
    )

    private class Dispatch(
        val ticket: Ticket,
        val onInvalidated: () -> Unit,
        var running: Boolean = false
    )

    private val lock = Any()
    private val lifecycleBarrier = ReentrantReadWriteLock(true)
    private var epoch = 0L
    private var nextSequence = 0L
    private var acceptingDispatches = true
    private val activeDispatches = linkedMapOf<Long, Dispatch>()

    /** Returns null while the camera lifecycle is closed and cannot accept asynchronous work. */
    fun begin(onInvalidated: () -> Unit = {}): Ticket? {
        val readLock = lifecycleBarrier.readLock()
        readLock.lock()
        return try {
            synchronized(lock) {
                if (!acceptingDispatches) return@synchronized null
                val ticket = Ticket(epoch = epoch, sequence = ++nextSequence)
                check(activeDispatches.put(ticket.sequence, Dispatch(ticket, onInvalidated)) == null) {
                    "Duplicate Camera2 refresh dispatch sequence ${ticket.sequence}"
                }
                ticket
            }
        } finally {
            readLock.unlock()
        }
    }

    fun isActive(ticket: Ticket): Boolean = synchronized(lock) {
        val dispatch = activeDispatches[ticket.sequence]
        ticket.epoch == epoch && dispatch?.ticket === ticket
    }

    /** Returns true only for the exact current-lifecycle dispatch that owned the completion. */
    fun complete(ticket: Ticket): Boolean = synchronized(lock) {
        val dispatch = activeDispatches[ticket.sequence]
        if (ticket.epoch != epoch || dispatch?.ticket !== ticket) {
            false
        } else {
            activeDispatches.remove(ticket.sequence)
            true
        }
    }

    /**
     * Runs [action] only while lifecycle invalidation is excluded. This closes the isActive/action
     * TOCTOU window: close/reopen cannot retire the ticket between admission and camera mutation.
     */
    fun executeIfActive(ticket: Ticket, action: () -> Unit): Boolean {
        val readLock = lifecycleBarrier.readLock()
        readLock.lock()
        return try {
            val claimed = synchronized(lock) {
                val dispatch = activeDispatches[ticket.sequence]
                if (
                    ticket.epoch != epoch ||
                    dispatch?.ticket !== ticket ||
                    dispatch.running
                ) {
                    false
                } else {
                    dispatch.running = true
                    true
                }
            }
            if (!claimed) {
                false
            } else {
                try {
                    action()
                } finally {
                    complete(ticket)
                }
                true
            }
        } finally {
            readLock.unlock()
        }
    }

    fun pendingCount(): Int = synchronized(lock) { activeDispatches.size }

    /**
     * Stops new dispatch admission, waits for an already-running camera action to leave its read
     * lease, and returns every queued completion callback for exact-once settlement by the caller.
     */
    fun invalidateLifecycle(): List<() -> Unit> {
        val writeLock = lifecycleBarrier.writeLock()
        writeLock.lock()
        return try {
            synchronized(lock) {
                epoch += 1L
                acceptingDispatches = false
                val callbacks = activeDispatches.values.map { it.onInvalidated }
                activeDispatches.clear()
                callbacks
            }
        } finally {
            writeLock.unlock()
        }
    }

    /** Reopens dispatch admission only after Camera2 has published the next device lifecycle. */
    fun resumeLifecycle(): Long {
        val writeLock = lifecycleBarrier.writeLock()
        writeLock.lock()
        return try {
            synchronized(lock) {
                acceptingDispatches = true
                epoch
            }
        } finally {
            writeLock.unlock()
        }
    }
}
