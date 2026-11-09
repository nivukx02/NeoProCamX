package com.neoproc.neoproccamx.tessera

import java.util.concurrent.CompletableFuture

/**
 * Software execution classes inspired by the Tessera-X packet model.
 *
 * These are scheduling contracts over the phone's existing CPU/GPU/NPU. They do not claim that
 * Tessera-X silicon exists in the device. Camera-visible work always outranks optional work.
 */
internal enum class TesseraWorkClass(val schedulingRank: Int, val realtime: Boolean) {
    PREVIEW(schedulingRank = 0, realtime = true),
    RECORDING(schedulingRank = 1, realtime = true),
    CAPTURE(schedulingRank = 2, realtime = true),
    REALTIME_AI(schedulingRank = 3, realtime = true),
    /** User-visible export/develop work: faster than optional maintenance, below every camera deadline. */
    OFFLINE_CONVERSION(schedulingRank = 4, realtime = false),
    BACKGROUND(schedulingRank = 5, realtime = false)
}

/** The real execution resources that software can route work toward on an Android device. */
internal enum class TesseraExecutionBackend {
    CONTROL_CPU,
    VECTOR_GPU,
    MATRIX_NPU
}

/**
 * A precision promise attached to a task. The runtime never changes this promise to gain speed.
 */
internal enum class TesseraPrecisionContract {
    INTEGER_EXACT,
    FP32_STRICT,
    FP16_STORAGE_FP32_ACCUMULATION
}

internal data class TesseraTaskSpec(
    val name: String,
    val workClass: TesseraWorkClass,
    val preferredBackends: List<TesseraExecutionBackend> = listOf(TesseraExecutionBackend.CONTROL_CPU),
    val precision: TesseraPrecisionContract = TesseraPrecisionContract.FP32_STRICT,
    val deadlineNs: Long? = null,
    val estimatedWorkingSetBytes: Long = 0L
) {
    init {
        require(name.isNotBlank()) { "Tessera task name must not be blank" }
        require(preferredBackends.isNotEmpty()) { "Tessera task must declare at least one backend" }
        require(preferredBackends.distinct().size == preferredBackends.size) {
            "Tessera backend preference list must not contain duplicates"
        }
        require(deadlineNs == null || deadlineNs > 0L) { "Tessera deadline must be positive" }
        require(estimatedWorkingSetBytes >= 0L) { "Tessera working-set estimate must not be negative" }
    }
}

internal enum class TesseraRejectionReason {
    NONE,
    RUNTIME_CLOSED,
    BACKGROUND_CAPACITY_RESERVED,
    CAPACITY_EXHAUSTED,
    NO_COMPATIBLE_BACKEND,
    DEADLINE_EXPIRED
}

internal data class TesseraSubmission<T>(
    val accepted: Boolean,
    val backend: TesseraExecutionBackend?,
    val rejectionReason: TesseraRejectionReason,
    val future: CompletableFuture<T>,
    private val retirementFuture: CompletableFuture<Unit>,
    private val cancelAction: () -> Boolean
) {
    fun cancel(): Boolean = cancelAction()

    /**
     * Waits for the worker payload itself to retire, not merely for [future] to become cancelled.
     *
     * `CompletableFuture.cancel(false)` is intentionally non-interrupting, so its result future may be
     * terminal while native/codec work is still unwinding. Callers that own exclusive camera resources
     * use this barrier before returning those resources to live preview/recording.
     */
    fun awaitRetirement() {
        retirementFuture.join()
    }
}

internal data class TesseraRuntimeTelemetry(
    val acceptedTasks: Long,
    val completedTasks: Long,
    val failedTasks: Long,
    val cancelledTasks: Long,
    val rejectedTasks: Long,
    val preemptedBackgroundTasks: Long,
    val deadlineMisses: Long,
    val outstandingTasks: Int,
    val backgroundOutstandingTasks: Int,
    val peakOutstandingTasks: Int,
    val activeCameraSessions: Int
)

internal data class TesseraBackendAvailability(
    val controlCpu: Boolean = true,
    val vectorGpu: Boolean = false,
    val matrixNpu: Boolean = false
) {
    fun supports(backend: TesseraExecutionBackend): Boolean = when (backend) {
        TesseraExecutionBackend.CONTROL_CPU -> controlCpu
        TesseraExecutionBackend.VECTOR_GPU -> vectorGpu
        TesseraExecutionBackend.MATRIX_NPU -> matrixNpu
    }
}

internal data class TesseraRuntimeConfig(
    val workerCount: Int,
    val maxOutstandingTasks: Int,
    val realtimeReservedTasks: Int,
    val backendAvailability: TesseraBackendAvailability = TesseraBackendAvailability()
) {
    init {
        require(workerCount in 1..64) { "Tessera worker count must be in 1..64" }
        require(maxOutstandingTasks >= workerCount) {
            "Tessera outstanding capacity must cover every worker"
        }
        require(realtimeReservedTasks in 1 until maxOutstandingTasks) {
            "Tessera realtime reserve must be positive and smaller than total capacity"
        }
        require(backendAvailability.controlCpu) {
            "Tessera requires the Android control CPU as a guaranteed progress backend"
        }
    }

    companion object {
        fun forDevice(cpuCores: Int = Runtime.getRuntime().availableProcessors()): TesseraRuntimeConfig {
            val safeCores = cpuCores.coerceAtLeast(1)
            val workers = (safeCores / 2).coerceIn(2, 6)
            return TesseraRuntimeConfig(
                workerCount = workers,
                maxOutstandingTasks = 64,
                realtimeReservedTasks = 16
            )
        }
    }
}
