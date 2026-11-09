package com.neoproc.neoproccamx.recording

import android.media.MediaCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class RecordingMuxerSampleQueueTest {
    @Test
    fun `non strict enqueue drops oldest sample when queue is saturated`() {
        val queue = RecordingMuxerSampleQueue(maxPendingSamples = 2, strictNoDrop = false)

        queue.enqueue(0, byteBufferOf(1, 2), size = 2, presentationTimeUs = 10L, flags = 0)
        queue.enqueue(0, byteBufferOf(3, 4), size = 2, presentationTimeUs = 20L, flags = 0)
        val result = queue.enqueue(0, byteBufferOf(5, 6), size = 2, presentationTimeUs = 30L, flags = 0)

        assertEquals(1, result.droppedSamples)
        assertEquals(20L, queue.awaitPacket(0L)?.presentationTimeUs)
        assertEquals(30L, queue.awaitPacket(0L)?.presentationTimeUs)
    }

    @Test
    fun `strict enqueue waits for writer capacity instead of dropping samples`() {
        val queue = RecordingMuxerSampleQueue(maxPendingSamples = 2, strictNoDrop = true)
        val executor = Executors.newSingleThreadExecutor()

        try {
            queue.enqueue(0, byteBufferOf(1, 2), size = 2, presentationTimeUs = 10L, flags = 0)
            queue.enqueue(0, byteBufferOf(3, 4), size = 2, presentationTimeUs = 20L, flags = 0)

            val blockedEnqueue = executor.submit<RecordingMuxerEnqueueResult> {
                queue.enqueue(0, byteBufferOf(5, 6), size = 2, presentationTimeUs = 30L, flags = 0)
            }

            assertStrictProducerIsWaiting(queue, blockedEnqueue)
            val first = queue.awaitPacket(0L)
            assertNotNull(first)
            queue.recycle(first!!.data)

            val result = blockedEnqueue.get(1L, TimeUnit.SECONDS)
            assertTrue(result.enqueued)
            assertEquals(0, result.droppedSamples)
            assertTrue(result.strictBackpressureWaits > 0)
            assertEquals(0, queue.getDroppedCount())
            assertTrue(queue.getStrictBackpressureWaitCount() > 0)
            assertEquals(20L, queue.awaitPacket(0L)?.presentationTimeUs)
            assertEquals(30L, queue.awaitPacket(0L)?.presentationTimeUs)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `recycled buffers are reused for later packets`() {
        val queue = RecordingMuxerSampleQueue(maxPendingSamples = 2)

        queue.enqueue(1, byteBufferOf(9, 8, 7), size = 3, presentationTimeUs = 99L, flags = 1)
        val first = queue.awaitPacket(0L)
        assertNotNull(first)
        val firstBuffer = first!!.data
        queue.recycle(firstBuffer)

        queue.enqueue(1, byteBufferOf(6, 5, 4), size = 3, presentationTimeUs = 100L, flags = 2)
        val second = queue.awaitPacket(0L)

        assertNotNull(second)
        val secondPacket = second!!
        assertTrue(firstBuffer === secondPacket.data)
        assertEquals(100L, secondPacket.presentationTimeUs)
        assertEquals(2, secondPacket.flags)
        assertTrue(secondPacket.enqueuedAtNs > 0L)
    }

    @Test
    fun `prewarmed buffers are used before allocating new packet buffers`() {
        val queue = RecordingMuxerSampleQueue(maxPendingSamples = 2, strictNoDrop = true)
        queue.prewarmBuffers(count = 1, bufferCapacity = 16)

        val result = queue.enqueue(1, byteBufferOf(1, 2, 3, 4), size = 4, presentationTimeUs = 40L, flags = 0)
        val packet = queue.awaitPacket(0L)

        assertFalse(result.allocatedBuffer)
        assertEquals(0, queue.getRuntimeBufferAllocationCount())
        assertNotNull(packet)
        assertTrue(packet!!.data.capacity() >= 16)
        assertEquals(40L, packet.presentationTimeUs)
    }

    @Test
    fun `small audio packet never consumes multi megabyte prewarmed video buffer`() {
        val queue = RecordingMuxerSampleQueue(
            maxPendingSamples = 8,
            strictNoDrop = true,
            maxPendingBufferBytes = 8L * 1024L * 1024L
        )
        queue.prewarmBuffers(count = 2, bufferCapacity = 2_400_000)

        val audio = queue.enqueue(
            trackIndex = 1,
            source = byteBufferOf(1, 2, 3, 4),
            size = 4,
            presentationTimeUs = 20L,
            flags = 0
        )
        val audioPacket = queue.awaitPacket(0L)

        assertTrue(audio.enqueued)
        assertNotNull(audioPacket)
        assertEquals(4 * 1024, audioPacket!!.data.capacity())
        assertEquals(0L, queue.pendingBufferByteCount())
        queue.recycle(audioPacket.data)

        val video = queue.enqueue(
            trackIndex = 0,
            source = ByteBuffer.allocateDirect(1_000_000),
            size = 1_000_000,
            presentationTimeUs = 40L,
            flags = 0
        )
        val videoPacket = queue.awaitPacket(0L)

        assertTrue(video.enqueued)
        assertFalse(video.allocatedBuffer)
        assertNotNull(videoPacket)
        assertEquals(2_400_000, videoPacket!!.data.capacity())
    }

    @Test
    fun `pool miss allocation is reported for thermal diagnostics`() {
        val queue = RecordingMuxerSampleQueue(maxPendingSamples = 2, strictNoDrop = true)

        val result = queue.enqueue(1, byteBufferOf(1, 2, 3, 4), size = 4, presentationTimeUs = 40L, flags = 0)

        assertTrue(result.allocatedBuffer)
        assertEquals(1, queue.getRuntimeBufferAllocationCount())
    }

    @Test
    fun `invalid packets are rejected without queue growth`() {
        val queue = RecordingMuxerSampleQueue(maxPendingSamples = 2)

        val rejected = queue.enqueue(-1, byteBufferOf(1), size = 1, presentationTimeUs = 0L, flags = 0)

        assertFalse(rejected.enqueued)
        assertFalse(queue.hasPending())
    }

    @Test
    fun `buffer info offset and size are respected when enqueuing`() {
        val queue = RecordingMuxerSampleQueue(maxPendingSamples = 2)
        val source = byteBufferOf(10, 11, 12, 13, 14)
        val info = MediaCodec.BufferInfo().apply {
            offset = 1
            size = 3
            presentationTimeUs = 1234L
            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
        }

        val result = queue.enqueue(trackIndex = 0, source = source, info = info)
        assertTrue(result.enqueued)
        val packet = queue.awaitPacket(0L)
        assertNotNull(packet)
        val data = packet!!.data.duplicate()
        val copied = ByteArray(packet.size)
        data.get(copied)
        assertEquals(listOf(11.toByte(), 12.toByte(), 13.toByte()), copied.toList())
        assertEquals(1234L, packet.presentationTimeUs)
    }

    @Test
    fun `out of bounds offset is rejected`() {
        val queue = RecordingMuxerSampleQueue(maxPendingSamples = 2)
        val source = byteBufferOf(1, 2, 3)
        val result = queue.enqueue(
            trackIndex = 0,
            source = source,
            offset = 2,
            size = 2,
            presentationTimeUs = 0L,
            flags = 0
        )

        assertFalse(result.enqueued)
        assertFalse(queue.hasPending())
    }

    @Test
    fun `video packet preserves exact frame identity until writer dequeue`() {
        val queue = RecordingMuxerSampleQueue(maxPendingSamples = 2, strictNoDrop = false)
        val identity = framePresentation(sensorTimestampNs = 9_000L, encoderPtsUs = 90L)

        val result = queue.enqueue(
            trackIndex = 0,
            source = byteBufferOf(1, 2, 3),
            size = 3,
            presentationTimeUs = 90L,
            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME,
            framePresentation = identity
        )
        val packet = queue.awaitPacket(0L)

        assertTrue(result.enqueued)
        assertEquals(0, result.lostFrameIdentities)
        assertEquals(identity, packet?.framePresentation)
    }

    @Test
    fun `dropped and rejected video packets report identity loss`() {
        val queue = RecordingMuxerSampleQueue(maxPendingSamples = 1, strictNoDrop = false)
        val first = framePresentation(sensorTimestampNs = 1_000L, encoderPtsUs = 10L)
        val second = framePresentation(sensorTimestampNs = 2_000L, encoderPtsUs = 20L)
        queue.enqueue(
            trackIndex = 0,
            source = byteBufferOf(1),
            size = 1,
            presentationTimeUs = 10L,
            flags = 0,
            framePresentation = first
        )

        val overflow = queue.enqueue(
            trackIndex = 0,
            source = byteBufferOf(2),
            size = 1,
            presentationTimeUs = 20L,
            flags = 0,
            framePresentation = second
        )
        val rejected = queue.enqueue(
            trackIndex = -1,
            source = byteBufferOf(3),
            size = 1,
            presentationTimeUs = 30L,
            flags = 0,
            framePresentation = framePresentation(3_000L, 30L)
        )

        assertTrue(overflow.enqueued)
        assertEquals(1, overflow.droppedSamples)
        assertEquals(1, overflow.lostFrameIdentities)
        assertEquals(second, queue.awaitPacket(0L)?.framePresentation)
        assertFalse(rejected.enqueued)
        assertEquals(1, rejected.lostFrameIdentities)
    }

    @Test
    fun `strict byte budget blocks before direct buffers can grow without bound`() {
        val queue = RecordingMuxerSampleQueue(
            maxPendingSamples = 16,
            strictNoDrop = true,
            maxPendingBufferBytes = 4L
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            queue.enqueue(0, byteBufferOf(1, 2, 3), size = 3, presentationTimeUs = 10L, flags = 0)
            val blockedEnqueue = executor.submit<RecordingMuxerEnqueueResult> {
                queue.enqueue(0, byteBufferOf(4, 5, 6), size = 3, presentationTimeUs = 20L, flags = 0)
            }

            assertStrictProducerIsWaiting(queue, blockedEnqueue)
            assertFalse(blockedEnqueue.isDone)
            assertEquals(3L, queue.pendingBufferByteCount())

            val first = queue.awaitPacket(0L)
            assertNotNull(first)
            queue.recycle(first!!.data)

            val result = blockedEnqueue.get(1L, TimeUnit.SECONDS)
            assertTrue(result.enqueued)
            assertEquals(0, result.droppedSamples)
            assertTrue(result.strictBackpressureWaits > 0)
            assertTrue(queue.getPeakPendingBufferByteCount() <= 4L)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `oversized strict sample fails terminally before queue allocates a direct buffer`() {
        val queue = RecordingMuxerSampleQueue(
            maxPendingSamples = 4,
            strictNoDrop = true,
            maxPendingBufferBytes = 4L
        )
        val identity = framePresentation(sensorTimestampNs = 1_000L, encoderPtsUs = 10L)

        val result = queue.enqueue(
            trackIndex = 0,
            source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5)),
            size = 5,
            presentationTimeUs = 10L,
            flags = 0,
            framePresentation = identity
        )

        assertFalse(result.enqueued)
        assertFalse(result.allocatedBuffer)
        assertEquals(0, queue.getRuntimeBufferAllocationCount())
        assertEquals(0, queue.pendingCount())
        assertEquals(0L, queue.pendingBufferByteCount())
        assertEquals(1, result.lostFrameIdentities)
        assertEquals(RecordingMuxerQueueFailureReason.EXPLICIT_FAILURE, result.failure?.reason)
        assertEquals(result.failure, queue.currentFailure())

        val afterTerminalFailure = queue.enqueue(
            trackIndex = 0,
            source = ByteBuffer.wrap(byteArrayOf(9)),
            size = 1,
            presentationTimeUs = 20L,
            flags = 0
        )
        assertFalse(afterTerminalFailure.enqueued)
        assertEquals(result.failure, afterTerminalFailure.failure)
        assertEquals(0, queue.getRuntimeBufferAllocationCount())
    }

    @Test
    fun `oversized standard sample fails explicitly instead of silently losing an access unit`() {
        val queue = RecordingMuxerSampleQueue(
            maxPendingSamples = 4,
            strictNoDrop = false,
            maxPendingBufferBytes = 4L
        )

        val oversized = queue.enqueue(
            trackIndex = 0,
            source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5)),
            size = 5,
            presentationTimeUs = 10L,
            flags = 0
        )

        assertFalse(oversized.enqueued)
        assertFalse(oversized.allocatedBuffer)
        assertEquals(RecordingMuxerQueueFailureReason.EXPLICIT_FAILURE, oversized.failure?.reason)
        assertEquals(oversized.failure, queue.currentFailure())
        assertEquals(0, queue.getRuntimeBufferAllocationCount())

        val valid = queue.enqueue(
            trackIndex = 0,
            source = ByteBuffer.wrap(byteArrayOf(7, 8)),
            size = 2,
            presentationTimeUs = 20L,
            flags = 0
        )
        assertFalse(valid.enqueued)
        assertEquals(oversized.failure, valid.failure)
    }

    @Test
    fun `byte budget counts reused buffer capacity and reset drains reservations`() {
        val queue = RecordingMuxerSampleQueue(
            maxPendingSamples = 8,
            strictNoDrop = true,
            maxPendingBufferBytes = 8L
        )
        queue.prewarmBuffers(count = 1, bufferCapacity = 8)

        val result = queue.enqueue(
            0,
            byteBufferOf(1),
            size = 1,
            presentationTimeUs = 10L,
            flags = 0
        )

        assertTrue(result.enqueued)
        assertEquals(8L, queue.pendingBufferByteCount())
        queue.reset()
        assertEquals(0L, queue.pendingBufferByteCount())
        assertFalse(queue.hasPending())
    }

    @Test
    fun `strict full queue reaches one monotonic deadline and fails visibly without dropping queued sample`() {
        val queue = RecordingMuxerSampleQueue(
            maxPendingSamples = 1,
            strictNoDrop = true,
            maxPendingBufferBytes = 16L,
            maxStrictBackpressureWaitMs = 60L
        )
        val firstIdentity = framePresentation(sensorTimestampNs = 1_000L, encoderPtsUs = 10L)
        val timedOutIdentity = framePresentation(sensorTimestampNs = 2_000L, encoderPtsUs = 20L)
        assertTrue(
            queue.enqueue(
                0,
                byteBufferOf(1, 2, 3),
                size = 3,
                presentationTimeUs = 10L,
                flags = 0,
                framePresentation = firstIdentity
            ).enqueued
        )

        val result = queue.enqueue(
            0,
            byteBufferOf(4, 5, 6),
            size = 3,
            presentationTimeUs = 20L,
            flags = 0,
            framePresentation = timedOutIdentity
        )

        assertFalse(result.enqueued)
        assertEquals(
            RecordingMuxerQueueFailureReason.STRICT_BACKPRESSURE_TIMEOUT,
            result.failure?.reason
        )
        assertEquals(1, result.lostFrameIdentities)
        assertEquals(1, queue.getStrictBackpressureTimeoutCount())
        assertEquals(result.failure, queue.currentFailure())
        assertEquals(1, queue.pendingCount())
        assertEquals(3L, queue.pendingBufferByteCount())
        assertEquals(firstIdentity, queue.awaitPacket(0L)?.framePresentation)
    }

    @Test
    fun `reset retires blocked generation and never admits its stale packet into fresh session`() {
        val queue = RecordingMuxerSampleQueue(
            maxPendingSamples = 1,
            strictNoDrop = true,
            maxStrictBackpressureWaitMs = 5_000L
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            queue.enqueue(0, byteBufferOf(1), size = 1, presentationTimeUs = 10L, flags = 0)
            val stale = executor.submit<RecordingMuxerEnqueueResult> {
                queue.enqueue(0, byteBufferOf(2), size = 1, presentationTimeUs = 20L, flags = 0)
            }
            assertStrictProducerIsWaiting(queue, stale)
            assertTrue(queue.getStrictBackpressureWaitCount() > 0)

            val reset = queue.reset()
            val staleResult = stale.get(1L, TimeUnit.SECONDS)
            val freshResult =
                queue.enqueue(0, byteBufferOf(3), size = 1, presentationTimeUs = 30L, flags = 0)

            assertEquals(1, reset.discardedSamples)
            assertFalse(staleResult.enqueued)
            assertEquals(RecordingMuxerQueueFailureReason.RESET, staleResult.failure?.reason)
            assertTrue(freshResult.enqueued)
            assertEquals(1, queue.pendingCount())
            assertEquals(30L, queue.awaitPacket(0L)?.presentationTimeUs)
            assertNull(queue.awaitPacket(0L))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `close wakes strict producer and reports every discarded identity`() {
        val queue = RecordingMuxerSampleQueue(
            maxPendingSamples = 1,
            strictNoDrop = true,
            maxStrictBackpressureWaitMs = 5_000L
        )
        val executor = Executors.newSingleThreadExecutor()
        val queuedIdentity = framePresentation(sensorTimestampNs = 1_000L, encoderPtsUs = 10L)
        val blockedIdentity = framePresentation(sensorTimestampNs = 2_000L, encoderPtsUs = 20L)

        try {
            queue.enqueue(
                0,
                byteBufferOf(1),
                size = 1,
                presentationTimeUs = 10L,
                flags = 0,
                framePresentation = queuedIdentity
            )
            val blocked = executor.submit<RecordingMuxerEnqueueResult> {
                queue.enqueue(
                    0,
                    byteBufferOf(2),
                    size = 1,
                    presentationTimeUs = 20L,
                    flags = 0,
                    framePresentation = blockedIdentity
                )
            }
            assertStrictProducerIsWaiting(queue, blocked)
            assertTrue(queue.getStrictBackpressureWaitCount() > 0)

            val closeReport = queue.close()
            val result = blocked.get(1L, TimeUnit.SECONDS)

            assertEquals(1, closeReport.discardedSamples)
            assertEquals(1, closeReport.lostFrameIdentities)
            assertEquals(RecordingMuxerQueueFailureReason.CLOSED, closeReport.failure?.reason)
            assertFalse(result.enqueued)
            assertEquals(RecordingMuxerQueueFailureReason.CLOSED, result.failure?.reason)
            assertEquals(1, result.lostFrameIdentities)
            assertEquals(0, queue.pendingCount())
            assertEquals(0L, queue.pendingBufferByteCount())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `explicit writer failure wakes all strict producers with original root cause`() {
        val queue = RecordingMuxerSampleQueue(
            maxPendingSamples = 1,
            strictNoDrop = true,
            maxStrictBackpressureWaitMs = 5_000L
        )
        val executor = Executors.newSingleThreadExecutor()
        val writerFailure = IllegalStateException("container write failed")

        try {
            queue.enqueue(0, byteBufferOf(1), size = 1, presentationTimeUs = 10L, flags = 0)
            val blocked = executor.submit<RecordingMuxerEnqueueResult> {
                queue.enqueue(0, byteBufferOf(2), size = 1, presentationTimeUs = 20L, flags = 0)
            }
            assertStrictProducerIsWaiting(queue, blocked)
            assertTrue(queue.getStrictBackpressureWaitCount() > 0)

            val failureReport = queue.fail(writerFailure)
            val result = blocked.get(1L, TimeUnit.SECONDS)

            assertEquals(1, failureReport.discardedSamples)
            assertEquals(RecordingMuxerQueueFailureReason.EXPLICIT_FAILURE, result.failure?.reason)
            assertTrue(result.failure?.cause === writerFailure)
            assertEquals(failureReport.failure, queue.currentFailure())
            assertEquals(0L, queue.pendingBufferByteCount())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `byte-budget waiter cancellation cannot subtract a fresh generation reservation`() {
        val queue = RecordingMuxerSampleQueue(
            maxPendingSamples = 8,
            strictNoDrop = true,
            maxPendingBufferBytes = 3L,
            maxStrictBackpressureWaitMs = 5_000L
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            queue.enqueue(0, byteBufferOf(1, 2, 3), size = 3, presentationTimeUs = 10L, flags = 0)
            val blocked = executor.submit<RecordingMuxerEnqueueResult> {
                queue.enqueue(0, byteBufferOf(4, 5, 6), size = 3, presentationTimeUs = 20L, flags = 0)
            }
            assertStrictProducerIsWaiting(queue, blocked)
            assertTrue(queue.getStrictBackpressureWaitCount() > 0)

            queue.reset()
            val fresh = queue.enqueue(
                0,
                byteBufferOf(7, 8),
                size = 2,
                presentationTimeUs = 30L,
                flags = 0
            )
            val cancelled = blocked.get(1L, TimeUnit.SECONDS)

            assertTrue(fresh.enqueued)
            assertFalse(cancelled.enqueued)
            assertEquals(RecordingMuxerQueueFailureReason.RESET, cancelled.failure?.reason)
            val freshReservationBytes = queue.pendingBufferByteCount()
            assertTrue(freshReservationBytes > 0L)
            val freshPacket = queue.awaitPacket(0L)
            assertEquals(30L, freshPacket?.presentationTimeUs)
            assertEquals(freshPacket?.data?.capacity()?.toLong(), freshReservationBytes)
            assertEquals(0L, queue.pendingBufferByteCount())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `retired writer cannot dequeue packets from the fresh generation`() {
        val queue = RecordingMuxerSampleQueue(
            maxPendingSamples = 2,
            strictNoDrop = true,
            maxStrictBackpressureWaitMs = 5_000L
        )
        val executor = Executors.newSingleThreadExecutor()
        val retiredGeneration = queue.currentGeneration()

        try {
            val retiredWriter = executor.submit<RecordingMuxerSample?> {
                queue.awaitPacket(
                    expectedGeneration = retiredGeneration,
                    waitMs = 5_000L
                )
            }
            assertStillWaiting(retiredWriter)

            val freshGeneration = queue.reset().queueGeneration
            val fresh = queue.enqueue(
                0,
                byteBufferOf(7, 8, 9),
                size = 3,
                presentationTimeUs = 30L,
                flags = 0
            )

            assertNull(retiredWriter.get(1L, TimeUnit.SECONDS))
            assertTrue(fresh.enqueued)
            assertEquals(
                30L,
                queue.awaitPacket(
                    expectedGeneration = freshGeneration,
                    waitMs = 0L
                )?.presentationTimeUs
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `producer expected generation rejects reset that happened before admission`() {
        val queue = RecordingMuxerSampleQueue(maxPendingSamples = 2, strictNoDrop = true)
        val retiredGeneration = queue.currentGeneration()
        val freshGeneration = queue.reset().queueGeneration

        val stale = queue.enqueue(
            trackIndex = 0,
            source = byteBufferOf(1, 2, 3),
            size = 3,
            presentationTimeUs = 10L,
            flags = 0,
            expectedGeneration = retiredGeneration
        )

        assertFalse(stale.enqueued)
        assertEquals(RecordingMuxerQueueFailureReason.RESET, stale.failure?.reason)
        assertEquals(retiredGeneration, stale.queueGeneration)
        assertTrue(queue.isCurrentGeneration(freshGeneration))
        assertNull(queue.awaitPacket(expectedGeneration = freshGeneration, waitMs = 0L))
    }

    @Test
    fun `stale writer failure cannot terminate a freshly reset queue`() {
        val queue = RecordingMuxerSampleQueue(maxPendingSamples = 2, strictNoDrop = true)
        val retiredGeneration = queue.currentGeneration()
        val freshGeneration = queue.reset().queueGeneration
        val fresh = queue.enqueue(
            0,
            byteBufferOf(1, 2),
            size = 2,
            presentationTimeUs = 40L,
            flags = 0
        )

        val staleFailure = queue.fail(
            cause = IllegalStateException("old writer failed after cancellation"),
            expectedGeneration = retiredGeneration
        )

        assertTrue(fresh.enqueued)
        assertNull(staleFailure.failure)
        assertNull(queue.currentFailure())
        assertTrue(queue.isCurrentGeneration(freshGeneration))
        assertEquals(
            40L,
            queue.awaitPacket(
                expectedGeneration = freshGeneration,
                waitMs = 0L
            )?.presentationTimeUs
        )
    }

    private fun byteBufferOf(vararg bytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).apply {
            bytes.forEach { put(it.toByte()) }
            flip()
        }

    private fun framePresentation(
        sensorTimestampNs: Long,
        encoderPtsUs: Long
    ): RecordingFramePresentation =
        RecordingFramePresentation(
            sensorTimestampNs = sensorTimestampNs,
            encoderPresentationTimeUs = encoderPtsUs,
            submittedAtNs = sensorTimestampNs + 1L
        )

    private fun assertStillWaiting(future: java.util.concurrent.Future<*>) {
        assertThrows(TimeoutException::class.java) {
            future.get(100L, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Observe the queue's own admission telemetry before releasing capacity. A timed Future.get()
     * alone is not proof that the producer reached the queue: under a saturated full-suite worker,
     * the executor task can remain unscheduled for the entire probe window and then start only after
     * the test has already dequeued the packet. Waiting for the monotonic queue counter removes that
     * scheduler race while retaining the production assertion that strict admission really blocks.
     */
    private fun assertStrictProducerIsWaiting(
        queue: RecordingMuxerSampleQueue,
        future: java.util.concurrent.Future<*>
    ) {
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
        while (
            queue.getStrictBackpressureWaitCount() == 0 &&
            !future.isDone &&
            System.nanoTime() < deadlineNs
        ) {
            Thread.sleep(1L)
        }
        assertTrue(
            "Strict producer never reached the queue's backpressure wait state",
            queue.getStrictBackpressureWaitCount() > 0
        )
        assertFalse("Strict producer completed before capacity was released", future.isDone)
    }
}
