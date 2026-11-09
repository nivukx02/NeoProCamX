package com.neoproc.neoproccamx.recording

import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Gives acquisition priority over optional, byte-identical public-library replication.
 *
 * A capture lease closes background admission synchronously. The off-main startup owner then waits
 * for the already admitted bounded I/O chunk to retire before arming any recording backend. Copy
 * and read-back resume at their exact stream positions after the final capture lease closes; no
 * samples, checksums, publication checks, or private masters are discarded to obtain priority.
 * This cannot remove unrelated applications' I/O or override the kernel's thermal/storage limits.
 */
internal class RecordingPublicationIoGate {
    private val lock = ReentrantLock(true)
    private val changed = lock.newCondition()
    private val captures = mutableSetOf<Long>()
    private var nextGeneration = 0L
    private var activeChunks = 0
    private var completedChunks = 0L
    private var pausedChunks = 0L

    data class Snapshot(
        val captureOwners: Int,
        val activeBackgroundChunks: Int,
        val completedBackgroundChunks: Long,
        val pausedBackgroundChunks: Long
    )

    class CaptureLease internal constructor(
        private val owner: RecordingPublicationIoGate,
        internal val generation: Long
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        fun awaitBackgroundIdle(timeoutMs: Long): Boolean {
            require(timeoutMs >= 0L) { "Storage handoff timeout must not be negative" }
            check(!closed.get()) { "Storage capture lease is already closed" }
            return owner.awaitBackgroundIdle(this, timeoutMs)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) owner.releaseCapture(generation)
        }
    }

    fun acquireCapture(): CaptureLease = lock.withLock {
        nextGeneration = Math.addExact(nextGeneration, 1L)
        captures += nextGeneration
        CaptureLease(this, nextGeneration)
    }

    fun snapshot(): Snapshot = lock.withLock {
        Snapshot(captures.size, activeChunks, completedChunks, pausedChunks)
    }

    /** Run only bounded work here. Never hold a permit for an entire multi-gigabyte publication. */
    fun <T> backgroundChunk(
        shouldContinue: () -> Boolean = { true },
        operation: () -> T
    ): T {
        lock.lockInterruptibly()
        try {
            var pauseCounted = false
            while (captures.isNotEmpty()) {
                checkContinuation(shouldContinue)
                if (!pauseCounted) {
                    pausedChunks += 1L
                    pauseCounted = true
                }
                // A paused conversion must still observe user cancellation without waiting for a
                // minutes-long recording to finish. This blocks only the optional background lane.
                changed.await(CANCELLATION_POLL_MS, TimeUnit.MILLISECONDS)
            }
            checkContinuation(shouldContinue)
            activeChunks += 1
        } finally {
            lock.unlock()
        }
        return try {
            operation()
        } finally {
            lock.withLock {
                check(activeChunks > 0) { "Publication I/O permit was retired more than once" }
                activeChunks -= 1
                completedChunks += 1L
                changed.signalAll()
            }
        }
    }

    private fun awaitBackgroundIdle(lease: CaptureLease, timeoutMs: Long): Boolean {
        var remainingNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.lockInterruptibly()
        return try {
            check(lease.generation in captures) { "Stale storage capture lease" }
            while (activeChunks != 0) {
                if (remainingNs <= 0L) return false
                remainingNs = changed.awaitNanos(remainingNs)
                check(lease.generation in captures) { "Storage capture lease closed during handoff" }
            }
            true
        } finally {
            lock.unlock()
        }
    }

    private fun releaseCapture(generation: Long) = lock.withLock {
        check(captures.remove(generation)) { "Unknown storage capture lease" }
        changed.signalAll()
    }

    private fun checkContinuation(shouldContinue: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || !shouldContinue()) {
            throw CancellationException("Background publication interrupted or cancelled")
        }
    }

    companion object {
        /** Process ownership is shared by Video, Pro, RAW and the MTP publication scheduler. */
        val process = RecordingPublicationIoGate()
        const val MAX_BACKGROUND_CHUNK_BYTES = 1024 * 1024
        const val START_HANDOFF_TIMEOUT_MS = 2_000L
        private const val CANCELLATION_POLL_MS = 100L
    }
}
