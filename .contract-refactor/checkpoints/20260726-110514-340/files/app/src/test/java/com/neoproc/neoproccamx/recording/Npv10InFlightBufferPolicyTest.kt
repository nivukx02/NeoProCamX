package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10InFlightBufferPolicyTest {
    @Test fun `full sensor p010 stays inside direct buffer budget`() {
        val pending = Npv10InFlightBufferPolicy.pendingFrames(4096, 3072)
        val payload = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(4096, 3072)
        assertTrue(pending in Npv10InFlightBufferPolicy.MIN_PENDING_FRAMES..4)
        assertTrue(payload * pending <= Npv10InFlightBufferPolicy.MAX_DIRECT_BUFFER_BYTES)
        assertEquals(pending + 2, Npv10InFlightBufferPolicy.readerMaxImages(4096, 3072))
    }

    @Test fun `smaller streams retain deeper burst absorption`() {
        assertTrue(
            Npv10InFlightBufferPolicy.pendingFrames(1920, 1080) >
                Npv10InFlightBufferPolicy.pendingFrames(4096, 3072)
        )
    }
}
