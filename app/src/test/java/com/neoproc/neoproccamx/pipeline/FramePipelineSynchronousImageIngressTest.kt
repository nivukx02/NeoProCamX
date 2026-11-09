package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class FramePipelineSynchronousImageIngressTest {

    @Test
    fun `callback active before seal drains while later callbacks remain rejected`() {
        val quiescence = PipelineQuiescenceGate()
        val ingress = SynchronousImageIngressGate(quiescence::signalStateChanged)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val waitStarted = CountDownLatch(1)
        val waitFinished = CountDownLatch(1)
        val callbackExecutor = Executors.newSingleThreadExecutor()
        val waiterExecutor = Executors.newSingleThreadExecutor()

        try {
            val callback = callbackExecutor.submit<Boolean> {
                invokeTracked(ingress) {
                    callbackEntered.countDown()
                    check(releaseCallback.await(2, TimeUnit.SECONDS)) {
                        "Test callback release timed out"
                    }
                    true
                }
            }
            assertTrue("sink callback did not start", callbackEntered.await(1, TimeUnit.SECONDS))

            val idleWait = waiterExecutor.submit<Boolean> {
                waitStarted.countDown()
                try {
                    quiescence.awaitNotBusy(timeoutMs = 2_000L, isBusy = ingress::hasInFlight)
                } finally {
                    waitFinished.countDown()
                }
            }
            val seal = ingress.seal()
            assertTrue("idle waiter did not start", waitStarted.await(1, TimeUnit.SECONDS))
            assertFalse(
                "idle waiter returned while the sink callback still owned synchronous ingress",
                waitFinished.await(150, TimeUnit.MILLISECONDS)
            )
            assertNull("post-seal callback must not enter", ingress.tryEnter())

            releaseCallback.countDown()
            assertTrue(callback.get(1, TimeUnit.SECONDS))
            assertTrue(idleWait.get(1, TimeUnit.SECONDS))
            assertFalse(ingress.hasInFlight())
            assertNull("seal must remain active after drain", ingress.tryEnter())
            seal.close()
            requireNotNull(ingress.tryEnter()).close()
        } finally {
            releaseCallback.countDown()
            callbackExecutor.shutdownNow()
            waiterExecutor.shutdownNow()
        }
    }

    @Test
    fun `callback starting after seal never enters or invokes acquisition`() {
        val quiescence = PipelineQuiescenceGate()
        val ingress = SynchronousImageIngressGate(quiescence::signalStateChanged)
        val acquisitionCalls = AtomicInteger(0)
        val seal = ingress.seal()

        assertFalse(invokeTracked(ingress) {
            acquisitionCalls.incrementAndGet()
            true
        })
        assertEquals(0, acquisitionCalls.get())
        assertFalse(ingress.hasInFlight())

        seal.close()
        assertTrue(invokeTracked(ingress) {
            acquisitionCalls.incrementAndGet()
            true
        })
        assertEquals(1, acquisitionCalls.get())
    }

    @Test
    fun `queued deferred reader callback released after seal clears pending without acquire`() {
        val ingress = SynchronousImageIngressGate {}
        val reader = Any()
        val pendingReader = AtomicReference<Any?>(reader)
        val acquisitionCalls = AtomicInteger(0)
        val workerBlocked = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            executor.submit {
                workerBlocked.countDown()
                check(releaseWorker.await(2, TimeUnit.SECONDS))
            }
            assertTrue(workerBlocked.await(1, TimeUnit.SECONDS))
            val deferredDrain = executor.submit<Boolean> {
                val lease = ingress.tryEnter() ?: run {
                    pendingReader.compareAndSet(reader, null)
                    return@submit false
                }
                try {
                    acquisitionCalls.incrementAndGet()
                    true
                } finally {
                    lease.close()
                }
            }

            val seal = ingress.seal()
            try {
                releaseWorker.countDown()
                assertFalse(deferredDrain.get(1, TimeUnit.SECONDS))
                assertEquals(0, acquisitionCalls.get())
                assertNull(pendingReader.get())
                assertTrue(ingress.awaitIdle(0L))
            } finally {
                seal.close()
            }
        } finally {
            releaseWorker.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `nested seals remain closed until final idempotent owner release`() {
        val ingress = SynchronousImageIngressGate {}
        val first = ingress.seal()
        val second = ingress.seal()

        assertTrue(ingress.isSealed())
        assertNull(ingress.tryEnter())
        first.close()
        first.close()
        assertTrue(ingress.isSealed())
        assertNull(ingress.tryEnter())

        second.close()
        second.close()
        assertFalse(ingress.isSealed())
        val admitted = ingress.tryEnter()
        assertNotNull(admitted)
        requireNotNull(admitted).close()
        assertFalse(ingress.hasInFlight())
    }

    @Test
    fun `condition backed idle wait cannot overtake an admitted callback`() {
        val ingress = SynchronousImageIngressGate {}
        val lease = requireNotNull(ingress.tryEnter())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiter = executor.submit<Boolean> { ingress.awaitIdle(2_000L) }
            assertFalse("idle wait returned while callback lease remained owned", waiter.isDone)
            lease.close()
            assertTrue(waiter.get(1, TimeUnit.SECONDS))
            assertTrue(ingress.awaitIdle(0L))
        } finally {
            lease.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `sealed idle boundary cannot publish recording ownership across preview callback`() {
        val ingress = SynchronousImageIngressGate {}
        val previewLease = requireNotNull(ingress.tryEnter())
        val boundaryEntered = CountDownLatch(1)
        val recordingPublished = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val handoff = executor.submit<Boolean> {
                boundaryEntered.countDown()
                ingress.runAtSealedIdleBoundary(timeoutMs = 2_000L) {
                    assertTrue("handoff action must execute while ingress is sealed", ingress.isSealed())
                    assertNull("callback entered inside exact sink handoff", ingress.tryEnter())
                    recordingPublished.countDown()
                }
            }
            assertTrue(boundaryEntered.await(1, TimeUnit.SECONDS))
            assertFalse(
                "recording ownership published before the preview callback returned",
                recordingPublished.await(150, TimeUnit.MILLISECONDS)
            )
            assertNull("new callback entered while handoff waited for preview", ingress.tryEnter())

            previewLease.close()
            assertTrue(handoff.get(1, TimeUnit.SECONDS))
            assertTrue(recordingPublished.await(1, TimeUnit.SECONDS))
            requireNotNull(ingress.tryEnter()).close()
        } finally {
            previewLease.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `timed out sink boundary executes no publication and reopens admission`() {
        val ingress = SynchronousImageIngressGate {}
        val previewLease = requireNotNull(ingress.tryEnter())
        val publications = AtomicInteger(0)
        try {
            assertFalse(ingress.runAtSealedIdleBoundary(timeoutMs = 0L) {
                publications.incrementAndGet()
            })
            assertEquals(0, publications.get())
            assertFalse("timed-out boundary leaked its seal", ingress.isSealed())
        } finally {
            previewLease.close()
        }
        requireNotNull(ingress.tryEnter()).close()
    }

    @Test
    fun `decline and exception paths always balance ingress accounting`() {
        val quiescence = PipelineQuiescenceGate()
        val ingress = SynchronousImageIngressGate(quiescence::signalStateChanged)

        assertFalse(invokeTracked(ingress) { false })
        assertFalse(ingress.hasInFlight())

        assertThrows(IllegalStateException::class.java) {
            invokeTracked(ingress) { throw IllegalStateException("sink failure") }
        }
        assertFalse(ingress.hasInFlight())
        assertTrue(quiescence.awaitNotBusy(timeoutMs = 0L, isBusy = ingress::hasInFlight))
    }

    @Test
    fun `manager admission is first and lease closes from outer finally`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt"
        ).readText()
        val enqueue = source
            .substringAfter("fun enqueue(reader: ImageReader)")
            .substringBefore("private fun processAcquiredImage(")
        val busyPredicate = source
            .substringAfter("private fun hasInFlightImages(): Boolean =")
            .substringBefore("fun setDedicatedHardwareImportEnabled")
        val sealForClose = source
            .substringAfter("fun sealImageIngressForReaderClose(): FramePipelineImageIngressSeal")
            .substringBefore("fun awaitHardwareSubmitQuiescent")

        val firstAction = "val imageIngressLease = synchronousImageIngress.tryEnter() ?: return"
        val enterIndex = enqueue.indexOf(firstAction)
        val tryIndex = enqueue.indexOf("try {", enterIndex)
        val sinkSnapshotIndex = enqueue.indexOf("val rawYuvImageSinkSnapshot = rawYuvImageSink")
        val acquireNextIndex = enqueue.indexOf("reader.acquireNextImage()")
        val acquireLatestIndex = enqueue.indexOf("reader.acquireLatestImage()")
        val finallyIndex = enqueue.lastIndexOf("} finally {")
        val exitIndex = enqueue.indexOf("imageIngressLease.close()", finallyIndex)
        val firstExecutableLine = enqueue
            .substringAfter("{")
            .lineSequence()
            .map { line -> line.trim() }
            .first { line -> line.isNotEmpty() && !line.startsWith("//") }

        assertTrue(enterIndex >= 0)
        assertEquals(firstAction, firstExecutableLine)
        assertTrue(tryIndex > enterIndex)
        assertTrue(sinkSnapshotIndex > tryIndex)
        assertTrue(acquireNextIndex > sinkSnapshotIndex)
        assertTrue(acquireLatestIndex > sinkSnapshotIndex)
        assertTrue(finallyIndex > sinkSnapshotIndex)
        assertTrue(exitIndex > finallyIndex)
        assertTrue(
            busyPredicate.trimStart().startsWith("synchronousImageIngress.hasInFlight()")
        )
        assertTrue(busyPredicate.contains("pendingHardwareReader.get() != null"))
        assertTrue(source.contains("fun sealImageIngressForReaderClose()"))
        assertTrue(source.contains("fun setRawYuvImageSinkAtExactIngressBoundary("))
        assertTrue(source.contains("synchronousImageIngress.runAtSealedIdleBoundary(timeoutMs)"))
        assertTrue(source.contains("synchronousImageIngress.seal()"))
        assertTrue(sealForClose.contains("pendingHardwareReader.getAndSet(null)"))
        val hardwareDrain = source
            .substringAfter("private fun drainHardwareReader(reader: ImageReader, deferred: Boolean): Int")
            .substringBefore("private fun scheduleDeferredHardwareDrain")
        assertTrue(hardwareDrain.contains("synchronousImageIngress.tryEnter()"))
        assertTrue(hardwareDrain.contains("pendingHardwareReader.compareAndSet(reader, null)"))
        assertTrue(hardwareDrain.contains("imageIngressLease.close()"))
        val pendingCapacity = source
            .substringAfter("private fun pendingHardwareReaderHasAcquireCapacity(): Boolean")
            .substringBefore("private fun drainPendingHardwareReaderIfCapacity()")
        assertTrue(pendingCapacity.contains("synchronousImageIngress.isSealed()"))
        assertTrue(pendingCapacity.contains("pendingHardwareReader.compareAndSet(reader, null)"))
    }

    private fun invokeTracked(
        ingress: SynchronousImageIngressGate,
        callback: () -> Boolean
    ): Boolean {
        val lease = ingress.tryEnter() ?: return false
        return try {
            callback()
        } finally {
            lease.close()
        }
    }
}
