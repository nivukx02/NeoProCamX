package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CameraSessionRefreshDispatchTrackerTest {
    @Test
    fun `rejected handler post can release the exact queued dispatch`() {
        val tracker = CameraSessionRefreshDispatchTracker()
        val ticket = requireNotNull(tracker.begin())

        assertEquals(1, tracker.pendingCount())
        assertTrue(tracker.complete(ticket))
        assertEquals(0, tracker.pendingCount())
        assertFalse(tracker.complete(ticket))
    }

    @Test
    fun `old completion cannot decrement a reopened lifecycle`() {
        val tracker = CameraSessionRefreshDispatchTracker()
        val oldTicket = requireNotNull(tracker.begin())
        tracker.invalidateLifecycle()
        tracker.resumeLifecycle()
        val newTicket = requireNotNull(tracker.begin())

        assertFalse(tracker.complete(oldTicket))
        assertEquals(1, tracker.pendingCount())
        assertTrue(tracker.isActive(newTicket))
        assertTrue(tracker.complete(newTicket))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `lifecycle invalidation makes already queued work stale`() {
        val tracker = CameraSessionRefreshDispatchTracker()
        val ticket = requireNotNull(tracker.begin())

        tracker.invalidateLifecycle()

        assertFalse(tracker.isActive(ticket))
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `lifecycle invalidation owns every queued rejection exactly once`() {
        val tracker = CameraSessionRefreshDispatchTracker()
        val callbackCount = AtomicInteger(0)
        val first = requireNotNull(tracker.begin { callbackCount.incrementAndGet() })
        val second = requireNotNull(tracker.begin { callbackCount.incrementAndGet() })

        val callbacks = tracker.invalidateLifecycle()

        assertEquals(2, callbacks.size)
        callbacks.forEach { it() }
        assertEquals(2, callbackCount.get())
        assertFalse(tracker.complete(first))
        assertFalse(tracker.executeIfActive(second) { callbackCount.addAndGet(100) })
        assertEquals(2, callbackCount.get())
        assertNull(tracker.begin())
    }

    @Test
    fun `invalidation waits for admitted action and cannot split admission from execution`() {
        val tracker = CameraSessionRefreshDispatchTracker()
        val actionEntered = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val invalidationStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val ticket = requireNotNull(tracker.begin())
            val action = executor.submit<Boolean> {
                tracker.executeIfActive(ticket) {
                    actionEntered.countDown()
                    check(releaseAction.await(3, TimeUnit.SECONDS))
                }
            }
            assertTrue(actionEntered.await(3, TimeUnit.SECONDS))

            val invalidation = executor.submit<List<() -> Unit>> {
                invalidationStarted.countDown()
                tracker.invalidateLifecycle()
            }
            assertTrue(invalidationStarted.await(3, TimeUnit.SECONDS))
            Thread.sleep(50L)
            assertFalse("invalidation crossed a running dispatch", invalidation.isDone)

            releaseAction.countDown()
            assertTrue(action.get(3, TimeUnit.SECONDS))
            assertTrue(invalidation.get(3, TimeUnit.SECONDS).isEmpty())
            assertEquals(0, tracker.pendingCount())
        } finally {
            releaseAction.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `action exception retires ticket and releases lifecycle barrier`() {
        val tracker = CameraSessionRefreshDispatchTracker()
        val ticket = requireNotNull(tracker.begin())

        val failure = runCatching {
            tracker.executeIfActive(ticket) { error("injected") }
        }.exceptionOrNull()

        assertEquals("injected", failure?.message)
        assertEquals(0, tracker.pendingCount())
        assertTrue(tracker.invalidateLifecycle().isEmpty())
    }
}
