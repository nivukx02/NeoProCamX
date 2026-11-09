package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Linearizable admission barrier for synchronous [android.media.ImageReader] callbacks.
 *
 * A reader-close owner first obtains a [FramePipelineImageIngressSeal], then performs the existing
 * bounded pipeline-idle wait. Admission and sealing share one lock, so a callback is unambiguously
 * either admitted before the seal (and represented by [hasInFlight]) or rejected before it can touch
 * the ImageReader. Multiple lifecycle owners may overlap: admission reopens only after every seal is
 * closed. Leaking a seal intentionally fails closed instead of permitting use-after-close.
 */
class SynchronousImageIngressGate(
    private val signalStateChanged: () -> Unit
) {
    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private var inFlight = 0
    private var sealDepth = 0

    /** Never waits for a seal to reopen; a callback arriving while sealed is rejected. */
    fun tryEnter(): SynchronousImageIngressLease? = lock.withLock {
        if (sealDepth > 0) return@withLock null
        check(inFlight < Int.MAX_VALUE) { "Synchronous image ingress accounting overflow" }
        inFlight++
        SynchronousImageIngressLease(::exit)
    }

    /**
     * Closes admission at this call's lock-linearization point. The returned token owns one nesting
     * level and is idempotent, allowing unconditional cleanup from cancellation and exception paths.
     */
    fun seal(): FramePipelineImageIngressSeal = lock.withLock {
        check(sealDepth < Int.MAX_VALUE) { "Synchronous image ingress seal-depth overflow" }
        sealDepth++
        FramePipelineImageIngressSeal(::releaseSeal)
    }

    fun hasInFlight(): Boolean = lock.withLock { inFlight > 0 }

    internal fun isSealed(): Boolean = lock.withLock { sealDepth > 0 }

    /** Bounded, condition-backed proof that every callback admitted before a seal has returned. */
    fun awaitIdle(timeoutMs: Long): Boolean = lock.withLock {
        var remainingNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        while (inFlight > 0) {
            if (remainingNs <= 0L) return@withLock false
            remainingNs = try {
                idle.awaitNanos(remainingNs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@withLock false
            }
        }
        true
    }

    /**
     * Executes [action] at a linearizable callback boundary.
     *
     * Admission is sealed before the idle wait, so a callback is either fully complete before
     * [action] or cannot enter until after [action]. This is used when P010 ownership changes from
     * preview coalescing to ordered recording: an already-admitted `acquireLatestImage()` callback
     * must not cross the recording-sink publication and silently coalesce a master frame.
     *
     * A timeout leaves [action] unexecuted and always reopens admission. Exceptions from [action]
     * propagate after the seal is released.
     */
    fun runAtSealedIdleBoundary(timeoutMs: Long, action: () -> Unit): Boolean {
        val seal = seal()
        return try {
            if (!awaitIdle(timeoutMs)) {
                false
            } else {
                action()
                true
            }
        } finally {
            seal.close()
        }
    }

    private fun exit() {
        lock.withLock {
            check(inFlight > 0) { "Synchronous image ingress accounting underflow" }
            inFlight--
            if (inFlight == 0) idle.signalAll()
        }
        // Never call into the quiescence condition while holding this gate's lock. Its waiter reads
        // hasInFlight() while holding the condition lock, so doing so would invert the lock order.
        signalStateChanged()
    }

    private fun releaseSeal() {
        lock.withLock {
            check(sealDepth > 0) { "Synchronous image ingress seal-depth underflow" }
            sealDepth--
        }
    }
}

/** One admitted callback. Closing twice is harmless and cannot underflow ingress accounting. */
class SynchronousImageIngressLease internal constructor(
    private val release: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

/**
 * Reader-close ownership token. Admission stays sealed until all independently-issued tokens close.
 */
class FramePipelineImageIngressSeal internal constructor(
    private val release: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}
