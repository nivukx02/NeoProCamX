package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Npv10ExportNativeReadinessGateTest {

    @Test
    fun readyProbeIsExecutedExactlyOnceAndCached() {
        val calls = AtomicInteger()
        val gate = Npv10ExportNativeReadinessGate(1_000L) {
            calls.incrementAndGet()
            "ready"
        }
        try {
            assertTrue(gate.awaitReady())
            assertTrue(gate.awaitReady())
            assertEquals("ready", gate.unavailableReason())
            assertEquals(1, calls.get())
        } finally {
            gate.closeForTesting()
        }
    }

    @Test
    fun explicitNativeRefusalIsCachedFailClosed() {
        val calls = AtomicInteger()
        val gate = Npv10ExportNativeReadinessGate(1_000L) {
            calls.incrementAndGet()
            "pipeline unavailable"
        }
        try {
            assertFalse(gate.awaitReady())
            assertFalse(gate.awaitReady())
            assertEquals("pipeline unavailable", gate.unavailableReason())
            assertEquals(1, calls.get())
        } finally {
            gate.closeForTesting()
        }
    }

    @Test
    fun blockedVendorProbeTimesOutOnceWithoutBlockingFallback() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val gate = Npv10ExportNativeReadinessGate(40L) {
            calls.incrementAndGet()
            entered.countDown()
            release.await(5L, TimeUnit.SECONDS)
            "ready"
        }
        try {
            val started = System.nanoTime()
            assertFalse(gate.awaitReady())
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("probe never started", entered.await(1L, TimeUnit.SECONDS))
            assertTrue("bounded wait took ${elapsedMs}ms", elapsedMs < 1_000L)
            assertFalse(gate.awaitReady())
            assertTrue(gate.unavailableReason().contains("TIMEOUT_40MS"))
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            gate.closeForTesting()
        }
    }

    @Test
    fun probeExceptionFailsClosedAndIsNotRetried() {
        val calls = AtomicInteger()
        val gate = Npv10ExportNativeReadinessGate(1_000L) {
            calls.incrementAndGet()
            throw IllegalStateException("driver failed")
        }
        try {
            assertFalse(gate.awaitReady())
            assertFalse(gate.awaitReady())
            assertTrue(gate.unavailableReason().contains("IllegalStateException"))
            assertEquals(1, calls.get())
        } finally {
            gate.closeForTesting()
        }
    }
}
