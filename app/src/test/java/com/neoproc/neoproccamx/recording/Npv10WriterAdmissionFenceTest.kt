package com.neoproc.neoproccamx.recording

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10WriterAdmissionFenceTest {
    @Test fun slowNativeAdmissionDoesNotBlockPreparedFrameCommit() = withWorkers(2) { workers ->
        val fence = Npv10WriterAdmissionFence()
        val writerMonitor = Any()
        val enteredNative = CountDownLatch(1)
        val releaseNative = CountDownLatch(1)
        val admission = workers.submit<Int> {
            fence.withAdmission {
                val reserved = synchronized(writerMonitor) { 10 }
                enteredNative.countDown()
                await(releaseNative)
                reserved
            }
        }
        try {
            await(enteredNative)
            val commit = workers.submit<Int> { synchronized(writerMonitor) { 25 } }
            assertEquals(25, commit.get(3, TimeUnit.SECONDS))
            assertFalse(admission.isDone)
        } finally {
            releaseNative.countDown()
        }
        assertEquals(10, admission.get(3, TimeUnit.SECONDS))
    }

    @Test fun pendingCloseCannotHoldCommitMonitorWhileAdmissionWaits() = withWorkers(3) { workers ->
        val fence = Npv10WriterAdmissionFence()
        val writerMonitor = Any()
        val enteredNative = CountDownLatch(1)
        val releaseNative = CountDownLatch(1)
        val closeRequested = CountDownLatch(1)
        val resourceClosed = CountDownLatch(1)
        val admission = workers.submit {
            fence.withAdmission {
                synchronized(writerMonitor) { check(resourceClosed.count != 0L) }
                enteredNative.countDown()
                await(releaseNative)
            }
        }
        try {
            await(enteredNative)
            val close = workers.submit {
                closeRequested.countDown()
                fence.withClose { synchronized(writerMonitor) { resourceClosed.countDown() } }
            }
            await(closeRequested)
            val commit = workers.submit<Boolean> {
                synchronized(writerMonitor) { resourceClosed.count != 0L }
            }
            assertTrue(commit.get(3, TimeUnit.SECONDS))
            assertFalse(resourceClosed.await(50, TimeUnit.MILLISECONDS))
            releaseNative.countDown()
            admission.get(3, TimeUnit.SECONDS)
            close.get(3, TimeUnit.SECONDS)
            assertEquals(0L, resourceClosed.count)
        } finally {
            releaseNative.countDown()
        }
    }

    @Test fun twoPackingLanesCanAdmitConcurrently() = withWorkers(2) { workers ->
        val fence = Npv10WriterAdmissionFence()
        val bothAdmitted = CountDownLatch(2)
        val release = CountDownLatch(1)
        val admissions = List(2) {
            workers.submit {
                fence.withAdmission {
                    bothAdmitted.countDown()
                    await(release)
                }
            }
        }
        try {
            await(bothAdmitted)
        } finally {
            release.countDown()
        }
        admissions.forEach { it.get(3, TimeUnit.SECONDS) }
    }

    @Test fun nativeFailureReleasesAdmissionBeforeClose() = withWorkers(1) { workers ->
        val fence = Npv10WriterAdmissionFence()
        val failure = IOException("injected native extent failure")
        val observed = runCatching { fence.withAdmission { throw failure } }.exceptionOrNull()
        assertSame(failure, observed)
        assertTrue(workers.submit<Boolean> { fence.withClose { true } }.get(3, TimeUnit.SECONDS))
    }

    @Test fun validationFailureReleasesAdmissionBeforeClose() = withWorkers(1) { workers ->
        val fence = Npv10WriterAdmissionFence()
        val writerMonitor = Any()
        val failure = runCatching {
            fence.withAdmission { synchronized(writerMonitor) { check(false) { "closed writer" } } }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(workers.submit<Boolean> { fence.withClose { true } }.get(3, TimeUnit.SECONDS))
    }

    @Test fun closeFailureDoesNotStrandExclusiveFence() = withWorkers(1) { workers ->
        val fence = Npv10WriterAdmissionFence()
        val failure = IOException("injected final sync failure")
        assertSame(failure, runCatching { fence.withClose { throw failure } }.exceptionOrNull())
        assertTrue(workers.submit<Boolean> { fence.withClose { true } }.get(3, TimeUnit.SECONDS))
    }

    @Test fun closeFromAdmissionIsRejectedInsteadOfDeadlocking() {
        val fence = Npv10WriterAdmissionFence()
        val closed = AtomicBoolean(false)
        val failure = runCatching {
            fence.withAdmission { fence.withClose { closed.set(true) } }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(closed.get())
        fence.withClose { closed.set(true) }
        assertTrue(closed.get())
    }

    @Test fun closedWriterIsRecheckedOnNextAdmission() {
        val fence = Npv10WriterAdmissionFence()
        val writerMonitor = Any()
        var closed = false
        fence.withClose { synchronized(writerMonitor) { closed = true } }
        val failure = runCatching {
            fence.withAdmission { synchronized(writerMonitor) { check(!closed) } }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test fun concurrentAdmissionsAndCloseNeverOverlapResourceOwnership() = withWorkers(3) { workers ->
        val fence = Npv10WriterAdmissionFence()
        val activeAdmissions = AtomicInteger()
        val activeClose = AtomicBoolean()
        val operations = AtomicInteger()
        val start = CountDownLatch(1)
        val tasks = List(2) {
            workers.submit {
                await(start)
                repeat(1_000) {
                    fence.withAdmission {
                        activeAdmissions.incrementAndGet()
                        try {
                            assertFalse(activeClose.get())
                            operations.incrementAndGet()
                        } finally {
                            activeAdmissions.decrementAndGet()
                        }
                    }
                }
            }
        } + workers.submit {
            await(start)
            repeat(1_000) {
                fence.withClose {
                    assertTrue(activeClose.compareAndSet(false, true))
                    try {
                        assertEquals(0, activeAdmissions.get())
                        operations.incrementAndGet()
                    } finally {
                        activeClose.set(false)
                    }
                }
            }
        }
        start.countDown()
        tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        assertEquals(3_000, operations.get())
        assertEquals(0, activeAdmissions.get())
        assertFalse(activeClose.get())
    }

    private fun await(latch: CountDownLatch) {
        check(latch.await(3, TimeUnit.SECONDS)) { "Timed out waiting for the controlled test boundary" }
    }

    private fun withWorkers(count: Int, action: (ExecutorService) -> Unit) {
        val workers = Executors.newFixedThreadPool(count)
        try {
            action(workers)
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}
