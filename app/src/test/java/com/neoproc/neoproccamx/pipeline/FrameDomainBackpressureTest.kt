package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class FrameDomainBackpressureTest {
    @Test
    fun previewEvictsHistoryAndPollsNewestWithoutCallingItRecordingLoss() {
        var nowNs = 10L
        val released = AtomicInteger(0)
        val skipped = mutableListOf<Pair<Long, Boolean>>()
        val queue = BoundedFrameDomainQueue<String>(
            config = FrameDomainQueueConfig(
                domain = FrameDomain.PREVIEW,
                minimumCapacity = 1,
                maximumCapacity = 2,
                initialActiveLimit = 2,
                maxFrameAgeNs = 100L,
                backpressure = DomainBackpressureStrategy.LATEST,
                preferLatestFrame = true
            ),
            monotonicClockNs = { nowNs },
            onSkipped = { item, stale ->
                skipped += item.record.identity.snapshot().ingressSequence to stale
            },
            onFailure = { throw AssertionError("Unexpected queue cleanup failure", it) }
        )

        assertTrue(queue.offer(item(1L, released, enqueuedAtNs = 1L)).accepted)
        assertTrue(queue.offer(item(2L, released, enqueuedAtNs = 2L)).accepted)
        assertTrue(queue.offer(item(3L, released, enqueuedAtNs = 3L)).accepted)
        val newest = queue.poll()!!

        assertEquals(3L, newest.record.identity.snapshot().ingressSequence)
        assertEquals(listOf(1L to false, 2L to false), skipped)
        newest.close()
        assertEquals(3, released.get())
        assertEquals(2L, queue.telemetry().evicted)
        assertEquals(0L, queue.telemetry().backpressured)
    }

    @Test
    fun recordingQueueNeverEvictsAcceptedFrames() {
        val released = AtomicInteger(0)
        val queue = BoundedFrameDomainQueue<String>(
            config = FrameDomainQueueConfig(
                domain = FrameDomain.RECORDING,
                minimumCapacity = 1,
                maximumCapacity = 2,
                initialActiveLimit = 2,
                maxFrameAgeNs = 1L,
                backpressure = DomainBackpressureStrategy.PROTECTED,
                preferLatestFrame = false
            ),
            monotonicClockNs = { 1_000L },
            onSkipped = { _, _ -> error("Recording frames must not be skipped") },
            onFailure = { throw AssertionError("Unexpected queue cleanup failure", it) }
        )
        val first = item(1L, released, 1L)
        val second = item(2L, released, 2L)
        val rejected = item(3L, released, 3L)

        assertTrue(queue.offer(first).accepted)
        assertTrue(queue.offer(second).accepted)
        val result = queue.offer(rejected)

        assertFalse(result.accepted)
        assertFalse(result.ownershipTransferred)
        rejected.close()
        assertEquals(1L, queue.poll()!!.record.identity.snapshot().ingressSequence)
        assertEquals(2L, queue.poll()!!.record.identity.snapshot().ingressSequence)
        first.close()
        second.close()
        assertEquals(3, released.get())
        assertEquals(0L, queue.telemetry().evicted)
        assertEquals(1L, queue.telemetry().backpressured)
    }

    @Test
    fun sharedPayloadOwnerCloseIsIdempotent() {
        val released = AtomicInteger(0)
        val shared = SharedFrameResource("frame") { released.incrementAndGet() }

        shared.close()
        shared.close()

        assertEquals(1, released.get())
        assertFalse(shared.retain() != null)
    }

    private fun item(
        sequence: Long,
        released: AtomicInteger,
        enqueuedAtNs: Long
    ): FrameDomainWorkItem<String> {
        val record = SensorFrameRecord(
            initialIdentity = FrameIdentity(
                sessionGeneration = 1L,
                ingressSequence = sequence,
                frameNumber = sequence,
                sensorTimestampNs = sequence * 100L,
                source = FrameIdentitySource.CAMERA2_CAPTURE_RESULT
            ),
            initialCameraMetadata = null,
            arrivedAtMonotonicNs = enqueuedAtNs
        )
        record.lifecycle.mark(FrameLifecycleState.CAPTURED)
        record.lifecycle.mark(FrameLifecycleState.BUFFERED)
        val shared = SharedFrameResource("frame-$sequence") { released.incrementAndGet() }
        val lease = shared.retain()!!
        shared.close()
        return FrameDomainWorkItem(record, lease, enqueuedAtNs, deadlineNs = Long.MAX_VALUE)
    }
}
