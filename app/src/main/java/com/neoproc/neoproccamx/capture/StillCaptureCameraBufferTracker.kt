package com.neoproc.neoproccamx.capture

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Exact ownership proof for [android.media.Image] instances acquired by still-capture readers.
 *
 * Callback/drain idleness alone cannot prove that a reader is safe to close because RAW processing
 * deliberately retains an Image after its ImageReader callback returns. Every successful acquire
 * therefore owns one lease until, and only until, the matching Image.close() succeeds.
 */
internal class StillCaptureCameraBufferTracker {
    private val lock = ReentrantLock()
    private val allReleased = lock.newCondition()
    private var acquiredCount = 0L

    fun acquire(): StillCaptureCameraBufferLease = lock.withLock {
        check(acquiredCount < Long.MAX_VALUE) { "Still-capture camera-buffer accounting overflow" }
        acquiredCount += 1L
        StillCaptureCameraBufferLease(::release)
    }

    fun awaitReleased(timeoutMs: Long): Boolean = awaitReleasedNanos(
        TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
    )

    /** Uses an already-budgeted timeout so callers can share one deadline with ingress draining. */
    internal fun awaitReleasedNanos(timeoutNanos: Long): Boolean = lock.withLock {
        var remainingNanos = timeoutNanos.coerceAtLeast(0L)
        while (acquiredCount > 0L) {
            if (remainingNanos <= 0L) return@withLock false
            remainingNanos = try {
                allReleased.awaitNanos(remainingNanos)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@withLock false
            }
        }
        true
    }

    internal fun outstandingCount(): Long = lock.withLock { acquiredCount }

    private fun release() {
        lock.withLock {
            check(acquiredCount > 0L) { "Still-capture camera-buffer accounting underflow" }
            acquiredCount -= 1L
            if (acquiredCount == 0L) allReleased.signalAll()
        }
    }
}

/** Idempotent ownership token: duplicate cleanup cannot decrement exact accounting twice. */
internal class StillCaptureCameraBufferLease(
    private val release: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}
