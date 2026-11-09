package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactTimestampAcceptanceTrackerTest {
    @Test
    fun `only exact timestamp in current sink epoch satisfies ownership`() {
        val tracker = ExactTimestampAcceptanceTracker()
        val epoch = tracker.reset()

        assertTrue(tracker.record(epoch, 10L))
        assertFalse(tracker.contains(epoch, 11L))
        assertTrue(tracker.contains(epoch, 10L))
        assertFalse(tracker.await(epoch, 11L, 1L))
    }

    @Test
    fun `sink replacement makes old acceptance unable to prove new marker`() {
        val tracker = ExactTimestampAcceptanceTracker()
        val oldEpoch = tracker.reset()
        assertTrue(tracker.record(oldEpoch, 10L))

        val newEpoch = tracker.reset()
        assertFalse(tracker.record(oldEpoch, 11L))
        assertFalse(tracker.contains(newEpoch, 10L))
        assertTrue(tracker.record(newEpoch, 10L))
        assertTrue(tracker.contains(newEpoch, 10L))
    }

    @Test
    fun `interrupted ownership wait restores interrupt`() {
        val tracker = ExactTimestampAcceptanceTracker()
        val epoch = tracker.reset()
        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val result = AtomicBoolean(true)
        val thread = Thread {
            started.countDown()
            result.set(tracker.await(epoch, 10L, TimeUnit.MINUTES.toNanos(1L)))
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        thread.start()
        assertTrue(started.await(1L, TimeUnit.SECONDS))
        thread.interrupt()
        thread.join(1_000L)

        assertFalse(result.get())
        assertTrue(interrupted.get())
    }
}
