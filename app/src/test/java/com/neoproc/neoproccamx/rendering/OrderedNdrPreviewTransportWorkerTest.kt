package com.neoproc.neoproccamx.rendering

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedNdrPreviewTransportWorkerTest {
    @Test
    fun `worker is ordered bounded and drains every accepted owner`() {
        val worker = OrderedNdrPreviewTransportWorker(maxOutstanding = 3, threadName = "ndr-test")
        val firstMayFinish = CountDownLatch(1)
        val allFinished = CountDownLatch(3)
        val order = Collections.synchronizedList(mutableListOf<Int>())
        assertTrue(worker.resumeAfterDrain())

        repeat(3) { frame ->
            assertTrue(
                worker.submit("frame-$frame") {
                    if (frame == 0) assertTrue(firstMayFinish.await(2, TimeUnit.SECONDS))
                    order += frame
                    allFinished.countDown()
                }
            )
        }
        assertFalse(worker.submit("ring-overflow") { error("must not run") })
        assertFalse(worker.resumeAfterDrain())

        firstMayFinish.countDown()
        assertTrue(allFinished.await(2, TimeUnit.SECONDS))
        assertTrue(worker.pauseAndDrain(2_000L))
        val snapshot = worker.snapshot()
        assertEquals(listOf(0, 1, 2), order.toList())
        assertEquals(3L, snapshot.acceptedTasks)
        assertEquals(3L, snapshot.completedTasks)
        assertEquals(0L, snapshot.failedTasks)
        assertEquals(1L, snapshot.rejectedTasks)
        assertEquals(0, snapshot.outstandingTasks)
        assertTrue(worker.closeAndDrain(2_000L))
    }

    @Test
    fun `task failure is sticky until a drained lifecycle epoch explicitly recovers`() {
        val worker = OrderedNdrPreviewTransportWorker(maxOutstanding = 3, threadName = "ndr-fail-test")
        val attempted = CountDownLatch(1)
        assertTrue(worker.resumeAfterDrain())
        assertTrue(
            worker.submit("bad-token") {
                attempted.countDown()
                error("ack rejected")
            }
        )
        assertTrue(attempted.await(2, TimeUnit.SECONDS))
        assertTrue(worker.pauseAndDrain(2_000L))
        assertEquals(OrderedNdrPreviewTransportWorker.State.FAILED, worker.snapshot().state)
        assertNotNull(worker.snapshot().lastFailure)
        assertFalse(worker.submit("must-fail-closed") { })
        assertTrue(worker.resumeAfterDrain(clearFailure = true))
        assertEquals(OrderedNdrPreviewTransportWorker.State.RUNNING, worker.snapshot().state)
        assertTrue(worker.closeAndDrain(2_000L))
    }
}
