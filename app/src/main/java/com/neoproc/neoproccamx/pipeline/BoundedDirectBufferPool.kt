package com.neoproc.neoproccamx.pipeline

import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A hard-bounded pool for large direct buffers.
 *
 * Android direct buffers can be backed by non-movable ART arrays, so repeatedly calling
 * [ByteBuffer.allocateDirect] is not outside the managed-heap budget. The resident counter covers both
 * available and borrowed buffers and therefore prevents a stalled consumer from turning backpressure into
 * unbounded full-frame allocations.
 */
internal class BoundedDirectBufferPool(
    private val maxResidentBuffers: Int,
    private val allocator: (Int) -> ByteBuffer = ByteBuffer::allocateDirect
) : AutoCloseable {

    private val available = ArrayBlockingQueue<ByteBuffer>(maxResidentBuffers, true)
    private val residentBuffers = AtomicInteger(0)
    private val totalAllocations = AtomicInteger(0)
    private val closed = AtomicBoolean(false)

    init {
        require(maxResidentBuffers > 0) { "maxResidentBuffers must be positive" }
    }

    val residentCount: Int get() = residentBuffers.get()
    val availableCount: Int get() = available.size
    val allocationCount: Int get() = totalAllocations.get()

    /**
     * Borrow a buffer, allocating only while the resident bound has an unused slot. Once the bound is
     * reached, wait for a recycled buffer instead of allocating around storage backpressure.
     */
    fun acquire(minCapacity: Int, timeout: Long, unit: TimeUnit): ByteBuffer? {
        require(minCapacity > 0) { "minCapacity must be positive" }
        require(timeout >= 0L) { "timeout must not be negative" }
        if (closed.get()) return null

        val timeoutNs = unit.toNanos(timeout)
        val deadlineNs = System.nanoTime() + timeoutNs
        while (!closed.get()) {
            val immediate = available.poll()
            if (immediate != null) {
                if (immediate.capacity() >= minCapacity) return prepare(immediate)
                discardResidentBuffer()
                continue
            }

            if (tryReserveAllocationSlot()) {
                try {
                    val allocated = allocator(minCapacity)
                    totalAllocations.incrementAndGet()
                    check(allocated.capacity() >= minCapacity) {
                        "Direct-buffer allocator returned ${allocated.capacity()} bytes for $minCapacity"
                    }
                    return prepare(allocated)
                } catch (failure: Throwable) {
                    discardResidentBuffer()
                    throw failure
                }
            }

            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0L) return null
            val recycled = try {
                available.poll(remainingNs, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } ?: return null
            if (recycled.capacity() >= minCapacity) return prepare(recycled)
            discardResidentBuffer()
        }
        return null
    }

    /** Allocate the complete resident set before a latency-sensitive producer starts. */
    fun prewarm(minCapacity: Int): Boolean {
        val borrowed = ArrayList<ByteBuffer>(maxResidentBuffers)
        return try {
            repeat(maxResidentBuffers) {
                val buffer = acquire(minCapacity, 0L, TimeUnit.NANOSECONDS) ?: return false
                borrowed += buffer
            }
            true
        } finally {
            borrowed.forEach(::recycle)
        }
    }

    fun recycle(buffer: ByteBuffer) {
        buffer.clear()
        if (closed.get() || !available.offer(buffer)) {
            discardResidentBuffer()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        while (available.poll() != null) {
            discardResidentBuffer()
        }
    }

    private fun tryReserveAllocationSlot(): Boolean {
        while (true) {
            val current = residentBuffers.get()
            if (current >= maxResidentBuffers) return false
            if (residentBuffers.compareAndSet(current, current + 1)) return true
        }
    }

    private fun discardResidentBuffer() {
        val remaining = residentBuffers.decrementAndGet()
        check(remaining >= 0) { "Direct-buffer pool ownership was released more than once" }
    }

    private fun prepare(buffer: ByteBuffer): ByteBuffer = buffer.apply { clear() }
}
