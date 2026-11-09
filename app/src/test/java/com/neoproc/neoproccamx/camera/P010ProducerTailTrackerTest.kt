package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P010ProducerTailTrackerTest {
    private val session = Any()
    private fun tracker() = P010ProducerTailTracker(session, 7L)

    @Test fun `exact completed capture tail needs no additional exposure`() {
        val tracker = tracker()
        assertTrue(tracker.registerSequence(41))
        assertTrue(tracker.onFrameCompleted(session, 7L, 41, 100L, 4_000_000_000L))
        assertTrue(tracker.freeze(41))
        assertFalse(tracker.registerSequence(42))
        assertTrue(tracker.onSequenceCompleted(session, 7L, 41, 100L))
        val tail = tracker.await(1L)
        assertEquals(P010CapturedProducerTail.Proven(100L, 4_000_000_000L), tail)
        val boundary = P010StopBoundaryState(P010RepeatingSequenceIdentity(session, 7L, 41))
        assertTrue(boundary.onCompletedCapturedProducerTail(session, 7L, 100L, 4_000_000_000L))
        assertTrue(boundary.awaitCameraMarker(1L) is P010StopBoundaryCameraResult.MarkerReady)
        assertFalse(boundary.claimReaderFifoDrain())
        assertTrue(boundary.onRecorderImageOwnershipTransferred(4_000_000_000L))
        assertTrue(boundary.claimReaderFifoDrain())
        assertTrue(boundary.onReaderFifoDrained { it == 4_000_000_000L })
        assertEquals(P010StopBoundaryResult.Proven(4_000_000_000L), boundary.await(1L))
    }

    @Test fun `older repeating and one-shot control sequences must also drain`() {
        val tracker = tracker()
        listOf(40, 41, 42).forEach { assertTrue(tracker.registerSequence(it)) }
        tracker.onFrameCompleted(session, 7L, 40, 98L, 3_920_000_000L)
        tracker.onFrameCompleted(session, 7L, 41, 99L, 3_960_000_000L)
        tracker.onFrameCompleted(session, 7L, 42, 100L, 4_000_000_000L)
        assertTrue(tracker.freeze(41))
        tracker.onSequenceCompleted(session, 7L, 41, 99L)
        assertEquals(2, tracker.pendingSequenceCount())
        tracker.onSequenceCompleted(session, 7L, 40, 98L)
        assertEquals(1, tracker.pendingSequenceCount())
        tracker.onSequenceCompleted(session, 7L, 42, 100L)
        assertEquals(P010CapturedProducerTail.Proven(100L, 4_000_000_000L), tracker.await(1L))
    }

    @Test fun `captured tail rejects conflicting frame identity and never authorizes an extra exposure`() {
        val boundary = P010StopBoundaryState(P010RepeatingSequenceIdentity(session, 7L, 41))
        assertTrue(boundary.onCompletedCapturedProducerTail(session, 7L, 100L, 4_000_000_000L))
        assertFalse(boundary.onRepeatingSequenceCompleted(session, 7L, 41))
        assertFalse(boundary.onCompletedCapturedProducerTail(session, 7L, 101L, 4_000_000_000L))
        assertTrue(boundary.await(1L) is P010StopBoundaryResult.Failed)
    }

    @Test fun `completion before result waits for the exact final frame identity`() {
        val tracker = tracker()
        tracker.registerSequence(41)
        tracker.freeze(41)
        tracker.onSequenceCompleted(session, 7L, 41, 101L)
        tracker.onFrameCompleted(session, 7L, 41, 100L, 4_000_000_000L)
        tracker.onFrameCompleted(session, 7L, 41, 101L, 4_040_000_000L)
        assertEquals(P010CapturedProducerTail.Proven(101L, 4_040_000_000L), tracker.await(1L))
    }

    @Test fun `timeout is terminal and a late timestamp cannot manufacture success`() {
        val tracker = tracker()
        tracker.registerSequence(41)
        tracker.freeze(41)
        tracker.onSequenceCompleted(session, 7L, 41, 101L)
        assertTrue(tracker.await(1L) is P010CapturedProducerTail.Failed)
        assertFalse(tracker.onFrameCompleted(session, 7L, 41, 101L, 4_040_000_000L))
        assertTrue(tracker.await(1L) is P010CapturedProducerTail.Failed)
    }

    @Test fun `stale generations sessions and unregistered sequences cannot close a producer`() {
        val tracker = tracker()
        tracker.registerSequence(41)
        tracker.freeze(41)
        assertFalse(tracker.onSequenceCompleted(Any(), 7L, 41, 100L))
        assertFalse(tracker.onSequenceCompleted(session, 6L, 41, 100L))
        assertFalse(tracker.onFrameCompleted(session, 7L, 42, 100L, 4_000_000_000L))
        assertEquals(1, tracker.pendingSequenceCount())
        tracker.onFrameCompleted(session, 7L, 41, 100L, 4_000_000_000L)
        tracker.onSequenceCompleted(session, 7L, 41, 100L)
        assertTrue(tracker.await(1L) is P010CapturedProducerTail.Proven)
    }

    @Test fun `abort loss and contradictory timestamps fail closed`() {
        for (failure in 0..2) {
            val tracker = tracker()
            tracker.registerSequence(41)
            tracker.freeze(41)
            tracker.onFrameCompleted(session, 7L, 41, 100L, 4_000_000_000L)
            when (failure) {
                0 -> tracker.onSequenceAborted(session, 7L, 41)
                1 -> tracker.failWhileFrozen(session, 7L, "real buffer lost")
                else -> tracker.onFrameCompleted(session, 7L, 41, 100L, 4_040_000_000L)
            }
            tracker.onSequenceCompleted(session, 7L, 41, 100L)
            assertTrue(tracker.await(1L) is P010CapturedProducerTail.Failed)
        }
    }

    @Test fun `unrelated failed captures cannot poison an exact P010 sequence`() {
        val tracker = tracker()
        tracker.registerSequence(41)
        tracker.freeze(41)
        tracker.failSequenceWhileFrozen(session, 7L, 42, "unrelated still capture")
        tracker.failSequenceWhileFrozen(Any(), 7L, 41, "stale session")
        tracker.onFrameCompleted(session, 7L, 41, 100L, 4_000_000_000L)
        tracker.onSequenceCompleted(session, 7L, 41, 100L)
        assertTrue(tracker.await(1L) is P010CapturedProducerTail.Proven)
        val failed = tracker()
        failed.registerSequence(41)
        failed.freeze(41)
        failed.failSequenceWhileFrozen(session, 7L, 41, "owned capture failed")
        assertTrue(failed.await(1L) is P010CapturedProducerTail.Failed)
    }

    @Test fun `long-lived preview sequence history stays usable without evicting active ownership`() {
        val tracker = tracker()
        tracker.registerSequence(1)
        for (sequence in 2..2_000) {
            assertTrue(tracker.registerSequence(sequence))
            tracker.onFrameCompleted(session, 7L, sequence, sequence.toLong(), sequence * 40_000_000L)
            tracker.onSequenceCompleted(session, 7L, sequence, sequence.toLong())
        }
        assertTrue(tracker.freeze(1))
        tracker.onFrameCompleted(session, 7L, 1, 2_001L, 80_040_000_000L)
        tracker.onSequenceCompleted(session, 7L, 1, 2_001L)
        assertEquals(P010CapturedProducerTail.Proven(2_001L, 80_040_000_000L), tracker.await(1L))
    }
}
