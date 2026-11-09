package com.neoproc.neoproccamx.capture

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StillCaptureCameraBufferTrackerTest {

    @Test
    fun `acquired buffer blocks release proof until its lease closes`() {
        val tracker = StillCaptureCameraBufferTracker()
        val lease = tracker.acquire()
        val waiterStarted = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val proof = executor.submit<Boolean> {
                waiterStarted.countDown()
                tracker.awaitReleased(2_000L)
            }
            assertTrue(waiterStarted.await(1, TimeUnit.SECONDS))
            assertFalse("release proof overtook an acquired Image", proof.isDone)
            assertFalse(tracker.awaitReleased(0L))

            lease.close()
            assertTrue(proof.get(1, TimeUnit.SECONDS))
            assertTrue(tracker.awaitReleased(0L))
            assertEquals(0L, tracker.outstandingCount())
        } finally {
            lease.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `timeout fails closed and reader close action is not invoked`() {
        val tracker = StillCaptureCameraBufferTracker()
        val lease = tracker.acquire()
        val readerCloseCalls = AtomicInteger(0)

        try {
            val safeToCloseReader = tracker.awaitReleased(0L)
            if (safeToCloseReader) readerCloseCalls.incrementAndGet()

            assertFalse(safeToCloseReader)
            assertEquals(0, readerCloseCalls.get())
            assertEquals(1L, tracker.outstandingCount())
        } finally {
            lease.close()
        }
    }

    @Test
    fun `cancellation and processing failures release proof after successful image close`() {
        listOf<RuntimeException>(
            CancellationException("cancelled"),
            IllegalStateException("processing failed")
        ).forEach { failure ->
            val tracker = StillCaptureCameraBufferTracker()
            val imageCloseCalls = AtomicInteger(0)

            assertThrows(failure.javaClass) {
                simulateAcquiredImage(
                    tracker = tracker,
                    closeImage = { imageCloseCalls.incrementAndGet() },
                    process = { throw failure }
                )
            }

            assertEquals(1, imageCloseCalls.get())
            assertTrue(tracker.awaitReleased(0L))
            assertEquals(0L, tracker.outstandingCount())
        }
    }

    @Test
    fun `image close failure retains proof failure for guarded teardown retry`() {
        val tracker = StillCaptureCameraBufferTracker()

        assertThrows(IllegalStateException::class.java) {
            simulateAcquiredImage(
                tracker = tracker,
                closeImage = { throw IllegalStateException("close failed") },
                process = {}
            )
        }

        assertFalse(tracker.awaitReleased(0L))
        assertEquals(1L, tracker.outstandingCount())
    }

    @Test
    fun `double lease close is idempotent and cannot underflow`() {
        val tracker = StillCaptureCameraBufferTracker()
        val first = tracker.acquire()
        val second = tracker.acquire()

        first.close()
        first.close()
        assertEquals(1L, tracker.outstandingCount())
        assertFalse(tracker.awaitReleased(0L))

        second.close()
        second.close()
        assertEquals(0L, tracker.outstandingCount())
        assertTrue(tracker.awaitReleased(0L))
    }

    @Test
    fun `interrupted release wait fails closed and restores interrupt status`() {
        val tracker = StillCaptureCameraBufferTracker()
        val lease = tracker.acquire()
        val waiterStarted = CountDownLatch(1)
        val interruptPreserved = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val proof = executor.submit<Boolean> {
                waiterStarted.countDown()
                val released = tracker.awaitReleased(30_000L)
                interruptPreserved.set(Thread.currentThread().isInterrupted)
                released
            }
            assertTrue(waiterStarted.await(1, TimeUnit.SECONDS))
            proof.cancel(true)
            executor.shutdown()
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
            assertTrue(interruptPreserved.get())
            assertEquals(1L, tracker.outstandingCount())
        } finally {
            lease.close()
            executor.shutdownNow()
        }
    }

    private fun simulateAcquiredImage(
        tracker: StillCaptureCameraBufferTracker,
        closeImage: () -> Unit,
        process: () -> Unit
    ) {
        val lease = tracker.acquire()
        try {
            process()
        } finally {
            var imageClosed = false
            try {
                closeImage()
                imageClosed = true
            } finally {
                if (imageClosed) lease.close()
            }
        }
    }
}
