package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RealtimeFrameDispatcherLatestTest {
    @Test
    fun previewPollLatestReleasesHistoryAndTracksSkipsSeparately() {
        val closed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val dispatcher = RealtimeFrameDispatcher<String>(
            capacity = 3,
            acquireGuardNs = 1L,
            closeFrame = { closed += it }
        )
        dispatcher.offer("one", 1L, 10L)
        dispatcher.offer("two", 2L, 20L)
        dispatcher.offer("three", 3L, 30L)

        val latest = dispatcher.pollLatest(
            nowNs = 40L,
            maxFrameAgeNs = 100L,
            onSkipped = { frame, _ -> skipped += frame.frame }
        )

        assertEquals("three", latest!!.frame)
        assertEquals(listOf("one", "two"), skipped)
        assertEquals(listOf("one", "two"), closed)
        assertEquals(2L, dispatcher.telemetry().skippedPreviewFrames)
        assertEquals(0L, dispatcher.telemetry().droppedFrames)
        assertNull(dispatcher.poll())
    }

    @Test
    fun previewPollLatestReturnsTrueNewestFrameAfterRingOverflow() {
        val closed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val dispatcher = RealtimeFrameDispatcher<String>(
            capacity = 3,
            acquireGuardNs = 1L,
            closeFrame = { closed += it }
        )
        dispatcher.offer("one", sensorTimestampNs = 1L, nowNs = 10L)
        dispatcher.offer("two", sensorTimestampNs = 2L, nowNs = 20L)
        dispatcher.offer("three", sensorTimestampNs = 3L, nowNs = 30L)
        dispatcher.offer("four", sensorTimestampNs = 4L, nowNs = 40L)

        val latest = dispatcher.pollLatest(
            nowNs = 50L,
            maxFrameAgeNs = 100L,
            onSkipped = { frame, _ -> skipped += frame.frame }
        )

        assertEquals("four", latest?.frame)
        assertEquals(listOf("two", "three"), skipped)
        assertEquals(listOf("one", "two", "three"), closed)
        assertEquals(1, closed.count { it == "one" })
        assertEquals(1, closed.count { it == "two" })
        assertEquals(1, closed.count { it == "three" })
        assertEquals(0, closed.count { it == "four" })
        assertEquals(2L, dispatcher.telemetry().skippedPreviewFrames)
        assertEquals(1L, dispatcher.telemetry().droppedFrames)
        assertNull(dispatcher.poll())
    }

    @Test
    fun recordingCanDisableConflationEvenWhenPreviewDispatcherNormallyAllowsIt() {
        val dispatcher = RealtimeFrameDispatcher<String>(
            capacity = 1,
            acquireGuardNs = 1L,
            closeFrame = { _: String -> }
        )
        assertTrue(dispatcher.offer("accepted", 1L, 1L).accepted)

        val protected = dispatcher.offer(
            frame = "recording",
            sensorTimestampNs = 2L,
            nowNs = 10L,
            allowStaleFrameConflation = false
        )

        assertTrue(!protected.accepted)
        assertEquals("accepted", dispatcher.poll()!!.frame)
        assertEquals(0L, dispatcher.telemetry().droppedFrames)
        assertEquals(1L, dispatcher.telemetry().rejectedFrames)
    }

    @Test
    fun previewPollLatestPrefersValidSensorTimestampOverNewerInvalidArrival() {
        val closed = mutableListOf<String>()
        val dispatcher = RealtimeFrameDispatcher<String>(
            capacity = 2,
            acquireGuardNs = 1L,
            closeFrame = { closed += it }
        )
        dispatcher.offer("sensor-frame", sensorTimestampNs = 10L, nowNs = 10L)
        dispatcher.offer("invalid-timestamp", sensorTimestampNs = 0L, nowNs = 20L)

        val latest = dispatcher.pollLatest(nowNs = 30L, maxFrameAgeNs = 100L)

        assertEquals("sensor-frame", latest?.frame)
        assertEquals(listOf("invalid-timestamp"), closed)
        assertEquals(1L, dispatcher.telemetry().skippedPreviewFrames)
    }

    @Test
    fun previewPollLatestReleasesAllOwnedFramesBeforePropagatingAggregatedFailure() {
        val closed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val dispatcher = RealtimeFrameDispatcher<String>(
            capacity = 4,
            acquireGuardNs = 1L,
            closeFrame = { frame ->
                closed += frame
                if (frame == "two") error("close-two")
            }
        )
        dispatcher.offer("one", sensorTimestampNs = 1L, nowNs = 10L)
        dispatcher.offer("two", sensorTimestampNs = 2L, nowNs = 20L)
        dispatcher.offer("three", sensorTimestampNs = 3L, nowNs = 30L)
        dispatcher.offer("four", sensorTimestampNs = 4L, nowNs = 40L)

        val failure = try {
            dispatcher.pollLatest(
                nowNs = 50L,
                maxFrameAgeNs = 100L,
                onSkipped = { frame, _ ->
                    skipped += frame.frame
                    if (frame.frame == "one") error("callback-one")
                }
            )
            fail("pollLatest must propagate its first release failure")
            null
        } catch (expected: IllegalStateException) {
            expected
        }

        assertEquals("callback-one", failure?.message)
        assertTrue(failure?.suppressed?.any { it.message == "close-two" } == true)
        assertEquals(listOf("one", "two", "three"), skipped)
        assertEquals(listOf("one", "two", "three", "four"), closed)
        assertEquals(4, closed.toSet().size)
        assertEquals(3L, dispatcher.telemetry().skippedPreviewFrames)
        assertNull(dispatcher.poll())
    }
}
