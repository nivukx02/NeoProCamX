package com.neoproc.neoproccamx.recording

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10AudioPauseGateTest {
    @Test
    fun `pause and resume complete only after worker acknowledgement`() {
        val gate = Npv10AudioPauseGate()
        val executor = Executors.newSingleThreadExecutor()
        try {
            gate.activate()
            val pauseResult = executor.submit<Boolean> { gate.requestPause(1_000L) }
            awaitCondition { gate.shouldDiscardPcm() }
            assertFalse("Pause returned before the PCM boundary acknowledgement", pauseResult.isDone)
            gate.acknowledge(paused = true)
            assertTrue(pauseResult.get(1L, TimeUnit.SECONDS))

            val resumeResult = executor.submit<Boolean> { gate.requestResume(1_000L) }
            awaitCondition { !gate.shouldDiscardPcm() }
            assertFalse("Resume returned before capture restart acknowledgement", resumeResult.isDone)
            gate.acknowledge(paused = false)
            assertTrue(resumeResult.get(1L, TimeUnit.SECONDS))
        } finally {
            gate.terminate()
            executor.shutdownNow()
        }
    }

    @Test
    fun `termination wakes a pending transition and fails it closed`() {
        val gate = Npv10AudioPauseGate()
        val executor = Executors.newSingleThreadExecutor()
        try {
            gate.activate()
            val pauseResult = executor.submit<Boolean> { gate.requestPause(5_000L) }
            awaitCondition { gate.shouldDiscardPcm() }
            gate.terminate()
            assertFalse(pauseResult.get(1L, TimeUnit.SECONDS))
        } finally {
            gate.terminate()
            executor.shutdownNow()
        }
    }

    @Test
    fun `stale acknowledgement cannot complete the opposite transition`() {
        val gate = Npv10AudioPauseGate()
        val executor = Executors.newSingleThreadExecutor()
        try {
            gate.activate()
            val pauseResult = executor.submit<Boolean> { gate.requestPause(1_000L) }
            awaitCondition { gate.shouldDiscardPcm() }
            gate.acknowledge(paused = false)
            assertFalse(pauseResult.isDone)
            gate.acknowledge(paused = true)
            assertTrue(pauseResult.get(1L, TimeUnit.SECONDS))
        } finally {
            gate.terminate()
            executor.shutdownNow()
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L)
        while (!condition() && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertTrue("Timed out waiting for pause-gate request", condition())
    }
}
