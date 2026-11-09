package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99OrderedFrameIdentityQueueTest {
    @Test
    fun `queue preserves camera publication order across wraparound`() {
        val queue = Niv99OrderedFrameIdentityQueue(3)
        val output = Niv99FrameIdentity()

        assertTrue(queue.offer(identity(frame = 1, timestamp = 40)))
        assertTrue(queue.offer(identity(frame = 2, timestamp = 80)))
        assertTrue(queue.poll(output))
        assertEquals(1L, output.frameNumber)
        assertTrue(queue.offer(identity(frame = 3, timestamp = 120)))
        assertTrue(queue.offer(identity(frame = 4, timestamp = 160)))

        assertTrue(queue.poll(output))
        assertEquals(2L, output.frameNumber)
        assertTrue(queue.poll(output))
        assertEquals(3L, output.frameNumber)
        assertTrue(queue.poll(output))
        assertEquals(4L, output.frameNumber)
        assertFalse(queue.poll(output))
    }

    @Test
    fun `queue rejects overflow instead of silently replacing a frame`() {
        val queue = Niv99OrderedFrameIdentityQueue(2)
        val output = Niv99FrameIdentity()

        assertTrue(queue.offer(identity(frame = 1, timestamp = 40)))
        assertTrue(queue.offer(identity(frame = 2, timestamp = 80)))
        assertFalse(queue.offer(identity(frame = 3, timestamp = 120)))
        assertEquals(2, queue.size())
        assertTrue(queue.poll(output))
        assertEquals(1L, output.frameNumber)
        assertTrue(queue.poll(output))
        assertEquals(2L, output.frameNumber)
    }

    @Test
    fun `queue rejects incomplete identities and clear retires pending work`() {
        val queue = Niv99OrderedFrameIdentityQueue(4)
        val output = Niv99FrameIdentity()

        assertFalse(queue.offer(Niv99FrameIdentity()))
        assertTrue(queue.offer(identity(frame = 1, timestamp = 40)))
        queue.clear()

        assertEquals(0, queue.size())
        assertFalse(queue.poll(output))
    }

    private fun identity(frame: Long, timestamp: Long): Niv99FrameIdentity =
        Niv99FrameIdentity().set(
            cameraId = "0",
            sessionGeneration = 7L,
            frameNumber = frame,
            sensorTimestampNs = timestamp
        )
}
