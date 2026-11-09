package com.neoproc.neoproccamx.pipeline

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe pool for managing YUV frame buffers to reduce memory allocation overhead.
 * Provides efficient direct-buffer reuse for video processing pipeline.
 *
 * Uses a lock-free concurrent deque so multiple threads can acquire/release
 * buffers without contention.
 */
class YuvFramePool(
    private val maxPoolSize: Int = 10,
    private val frameWidth: Int = 1920,
    private val frameHeight: Int = 1080,
    private val maxRetainedBufferBytes: Int = frameWidth.coerceAtLeast(1) *
        frameHeight.coerceAtLeast(1) * 3 / 2 * 4
) {
    private val availableBuffers = ConcurrentLinkedDeque<ByteBuffer>()
    private val pooledCount = AtomicInteger(0)
    private val createdCount = AtomicInteger(0)
    private val activeCount = AtomicInteger(0)

    /**
     * Acquires a buffer from the pool or creates a new one.
     */
    fun acquire(): ByteBuffer = acquire(defaultBufferSize())

    /**
     * Acquires a buffer large enough for a specific YUV420 frame.
     */
    fun acquire(requiredBytes: Int): ByteBuffer {
        val requiredCapacity = requiredBytes.coerceAtLeast(defaultBufferSize())
        val deferred = ArrayList<ByteBuffer>()
        while (true) {
            val pooled = availableBuffers.pollFirst() ?: break
            pooledCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            if (pooled.capacity() >= requiredCapacity) {
                restoreDeferred(deferred)
                activeCount.incrementAndGet()
                pooled.clear()
                return pooled
            }
            deferred.add(pooled)
        }
        restoreDeferred(deferred)
        activeCount.incrementAndGet()
        createdCount.incrementAndGet()
        return createBuffer(requiredCapacity)
    }

    /**
     * Returns a buffer to the pool for reuse.
     * Direct buffers larger than the pool's standard size or over-capacity are discarded.
     */
    fun release(buffer: ByteBuffer) {
        activeCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        if (buffer.capacity() > maxRetainedBufferBytes.coerceAtLeast(defaultBufferSize())) return
        if (pooledCount.get() >= maxPoolSize) return
        buffer.clear()
        availableBuffers.offerLast(buffer)
        pooledCount.incrementAndGet()
    }

    /**
     * Creates a new YUV buffer with appropriate size.
     */
    private fun createBuffer(requiredBytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(requiredBytes.coerceAtLeast(defaultBufferSize()))

    private fun defaultBufferSize(): Int =
        frameWidth.coerceAtLeast(1) * frameHeight.coerceAtLeast(1) * 3 / 2

    private fun restoreDeferred(buffers: List<ByteBuffer>) {
        for (buffer in buffers) {
            if (pooledCount.get() >= maxPoolSize) break
            availableBuffers.offerLast(buffer)
            pooledCount.incrementAndGet()
        }
    }

    /**
     * Clears all buffers from the pool.
     */
    fun clear() {
        availableBuffers.clear()
        pooledCount.set(0)
        activeCount.set(0)
        createdCount.set(0)
    }

    internal fun snapshot(): FramePoolSnapshot =
        FramePoolSnapshot(
            activeBuffers = activeCount.get().coerceAtLeast(0),
            pooledBuffers = pooledCount.get().coerceAtLeast(0)
        )
}
