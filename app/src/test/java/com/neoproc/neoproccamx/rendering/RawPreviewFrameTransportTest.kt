package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.concurrent.thread

class RawPreviewFrameTransportTest {
    @Test
    fun `replacement and clear release every frame exactly once`() {
        val releaseCounts = AtomicIntegerArray(3)
        val mailbox = RawPreviewFrameMailbox()
        val first = frame(0, releaseCounts)
        val second = frame(1, releaseCounts)
        val third = frame(2, releaseCounts)

        mailbox.publish(first)
        mailbox.publish(second)
        assertEquals(1, releaseCounts.get(0))
        assertFalse(second.isClosed())

        val taken = mailbox.take()
        assertSame(second, taken)
        assertNull(mailbox.take())
        taken?.close()
        taken?.close()
        assertEquals(1, releaseCounts.get(1))

        mailbox.publish(third)
        mailbox.clear()
        mailbox.clear()
        assertEquals(1, releaseCounts.get(2))
    }

    @Test
    fun `publish take and clear races retain exact once ownership`() {
        val frameCount = 2_000
        val releaseCounts = AtomicIntegerArray(frameCount)
        val mailbox = RawPreviewFrameMailbox()
        val start = CountDownLatch(1)
        val producerDone = AtomicBoolean(false)

        val producer = thread(name = "raw-preview-producer") {
            start.await()
            repeat(frameCount) { mailbox.publish(frame(it, releaseCounts)) }
            producerDone.set(true)
        }
        val consumer = thread(name = "raw-preview-consumer") {
            start.await()
            while (!producerDone.get() || mailbox.hasPendingFrame()) {
                mailbox.take()?.close() ?: Thread.yield()
            }
        }
        val clearer = thread(name = "raw-preview-clearer") {
            start.await()
            repeat(frameCount) {
                mailbox.clear()
                if ((it and 31) == 0) Thread.yield()
            }
        }

        start.countDown()
        producer.join()
        consumer.join()
        clearer.join()
        mailbox.clear()

        repeat(frameCount) { index ->
            assertEquals("frame $index release count", 1, releaseCounts.get(index))
        }
    }

    @Test
    fun `four buffer pool never aliases a live lease and recycles idempotently`() {
        val pool = RawPreviewBufferPool(maximumBuffers = 4, bytesPerBuffer = 64)
        val leased = List(4) { requireNotNull(pool.acquire()) }

        assertEquals(4, pool.allocatedCount())
        assertEquals(4, pool.leasedCount())
        assertNull(pool.acquire())
        leased.indices.forEach { left ->
            leased.indices.filter { it != left }.forEach { right ->
                assertNotSame(leased[left], leased[right])
            }
        }

        val returned = leased.first()
        pool.recycle(returned)
        pool.recycle(returned)
        assertEquals(3, pool.leasedCount())
        assertSame(returned, pool.acquire())
        assertEquals(4, pool.leasedCount())
    }

    @Test
    fun `rgba byte count rejects invalid and overflowing dimensions`() {
        assertEquals(4 * 32 * 24, RawPreviewFrameLease.checkedRgba8ByteCount(32, 24))
        assertNull(RawPreviewFrameLease.checkedRgba8ByteCount(0, 24))
        assertNull(RawPreviewFrameLease.checkedRgba8ByteCount(32, -1))
        assertNull(RawPreviewFrameLease.checkedRgba8ByteCount(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    private fun frame(index: Int, releaseCounts: AtomicIntegerArray): RawPreviewFrameLease =
        RawPreviewFrameLease(
            pixels = ByteBuffer.allocateDirect(4),
            width = 1,
            height = 1,
            presentationEpoch = 7L,
            sensorTimestampNs = index.toLong() + 1L,
            recycle = { releaseCounts.incrementAndGet(index) }
        )
}
