package com.neoproc.neoproccamx.rendering

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Fair frame/mutation boundary for the project-owned NDR native consumers.
 *
 * A frame holds a read lease from its first render-bundle read through the final Vulkan/EGL/JNI
 * submission. A render-profile mutation holds the write lease across every native setter. The fair
 * lock prevents new frame readers from overtaking a queued mutation while allowing already-admitted
 * frames to finish and independent frame readers to run concurrently.
 *
 * Invariants:
 * 1. Lock order is boundary lease -> render-profile generation -> native/EGL owner locks.
 * 2. A changed or failed packet closes ingress before its first native setter.
 * 3. Releasing the mutation lease never reopens ingress; only successful finalization can do that.
 * 4. A read-to-write upgrade fails before blocking, so a frame callback cannot deadlock itself.
 */
internal class NdrNativeFrameBoundary(
    initiallyOpen: Boolean = false,
    private val onFrameAdmissionEpochObserved: ((Long) -> Unit)? = null
) {
    internal data class Snapshot(
        val epoch: Long,
        val ingressOpen: Boolean,
        val activeFrameLeases: Int
    )

    internal class FrameLease private constructor(
        private val owner: NdrNativeFrameBoundary,
        private val ownerThread: Thread
    ) : Closeable {
        private val released = AtomicBoolean(false)

        override fun close() {
            check(Thread.currentThread() === ownerThread) {
                "NDR frame lease must be released by its acquiring thread"
            }
            if (released.compareAndSet(false, true)) owner.releaseFrameLease()
        }

        internal companion object {
            fun create(owner: NdrNativeFrameBoundary): FrameLease =
                FrameLease(owner, Thread.currentThread())
        }
    }

    /**
     * Read-side ownership reserved for retiring work that was admitted before ingress closed.
     *
     * Unlike [FrameLease], this lease never observes or changes the ingress bit and therefore cannot
     * admit a new camera frame. It uses the same fair read/write boundary so an in-flight native/EGL
     * receipt can be polled and acknowledged without racing a render-profile mutation.
     */
    internal class RetirementLease private constructor(
        private val owner: NdrNativeFrameBoundary,
        private val ownerThread: Thread
    ) : Closeable {
        private val released = AtomicBoolean(false)

        override fun close() {
            check(Thread.currentThread() === ownerThread) {
                "NDR retirement lease must be released by its acquiring thread"
            }
            if (released.compareAndSet(false, true)) owner.releaseRetirementLease()
        }

        internal companion object {
            fun create(owner: NdrNativeFrameBoundary): RetirementLease =
                RetirementLease(owner, Thread.currentThread())
        }
    }

    private val boundary = ReentrantReadWriteLock(true)
    private val ingressChanged = boundary.writeLock().newCondition()
    /** Epoch and open bit share one CAS word so external route invalidation cannot be overwritten. */
    private val ingressState = AtomicLong(encodeIngressState(epoch = 0L, open = initiallyOpen))
    private val activeFrameLeases = AtomicInteger(0)

    /**
     * Waits only for an active/queued packet mutation. Once a packet is merely staged, this returns
     * null immediately while ingress remains intentionally fail-closed; camera threads never wait for
     * owner publication.
     */
    fun acquireFrameLease(): FrameLease? {
        return acquireOpenFrameLease()
    }

    /**
     * Waits for a short, explicitly bounded render-profile transition before admitting a frame.
     *
     * Recording owns a finite ImageReader ring, so an unbounded wait here would merely move a drop
     * into Camera2. Conversely, immediately rejecting every frame while a coherent profile packet is
     * staged creates a real hole in an otherwise continuous recording. This method bridges only that
     * bounded transition: it never opens ingress itself and it still fails closed on timeout,
     * interruption, lifecycle invalidation, or failed profile publication.
     */
    fun acquireFrameLeaseAwaitingIngress(timeoutNanos: Long): FrameLease? {
        require(timeoutNanos >= 0L) { "timeoutNanos must be non-negative" }
        val startedAtNs = System.nanoTime()
        acquireOpenFrameLease(timeoutNanos)?.let { return it }
        if (timeoutNanos == 0L) return null

        var remainingNs = timeoutNanos - (System.nanoTime() - startedAtNs)
        while (remainingNs > 0L) {
            val write = boundary.writeLock()
            val locked = try {
                write.tryLock(remainingNs, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            if (!locked) return null
            try {
                while (!ingressOpen(ingressState.get())) {
                    remainingNs = timeoutNanos - (System.nanoTime() - startedAtNs)
                    if (remainingNs <= 0L) return null
                    remainingNs = try {
                        ingressChanged.awaitNanos(remainingNs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                    if (remainingNs <= 0L && !ingressOpen(ingressState.get())) return null
                }
            } finally {
                write.unlock()
            }

            acquireOpenFrameLease()?.let { return it }
            remainingNs = timeoutNanos - (System.nanoTime() - startedAtNs)
        }
        return null
    }

    private fun acquireOpenFrameLease(timeoutNanos: Long? = null): FrameLease? {
        val read = boundary.readLock()
        val locked = if (timeoutNanos == null) {
            read.lock()
            true
        } else {
            try {
                read.tryLock(timeoutNanos.coerceAtLeast(0L), TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }
        if (!locked) return null
        val observedState = ingressState.get()
        if (!ingressOpen(observedState)) {
            read.unlock()
            return null
        }
        try {
            onFrameAdmissionEpochObserved?.invoke(ingressEpoch(observedState))
        } catch (failure: Throwable) {
            read.unlock()
            throw failure
        }
        activeFrameLeases.incrementAndGet()
        // External route invalidation is intentionally lock-free. Revalidate the exact CAS word
        // after publishing this reader so an invalidation between the open read and publication
        // rejects the lease instead of admitting a frame into the retired route epoch.
        if (ingressState.get() != observedState) {
            releaseFrameLease()
            return null
        }
        return FrameLease.create(this)
    }

    /**
     * Acquires mutation-safe ownership for completion/fence retirement only. This deliberately remains
     * available while frame ingress is closed: a profile replacement must be able to drain the exact
     * old-profile native output that caused the replacement to defer. Callers must not dispatch new
     * camera work while holding this lease.
     */
    fun acquireRetirementLease(): RetirementLease {
        val read = boundary.readLock()
        read.lock()
        return RetirementLease.create(this)
    }

    /** Executes one complete packet/generation mutation with exclusive frame-boundary ownership. */
    fun <T> withMutationLease(block: () -> T): T {
        requireNoFrameLeaseUpgrade()
        val write = boundary.writeLock()
        write.lock()
        return try {
            block()
        } finally {
            write.unlock()
        }
    }

    /** Closes ingress under the mutation lease. Releasing that lease does not reopen it. */
    fun closeIngressForMutation() {
        check(boundary.isWriteLockedByCurrentThread) {
            "NDR frame ingress may change only under the mutation lease"
        }
        closeIngressAndAdvanceEpoch()
    }

    /**
     * Lock-free invalidation used by preview-route ownership changes.
     *
     * It never waits for a frame, native lock, or owner thread. If it races finalization after the
     * publication callback's proof check, its epoch change makes finalization's open CAS fail.
     */
    fun invalidateIngressFromExternalRouteChange(): Long =
        ingressEpoch(closeIngressAndAdvanceEpoch())

    /** Lock-free epoch read used to bind external route proofs to this exact ingress state. */
    fun currentIngressEpoch(): Long = ingressEpoch(ingressState.get())

    /**
     * Runs final owner publication exclusively and opens ingress only after it returns true. Any false
     * result or exception leaves ingress closed, while try/finally always releases the write lock.
     */
    fun finalizeIngress(publication: () -> Boolean): Boolean = withMutationLease {
        val expectedClosedState = closeIngressForFinalization()
        val published = publication()
        if (!published) return@withMutationLease false
        val openedState = encodeIngressState(
            epoch = ingressEpoch(expectedClosedState) + 1L,
            open = true
        )
        val opened = ingressState.compareAndSet(expectedClosedState, openedState)
        if (opened) ingressChanged.signalAll()
        opened
    }

    fun requireMutationLease() {
        check(boundary.isWriteLockedByCurrentThread) {
            "NDR native packet apply requires exclusive frame-boundary ownership"
        }
    }

    fun snapshot(): Snapshot {
        val read = boundary.readLock()
        read.lock()
        return try {
            val state = ingressState.get()
            Snapshot(
                epoch = ingressEpoch(state),
                ingressOpen = ingressOpen(state),
                activeFrameLeases = activeFrameLeases.get()
            )
        } finally {
            read.unlock()
        }
    }

    private fun requireNoFrameLeaseUpgrade() {
        check(boundary.readHoldCount == 0 || boundary.isWriteLockedByCurrentThread) {
            "NDR packet mutation cannot begin from inside a frame lease"
        }
    }

    private fun releaseFrameLease() {
        check(activeFrameLeases.decrementAndGet() >= 0) { "NDR frame lease accounting underflow" }
        boundary.readLock().unlock()
    }

    private fun releaseRetirementLease() {
        boundary.readLock().unlock()
    }

    private fun closeIngressForFinalization(): Long {
        while (true) {
            val current = ingressState.get()
            val closed = current and OPEN_BIT.inv()
            if (current == closed || ingressState.compareAndSet(current, closed)) return closed
        }
    }

    private fun closeIngressAndAdvanceEpoch(): Long {
        while (true) {
            val current = ingressState.get()
            val next = encodeIngressState(epoch = ingressEpoch(current) + 1L, open = false)
            if (ingressState.compareAndSet(current, next)) return next
        }
    }

    private companion object {
        const val OPEN_BIT = 1L

        fun encodeIngressState(epoch: Long, open: Boolean): Long =
            (epoch shl 1) or if (open) OPEN_BIT else 0L

        fun ingressEpoch(state: Long): Long = state ushr 1

        fun ingressOpen(state: Long): Boolean = state and OPEN_BIT != 0L
    }
}
