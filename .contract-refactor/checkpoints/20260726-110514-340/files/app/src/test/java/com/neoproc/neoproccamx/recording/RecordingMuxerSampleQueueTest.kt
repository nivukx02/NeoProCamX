package com.neoproc.neoproccamx.recording

import android.media.MediaCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

            Thread.sleep(150L)
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

            Thread.sleep(150L)
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

    private fun byteBufferOf(vararg bytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).apply {
            bytes.forEach { put(it.toByte()) }
            flip()
        }
}
