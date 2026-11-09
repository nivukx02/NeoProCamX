package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDeliveryLivenessTrackerTest {
    private fun input(
        nowMs: Long,
        generation: Long = 1L,
        streaming: Boolean = true,
        refreshPending: Boolean = false,
        timestampNs: Long = 100L,
        importedFrames: Long = 1L
    ) = FrameDeliveryLivenessTracker.Input(
        nowElapsedMs = nowMs,
        sessionGeneration = generation,
        cameraStreaming = streaming,
        sessionRefreshPending = refreshPending,
        captureResultTimestampNs = timestampNs,
        sessionImportedFrames = importedFrames
    )

    @Test
    fun firstReadyObservation_doesNotInheritPriorFrameAge() {
        val tracker = FrameDeliveryLivenessTracker()

        val snapshot = tracker.update(input(nowMs = 20_000L, timestampNs = 5_000L))

        assertTrue(snapshot.frameDeliveryExpected)
        assertNull(snapshot.lastFrameDeliveryElapsedMs)
    }

    @Test
    fun laterCaptureResult_provesDeliveryForCurrentEpoch() {
        val tracker = FrameDeliveryLivenessTracker()
        tracker.update(input(nowMs = 100L, timestampNs = 1_000L))

        val snapshot = tracker.update(input(nowMs = 250L, timestampNs = 2_000L))

        assertEquals(250L, snapshot.lastFrameDeliveryElapsedMs)
    }

    @Test
    fun sessionGenerationChange_rejectsStaleTimestampUntilNewAdvance() {
        val tracker = FrameDeliveryLivenessTracker()
        tracker.update(input(nowMs = 100L, generation = 4L, timestampNs = 1_000L))
        tracker.update(input(nowMs = 200L, generation = 4L, timestampNs = 2_000L))

        val configured = tracker.update(
            input(nowMs = 10_000L, generation = 5L, timestampNs = 2_000L)
        )
        val fresh = tracker.update(
            input(nowMs = 10_150L, generation = 5L, timestampNs = 3_000L)
        )

        assertTrue(configured.frameDeliveryExpected)
        assertNull(configured.lastFrameDeliveryElapsedMs)
        assertEquals(10_150L, fresh.lastFrameDeliveryElapsedMs)
    }

    @Test
    fun refreshBoundary_pausesExpectationAndRequiresPostReadyFrame() {
        val tracker = FrameDeliveryLivenessTracker()
        tracker.update(input(nowMs = 100L, timestampNs = 1_000L))
        tracker.update(input(nowMs = 200L, timestampNs = 2_000L))

        val configuring = tracker.update(
            input(nowMs = 300L, refreshPending = true, timestampNs = 3_000L)
        )
        val configured = tracker.update(
            input(nowMs = 400L, refreshPending = false, timestampNs = 3_000L)
        )
        val fresh = tracker.update(
            input(nowMs = 500L, refreshPending = false, timestampNs = 4_000L)
        )

        assertFalse(configuring.frameDeliveryExpected)
        assertNull(configuring.lastFrameDeliveryElapsedMs)
        assertTrue(configured.frameDeliveryExpected)
        assertNull(configured.lastFrameDeliveryElapsedMs)
        assertEquals(500L, fresh.lastFrameDeliveryElapsedMs)
    }

    @Test
    fun foregroundLifecycleBoundary_rejectsBackgroundFrameHistory() {
        val tracker = FrameDeliveryLivenessTracker()
        tracker.update(input(nowMs = 100L, timestampNs = 1_000L))
        tracker.update(input(nowMs = 200L, timestampNs = 2_000L))

        val background = tracker.update(
            input(nowMs = 10_000L, streaming = false, timestampNs = 2_000L)
        )
        val resumed = tracker.update(
            input(nowMs = 20_000L, streaming = true, timestampNs = 2_000L)
        )
        val fresh = tracker.update(
            input(nowMs = 20_100L, streaming = true, timestampNs = 3_000L)
        )

        assertFalse(background.frameDeliveryExpected)
        assertNull(resumed.lastFrameDeliveryElapsedMs)
        assertEquals(20_100L, fresh.lastFrameDeliveryElapsedMs)
    }

    @Test
    fun importedFrameAdvance_canProveDeliveryWhenCaptureTimestampIsUnavailable() {
        val tracker = FrameDeliveryLivenessTracker()
        tracker.update(input(nowMs = 100L, timestampNs = 0L, importedFrames = 8L))

        val snapshot = tracker.update(
            input(nowMs = 200L, timestampNs = 0L, importedFrames = 9L)
        )

        assertEquals(200L, snapshot.lastFrameDeliveryElapsedMs)
    }
}
