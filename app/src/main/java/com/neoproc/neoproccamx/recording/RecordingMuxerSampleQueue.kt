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
    val data: ByteBuffer,
    val framePresentation: RecordingFramePresentation? = null,
    /** Monotonic time at which the fully-owned packet entered the storage writer queue. */
    val enqueuedAtNs: Long = 0L,
    /** Generation that owns this packet's resident-byte reservation. */
    val queueGeneration: Long = 0L
)

internal enum class RecordingMuxerQueueFailureReason {
    CLOSED,
    EXPLICIT_FAILURE,
    STRICT_BACKPRESSURE_TIMEOUT,
    RESET,
    INTERRUPTED
}

internal data class RecordingMuxerQueueFailure(
    val reason: RecordingMuxerQueueFailureReason,
    val message: String,
    val cause: Throwable? = null
)

internal data class RecordingMuxerQueueTerminationReport(
    val discardedSamples: Int,
    val lostFrameIdentities: Int,
    val failure: RecordingMuxerQueueFailure?,
    /** Generation visible after this lifecycle operation completed. */
    val queueGeneration: Long
)

internal data class RecordingMuxerEnqueueResult(
    val enqueued: Boolean,
    val droppedSamples: Int,
    val allocatedBuffer: Boolean = false,
    val strictBackpressureWaits: Int = 0,
    val lostFrameIdentities: Int = 0,
    /** Non-null whenever this sample was rejected by lifecycle or terminal backpressure failure. */
    val failure: RecordingMuxerQueueFailure? = null,
    /** Generation in which admission was attempted; null only for validation-only rejection. */
    val queueGeneration: Long? = null
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
    private val maxPendingBufferBytes: Long = DEFAULT_MAX_PENDING_BUFFER_BYTES,
    /**
     * Maximum end-to-end admission wait for one strict sample. The production default matches the
     * existing muxer-writer completion budget: it protects transient storage spikes without ever
     * allowing an encoder callback to wait forever after the writer has stopped making progress.
     */
    private val maxStrictBackpressureWaitMs: Long = DEFAULT_MAX_STRICT_BACKPRESSURE_WAIT_MS
) {
    private val pendingCapacity = maxPendingSamples.coerceAtLeast(1)
    private val pendingBufferByteCapacity = maxPendingBufferBytes.coerceAtLeast(1L)
    private val poolCapacity = minOf(
        if (strictNoDrop) pendingCapacity + 2 else pendingCapacity,
        if (strictNoDrop) MAX_STRICT_POOL_BUFFERS else MAX_STANDARD_POOL_BUFFERS
    )
    private val nonStrictCatchUpWaitNs = TimeUnit.MILLISECONDS.toNanos(NON_STRICT_CATCH_UP_WAIT_MS)
    private val maxStrictBackpressureWaitNs = TimeUnit.MILLISECONDS.toNanos(
        maxStrictBackpressureWaitMs.coerceAtLeast(1L)
    )

    /** Lock-free bounded queue for pending samples */
    private val pendingSamples = ArrayBlockingQueue<RecordingMuxerSample>(pendingCapacity)
    
    /** Video/large-packet buffers, kept separate so AAC cannot reserve a multi-megabyte slot. */
    private val bufferPool = ConcurrentLinkedQueue<ByteBuffer>()

    /** Size-classed audio/small-packet buffers. */
    private val smallBufferPool = ConcurrentLinkedQueue<ByteBuffer>()

    private val bufferPoolLock = Any()

    /** Bytes retained by buffers currently in [bufferPool]. Guarded by [bufferPoolLock]. */
    private var pooledBufferBytes = 0L

    /** Whether returned buffers may be retained. Guarded by [bufferPoolLock]. */
    private var retainRecycledBuffers = true
    
    /** Current buffer pool size (approximate, for bounds checking) */
    private val poolSize = AtomicInteger(0)
    
    /** Total dropped samples counter */
    private val totalDropped = AtomicInteger(0)

    /** Direct packet buffers allocated after start/prewarm. This should stay near zero while healthy. */
    private val totalRuntimeBufferAllocations = AtomicInteger(0)

    /** Strict no-drop queue waits after the first short catch-up window expired. */
    private val totalStrictBackpressureWaits = AtomicInteger(0)

    /** Strict admissions that declared the queue failed after their monotonic deadline elapsed. */
    private val totalStrictBackpressureTimeouts = AtomicInteger(0)

    /** Samples explicitly discarded by reset/close/failure teardown. */
    private val totalTerminalDiscardedSamples = AtomicInteger(0)

    /** Frame identities explicitly discarded by reset/close/failure teardown. */
    private val totalTerminalLostFrameIdentities = AtomicInteger(0)

    /** Capacity bytes reserved by queued packets. Guarded by [pendingByteBudgetLock]. */
    private val pendingByteBudgetLock = ReentrantLock()
    private val pendingByteBudgetChanged = pendingByteBudgetLock.newCondition()
    private var pendingBufferBytes = 0L
    private var resetGeneration = 0L
    private val peakPendingBufferBytes = AtomicLong(0L)

    private enum class QueueState {
        OPEN,
        CLOSED,
        FAILED
    }

    /** Guarded by [pendingByteBudgetLock]. */
    private var queueState = QueueState.OPEN

    /** Guarded by [pendingByteBudgetLock]. */
    private var terminalFailure: RecordingMuxerQueueFailure? = null

    /**
     * Start a fresh queue generation.
     *
     * Advancing the generation is what makes reset safe: an enqueue that began in the previous
     * recording can no longer commit a stale packet after reset creates room in the queue. Any
     * discarded packets are returned to the caller instead of being silently forgotten.
     */
    fun reset(): RecordingMuxerQueueTerminationReport {
        val drained = ArrayList<RecordingMuxerSample>(pendingSamples.size)
        val report = pendingByteBudgetLock.withLock {
            val previousFailure = terminalFailure
            resetGeneration += 1L
            queueState = QueueState.OPEN
            terminalFailure = null
            pendingSamples.drainTo(drained)
            pendingBufferBytes = 0L
            totalDropped.set(0)
            totalRuntimeBufferAllocations.set(0)
            totalStrictBackpressureWaits.set(0)
            totalStrictBackpressureTimeouts.set(0)
            totalTerminalDiscardedSamples.set(0)
            totalTerminalLostFrameIdentities.set(0)
            peakPendingBufferBytes.set(0L)
            pendingByteBudgetChanged.signalAll()
            RecordingMuxerQueueTerminationReport(
                discardedSamples = drained.size,
                lostFrameIdentities = drained.count { it.framePresentation != null },
                failure = previousFailure,
                queueGeneration = resetGeneration
            )
        }
        drained.forEach { it.data.clear() }
        clearBufferPool(retainBuffers = true)
        return report
    }

    /**
     * Permanently stop admission for the current generation and release all queue-owned packets.
     * Blocked producers wake immediately and return a structured [RecordingMuxerQueueFailure].
     */
    fun close(): RecordingMuxerQueueTerminationReport = terminate(
        state = QueueState.CLOSED,
        failure = RecordingMuxerQueueFailure(
            reason = RecordingMuxerQueueFailureReason.CLOSED,
            message = "Muxer sample queue closed"
        )
    )

    /**
     * Fail the current generation after a terminal writer/container error. The first terminal root
     * cause wins, so concurrent producers all report the same failure instead of masking it.
     */
    fun fail(cause: Throwable): RecordingMuxerQueueTerminationReport = failInternal(
        cause = cause,
        expectedGeneration = null
    )

    /**
     * Fail only the generation owned by the reporting writer. A cancelled writer from a previous
     * recording must never be able to fail the queue after a new recording has reset it.
     */
    fun fail(
        cause: Throwable,
        expectedGeneration: Long
    ): RecordingMuxerQueueTerminationReport = failInternal(
        cause = cause,
        expectedGeneration = expectedGeneration
    )

    private fun failInternal(
        cause: Throwable,
        expectedGeneration: Long?
    ): RecordingMuxerQueueTerminationReport = terminate(
        state = QueueState.FAILED,
        failure = RecordingMuxerQueueFailure(
            reason = RecordingMuxerQueueFailureReason.EXPLICIT_FAILURE,
            message = cause.message?.let { "Muxer sample queue failed: $it" }
                ?: "Muxer sample queue failed: ${cause.javaClass.simpleName}",
            cause = cause
        ),
        expectedGeneration = expectedGeneration
    )

    fun prewarmBuffers(count: Int, bufferCapacity: Int) {
        val safeCount = count.coerceAtLeast(0)
        val safeCapacity = bufferCapacity.coerceAtLeast(1)
        repeat(safeCount) {
            synchronized(bufferPoolLock) {
                if (!retainRecycledBuffers) return
                if (poolSize.get() >= poolCapacity) return
                if (pooledBufferBytes + safeCapacity.toLong() > pendingBufferByteCapacity) return
                val warmed = ByteBuffer.allocateDirect(safeCapacity)
                if (safeCapacity <= SMALL_PACKET_POOL_MAX_CAPACITY_BYTES) {
                    smallBufferPool.offer(warmed)
                } else {
                    bufferPool.offer(warmed)
                }
                pooledBufferBytes += safeCapacity.toLong()
                poolSize.incrementAndGet()
            }
        }
    }

    /**
     * Wait for a packet with timeout.
     * Uses blocking queue's poll() for efficient waiting.
     */
    fun awaitPacket(waitMs: Long): RecordingMuxerSample? =
        awaitPacket(expectedGeneration = currentGeneration(), waitMs = waitMs)

    /**
     * Wait for a packet owned by [expectedGeneration]. Queue lifecycle and dequeue share the same
     * lock, so a retired writer cannot wake after reset and steal a packet from the new recording.
     */
    fun awaitPacket(expectedGeneration: Long, waitMs: Long): RecordingMuxerSample? {
        val waitBudgetNs = TimeUnit.MILLISECONDS.toNanos(waitMs.coerceAtLeast(0L))
        return pendingByteBudgetLock.withLock {
            var remainingNs = waitBudgetNs
            while (expectedGeneration == resetGeneration) {
                val packet = pendingSamples.poll()
                if (packet != null) {
                    check(packet.queueGeneration == expectedGeneration) {
                        "Muxer packet generation ${packet.queueGeneration} does not match writer generation $expectedGeneration"
                    }
                    releasePendingBufferBytesLocked(
                        releasedBytes = packet.data.capacity().toLong(),
                        generation = packet.queueGeneration
                    )
                    pendingByteBudgetChanged.signalAll()
                    return@withLock packet
                }
                if (remainingNs <= 0L) return@withLock null
                remainingNs = try {
                    pendingByteBudgetChanged.awaitNanos(remainingNs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@withLock null
                }
            }
            null
        }
    }

    fun currentGeneration(): Long = pendingByteBudgetLock.withLock { resetGeneration }

    fun isCurrentGeneration(generation: Long): Boolean =
        pendingByteBudgetLock.withLock { generation == resetGeneration }

    fun enqueue(
        trackIndex: Int,
        source: ByteBuffer,
        info: MediaCodec.BufferInfo,
        framePresentation: RecordingFramePresentation? = null,
        expectedGeneration: Long? = null
    ): RecordingMuxerEnqueueResult {
        return enqueue(
            trackIndex = trackIndex,
            source = source,
            offset = info.offset,
            size = info.size,
            presentationTimeUs = info.presentationTimeUs,
            flags = info.flags,
            framePresentation = framePresentation,
            expectedGeneration = expectedGeneration
        )
    }

    /** Copies an encoded packet into bounded queue-owned storage. */
    fun enqueue(
        trackIndex: Int,
        source: ByteBuffer,
        size: Int,
        presentationTimeUs: Long,
        flags: Int,
        framePresentation: RecordingFramePresentation? = null,
        expectedGeneration: Long? = null
    ): RecordingMuxerEnqueueResult {
        return enqueue(
            trackIndex = trackIndex,
            source = source,
            offset = source.position(),
            size = size,
            presentationTimeUs = presentationTimeUs,
            flags = flags,
            framePresentation = framePresentation,
            expectedGeneration = expectedGeneration
        )
    }

    fun enqueue(
        trackIndex: Int,
        source: ByteBuffer,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        flags: Int,
        framePresentation: RecordingFramePresentation? = null,
        expectedGeneration: Long? = null
    ): RecordingMuxerEnqueueResult {
        if (trackIndex < 0 || size <= 0) {
            return RecordingMuxerEnqueueResult(
                enqueued = false,
                droppedSamples = 0,
                lostFrameIdentities = framePresentation.identityCount()
            )
        }
        if (offset < 0 || offset > source.limit() - size) {
            return RecordingMuxerEnqueueResult(
                enqueued = false,
                droppedSamples = 0,
                lostFrameIdentities = framePresentation.identityCount()
            )
        }

        val admission = beginAdmission(expectedGeneration)
        if (admission.failure != null) {
            return RecordingMuxerEnqueueResult(
                enqueued = false,
                droppedSamples = 0,
                lostFrameIdentities = framePresentation.identityCount(),
                failure = admission.failure,
                queueGeneration = admission.generation
            )
        }
        if (size.toLong() > pendingBufferByteCapacity) {
            val failure = rejectOversizedSample(
                sampleBytes = size.toLong(),
                admission = admission
            )
            return RecordingMuxerEnqueueResult(
                enqueued = false,
                droppedSamples = 0,
                allocatedBuffer = false,
                lostFrameIdentities = framePresentation.identityCount(),
                failure = failure,
                queueGeneration = admission.generation
            )
        }
        
        // Obtain buffer from pool (lock-free). A runtime allocation here means the prewarmed RAM
        // writer pool was too small for the current bitrate burst; it is telemetry, not a quality change.
        val obtainedBuffer = obtainBuffer(size)
        val packetBuffer = obtainedBuffer.buffer

        // Reserve the actual direct-buffer capacity, not merely the encoded payload size. A 1 KiB
        // audio sample can reuse a multi-megabyte video buffer, so payload-only accounting still
        // permits the resident direct-buffer set to grow far beyond the intended memory ceiling.
        val byteReservation = reservePendingBufferBytes(
            requiredBytes = packetBuffer.capacity().toLong(),
            admission = admission
        )
        if (!byteReservation.reserved) {
            recycle(packetBuffer)
            if (byteReservation.droppedSamples > 0) {
                totalDropped.addAndGet(byteReservation.droppedSamples)
            }
            return RecordingMuxerEnqueueResult(
                enqueued = false,
                droppedSamples = byteReservation.droppedSamples,
                allocatedBuffer = obtainedBuffer.allocated,
                strictBackpressureWaits = byteReservation.strictBackpressureWaits,
                lostFrameIdentities =
                    byteReservation.lostFrameIdentities + framePresentation.identityCount(),
                failure = byteReservation.failure,
                queueGeneration = admission.generation
            )
        }
        
        // Copy data only after bounded admission has reserved resident memory. If a concurrently
        // mutated source violates the validated bounds, release ownership before surfacing the bug.
        try {
            val sourceCopy = source.duplicate().apply {
                position(offset)
                limit(offset + size)
            }
            packetBuffer.clear()
            packetBuffer.put(sourceCopy)
            packetBuffer.flip()
        } catch (failure: Throwable) {
            releasePendingBufferBytes(
                releasedBytes = packetBuffer.capacity().toLong(),
                generation = admission.generation
            )
            recycle(packetBuffer)
            throw failure
        }
        
        val packet = RecordingMuxerSample(
            trackIndex = trackIndex,
            size = size,
            presentationTimeUs = presentationTimeUs,
            flags = flags,
            data = packetBuffer,
            framePresentation = framePresentation,
            enqueuedAtNs = System.nanoTime(),
            queueGeneration = admission.generation
        )

        val commit = commitPacket(packet = packet, admission = admission)
        val droppedSamples = byteReservation.droppedSamples + commit.droppedSamples
        val strictBackpressureWaits =
            byteReservation.strictBackpressureWaits + commit.strictBackpressureWaits
        val lostFrameIdentities =
            byteReservation.lostFrameIdentities + commit.lostFrameIdentities

        if (!commit.enqueued) {
            releasePendingBufferBytes(
                releasedBytes = packetBuffer.capacity().toLong(),
                generation = admission.generation
            )
            recycle(packetBuffer)
            if (droppedSamples > 0) {
                totalDropped.addAndGet(droppedSamples)
            }
            return RecordingMuxerEnqueueResult(
                enqueued = false,
                droppedSamples = droppedSamples,
                allocatedBuffer = obtainedBuffer.allocated,
                strictBackpressureWaits = strictBackpressureWaits,
                lostFrameIdentities = lostFrameIdentities + framePresentation.identityCount(),
                failure = commit.failure,
                queueGeneration = admission.generation
            )
        }

        if (droppedSamples > 0) {
            totalDropped.addAndGet(droppedSamples)
        }
        
        return RecordingMuxerEnqueueResult(
            enqueued = true,
            droppedSamples = droppedSamples,
            allocatedBuffer = obtainedBuffer.allocated,
            strictBackpressureWaits = strictBackpressureWaits,
            lostFrameIdentities = lostFrameIdentities,
            failure = null,
            queueGeneration = admission.generation
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
                retainRecycledBuffers &&
                poolSize.get() < poolCapacity &&
                pooledBufferBytes + buffer.capacity().toLong() <= pendingBufferByteCapacity
            ) {
                if (buffer.capacity() <= SMALL_PACKET_POOL_MAX_CAPACITY_BYTES) {
                    smallBufferPool.offer(buffer)
                } else {
                    bufferPool.offer(buffer)
                }
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

    fun getStrictBackpressureTimeoutCount(): Int = totalStrictBackpressureTimeouts.get()

    fun getTerminalDiscardedSampleCount(): Int = totalTerminalDiscardedSamples.get()

    fun getTerminalLostFrameIdentityCount(): Int = totalTerminalLostFrameIdentities.get()

    fun currentFailure(): RecordingMuxerQueueFailure? =
        pendingByteBudgetLock.withLock { terminalFailure }

    fun pendingBufferByteCount(): Long = pendingByteBudgetLock.withLock { pendingBufferBytes }

    fun getPeakPendingBufferByteCount(): Long = peakPendingBufferBytes.get()

    /** Try to reuse from the bounded pool first, allocate only on a miss. */
    private data class ObtainedBuffer(
        val buffer: ByteBuffer,
        val allocated: Boolean
    )

    private fun obtainBuffer(minCapacity: Int): ObtainedBuffer {
        synchronized(bufferPoolLock) {
            val selectedPool = if (minCapacity <= SMALL_PACKET_POOL_MAX_CAPACITY_BYTES) {
                smallBufferPool
            } else {
                bufferPool
            }
            var buffer = selectedPool.poll()
            while (buffer != null) {
                poolSize.decrementAndGet()
                pooledBufferBytes =
                    (pooledBufferBytes - buffer.capacity().toLong()).coerceAtLeast(0L)
                if (buffer.capacity() >= minCapacity) {
                    buffer.clear()
                    return ObtainedBuffer(buffer = buffer, allocated = false)
                }
                buffer = selectedPool.poll()
            }
        }
        
        // Small AAC/config packets use their own bounded size class. This prevents a ~700-byte
        // packet from consuming and reserving a 2.4 MiB prewarmed video buffer under sustained
        // 4K recording. Video buffers remain unchanged and retain their prewarmed capacity.
        totalRuntimeBufferAllocations.incrementAndGet()
        val allocationCapacity = if (
            minCapacity <= SMALL_PACKET_POOL_MAX_CAPACITY_BYTES &&
            pendingBufferByteCapacity >= SMALL_PACKET_POOL_MIN_CAPACITY_BYTES
        ) {
            roundedSmallPacketCapacity(minCapacity)
        } else {
            minCapacity
        }
        return ObtainedBuffer(
            buffer = ByteBuffer.allocateDirect(allocationCapacity),
            allocated = true
        )
    }

    private data class PendingByteReservation(
        val reserved: Boolean,
        val droppedSamples: Int,
        val strictBackpressureWaits: Int,
        val lostFrameIdentities: Int,
        val failure: RecordingMuxerQueueFailure?
    )

    private data class EnqueueAdmission(
        val generation: Long,
        val deadlineNs: Long,
        val failure: RecordingMuxerQueueFailure?
    )

    private data class PacketCommit(
        val enqueued: Boolean,
        val droppedSamples: Int,
        val strictBackpressureWaits: Int,
        val lostFrameIdentities: Int,
        val failure: RecordingMuxerQueueFailure?
    )

    private fun beginAdmission(expectedGeneration: Long?): EnqueueAdmission =
        pendingByteBudgetLock.withLock {
            val admissionGeneration = expectedGeneration ?: resetGeneration
            val failure = rejectionForGenerationLocked(admissionGeneration)
            val nowNs = System.nanoTime()
            val waitBudgetNs = if (strictNoDrop) {
                maxStrictBackpressureWaitNs
            } else {
                nonStrictCatchUpWaitNs
            }
            EnqueueAdmission(
                generation = admissionGeneration,
                deadlineNs = nowNs + waitBudgetNs,
                failure = failure
            )
        }

    private fun reservePendingBufferBytes(
        requiredBytes: Long,
        admission: EnqueueAdmission
    ): PendingByteReservation {
        val samplesToRecycle = ArrayList<RecordingMuxerSample>()
        var waits = 0
        var reserved = false
        var failure: RecordingMuxerQueueFailure? = null
        pendingByteBudgetLock.withLock {
            fun fits(): Boolean =
                requiredBytes <= pendingBufferByteCapacity - pendingBufferBytes

            if (strictNoDrop) {
                while (!fits()) {
                    failure = rejectionForGenerationLocked(admission.generation)
                    if (failure != null) break
                    val remainingNs = admission.deadlineNs - System.nanoTime()
                    if (remainingNs <= 0L) {
                        failure = failBackpressureLocked("resident byte budget")
                        break
                    }
                    waits += 1
                    totalStrictBackpressureWaits.incrementAndGet()
                    try {
                        pendingByteBudgetChanged.awaitNanos(remainingNs)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        failure = interruptedFailure(e)
                        break
                    }
                }
            } else {
                while (!fits()) {
                    failure = rejectionForGenerationLocked(admission.generation)
                    if (failure != null) break
                    val dropped = pendingSamples.poll() ?: break
                    releasePendingBufferBytesLocked(
                        releasedBytes = dropped.data.capacity().toLong(),
                        generation = dropped.queueGeneration
                    )
                    samplesToRecycle += dropped
                }
            }

            if (failure == null) {
                failure = rejectionForGenerationLocked(admission.generation)
            }
            if (failure == null && fits()) {
                pendingBufferBytes += requiredBytes
                peakPendingBufferBytes.accumulateAndGet(pendingBufferBytes) { current, candidate ->
                    maxOf(current, candidate)
                }
                reserved = true
            }
        }
        samplesToRecycle.forEach { recycle(it.data) }
        return PendingByteReservation(
            reserved = reserved,
            droppedSamples = samplesToRecycle.size,
            strictBackpressureWaits = waits,
            lostFrameIdentities = samplesToRecycle.count { it.framePresentation != null },
            failure = failure
        )
    }

    /**
     * Reject an encoded access unit that can never fit the resident-byte budget. This is terminal
     * for every recording mode: waiting cannot make it fit, and continuing would silently publish a
     * clip with a missing encoded access unit.
     */
    private fun rejectOversizedSample(
        sampleBytes: Long,
        admission: EnqueueAdmission
    ): RecordingMuxerQueueFailure? = pendingByteBudgetLock.withLock {
        rejectionForGenerationLocked(admission.generation)?.let { return@withLock it }

        val failure = RecordingMuxerQueueFailure(
            reason = RecordingMuxerQueueFailureReason.EXPLICIT_FAILURE,
            message = "Encoded muxer sample $sampleBytes bytes exceeds resident byte capacity " +
                "$pendingBufferByteCapacity bytes"
        )
        queueState = QueueState.FAILED
        terminalFailure = failure
        pendingByteBudgetChanged.signalAll()
        failure
    }

    private fun commitPacket(
        packet: RecordingMuxerSample,
        admission: EnqueueAdmission
    ): PacketCommit {
        val samplesToRecycle = ArrayList<RecordingMuxerSample>()
        var waits = 0
        var enqueued = false
        var failure: RecordingMuxerQueueFailure? = null
        pendingByteBudgetLock.withLock {
            while (!enqueued) {
                failure = rejectionForGenerationLocked(admission.generation)
                if (failure != null) break
                if (pendingSamples.offer(packet)) {
                    enqueued = true
                    pendingByteBudgetChanged.signalAll()
                    break
                }

                val remainingNs = admission.deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) {
                    if (strictNoDrop) {
                        failure = failBackpressureLocked("sample-count capacity")
                    }
                    break
                }
                if (strictNoDrop) {
                    waits += 1
                    totalStrictBackpressureWaits.incrementAndGet()
                }
                try {
                    pendingByteBudgetChanged.awaitNanos(remainingNs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    failure = interruptedFailure(e)
                    break
                }
            }

            if (!strictNoDrop && failure == null && !enqueued) {
                val dropBudget = 3
                while (samplesToRecycle.size < dropBudget && pendingSamples.remainingCapacity() == 0) {
                    val dropped = pendingSamples.poll() ?: break
                    releasePendingBufferBytesLocked(
                        releasedBytes = dropped.data.capacity().toLong(),
                        generation = dropped.queueGeneration
                    )
                    samplesToRecycle += dropped
                }
                failure = rejectionForGenerationLocked(admission.generation)
                if (failure == null) {
                    enqueued = pendingSamples.offer(packet)
                    if (enqueued) {
                        pendingByteBudgetChanged.signalAll()
                    }
                }
            }
        }
        samplesToRecycle.forEach { recycle(it.data) }
        return PacketCommit(
            enqueued = enqueued,
            droppedSamples = samplesToRecycle.size,
            strictBackpressureWaits = waits,
            lostFrameIdentities = samplesToRecycle.count { it.framePresentation != null },
            failure = failure
        )
    }

    private fun rejectionForGenerationLocked(generation: Long): RecordingMuxerQueueFailure? {
        if (queueState != QueueState.OPEN) {
            return terminalFailure ?: RecordingMuxerQueueFailure(
                reason = RecordingMuxerQueueFailureReason.EXPLICIT_FAILURE,
                message = "Muxer sample queue is not accepting samples"
            )
        }
        if (generation != resetGeneration) {
            return RecordingMuxerQueueFailure(
                reason = RecordingMuxerQueueFailureReason.RESET,
                message = "Muxer sample belongs to a retired queue generation"
            )
        }
        return null
    }

    private fun failBackpressureLocked(stage: String): RecordingMuxerQueueFailure {
        terminalFailure?.let { return it }
        val failure = RecordingMuxerQueueFailure(
            reason = RecordingMuxerQueueFailureReason.STRICT_BACKPRESSURE_TIMEOUT,
            message = "Strict muxer admission exceeded ${maxStrictBackpressureWaitMs.coerceAtLeast(1L)}ms at $stage"
        )
        queueState = QueueState.FAILED
        terminalFailure = failure
        totalStrictBackpressureTimeouts.incrementAndGet()
        pendingByteBudgetChanged.signalAll()
        return failure
    }

    private fun interruptedFailure(cause: InterruptedException): RecordingMuxerQueueFailure =
        RecordingMuxerQueueFailure(
            reason = RecordingMuxerQueueFailureReason.INTERRUPTED,
            message = "Muxer sample admission interrupted",
            cause = cause
        )

    private fun terminate(
        state: QueueState,
        failure: RecordingMuxerQueueFailure,
        expectedGeneration: Long? = null
    ): RecordingMuxerQueueTerminationReport {
        val drained = ArrayList<RecordingMuxerSample>(pendingSamples.size)
        val report = pendingByteBudgetLock.withLock {
            if (expectedGeneration != null && expectedGeneration != resetGeneration) {
                return@withLock RecordingMuxerQueueTerminationReport(
                    discardedSamples = 0,
                    lostFrameIdentities = 0,
                    failure = null,
                    queueGeneration = resetGeneration
                )
            }
            val effectiveFailure = terminalFailure ?: failure
            if (queueState == QueueState.OPEN) {
                queueState = state
            }
            terminalFailure = effectiveFailure
            resetGeneration += 1L
            pendingSamples.drainTo(drained)
            pendingBufferBytes = 0L
            val lostIdentities = drained.count { it.framePresentation != null }
            totalDropped.addAndGet(drained.size)
            totalTerminalDiscardedSamples.addAndGet(drained.size)
            totalTerminalLostFrameIdentities.addAndGet(lostIdentities)
            pendingByteBudgetChanged.signalAll()
            RecordingMuxerQueueTerminationReport(
                discardedSamples = drained.size,
                lostFrameIdentities = lostIdentities,
                failure = effectiveFailure,
                queueGeneration = resetGeneration
            )
        }
        drained.forEach { it.data.clear() }
        clearBufferPool(retainBuffers = false)
        return report
    }

    private fun clearBufferPool(retainBuffers: Boolean) {
        synchronized(bufferPoolLock) {
            bufferPool.clear()
            smallBufferPool.clear()
            pooledBufferBytes = 0L
            poolSize.set(0)
            retainRecycledBuffers = retainBuffers
        }
    }

    private fun roundedSmallPacketCapacity(requiredBytes: Int): Int {
        var capacity = SMALL_PACKET_POOL_MIN_CAPACITY_BYTES
        while (capacity < requiredBytes && capacity < SMALL_PACKET_POOL_MAX_CAPACITY_BYTES) {
            capacity = capacity shl 1
        }
        return capacity.coerceAtLeast(requiredBytes)
    }

    private fun RecordingFramePresentation?.identityCount(): Int = if (this == null) 0 else 1

    private fun releasePendingBufferBytes(releasedBytes: Long, generation: Long) {
        pendingByteBudgetLock.withLock {
            releasePendingBufferBytesLocked(releasedBytes, generation)
            pendingByteBudgetChanged.signalAll()
        }
    }

    private fun releasePendingBufferBytesLocked(releasedBytes: Long, generation: Long) {
        if (generation == resetGeneration) {
            pendingBufferBytes = (pendingBufferBytes - releasedBytes).coerceAtLeast(0L)
        }
    }

    private companion object {
        const val DEFAULT_MAX_PENDING_BUFFER_BYTES = 96L * 1024L * 1024L
        const val DEFAULT_MAX_STRICT_BACKPRESSURE_WAIT_MS =
            RecordingStopFinalizationPolicy.WRITER_COMPLETION_BUDGET_MS
        const val NON_STRICT_CATCH_UP_WAIT_MS = 40L
        const val MAX_STRICT_POOL_BUFFERS = 96
        const val MAX_STANDARD_POOL_BUFFERS = 24
        const val SMALL_PACKET_POOL_MIN_CAPACITY_BYTES = 4 * 1024
        const val SMALL_PACKET_POOL_MAX_CAPACITY_BYTES = 64 * 1024
    }
}
