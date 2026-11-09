package com.neoproc.neoproccamx.tessera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class NeoFusionConversionEngineTest {

    @Test
    fun `accepted conversion keeps declared precision and returns result`() {
        val runtime = runtime()
        try {
            var now = 100L
            val engine = NeoFusionConversionEngine(runtime) { now.also { now += 25L } }

            assertEquals(
                "main10",
                engine.execute(
                    name = "raw-main10",
                    precision = TesseraPrecisionContract.FP32_STRICT
                ) { "main10" }
            )

            val telemetry = requireNotNull(engine.latestTelemetry())
            assertTrue(telemetry.scheduled)
            assertEquals(TesseraWorkClass.OFFLINE_CONVERSION.schedulingRank, 4)
            assertEquals(TesseraPrecisionContract.FP32_STRICT, telemetry.precision)
            assertEquals(TesseraExecutionBackend.CONTROL_CPU, telemetry.backend)
            assertEquals(TesseraRejectionReason.NONE, telemetry.rejectionReason)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `closed scheduler falls back once to caller without changing operation`() {
        val runtime = runtime()
        runtime.close()
        val engine = NeoFusionConversionEngine(runtime)
        var invocations = 0

        val result = engine.execute("fallback") {
            invocations += 1
            10
        }

        assertEquals(10, result)
        assertEquals(1, invocations)
        val telemetry = requireNotNull(engine.latestTelemetry())
        assertFalse(telemetry.scheduled)
        assertEquals(TesseraRejectionReason.RUNTIME_CLOSED, telemetry.rejectionReason)
    }

    @Test
    fun `interrupted caller waits for running conversion retirement before returning`() {
        val runtime = runtime()
        val operationStarted = CountDownLatch(1)
        val releaseOperation = CountDownLatch(1)
        val operationRetired = AtomicBoolean(false)
        val callerDone = CountDownLatch(1)
        val callerFailure = AtomicReference<Throwable?>()
        val interruptRestored = AtomicBoolean(false)
        val engine = NeoFusionConversionEngine(runtime)
        val caller = thread(name = "neo-fusion-interruption-test") {
            try {
                engine.execute("interruptible-isolation") {
                    operationStarted.countDown()
                    try {
                        releaseOperation.await()
                    } finally {
                        operationRetired.set(true)
                    }
                    "completed"
                }
            } catch (failure: Throwable) {
                callerFailure.set(failure)
                interruptRestored.set(Thread.currentThread().isInterrupted)
            } finally {
                callerDone.countDown()
            }
        }

        try {
            assertTrue(operationStarted.await(2, TimeUnit.SECONDS))
            caller.interrupt()

            assertFalse("caller returned while conversion payload was still live", callerDone.await(150, TimeUnit.MILLISECONDS))
            assertFalse(operationRetired.get())

            releaseOperation.countDown()
            assertTrue(callerDone.await(2, TimeUnit.SECONDS))
            assertTrue(operationRetired.get())
            assertTrue(callerFailure.get() is InterruptedException)
            assertTrue("interrupted status must be restored after the retirement barrier", interruptRestored.get())
            assertEquals(0L, runtime.telemetry().completedTasks)
            assertEquals(1L, runtime.telemetry().cancelledTasks)
            assertEquals(0, runtime.telemetry().outstandingTasks)
        } finally {
            releaseOperation.countDown()
            caller.join(2_000L)
            runtime.close()
        }
    }

    @Test
    fun `cancellation exception returns only after scheduled task retirement`() {
        val runtime = runtime()
        val operationRetired = AtomicBoolean(false)
        val engine = NeoFusionConversionEngine(runtime)

        try {
            val failure = runCatching {
                engine.execute("cancelled-conversion") {
                    try {
                        throw CancellationException("conversion cancelled")
                    } finally {
                        operationRetired.set(true)
                    }
                }
            }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertTrue(operationRetired.get())
            assertEquals(0, runtime.telemetry().outstandingTasks)
            assertEquals(0L, runtime.telemetry().completedTasks)
            assertEquals(1L, runtime.telemetry().cancelledTasks)
        } finally {
            runtime.close()
        }
    }

    private fun runtime(): TesseraComputeRuntime = TesseraComputeRuntime(
        TesseraRuntimeConfig(
            workerCount = 1,
            maxOutstandingTasks = 4,
            realtimeReservedTasks = 1
        )
    )
}
