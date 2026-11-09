package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
