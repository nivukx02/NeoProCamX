package com.neoproc.neoproccamx.recording

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFatalFailureSignalTest {
    @Test
    fun `audio horizon failure is replayed without clearing publication poison`() {
        val signal = RecordingFatalFailureSignal()
        signal.reset()
        val failure = signal.recordFirst("Audio/video horizon did not converge within 2000000us")!!
        assertSame(failure, signal.failures.value)
        assertTrue(signal.claimStop(failure))
        assertFalse(signal.claimStop(failure))
        assertEquals(failure.message, signal.get())
        assertSame(failure, signal.failures.value)
    }

    @Test
    fun `cleanup failures cannot replace the first root cause`() {
        val signal = RecordingFatalFailureSignal()
        val first = signal.recordFirst("audio horizon")!!
        assertNull(signal.recordFirst("video EOS failure during cleanup"))
        assertEquals("audio horizon", signal.get())
        assertSame(first, signal.failures.value)
    }

    @Test
    fun `queued old failure cannot stop the next clip even with an identical message`() {
        val signal = RecordingFatalFailureSignal()
        signal.reset()
        val old = signal.recordFirst("encoder failed")!!
        signal.reset()
        assertNull(signal.get())
        assertFalse(signal.claimStop(old))
        val next = signal.recordFirst("encoder failed")!!
        assertTrue(next.generation > old.generation)
        assertFalse(signal.claimStop(old))
        assertTrue(signal.claimStop(next))
        assertFalse(signal.claimStop(next))
    }

    @Test
    fun `not claiming during startup retains the cause for an active backend`() {
        val signal = RecordingFatalFailureSignal()
        signal.reset()
        val failure = signal.recordFirst("audio initialization failed")!!
        // The observer has not claimed while the backend is Starting.
        assertSame(failure, signal.failures.value)
        assertTrue(signal.claimStop(failure))
    }

    @Test
    fun `blank reasons remain an explicit error`() {
        val signal = RecordingFatalFailureSignal()
        assertEquals("Unspecified fatal recording pipeline failure", signal.recordFirst(" \n ")!!.message)
    }

    @Test
    fun `concurrent worker failures produce one first cause and one stop claim`() {
        val signal = RecordingFatalFailureSignal()
        signal.reset()
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(64)
        val publications = AtomicInteger()
        val stops = AtomicInteger()
        try {
            repeat(64) { index ->
                workers.execute {
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        if (signal.recordFirst("worker-$index") != null) publications.incrementAndGet()
                        signal.failures.value?.let { if (signal.claimStop(it)) stops.incrementAndGet() }
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(1, publications.get())
            assertEquals(1, stops.get())
            assertNotNull(signal.get())
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
