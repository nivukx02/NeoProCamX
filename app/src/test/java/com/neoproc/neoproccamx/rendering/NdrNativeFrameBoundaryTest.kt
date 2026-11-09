package com.neoproc.neoproccamx.rendering

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NdrNativeFrameBoundaryTest {
    @Test
    fun `ingress honors closed and open initial states`() {
        val closed = NdrNativeFrameBoundary(initiallyOpen = false)
        assertNull(closed.acquireFrameLease())
        assertEquals(
            NdrNativeFrameBoundary.Snapshot(
                epoch = 0L,
                ingressOpen = false,
                activeFrameLeases = 0
            ),
            closed.snapshot()
        )

        val open = NdrNativeFrameBoundary(initiallyOpen = true)
        val lease = checkNotNull(open.acquireFrameLease())
        assertEquals(1, open.snapshot().activeFrameLeases)
        lease.close()
        assertEquals(0, open.snapshot().activeFrameLeases)
        assertTrue(open.snapshot().ingressOpen)
    }

    @Test
    fun `active frame reader excludes mutation writer`() {
        val boundary = NdrNativeFrameBoundary(initiallyOpen = true)
        val readerAcquired = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val writerAttempting = CountDownLatch(1)
        val writerEntered = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()

        val reader = thread(start = true, name = "ndr-boundary-active-reader") {
            var lease: NdrNativeFrameBoundary.FrameLease? = null
            try {
                lease = checkNotNull(boundary.acquireFrameLease())
                readerAcquired.countDown()
                check(releaseReader.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            } catch (failure: Throwable) {
                workerFailure.compareAndSet(null, failure)
            } finally {
                lease?.close()
            }
        }
        await(readerAcquired, "reader did not acquire its frame lease")

        val writer = thread(start = true, name = "ndr-boundary-excluded-writer") {
            try {
                writerAttempting.countDown()
                boundary.withMutationLease { writerEntered.countDown() }
            } catch (failure: Throwable) {
                workerFailure.compareAndSet(null, failure)
            }
        }
        await(writerAttempting, "writer did not attempt mutation")
        awaitQueued(writer, "writer did not queue behind active reader")
        assertEquals(1L, writerEntered.count)

        releaseReader.countDown()
        await(writerEntered, "writer did not enter after the reader released")
        join(reader)
        join(writer)
        throwWorkerFailure(workerFailure)
        assertEquals(0, boundary.snapshot().activeFrameLeases)
    }

    @Test
    fun `queued fair writer prevents new frame reader barging`() {
        val boundary = NdrNativeFrameBoundary(initiallyOpen = true)
        val firstReaderAcquired = CountDownLatch(1)
        val releaseFirstReader = CountDownLatch(1)
        val writerAttempting = CountDownLatch(1)
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val secondReaderAttempting = CountDownLatch(1)
        val secondReaderEntered = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val workerFailure = AtomicReference<Throwable?>()

        val firstReader = thread(start = true, name = "ndr-boundary-first-reader") {
            var lease: NdrNativeFrameBoundary.FrameLease? = null
            try {
                lease = checkNotNull(boundary.acquireFrameLease())
                firstReaderAcquired.countDown()
                check(releaseFirstReader.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            } catch (failure: Throwable) {
                workerFailure.compareAndSet(null, failure)
            } finally {
                lease?.close()
            }
        }
        await(firstReaderAcquired, "first reader did not acquire its frame lease")

        val writer = thread(start = true, name = "ndr-boundary-fair-writer") {
            try {
                writerAttempting.countDown()
                boundary.withMutationLease {
                    order += "writer"
                    writerEntered.countDown()
                    check(releaseWriter.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                }
            } catch (failure: Throwable) {
                workerFailure.compareAndSet(null, failure)
            }
        }
        await(writerAttempting, "writer did not attempt mutation")
        awaitQueued(writer, "writer did not queue behind first reader")

        val secondReader = thread(start = true, name = "ndr-boundary-second-reader") {
            var lease: NdrNativeFrameBoundary.FrameLease? = null
            try {
                secondReaderAttempting.countDown()
                lease = checkNotNull(boundary.acquireFrameLease())
                order += "second-reader"
                secondReaderEntered.countDown()
            } catch (failure: Throwable) {
                workerFailure.compareAndSet(null, failure)
            } finally {
                lease?.close()
            }
        }
        await(secondReaderAttempting, "second reader did not attempt ingress")
        awaitQueued(secondReader, "second reader barged instead of queueing behind writer")
        assertEquals(1L, writerEntered.count)
        assertEquals(1L, secondReaderEntered.count)

        releaseFirstReader.countDown()
        await(writerEntered, "queued writer did not acquire after first reader released")
        assertEquals("second reader entered while the queued writer owned the boundary", 1L, secondReaderEntered.count)

        releaseWriter.countDown()
        await(secondReaderEntered, "second reader did not acquire after writer released")
        join(firstReader)
        join(writer)
        join(secondReader)
        throwWorkerFailure(workerFailure)
        assertEquals(listOf("writer", "second-reader"), order.toList())
        assertEquals(0, boundary.snapshot().activeFrameLeases)
    }

    @Test
    fun `successful finalization opens ingress`() {
        val boundary = NdrNativeFrameBoundary(initiallyOpen = false)

        assertTrue(boundary.finalizeIngress { true })
        assertEquals(
            NdrNativeFrameBoundary.Snapshot(
                epoch = 1L,
                ingressOpen = true,
                activeFrameLeases = 0
            ),
            boundary.snapshot()
        )
        checkNotNull(boundary.acquireFrameLease()).close()
    }

    @Test
    fun `recording frame waits through a bounded profile transition`() {
        val boundary = NdrNativeFrameBoundary(initiallyOpen = false)
        val waiterStarted = CountDownLatch(1)
        val lease = AtomicReference<NdrNativeFrameBoundary.FrameLease?>()
        val workerFailure = AtomicReference<Throwable?>()

        val waiter = thread(start = true, name = "ndr-boundary-recording-waiter") {
            try {
                waiterStarted.countDown()
                val acquiredLease =
                    boundary.acquireFrameLeaseAwaitingIngress(
                        TimeUnit.SECONDS.toNanos(WORKER_TIMEOUT_SECONDS)
                    )
                lease.set(acquiredLease)
                acquiredLease?.close()
            } catch (failure: Throwable) {
                workerFailure.set(failure)
            }
        }
        await(waiterStarted, "recording waiter did not start")
        awaitQueued(waiter, "recording waiter did not wait for profile publication")

        assertTrue(boundary.finalizeIngress { true })
        join(waiter)
        throwWorkerFailure(workerFailure)
        checkNotNull(lease.get())
        assertEquals(0, boundary.snapshot().activeFrameLeases)
    }

    @Test
    fun `recording frame timeout remains fail closed`() {
        val boundary = NdrNativeFrameBoundary(initiallyOpen = false)

        assertNull(boundary.acquireFrameLeaseAwaitingIngress(TimeUnit.MILLISECONDS.toNanos(5L)))
        assertFalse(boundary.snapshot().ingressOpen)
        assertEquals(0, boundary.snapshot().activeFrameLeases)
    }

    @Test
    fun `recording frame timeout includes an active mutation lock`() {
        val boundary = NdrNativeFrameBoundary(initiallyOpen = true)
        val mutationEntered = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val waiterCompleted = CountDownLatch(1)
        val acquired = AtomicReference<NdrNativeFrameBoundary.FrameLease?>()
        val workerFailure = AtomicReference<Throwable?>()

        val writer = thread(start = true, name = "ndr-boundary-held-mutation") {
            try {
                boundary.withMutationLease {
                    boundary.closeIngressForMutation()
                    mutationEntered.countDown()
                    check(releaseMutation.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                }
            } catch (failure: Throwable) {
                workerFailure.compareAndSet(null, failure)
            }
        }
        await(mutationEntered, "mutation did not acquire the boundary")

        val waiter = thread(start = true, name = "ndr-boundary-bounded-recording-waiter") {
            try {
                acquired.set(
                    boundary.acquireFrameLeaseAwaitingIngress(TimeUnit.MILLISECONDS.toNanos(20L))
                )
            } catch (failure: Throwable) {
                workerFailure.compareAndSet(null, failure)
            } finally {
                waiterCompleted.countDown()
            }
        }
        try {
            assertTrue(
                "recording ingress wait exceeded its deadline while mutation owned the lock",
                waiterCompleted.await(1L, TimeUnit.SECONDS)
            )
            assertNull(acquired.get())
        } finally {
            releaseMutation.countDown()
        }
        join(waiter)
        join(writer)
        throwWorkerFailure(workerFailure)
        assertFalse(boundary.snapshot().ingressOpen)
        assertEquals(0, boundary.snapshot().activeFrameLeases)
    }

    @Test
    fun `false and exceptional finalization remain closed and always unlock`() {
        val rejected = NdrNativeFrameBoundary(initiallyOpen = true)
        assertFalse(rejected.finalizeIngress { false })
        assertFalse(rejected.snapshot().ingressOpen)
        assertEquals(0L, rejected.snapshot().epoch)
        assertNull(rejected.acquireFrameLease())
        var rejectedBoundaryUnlocked = false
        rejected.withMutationLease { rejectedBoundaryUnlocked = true }
        assertTrue(rejectedBoundaryUnlocked)

        val exceptional = NdrNativeFrameBoundary(initiallyOpen = true)
        val expected = IllegalArgumentException("synthetic publication failure")
        val actual = assertThrows(IllegalArgumentException::class.java) {
            exceptional.finalizeIngress { throw expected }
        }
        assertSame(expected, actual)
        assertFalse(exceptional.snapshot().ingressOpen)
        assertEquals(0L, exceptional.snapshot().epoch)
        assertNull(exceptional.acquireFrameLease())
        var exceptionalBoundaryUnlocked = false
        exceptional.withMutationLease { exceptionalBoundaryUnlocked = true }
        assertTrue(exceptionalBoundaryUnlocked)
    }

    @Test
    fun `external route invalidation wins after publication proof but before reopen`() {
        val boundary = NdrNativeFrameBoundary(initiallyOpen = false)
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val result = AtomicReference<Boolean?>()
        val workerFailure = AtomicReference<Throwable?>()

        val finalizer = thread(start = true, name = "ndr-boundary-route-invalidation") {
            try {
                result.set(
                    boundary.finalizeIngress {
                        publicationEntered.countDown()
                        check(releasePublication.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        true
                    }
                )
            } catch (failure: Throwable) {
                workerFailure.set(failure)
            }
        }
        await(publicationEntered, "finalization did not enter publication")

        boundary.invalidateIngressFromExternalRouteChange()
        releasePublication.countDown()
        join(finalizer)
        throwWorkerFailure(workerFailure)

        assertEquals(false, result.get())
        assertFalse(boundary.snapshot().ingressOpen)
        assertEquals(1L, boundary.snapshot().epoch)
        assertNull(boundary.acquireFrameLease())
    }

    @Test
    fun `external invalidation between open read and lease publication rejects the frame`() {
        val stateObserved = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        val boundary = NdrNativeFrameBoundary(
            initiallyOpen = true,
            onFrameAdmissionEpochObserved = {
                stateObserved.countDown()
                check(releaseAdmission.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
        )
        val acquired = AtomicReference<NdrNativeFrameBoundary.FrameLease?>()
        val workerFailure = AtomicReference<Throwable?>()
        val reader = thread(start = true, name = "ndr-boundary-invalidation-admission") {
            try {
                acquired.set(boundary.acquireFrameLease())
            } catch (failure: Throwable) {
                workerFailure.set(failure)
            }
        }
        await(stateObserved, "frame admission did not observe the open ingress epoch")

        assertEquals(1L, boundary.invalidateIngressFromExternalRouteChange())
        releaseAdmission.countDown()
        join(reader)
        throwWorkerFailure(workerFailure)

        assertNull(acquired.get())
        assertEquals(0, boundary.snapshot().activeFrameLeases)
        assertFalse(boundary.snapshot().ingressOpen)
        assertEquals(1L, boundary.currentIngressEpoch())
    }

    @Test
    fun `mutation exception releases writer ownership`() {
        val boundary = NdrNativeFrameBoundary(initiallyOpen = true)
        val expected = IllegalStateException("synthetic mutation failure")

        val actual = assertThrows(IllegalStateException::class.java) {
            boundary.withMutationLease { throw expected }
        }
        assertSame(expected, actual)

        var subsequentMutationEntered = false
        boundary.withMutationLease { subsequentMutationEntered = true }
        assertTrue(subsequentMutationEntered)
        assertTrue(boundary.snapshot().ingressOpen)
    }

    @Test
    fun `read lease cannot upgrade to mutation lease`() {
        val boundary = NdrNativeFrameBoundary(initiallyOpen = true)
        val lease = checkNotNull(boundary.acquireFrameLease())

        val failure = try {
            assertThrows(IllegalStateException::class.java) {
                boundary.withMutationLease { fail("upgrade unexpectedly entered") }
            }
        } finally {
            lease.close()
        }
        assertTrue(failure.message.orEmpty().contains("cannot begin from inside a frame lease"))

        var mutationEnteredAfterRelease = false
        boundary.withMutationLease { mutationEnteredAfterRelease = true }
        assertTrue(mutationEnteredAfterRelease)
    }

    @Test
    fun `lease accounting remains exact across multiple and double closes`() {
        val boundary = NdrNativeFrameBoundary(initiallyOpen = true)
        val first = checkNotNull(boundary.acquireFrameLease())
        val second = checkNotNull(boundary.acquireFrameLease())
        assertEquals(2, boundary.snapshot().activeFrameLeases)

        first.close()
        assertEquals(1, boundary.snapshot().activeFrameLeases)
        first.close()
        assertEquals(1, boundary.snapshot().activeFrameLeases)

        second.close()
        assertEquals(0, boundary.snapshot().activeFrameLeases)
        second.close()
        assertEquals(0, boundary.snapshot().activeFrameLeases)
    }

    @Test
    fun `wrong thread close is rejected and owner can subsequently recover`() {
        val boundary = NdrNativeFrameBoundary(initiallyOpen = true)
        val lease = checkNotNull(boundary.acquireFrameLease())
        val closeFailure = AtomicReference<Throwable?>()

        val wrongThread = thread(start = true, name = "ndr-boundary-wrong-closer") {
            try {
                lease.close()
            } catch (failure: Throwable) {
                closeFailure.set(failure)
            }
        }
        join(wrongThread)

        val failure = closeFailure.get()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("acquiring thread"))
        assertEquals(1, boundary.snapshot().activeFrameLeases)

        lease.close()
        assertEquals(0, boundary.snapshot().activeFrameLeases)
        var mutationEntered = false
        boundary.withMutationLease { mutationEntered = true }
        assertTrue(mutationEntered)
    }

    @Test
    fun `closed ingress still permits retirement without admitting a frame`() {
        val boundary = NdrNativeFrameBoundary(initiallyOpen = true)
        boundary.withMutationLease { boundary.closeIngressForMutation() }

        assertNull(boundary.acquireFrameLease())
        val retirement = boundary.acquireRetirementLease()
        assertFalse(boundary.snapshot().ingressOpen)
        assertEquals(0, boundary.snapshot().activeFrameLeases)
        assertNull(boundary.acquireFrameLease())
        retirement.close()

        assertFalse(boundary.snapshot().ingressOpen)
        assertEquals(0, boundary.snapshot().activeFrameLeases)
    }

    @Test
    fun `retirement reader excludes mutation writer and releases exactly once`() {
        val boundary = NdrNativeFrameBoundary(initiallyOpen = false)
        val retirementAcquired = CountDownLatch(1)
        val releaseRetirement = CountDownLatch(1)
        val writerAttempting = CountDownLatch(1)
        val writerEntered = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()

        val retirement = thread(start = true, name = "ndr-boundary-retirement-reader") {
            var lease: NdrNativeFrameBoundary.RetirementLease? = null
            try {
                lease = boundary.acquireRetirementLease()
                retirementAcquired.countDown()
                check(releaseRetirement.await(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            } catch (failure: Throwable) {
                workerFailure.compareAndSet(null, failure)
            } finally {
                lease?.close()
                lease?.close()
            }
        }
        await(retirementAcquired, "retirement reader did not acquire")

        val writer = thread(start = true, name = "ndr-boundary-retirement-writer") {
            try {
                writerAttempting.countDown()
                boundary.withMutationLease { writerEntered.countDown() }
            } catch (failure: Throwable) {
                workerFailure.compareAndSet(null, failure)
            }
        }
        await(writerAttempting, "writer did not attempt mutation")
        awaitQueued(writer, "writer did not queue behind retirement reader")
        assertEquals(1L, writerEntered.count)

        releaseRetirement.countDown()
        await(writerEntered, "writer did not enter after retirement released")
        join(retirement)
        join(writer)
        throwWorkerFailure(workerFailure)
        assertNull(boundary.acquireFrameLease())
        assertEquals(0, boundary.snapshot().activeFrameLeases)
    }

    private fun await(latch: CountDownLatch, message: String) {
        assertTrue(message, latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    private fun awaitQueued(worker: Thread, message: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TEST_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            when (worker.state) {
                Thread.State.BLOCKED,
                Thread.State.WAITING,
                Thread.State.TIMED_WAITING -> return
                Thread.State.TERMINATED -> fail("$message; worker terminated early")
                else -> Thread.yield()
            }
        }
        fail(message)
    }

    private fun join(worker: Thread) {
        worker.join(TimeUnit.SECONDS.toMillis(TEST_TIMEOUT_SECONDS))
        if (worker.isAlive) {
            worker.interrupt()
            fail("${worker.name} did not terminate")
        }
    }

    private fun throwWorkerFailure(failure: AtomicReference<Throwable?>) {
        failure.get()?.let { throw AssertionError("NDR boundary worker failed", it) }
    }

    private companion object {
        const val TEST_TIMEOUT_SECONDS = 3L
        const val WORKER_TIMEOUT_SECONDS = 10L
    }
}
