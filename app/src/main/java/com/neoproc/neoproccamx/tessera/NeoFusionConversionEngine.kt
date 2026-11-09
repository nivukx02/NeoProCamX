package com.neoproc.neoproccamx.tessera

import java.util.concurrent.ExecutionException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded orchestration seam for user-visible offline conversions.
 *
 * The Tessera worker owns only coordination. Existing converter lanes still execute their proven Vulkan,
 * MediaCodec and CPU-parallel kernels, so scheduling cannot silently replace precision or pixel math.
 * Camera deadline classes outrank this work; optional maintenance remains below it.
 */
internal class NeoFusionConversionEngine(
    private val runtime: TesseraComputeRuntime,
    private val nanoTime: () -> Long = System::nanoTime
) {
    internal data class Telemetry(
        val name: String,
        val scheduled: Boolean,
        val backend: TesseraExecutionBackend,
        val precision: TesseraPrecisionContract,
        val elapsedNanos: Long,
        val rejectionReason: TesseraRejectionReason
    )

    private val latest = AtomicReference<Telemetry?>(null)

    fun latestTelemetry(): Telemetry? = latest.get()

    fun <T> execute(
        name: String,
        precision: TesseraPrecisionContract = TesseraPrecisionContract.FP32_STRICT,
        estimatedWorkingSetBytes: Long = 0L,
        operation: () -> T
    ): T {
        val start = nanoTime()
        // CONTROL_CPU means the orchestration task itself. The operation continues to use the converter's
        // independently parity-gated GPU/CPU/codec lanes; claiming VECTOR_GPU here would be misleading.
        val submission = runtime.submit(
            TesseraTaskSpec(
                name = name,
                workClass = TesseraWorkClass.OFFLINE_CONVERSION,
                preferredBackends = listOf(TesseraExecutionBackend.CONTROL_CPU),
                precision = precision,
                estimatedWorkingSetBytes = estimatedWorkingSetBytes
            )
        ) { operation() }

        if (!submission.accepted) {
            // Conversion isolation has already closed Camera2 and serialized user exports. If the shared
            // scheduler is momentarily full or shutting down, preserve feature availability by running on
            // the caller's existing background thread with exactly the same operation and precision.
            return try {
                operation()
            } finally {
                latest.set(
                    Telemetry(
                        name = name,
                        scheduled = false,
                        backend = TesseraExecutionBackend.CONTROL_CPU,
                        precision = precision,
                        elapsedNanos = (nanoTime() - start).coerceAtLeast(0L),
                        rejectionReason = submission.rejectionReason
                    )
                )
            }
        }

        return try {
            submission.future.get()
        } catch (error: InterruptedException) {
            submission.cancel()
            // Cancelling a CompletableFuture does not stop an already-running native/codec operation.
            // Keep conversion isolation (and therefore Camera2 closure) until the worker payload itself
            // has returned and released its resources; only then may the caller's finally reopen camera.
            submission.awaitRetirement()
            Thread.currentThread().interrupt()
            throw error
        } catch (error: CancellationException) {
            // A runtime/preemption cancellation can make the result future terminal just before the
            // ScheduledTask finally block publishes retirement. Preserve exclusive camera ownership
            // across that unwind exactly as for caller interruption.
            submission.awaitRetirement()
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } finally {
            latest.set(
                Telemetry(
                    name = name,
                    scheduled = true,
                    backend = submission.backend ?: TesseraExecutionBackend.CONTROL_CPU,
                    precision = precision,
                    elapsedNanos = (nanoTime() - start).coerceAtLeast(0L),
                    rejectionReason = submission.rejectionReason
                )
            )
        }
    }
}
