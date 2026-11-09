package com.neoproc.neoproccamx.tessera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TesseraComputeRuntimeTest {
    @Test
    fun `offline conversion overtakes optional background but remains non-realtime`() {
        val runtime = runtime(workerCount = 1, capacity = 8, reserve = 2)
        val workerBlocked = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        try {
            val blocker = runtime.submit(spec("blocker", TesseraWorkClass.CAPTURE)) {
                workerBlocked.countDown()
                releaseWorker.await(2, TimeUnit.SECONDS)
                order += "blocker"
            }
            assertTrue(workerBlocked.await(2, TimeUnit.SECONDS))
            val background = runtime.submit(spec("maintenance", TesseraWorkClass.BACKGROUND)) {
                order += "background"
            }
            val conversion = runtime.submit(spec("conversion", TesseraWorkClass.OFFLINE_CONVERSION)) {
                order += "conversion"
            }

            releaseWorker.countDown()
            blocker.future.get(2, TimeUnit.SECONDS)
            conversion.future.get(2, TimeUnit.SECONDS)
            background.future.get(2, TimeUnit.SECONDS)

            assertEquals(listOf("blocker", "conversion", "background"), order)
            assertFalse(TesseraWorkClass.OFFLINE_CONVERSION.realtime)
        } finally {
            releaseWorker.countDown()
            runtime.close()
        }
    }

    @Test
    fun `recording work overtakes queued background work without changing precision`() {
        val runtime = runtime(workerCount = 1, capacity = 8, reserve = 2)
        val workerBlocked = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())

        try {
            val blocker = runtime.submit(spec("blocker", TesseraWorkClass.CAPTURE)) { _ ->
                workerBlocked.countDown()
                assertTrue(releaseWorker.await(2, TimeUnit.SECONDS))
                order += "blocker"
            }
            assertTrue(blocker.accepted)
            assertTrue(workerBlocked.await(2, TimeUnit.SECONDS))

            val background = runtime.submit(spec("background", TesseraWorkClass.BACKGROUND)) { _ ->
                order += "background"
            }
            val recording = runtime.submit(
                spec("recording", TesseraWorkClass.RECORDING).copy(
                    precision = TesseraPrecisionContract.FP32_STRICT
                )
            ) { backend ->
                assertEquals(TesseraExecutionBackend.CONTROL_CPU, backend)
                order += "recording"
            }

            releaseWorker.countDown()
            blocker.future.get(2, TimeUnit.SECONDS)
            recording.future.get(2, TimeUnit.SECONDS)
            background.future.get(2, TimeUnit.SECONDS)

            assertEquals(listOf("blocker", "recording", "background"), order)
            assertEquals(3L, runtime.telemetry().completedTasks)
        } finally {
            releaseWorker.countDown()
            runtime.close()
        }
    }

    @Test
    fun `background admission cannot consume realtime reserve`() {
        val runtime = runtime(workerCount = 1, capacity = 4, reserve = 2)
        val workerBlocked = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)

        try {
            val first = runtime.submit(spec("background-1", TesseraWorkClass.BACKGROUND)) { _ ->
                workerBlocked.countDown()
                releaseWorker.await(2, TimeUnit.SECONDS)
            }
            assertTrue(first.accepted)
            assertTrue(workerBlocked.await(2, TimeUnit.SECONDS))
            assertTrue(runtime.submit(spec("background-2", TesseraWorkClass.BACKGROUND)) { _ -> Unit }.accepted)

            val rejected = runtime.submit(spec("background-3", TesseraWorkClass.BACKGROUND)) { _ -> Unit }
            assertFalse(rejected.accepted)
            assertEquals(TesseraRejectionReason.BACKGROUND_CAPACITY_RESERVED, rejected.rejectionReason)

            val preview = runtime.submit(spec("preview", TesseraWorkClass.PREVIEW)) { _ -> Unit }
            val recording = runtime.submit(spec("recording", TesseraWorkClass.RECORDING)) { _ -> Unit }
            assertTrue(preview.accepted)
            assertTrue(recording.accepted)
        } finally {
            releaseWorker.countDown()
            runtime.close()
        }
    }

    @Test
    fun `critical work preempts queued background at absolute capacity`() {
        val runtime = runtime(workerCount = 1, capacity = 4, reserve = 1)
        val workerBlocked = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)

        try {
            val running = runtime.submit(spec("running-preview", TesseraWorkClass.PREVIEW)) { _ ->
                workerBlocked.countDown()
                releaseWorker.await(2, TimeUnit.SECONDS)
            }
            assertTrue(running.accepted)
            assertTrue(workerBlocked.await(2, TimeUnit.SECONDS))
            val backgroundOne = runtime.submit(spec("background-1", TesseraWorkClass.BACKGROUND)) { _ -> Unit }
            val backgroundTwo = runtime.submit(spec("background-2", TesseraWorkClass.BACKGROUND)) { _ -> Unit }
            val queuedCapture = runtime.submit(spec("queued-capture", TesseraWorkClass.CAPTURE)) { _ -> Unit }
            assertTrue(backgroundOne.accepted)
            assertTrue(backgroundTwo.accepted)
            assertTrue(queuedCapture.accepted)

            val urgent = runtime.submit(spec("urgent-recording", TesseraWorkClass.RECORDING)) { _ -> Unit }
            assertTrue(urgent.accepted)
            assertEquals(1L, runtime.telemetry().preemptedBackgroundTasks)
            assertTrue(backgroundOne.future.isCompletedExceptionally || backgroundTwo.future.isCompletedExceptionally)
        } finally {
            releaseWorker.countDown()
            runtime.close()
        }
    }

    @Test
    fun `backend routing uses only a capability that was actually registered`() {
        val runtime = runtime(workerCount = 1, capacity = 4, reserve = 1)
        try {
            val beforeRegistration = runtime.submit(
                spec("gpu-only", TesseraWorkClass.BACKGROUND).copy(
                    preferredBackends = listOf(TesseraExecutionBackend.VECTOR_GPU)
                )
            ) { it }
            assertFalse(beforeRegistration.accepted)
            assertEquals(TesseraRejectionReason.NO_COMPATIBLE_BACKEND, beforeRegistration.rejectionReason)

            runtime.reportBackendAvailability(TesseraExecutionBackend.VECTOR_GPU, true)
            val afterRegistration = runtime.submit(
                spec("gpu-ready", TesseraWorkClass.BACKGROUND).copy(
                    preferredBackends = listOf(
                        TesseraExecutionBackend.VECTOR_GPU,
                        TesseraExecutionBackend.CONTROL_CPU
                    )
                )
            ) { it }
            assertTrue(afterRegistration.accepted)
            assertEquals(
                TesseraExecutionBackend.VECTOR_GPU,
                afterRegistration.future.get(2, TimeUnit.SECONDS)
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `provider shutdown cannot overlap a replacement runtime with retiring work`() {
        // The provider is process-wide; begin from a fully retired generation without changing its
        // device-derived configuration, then prove replacement creation is serialized with shutdown.
        TesseraRuntimeProvider.shutdown()
        val retiringRuntime = TesseraRuntimeProvider.runtime()
        val operationStarted = CountDownLatch(1)
        val releaseOperation = CountDownLatch(1)
        val shutdownDone = CountDownLatch(1)
        val replacementAttempted = CountDownLatch(1)
        val replacementReady = CountDownLatch(1)
        val replacement = AtomicReference<TesseraComputeRuntime?>()
        var shutdownThread: Thread? = null
        var replacementThread: Thread? = null
        val running = retiringRuntime.submit(spec("provider-retirement", TesseraWorkClass.BACKGROUND)) {
            operationStarted.countDown()
            releaseOperation.await()
        }
        try {
            assertTrue(running.accepted)
            assertTrue(operationStarted.await(2, TimeUnit.SECONDS))

            shutdownThread = thread(name = "tessera-provider-shutdown") {
                try {
                    TesseraRuntimeProvider.shutdown()
                } finally {
                    shutdownDone.countDown()
                }
            }
            // Observe the old runtime's closed admission gate, proving shutdown owns the provider lock.
            val admissionDeadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var closedObserved = false
            while (!closedObserved && System.nanoTime() < admissionDeadlineNs) {
                val probe = retiringRuntime.submit(spec("shutdown-probe", TesseraWorkClass.BACKGROUND)) { Unit }
                closedObserved = !probe.accepted && probe.rejectionReason == TesseraRejectionReason.RUNTIME_CLOSED
                if (!closedObserved) Thread.yield()
            }
            assertTrue("shutdown did not close old-runtime admission", closedObserved)

            replacementThread = thread(name = "tessera-provider-replacement") {
                replacementAttempted.countDown()
                replacement.set(TesseraRuntimeProvider.runtime())
                replacementReady.countDown()
            }
            assertTrue(replacementAttempted.await(2, TimeUnit.SECONDS))
            assertFalse(
                "replacement runtime became available before old work retired",
                replacementReady.await(150, TimeUnit.MILLISECONDS)
            )
            assertFalse(shutdownDone.await(0, TimeUnit.MILLISECONDS))

            releaseOperation.countDown()
            assertTrue(shutdownDone.await(2, TimeUnit.SECONDS))
            assertTrue(replacementReady.await(2, TimeUnit.SECONDS))
            assertTrue(replacement.get() !== retiringRuntime)
        } finally {
            releaseOperation.countDown()
            shutdownThread?.join(2_000L)
            replacementThread?.join(2_000L)
            TesseraRuntimeProvider.shutdown()
        }
    }

    private fun runtime(workerCount: Int, capacity: Int, reserve: Int): TesseraComputeRuntime =
        TesseraComputeRuntime(
            TesseraRuntimeConfig(
                workerCount = workerCount,
                maxOutstandingTasks = capacity,
                realtimeReservedTasks = reserve
            )
        )

    private fun spec(name: String, workClass: TesseraWorkClass): TesseraTaskSpec =
        TesseraTaskSpec(
            name = name,
            workClass = workClass,
            preferredBackends = listOf(TesseraExecutionBackend.CONTROL_CPU),
            precision = TesseraPrecisionContract.INTEGER_EXACT
        )
}
