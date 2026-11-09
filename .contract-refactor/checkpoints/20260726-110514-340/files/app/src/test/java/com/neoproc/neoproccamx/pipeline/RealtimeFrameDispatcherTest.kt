package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeFrameDispatcherTest {

    @Test
    fun staleFrameIsConflatedAndClosedAfterTwoMillisecondGuard() {
        val closed = mutableListOf<String>()
        val dispatcher = RealtimeFrameDispatcher<String>(
            capacity = 1,
            acquireGuardNs = PipelineOrchestrator.FRAME_ACQUIRE_GUARD_NS,
            closeFrame = { closed += it }
        )

        assertTrue(dispatcher.offer("old", sensorTimestampNs = 100L, nowNs = 1_000_000L).accepted)
        val result = dispatcher.offer("new", sensorTimestampNs = 200L, nowNs = 3_100_001L)

        assertTrue(result.accepted)
        assertEquals(listOf("old"), closed)
        assertEquals(1L, dispatcher.telemetry().droppedFrames)
        assertEquals("new", dispatcher.poll()?.frame)
        assertNull(dispatcher.poll())
    }

    @Test
    fun freshFrameIsRejectedWithoutTakingOwnershipOrBlockingProducer() {
        val closed = mutableListOf<String>()
        val dispatcher = RealtimeFrameDispatcher<String>(
            capacity = 1,
            acquireGuardNs = PipelineOrchestrator.FRAME_ACQUIRE_GUARD_NS,
            closeFrame = { closed += it }
        )

        assertTrue(dispatcher.offer("active", sensorTimestampNs = 100L, nowNs = 1_000_000L).accepted)
        val result = dispatcher.offer("incoming", sensorTimestampNs = 200L, nowNs = 1_500_000L)

        assertFalse(result.accepted)
        assertEquals(emptyList<String>(), closed)
        assertEquals(1L, dispatcher.telemetry().rejectedFrames)
        assertEquals("active", dispatcher.poll()?.frame)
    }

    @Test
    fun strictNoConflationRejectsStaleReplacementInsteadOfDroppingQueuedFrame() {
        val closed = mutableListOf<String>()
        val dispatcher = RealtimeFrameDispatcher<String>(
            capacity = 1,
            acquireGuardNs = PipelineOrchestrator.FRAME_ACQUIRE_GUARD_NS,
            closeFrame = { closed += it },
            allowsStaleFrameConflation = false
        )

        assertTrue(dispatcher.offer("old", sensorTimestampNs = 100L, nowNs = 1_000_000L).accepted)
        val result = dispatcher.offer("new", sensorTimestampNs = 200L, nowNs = 3_100_001L)

        assertFalse(result.accepted)
        assertTrue(result.reason.contains("strict-no-conflation-backpressure"))
        assertEquals(emptyList<String>(), closed)
        assertEquals(0L, dispatcher.telemetry().droppedFrames)
        assertEquals(1L, dispatcher.telemetry().rejectedFrames)
        assertEquals("old", dispatcher.poll()?.frame)
        assertFalse(dispatcher.allowsStaleFrameConflation)
    }

    @Test
    fun clearClosesQueuedFramesAndResetsRingState() {
        val closed = mutableListOf<String>()
        val dispatcher = RealtimeFrameDispatcher<String>(
            capacity = 2,
            acquireGuardNs = PipelineOrchestrator.FRAME_ACQUIRE_GUARD_NS,
            closeFrame = { closed += it }
        )

        assertTrue(dispatcher.offer("a", sensorTimestampNs = 100L, nowNs = 1_000_000L).accepted)
        assertTrue(dispatcher.offer("b", sensorTimestampNs = 200L, nowNs = 2_000_000L).accepted)
        assertEquals("a", dispatcher.poll(nowNs = 3_000_000L)?.frame)
        assertTrue(dispatcher.offer("c", sensorTimestampNs = 300L, nowNs = 4_000_000L).accepted)

        dispatcher.clear()

        assertEquals(setOf("b", "c"), closed.toSet())
        assertEquals(2, closed.size)
        assertFalse(dispatcher.hasQueuedFrames())
        assertEquals(0L, dispatcher.telemetry().acceptedFrames)
        assertEquals(0L, dispatcher.telemetry().droppedFrames)
        assertEquals(0L, dispatcher.telemetry().rejectedFrames)
        assertEquals(0, dispatcher.telemetry().queuedFrames)

        assertTrue(dispatcher.offer("fresh", sensorTimestampNs = 400L, nowNs = 5_000_000L).accepted)
        assertEquals("fresh", dispatcher.poll(nowNs = 6_000_000L)?.frame)
        assertNull(dispatcher.poll())
    }
}
