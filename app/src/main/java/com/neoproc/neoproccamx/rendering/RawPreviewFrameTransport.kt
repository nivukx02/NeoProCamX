package com.neoproc.neoproccamx.rendering

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Exclusive ownership token for one CPU-readback RAW preview frame.
 *
 * The producer must not touch [pixels] after publishing this lease. Ownership returns to the
 * producer only when [close] runs. Idempotent close makes replacement, rejection, upload failure,
 * epoch invalidation, and compositor teardown safe to race without returning a buffer twice.
 */
internal class RawPreviewFrameLease(
    val pixels: ByteBuffer,
    val width: Int,
    val height: Int,
    val presentationEpoch: Long,
    val sensorTimestampNs: Long,
    private val recycle: (ByteBuffer) -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val requiredByteCount: Int
        get() = checkedRgba8ByteCount(width, height) ?: 0

    fun isClosed(): Boolean = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) recycle(pixels)
    }

    companion object {
        fun checkedRgba8ByteCount(width: Int, height: Int): Int? {
            if (width <= 0 || height <= 0) return null
            val bytes = width.toLong() * height.toLong() * RGBA8_BYTES_PER_PIXEL
            return bytes.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
        }

        private const val RGBA8_BYTES_PER_PIXEL = 4L
    }
}

/**
 * Latest-frame single-slot mailbox. Publishing atomically transfers ownership and releases any
 * superseded frame; taking transfers the one pending lease to the GL thread.
 */
internal class RawPreviewFrameMailbox {
    private val pending = AtomicReference<RawPreviewFrameLease?>(null)

    fun publish(frame: RawPreviewFrameLease) {
        pending.getAndSet(frame)?.close()
    }

    fun take(): RawPreviewFrameLease? = pending.getAndSet(null)

    fun hasPendingFrame(): Boolean = pending.get() != null

    fun clear() {
        pending.getAndSet(null)?.close()
    }
}

/**
 * Fixed-capacity direct-buffer pool for RAW preview readback. A buffer is either available or leased;
 * identity tracking prevents a duplicate recycle from placing the same mutable storage in two slots.
 */
internal class RawPreviewBufferPool(
    private val maximumBuffers: Int,
    private val bytesPerBuffer: Int
) {
    private val lock = Any()
    private val available = ArrayDeque<ByteBuffer>(maximumBuffers)
    private val leased = Collections.newSetFromMap(IdentityHashMap<ByteBuffer, Boolean>())
    private var allocated = 0

    init {
        require(maximumBuffers > 0) { "maximumBuffers must be positive" }
        require(bytesPerBuffer > 0) { "bytesPerBuffer must be positive" }
    }

    fun acquire(): ByteBuffer? = synchronized(lock) {
        val buffer = available.pollFirst() ?: if (allocated < maximumBuffers) {
            ByteBuffer.allocateDirect(bytesPerBuffer)
                .order(ByteOrder.nativeOrder())
                .also { allocated += 1 }
        } else {
            null
        }
        buffer?.also {
            check(leased.add(it)) { "RAW preview buffer already leased" }
            it.clear()
        }
    }

    fun recycle(buffer: ByteBuffer) = synchronized(lock) {
        if (!leased.remove(buffer)) return@synchronized
        buffer.clear()
        available.addLast(buffer)
    }

    fun leasedCount(): Int = synchronized(lock) { leased.size }

    fun allocatedCount(): Int = synchronized(lock) { allocated }
}
