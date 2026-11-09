package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RealtimeFrameDispatcherTest {

    @Test
    fun queueLatencyAverageUsesOnlyTerminalLatencyObservations() {
        val dispatcher = RealtimeFrameDispatcher<String>(
            capacity = 2,
            acquireGuardNs = 1L,
            closeFrame = {}
        )
        assertTrue(dispatcher.offer("one", sensorTimestampNs = 1L, nowNs = 10L).accepted)
        assertTrue(dispatcher.offer("two", sensorTimestampNs = 2L, nowNs = 20L).accepted)

        assertEquals(0L, dispatcher.telemetry().queueLatencySamples)
        assertEquals(0L, dispatcher.telemetry().averageQueueLatencyNs)
        assertEquals("one", dispatcher.poll(nowNs = 30L)?.frame)
        assertEquals("two", dispatcher.poll(nowNs = 50L)?.frame)

        val telemetry = dispatcher.telemetry()
        assertEquals(2L, telemetry.queueLatencySamples)
        assertEquals(25L, telemetry.averageQueueLatencyNs)
    }

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
        assertEquals(1L, dispatcher.telemetry().queueLatencySamples)
        assertEquals(2_100_001L, dispatcher.telemetry().averageQueueLatencyNs)
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
    fun recordingFifoRemainsOrderedBecauseStrictDispatcherRejectsOverflow() {
        val closed = mutableListOf<String>()
        val dispatcher = RealtimeFrameDispatcher<String>(
            capacity = 3,
            acquireGuardNs = 1L,
            closeFrame = { closed += it },
            allowsStaleFrameConflation = false
        )

        assertTrue(dispatcher.offer("one", sensorTimestampNs = 1L, nowNs = 10L).accepted)
        assertTrue(dispatcher.offer("two", sensorTimestampNs = 2L, nowNs = 20L).accepted)
        assertTrue(dispatcher.offer("three", sensorTimestampNs = 3L, nowNs = 30L).accepted)
        assertFalse(dispatcher.offer("four", sensorTimestampNs = 4L, nowNs = 40L).accepted)

        assertEquals(emptyList<String>(), closed)
        assertEquals("one", dispatcher.poll(nowNs = 50L)?.frame)
        assertEquals("two", dispatcher.poll(nowNs = 50L)?.frame)
        assertEquals("three", dispatcher.poll(nowNs = 50L)?.frame)
        assertNull(dispatcher.poll(nowNs = 50L))
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
        assertEquals(0L, dispatcher.telemetry().queueLatencySamples)
        assertEquals(0, dispatcher.telemetry().queuedFrames)

        assertTrue(dispatcher.offer("fresh", sensorTimestampNs = 400L, nowNs = 5_000_000L).accepted)
        assertEquals("fresh", dispatcher.poll(nowNs = 6_000_000L)?.frame)
        assertNull(dispatcher.poll())
    }

    @Test
    fun clearRejectsOverlappingProducerWithoutWaitingOrOrphaningOwnership() {
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closed = mutableListOf<String>()
        val dispatcher = RealtimeFrameDispatcher<String>(
            capacity = 1,
            acquireGuardNs = PipelineOrchestrator.FRAME_ACQUIRE_GUARD_NS,
            closeFrame = { frame ->
                if (frame == "queued-before-stop") {
                    closeStarted.countDown()
                    releaseClose.await(5L, TimeUnit.SECONDS)
                }
                synchronized(closed) { closed += frame }
            }
        )
        assertTrue(
            dispatcher.offer(
                "queued-before-stop",
                sensorTimestampNs = 100L,
                nowNs = 1_000_000L
            ).accepted
        )

        val workers = Executors.newFixedThreadPool(2)
        val clearFuture = workers.submit { dispatcher.clear() }
        try {
            assertTrue("clear did not detach the queued frame", closeStarted.await(2L, TimeUnit.SECONDS))

            val racingOffer = workers.submit(Callable {
                dispatcher.offer(
                    "producer-during-stop",
                    sensorTimestampNs = 200L,
                    nowNs = 2_000_000L
                )
            }).get(1L, TimeUnit.SECONDS)

            assertFalse(racingOffer.accepted)
            assertTrue(racingOffer.reason.contains("clear-in-progress-nonblocking-reject"))
            assertFalse(dispatcher.hasQueuedFrames())
        } finally {
            releaseClose.countDown()
            clearFuture.get(2L, TimeUnit.SECONDS)
            workers.shutdownNow()
        }

        assertEquals(listOf("queued-before-stop"), synchronized(closed) { closed.toList() })
        assertEquals(1L, dispatcher.telemetry().rejectedFrames)
        assertTrue(dispatcher.offer("fresh-session", 300L, 3_000_000L).accepted)
        assertEquals("fresh-session", dispatcher.poll(nowNs = 4_000_000L)?.frame)
    }
}
