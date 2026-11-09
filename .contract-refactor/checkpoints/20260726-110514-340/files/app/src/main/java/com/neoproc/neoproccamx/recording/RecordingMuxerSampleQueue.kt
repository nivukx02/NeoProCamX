package com.neoproc.neoproccamx.recording

import android.media.MediaCodec
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class RecordingMuxerSample(
    val trackIndex: Int,
    val size: Int,
    val presentationTimeUs: Long,
    val flags: Int,
    val data: ByteBuffer
)

internal data class RecordingMuxerEnqueueResult(
    val enqueued: Boolean,
    val droppedSamples: Int,
    val allocatedBuffer: Boolean = false,
    val strictBackpressureWaits: Int = 0
)

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * HIGH-PERFORMANCE MUXER SAMPLE QUEUE
 * ════════════════════════════════════════════════════════════════════════════════
 * 
 * Bounded by both sample count and resident packet-buffer bytes. The byte bound is essential for
 * high-bitrate HEVC: a count-only queue can retain hundreds of multi-megabyte direct buffers and
 * exhaust the process heap long before it reaches its nominal sample capacity.
 */
internal class RecordingMuxerSampleQueue(
    private val maxPendingSamples: Int,
    private val strictNoDrop: Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly,
    private val maxPendingBufferBytes: Long = DEFAULT_MAX_PENDING_BUFFER_BYTES
) {
    private val pendingCapacity = maxPendingSamples.coerceAtLeast(1)
    private val pendingBufferByteCapacity = maxPendingBufferBytes.coerceAtLeast(1L)
    private val poolCapacity = minOf(
        if (strictNoDrop) pendingCapacity + 2 else pendingCapacity,
        if (strictNoDrop) MAX_STRICT_POOL_BUFFERS else MAX_STANDARD_POOL_BUFFERS
    )
    private val fullQueueWaitMs = if (strictNoDrop) 120L else 40L

    /** Lock-free bounded queue for pending samples */
    private val pendingSamples = ArrayBlockingQueue<RecordingMuxerSample>(pendingCapacity)
    
    /** Lock-free buffer pool for ByteBuffer reuse */
    private val bufferPool = ConcurrentLinkedQueue<ByteBuffer>()

    private val bufferPoolLock = Any()

    /** Bytes retained by buffers currently in [bufferPool]. Guarded by [bufferPoolLock]. */
    private var pooledBufferBytes = 0L
    
    /** Current buffer pool size (approximate, for bounds checking) */
    private val poolSize = AtomicInteger(0)
    
    /** Total dropped samples counter */
    private val totalDropped = AtomicInteger(0)

    /** Direct packet buffers allocated after start/prewarm. This should stay near zero while healthy. */
    private val totalRuntimeBufferAllocations = AtomicInteger(0)

    /** Strict no-drop queue waits after the first short catch-up window expired. */
    private val totalStrictBackpressureWaits = AtomicInteger(0)

    /** Capacity bytes reserved by queued packets. Guarded by [pendingByteBudgetLock]. */
    private val pendingByteBudgetLock = ReentrantLock()
    private val pendingByteBudgetChanged = pendingByteBudgetLock.newCondition()
    private var pendingBufferBytes = 0L
    private var resetGeneration = 0L
    private val peakPendingBufferBytes = AtomicLong(0L)

    fun reset() {
        val drained = ArrayList<RecordingMuxerSample>(pendingSamples.size)
        pendingSamples.drainTo(drained)
        drained.forEach { it.data.clear() }
        pendingByteBudgetLock.withLock {
            resetGeneration += 1L
            pendingBufferBytes = 0L
            pendingByteBudgetChanged.signalAll()
        }
        synchronized(bufferPoolLock) {
            bufferPool.clear()
            pooledBufferBytes = 0L
            poolSize.set(0)
        }
        totalDropped.set(0)
        totalRuntimeBufferAllocations.set(0)
        totalStrictBackpressureWaits.set(0)
        peakPendingBufferBytes.set(0L)
    }

    fun prewarmBuffers(count: Int, bufferCapacity: Int) {
        val safeCount = count.coerceAtLeast(0)
        val safeCapacity = bufferCapacity.coerceAtLeast(1)
        repeat(safeCount) {
            synchronized(bufferPoolLock) {
                if (poolSize.get() >= poolCapacity) return
                if (pooledBufferBytes + safeCapacity.toLong() > pendingBufferByteCapacity) return
                bufferPool.offer(ByteBuffer.allocateDirect(safeCapacity))
                pooledBufferBytes += safeCapacity.toLong()
                poolSize.incrementAndGet()
            }
        }
    }

    /**
     * Wait for a packet with timeout.
     * Uses blocking queue's poll() for efficient waiting.
     */
    fun awaitPacket(waitMs: Long): RecordingMuxerSample? {
        val packet = try {
            pendingSamples.poll(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
        if (packet != null) {
            releasePendingBufferBytes(packet.data.capacity().toLong())
        }
        return packet
    }

    fun enqueue(trackIndex: Int, source: ByteBuffer, info: MediaCodec.BufferInfo): RecordingMuxerEnqueueResult {
        return enqueue(
            trackIndex = trackIndex,
            source = source,
            offset = info.offset,
            size = info.size,
            presentationTimeUs = info.presentationTimeUs,
            flags = info.flags
        )
    }

    /** Copies an encoded packet into bounded queue-owned storage. */
    fun enqueue(
        trackIndex: Int,
        source: ByteBuffer,
        size: Int,
        presentationTimeUs: Long,
        flags: Int
    ): RecordingMuxerEnqueueResult {
        return enqueue(
            trackIndex = trackIndex,
            source = source,
            offset = source.position(),
            size = size,
            presentationTimeUs = presentationTimeUs,
            flags = flags
        )
    }

    fun enqueue(
        trackIndex: Int,
        source: ByteBuffer,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        flags: Int
    ): RecordingMuxerEnqueueResult {
        if (trackIndex < 0 || size <= 0) return RecordingMuxerEnqueueResult(false, 0)
        if (offset < 0 || offset + size > source.limit()) {
            return RecordingMuxerEnqueueResult(enqueued = false, droppedSamples = 0)
        }
        
        // Obtain buffer from pool (lock-free). A runtime allocation here means the prewarmed RAM
        // writer pool was too small for the current bitrate burst; it is telemetry, not a quality change.
        val obtainedBuffer = obtainBuffer(size)
        val packetBuffer = obtainedBuffer.buffer

        // Reserve the actual direct-buffer capacity, not merely the encoded payload size. A 1 KiB
        // audio sample can reuse a multi-megabyte video buffer, so payload-only accounting still
        // permits the resident direct-buffer set to grow far beyond the intended memory ceiling.
        val byteReservation = reservePendingBufferBytes(packetBuffer.capacity().toLong())
        if (!byteReservation.reserved) {
            recycle(packetBuffer)
            if (byteReservation.droppedSamples > 0) {
                totalDropped.addAndGet(byteReservation.droppedSamples)
            }
            return RecordingMuxerEnqueueResult(
                enqueued = false,
                droppedSamples = byteReservation.droppedSamples,
                allocatedBuffer = obtainedBuffer.allocated,
                strictBackpressureWaits = byteReservation.strictBackpressureWaits
            )
        }
        
        // Copy data
        val sourceCopy = source.duplicate().apply {
            position(offset)
            limit(offset + size)
        }
        packetBuffer.clear()
        packetBuffer.put(sourceCopy)
        packetBuffer.flip()
        
        val packet = RecordingMuxerSample(
            trackIndex = trackIndex,
            size = size,
            presentationTimeUs = presentationTimeUs,
            flags = flags,
            data = packetBuffer
        )
        
        var droppedSamples = byteReservation.droppedSamples
        var strictBackpressureWaits = byteReservation.strictBackpressureWaits
        
        // Try to enqueue without blocking
        if (!pendingSamples.offer(packet)) {
            if (strictNoDrop) {
                totalStrictBackpressureWaits.incrementAndGet()
                strictBackpressureWaits += 1
            }
            val acceptedAfterWriterCatchUp = try {
                pendingSamples.offer(packet, fullQueueWaitMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (acceptedAfterWriterCatchUp) {
                totalDropped.addAndGet(droppedSamples)
                return RecordingMuxerEnqueueResult(
                    enqueued = true,
                    droppedSamples = droppedSamples,
                    allocatedBuffer = obtainedBuffer.allocated,
                    strictBackpressureWaits = strictBackpressureWaits
                )
            }

            if (strictNoDrop) {
                while (true) {
                    try {
                        if (pendingSamples.offer(packet, fullQueueWaitMs, TimeUnit.MILLISECONDS)) {
                            return RecordingMuxerEnqueueResult(
                                enqueued = true,
                                droppedSamples = 0,
                                allocatedBuffer = obtainedBuffer.allocated,
                                strictBackpressureWaits = strictBackpressureWaits
                            )
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        releasePendingBufferBytes(packetBuffer.capacity().toLong())
                        recycle(packetBuffer)
                        return RecordingMuxerEnqueueResult(
                            enqueued = false,
                            droppedSamples = 0,
                            allocatedBuffer = obtainedBuffer.allocated,
                            strictBackpressureWaits = strictBackpressureWaits
                        )
                    }
                    strictBackpressureWaits += 1
                    totalStrictBackpressureWaits.incrementAndGet()
                }
            }

            // Queue is full - drop oldest samples
            val dropBudget = 3
            while (droppedSamples < dropBudget && pendingSamples.remainingCapacity() == 0) {
                val dropped = pendingSamples.poll()
                if (dropped != null) {
                    releasePendingBufferBytes(dropped.data.capacity().toLong())
                    recycle(dropped.data)
                    droppedSamples++
                } else {
                    break
                }
            }
            
            // Try again
            if (!pendingSamples.offer(packet)) {
                // Still can't enqueue - recycle and return failure
                releasePendingBufferBytes(packetBuffer.capacity().toLong())
                recycle(packetBuffer)
                totalDropped.addAndGet(droppedSamples + 1)
                return RecordingMuxerEnqueueResult(enqueued = false, droppedSamples = droppedSamples)
            }
        }
        
        if (droppedSamples > 0) {
            totalDropped.addAndGet(droppedSamples)
        }
        
        return RecordingMuxerEnqueueResult(
            enqueued = true,
            droppedSamples = droppedSamples,
            allocatedBuffer = obtainedBuffer.allocated,
            strictBackpressureWaits = strictBackpressureWaits
        )
    }

    /** Recycle a packet buffer back to the byte-bounded pool. */
    fun recycle(buffer: ByteBuffer) {
        buffer.clear()

        // Bound both pool count and retained direct-buffer bytes. Buffers beyond this limit are not
        // referenced by the queue and can be reclaimed instead of turning a temporary burst into a
        // permanent direct-memory footprint.
        synchronized(bufferPoolLock) {
            if (
                poolSize.get() < poolCapacity &&
                pooledBufferBytes + buffer.capacity().toLong() <= pendingBufferByteCapacity
            ) {
                bufferPool.offer(buffer)
                pooledBufferBytes += buffer.capacity().toLong()
                poolSize.incrementAndGet()
            }
        }
    }

    fun hasPending(): Boolean = !pendingSamples.isEmpty()
    
    fun pendingCount(): Int = pendingSamples.size
    
    fun getDroppedCount(): Int = totalDropped.get()

    fun getRuntimeBufferAllocationCount(): Int = totalRuntimeBufferAllocations.get()

    fun getStrictBackpressureWaitCount(): Int = totalStrictBackpressureWaits.get()

    fun pendingBufferByteCount(): Long = pendingByteBudgetLock.withLock { pendingBufferBytes }

    fun getPeakPendingBufferByteCount(): Long = peakPendingBufferBytes.get()

    /** Try to reuse from the bounded pool first, allocate only on a miss. */
    private data class ObtainedBuffer(
        val buffer: ByteBuffer,
        val allocated: Boolean
    )

    private fun obtainBuffer(minCapacity: Int): ObtainedBuffer {
        synchronized(bufferPoolLock) {
            var buffer = bufferPool.poll()
            while (buffer != null) {
                poolSize.decrementAndGet()
                pooledBufferBytes =
                    (pooledBufferBytes - buffer.capacity().toLong()).coerceAtLeast(0L)
                if (buffer.capacity() >= minCapacity) {
                    buffer.clear()
                    return ObtainedBuffer(buffer = buffer, allocated = false)
                }
                buffer = bufferPool.poll()
            }
        }
        
        // Allocate new direct buffer
        totalRuntimeBufferAllocations.incrementAndGet()
        return ObtainedBuffer(buffer = ByteBuffer.allocateDirect(minCapacity), allocated = true)
    }

    private data class PendingByteReservation(
        val reserved: Boolean,
        val droppedSamples: Int,
        val strictBackpressureWaits: Int
    )

    private fun reservePendingBufferBytes(requiredBytes: Long): PendingByteReservation {
        val buffersToRecycle = ArrayList<ByteBuffer>()
        var waits = 0
        var interrupted = false
        var reserved = false
        pendingByteBudgetLock.withLock {
            val reservationGeneration = resetGeneration
            fun fits(): Boolean =
                pendingBufferBytes == 0L ||
                    pendingBufferBytes + requiredBytes <= pendingBufferByteCapacity

            if (strictNoDrop) {
                while (!fits() && resetGeneration == reservationGeneration) {
                    waits += 1
                    totalStrictBackpressureWaits.incrementAndGet()
                    try {
                        pendingByteBudgetChanged.await(fullQueueWaitMs, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        interrupted = true
                        break
                    }
                }
            } else {
                while (!fits()) {
                    val dropped = pendingSamples.poll() ?: break
                    pendingBufferBytes =
                        (pendingBufferBytes - dropped.data.capacity().toLong()).coerceAtLeast(0L)
                    buffersToRecycle += dropped.data
                }
            }

            if (!interrupted && resetGeneration == reservationGeneration && fits()) {
                pendingBufferBytes += requiredBytes
                peakPendingBufferBytes.accumulateAndGet(pendingBufferBytes) { current, candidate ->
                    maxOf(current, candidate)
                }
                reserved = true
            }
        }
        buffersToRecycle.forEach(::recycle)
        return PendingByteReservation(
            reserved = reserved,
            droppedSamples = buffersToRecycle.size,
            strictBackpressureWaits = waits
        )
    }

    private fun releasePendingBufferBytes(releasedBytes: Long) {
        pendingByteBudgetLock.withLock {
            pendingBufferBytes = (pendingBufferBytes - releasedBytes).coerceAtLeast(0L)
            pendingByteBudgetChanged.signalAll()
        }
    }

    private companion object {
        const val DEFAULT_MAX_PENDING_BUFFER_BYTES = 96L * 1024L * 1024L
        const val MAX_STRICT_POOL_BUFFERS = 96
        const val MAX_STANDARD_POOL_BUFFERS = 24
    }
}
