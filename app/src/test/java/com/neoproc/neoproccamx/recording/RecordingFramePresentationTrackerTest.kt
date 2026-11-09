package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

class RecordingFramePresentationTrackerTest {
    @Test
    fun `encoder drain lag preserves every frame identity in submission order`() {
        val tracker = RecordingFramePresentationTracker(maxPendingPresentations = 4)
        tracker.offer(frame(sensorTimestampNs = 1_000L, encoderPtsUs = 10L))
        tracker.offer(frame(sensorTimestampNs = 2_000L, encoderPtsUs = 20L))
        tracker.offer(frame(sensorTimestampNs = 3_000L, encoderPtsUs = 30L))

        assertEquals(1_000L, tracker.match(10L)?.sensorTimestampNs)
        assertEquals(2_000L, tracker.match(20L)?.sensorTimestampNs)
        assertEquals(3_000L, tracker.match(30L)?.sensorTimestampNs)

        val snapshot = tracker.snapshot()
        assertEquals(3L, snapshot.submittedPresentations)
        assertEquals(3L, snapshot.matchedPresentations)
        assertEquals(0, snapshot.pendingPresentations)
        assertEquals(0L, snapshot.lostPresentations)
    }

    @Test
    fun `exact pts lookup survives encoder output reordering and missing output`() {
        val tracker = RecordingFramePresentationTracker(maxPendingPresentations = 4)
        tracker.offer(frame(sensorTimestampNs = 1_000L, encoderPtsUs = 10L))
        tracker.offer(frame(sensorTimestampNs = 2_000L, encoderPtsUs = 20L))
        tracker.offer(frame(sensorTimestampNs = 3_000L, encoderPtsUs = 30L))

        assertEquals(2_000L, tracker.match(20L)?.sensorTimestampNs)
        assertNull(tracker.match(25L))
        assertEquals(1_000L, tracker.match(10L)?.sensorTimestampNs)
        assertEquals(3_000L, tracker.match(30L)?.sensorTimestampNs)
        assertEquals(1L, tracker.snapshot().unmatchedEncoderOutputs)
    }

    @Test
    fun `match returns live pending depth under the same consumer lock`() {
        val tracker = RecordingFramePresentationTracker(maxPendingPresentations = 4)
        tracker.offer(frame(sensorTimestampNs = 1_000L, encoderPtsUs = 10L))
        tracker.offer(frame(sensorTimestampNs = 2_000L, encoderPtsUs = 20L))

        val match = tracker.matchWithDepth(10L)

        assertEquals(1_000L, match.presentation?.sensorTimestampNs)
        assertEquals(1, match.pendingAfterMatch)
    }

    @Test
    fun `bounded tracker reports eviction without shifting remaining identities`() {
        val tracker = RecordingFramePresentationTracker(maxPendingPresentations = 2)
        tracker.offer(frame(sensorTimestampNs = 1_000L, encoderPtsUs = 10L))
        tracker.offer(frame(sensorTimestampNs = 2_000L, encoderPtsUs = 20L))

        val result = tracker.offer(frame(sensorTimestampNs = 3_000L, encoderPtsUs = 30L))

        assertTrue(result.accepted)
        assertEquals(1, result.evictedPresentations)
        assertNull(tracker.match(10L))
        assertEquals(2_000L, tracker.match(20L)?.sensorTimestampNs)
        assertEquals(3_000L, tracker.match(30L)?.sensorTimestampNs)
        assertEquals(1L, tracker.snapshot().evictedPresentations)
    }

    @Test
    fun `duplicate encoder pts is rejected and diagnosed`() {
        val tracker = RecordingFramePresentationTracker(maxPendingPresentations = 2)
        assertTrue(tracker.offer(frame(sensorTimestampNs = 1_000L, encoderPtsUs = 10L)).accepted)

        val duplicate = tracker.offer(frame(sensorTimestampNs = 2_000L, encoderPtsUs = 10L))

        assertFalse(duplicate.accepted)
        assertTrue(duplicate.duplicatePresentationTime)
        assertEquals(1_000L, tracker.match(10L)?.sensorTimestampNs)
        assertEquals(1L, tracker.snapshot().duplicatePresentations)
    }

    @Test
    fun `producer contention waits for exact identity admission instead of losing the frame`() {
        val tracker = RecordingFramePresentationTracker(maxPendingPresentations = 2)
        val lockField = tracker.javaClass.getDeclaredField("lock").apply { isAccessible = true }
        val trackerLock = lockField.get(tracker) as ReentrantLock
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val offerStarted = CountDownLatch(1)
        val offerCompleted = CountDownLatch(1)
        val resultRef = AtomicReference<RecordingFramePresentationOfferResult>()
        val holder = thread(start = true, name = "encoder-tracker-lock-holder") {
            trackerLock.lock()
            try {
                locked.countDown()
                release.await(5L, TimeUnit.SECONDS)
            } finally {
                trackerLock.unlock()
            }
        }
        assertTrue(locked.await(5L, TimeUnit.SECONDS))

        val producer = thread(start = true, name = "encoder-tracker-producer") {
            offerStarted.countDown()
            resultRef.set(tracker.offer(frame(sensorTimestampNs = 1_000L, encoderPtsUs = 10L)))
            offerCompleted.countDown()
        }
        assertTrue(offerStarted.await(5L, TimeUnit.SECONDS))
        assertFalse("offer bypassed the identity serialization barrier", offerCompleted.await(100L, TimeUnit.MILLISECONDS))
        release.countDown()
        holder.join(5_000L)
        producer.join(5_000L)

        assertTrue(offerCompleted.await(1L, TimeUnit.SECONDS))
        val result = resultRef.get()
        assertTrue(result.accepted)
        assertFalse(result.producerContentionRejected)
        assertEquals(1_000L, tracker.match(10L)?.sensorTimestampNs)
        val snapshot = tracker.snapshot()
        assertEquals(1L, snapshot.submittedPresentations)
        assertEquals(0L, snapshot.producerContentionRejections)
        assertEquals(0L, snapshot.lostPresentations)
    }

    @Test
    fun `frame identity becomes visible only after successful container write`() {
        val presentation = frame(sensorTimestampNs = 42_000L, encoderPtsUs = 42L)

        assertNull(
            RecordingFrameCommitPolicy.committedSensorTimestampNs(
                framePresentation = presentation,
                containerWriteSucceeded = false
            )
        )
        assertEquals(
            42_000L,
            RecordingFrameCommitPolicy.committedSensorTimestampNs(
                framePresentation = presentation,
                containerWriteSucceeded = true
            )
        )
    }

    private fun frame(sensorTimestampNs: Long, encoderPtsUs: Long) =
        RecordingFramePresentation(
            sensorTimestampNs = sensorTimestampNs,
            encoderPresentationTimeUs = encoderPtsUs,
            submittedAtNs = sensorTimestampNs + 1L
        )
}
