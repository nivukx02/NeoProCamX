package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CameraConversionIsolationCoordinatorTest {
    @Test fun `conversion closes camera before work and reopens after`() {
        var closed = false
        val events = mutableListOf<String>()
        val coordinator = CameraConversionIsolationCoordinator(
            dispatchMainAndWait = { action -> action() },
            captureActive = { false },
            cameraClosed = { closed },
            closeCamera = { events += "close"; closed = true },
            reopenCamera = { events += "open"; closed = false },
            sleep = {}
        )
        val result = coordinator.run { events += "convert"; "ok" }
        assertEquals("ok", result)
        assertEquals(listOf("close", "convert", "open"), events)
    }

    @Test(expected = IllegalStateException::class)
    fun `active recording blocks conversion before camera teardown`() {
        val coordinator = CameraConversionIsolationCoordinator(
            dispatchMainAndWait = { action -> action() }, captureActive = { true },
            cameraClosed = { false }, closeCamera = {}, reopenCamera = {}, sleep = {}
        )
        coordinator.run { Unit }
    }

    @Test fun `conversion waits for asynchronous Camera2 close acknowledgement`() {
        var closed = false
        var polls = 0
        var workStarted = false
        val coordinator = CameraConversionIsolationCoordinator(
            dispatchMainAndWait = { it() }, captureActive = { false }, cameraClosed = { closed },
            closeCamera = {}, reopenCamera = {},
            sleep = { if (++polls == 3) closed = true }
        )

        coordinator.run { workStarted = true }

        assertEquals(3, polls)
        assertTrue(workStarted)
    }

    @Test fun `trace proves close acknowledgement precedes conversion and reopen follows it`() {
        var closed = false
        val trace = mutableListOf<String>()
        val coordinator = CameraConversionIsolationCoordinator(
            dispatchMainAndWait = { it() }, captureActive = { false }, cameraClosed = { closed },
            closeCamera = { closed = true }, reopenCamera = { closed = false }, sleep = {}, trace = trace::add
        )

        coordinator.run { trace += "converter" }

        assertEquals(
            listOf(
                "request", "close-dispatched", "camera-release-acknowledged", "work-start",
                "converter", "work-finished", "reopen-dispatched"
            ),
            trace
        )
    }

    @Test fun `isolation remains active for the complete conversion work window`() {
        var closed = false
        lateinit var coordinator: CameraConversionIsolationCoordinator
        coordinator = CameraConversionIsolationCoordinator(
            dispatchMainAndWait = { it() }, captureActive = { false }, cameraClosed = { closed },
            closeCamera = { closed = true }, reopenCamera = { closed = false }, sleep = {}
        )

        coordinator.run { assertTrue(coordinator.isActive) }

        assertFalse(coordinator.isActive)
    }

    @Test fun `close acknowledgement timeout aborts conversion and restores camera`() {
        var now = 0L
        var workStarted = false
        var reopened = false
        val coordinator = CameraConversionIsolationCoordinator(
            dispatchMainAndWait = { it() }, captureActive = { false }, cameraClosed = { false },
            closeCamera = {}, reopenCamera = { reopened = true },
            sleep = { now += 1_000_000L }, nanoTime = { now }, closeTimeoutMs = 3L
        )

        val failed = runCatching { coordinator.run { workStarted = true } }.isFailure

        assertTrue(failed)
        assertFalse(workStarted)
        assertTrue(reopened)
    }

    @Test fun `overlapping conversion fails fast without a second camera lifecycle`() {
        var closed = false
        val closeCount = AtomicInteger(0)
        val reopenCount = AtomicInteger(0)
        val workStarted = CountDownLatch(1)
        val releaseWork = CountDownLatch(1)
        val coordinator = CameraConversionIsolationCoordinator(
            dispatchMainAndWait = { it() },
            captureActive = { false },
            cameraClosed = { closed },
            closeCamera = { closeCount.incrementAndGet(); closed = true },
            reopenCamera = { reopenCount.incrementAndGet(); closed = false },
            sleep = {}
        )
        val owner = Thread {
            coordinator.run {
                workStarted.countDown()
                check(releaseWork.await(5, TimeUnit.SECONDS))
            }
        }
        owner.start()
        assertTrue(workStarted.await(5, TimeUnit.SECONDS))

        val startedNanos = System.nanoTime()
        val failure = runCatching { coordinator.run { error("must not run") } }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("already owns"))
        assertTrue("overlap rejection blocked for ${elapsedMs}ms", elapsedMs < 250L)
        assertEquals(1, closeCount.get())
        assertEquals(0, reopenCount.get())
        releaseWork.countDown()
        owner.join(5_000L)
        assertFalse(owner.isAlive)
        assertEquals(1, reopenCount.get())
        assertFalse(coordinator.isActive)
    }
}
