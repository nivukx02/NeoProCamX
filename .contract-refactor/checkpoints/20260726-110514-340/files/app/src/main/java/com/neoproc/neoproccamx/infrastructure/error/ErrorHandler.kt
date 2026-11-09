package com.neoproc.neoproccamx.infrastructure.error

import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * ERROR HANDLER — COMPREHENSIVE ERROR MANAGEMENT AND RECOVERY INFRASTRUCTURE
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Enterprise-grade error handling system providing:
 *   - Structured exception classification and categorization
 *   - Automatic recovery strategy selection
 *   - Error rate monitoring and alerting
 *   - Circuit breaker integration for cascading failure prevention
 *   - Comprehensive error reporting with full stack context
 *   - Graceful degradation pathways
 *
 * Thread Safety: All operations are thread-safe via concurrent data structures.
 * Recovery: Automatic retry with exponential backoff and fallback mechanisms.
 *
 * @author NeoProCamX Infrastructure Team
 * @version 10.0.0-ENTERPRISE
 * @since 1.0.0
 */
object ErrorHandler {

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // ERROR SEVERITY CLASSIFICATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Comprehensive error severity levels with recovery strategies.
     */
    enum class ErrorSeverity(val priority: Int, val requiresImmediateAction: Boolean, val userVisible: Boolean) {
        /** Transient error, automatic retry will likely succeed. */
        TRANSIENT(0, false, false),

        /** Recoverable error, requires fallback strategy. */
        RECOVERABLE(1, false, true),

        /** Warning condition, monitoring required but operation continues. */
        WARNING(2, false, false),

        /** Critical error, core functionality compromised. */
        CRITICAL(3, true, true),

        /** Fatal error, system restart required. */
        FATAL(4, true, true),

        /** Catastrophic error, data loss possible. */
        CATASTROPHIC(5, true, true);

        companion object {
            /**
             * Maps exception types to default severity levels.
             */
            fun fromException(throwable: Throwable): ErrorSeverity {
                return when (throwable) {
                    is CancellationException -> TRANSIENT
                    is IllegalArgumentException -> RECOVERABLE
                    is IllegalStateException -> CRITICAL
                    is OutOfMemoryError -> CATASTROPHIC
                    is SecurityException -> CRITICAL
                    is UnsupportedOperationException -> RECOVERABLE
                    is NullPointerException -> CRITICAL
                    else -> RECOVERABLE
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // ERROR CATEGORY CLASSIFICATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Error categories for targeted handling and reporting.
     */
    enum class ErrorCategory {
        CAMERA_HARDWARE,
        CAMERA_SESSION,
        AI_INFERENCE,
        AI_MODEL_LOADING,
        RENDERING_PIPELINE,
        MEMORY_ALLOCATION,
        STORAGE_IO,
        NETWORK_COMMUNICATION,
        THERMAL_MANAGEMENT,
        UI_RENDERING,
        CONFIGURATION,
        PERMISSION,
        VALIDATION,
        UNKNOWN
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // ERROR CONTEXT DATA CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Immutable error context containing comprehensive diagnostic information.
     */
    data class ErrorContext(
        val severity: ErrorSeverity = ErrorSeverity.RECOVERABLE,
        val category: ErrorCategory = ErrorCategory.UNKNOWN,
        val operation: String = "unknown",
        val component: String = "unknown",
        val recoverable: Boolean = true,
        val retryable: Boolean = false,
        val maxRetries: Int = 3,
        val metadata: Map<String, Any> = emptyMap(),
        val correlationId: String = generateCorrelationId()
    ) {
        companion object {
            private val correlationCounter = AtomicLong(0)
            fun generateCorrelationId(): String =
                "ERR-${System.currentTimeMillis()}-${correlationCounter.incrementAndGet()}"
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // ERROR REPORT DATA CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Complete error report for logging and analysis.
     */
    data class ErrorReport(
        val timestamp: Long = System.currentTimeMillis(),
        val throwable: Throwable,
        val context: ErrorContext,
        val stackTrace: String,
        val threadName: String = Thread.currentThread().name,
        val processId: Int = android.os.Process.myPid(),
        val deviceState: DeviceStateSnapshot = captureDeviceState()
    ) {
        data class DeviceStateSnapshot(
            val availableMemory: Long,
            val totalMemory: Long,
            val cpuUsage: Float,
            val batteryLevel: Int,
            val thermalState: String
        )

        companion object {
            fun captureDeviceState(): DeviceStateSnapshot {
                val runtime = Runtime.getRuntime()
                return DeviceStateSnapshot(
                    availableMemory = runtime.freeMemory(),
                    totalMemory = runtime.totalMemory(),
                    cpuUsage = -1f, // Would require native code
                    batteryLevel = -1, // Would require battery service
                    thermalState = "unknown"
                )
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INTERNAL STATE MANAGEMENT
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /** Error processing scope with I/O isolation. */
    private val errorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Error statistics by category. */
    private val errorStatistics = ConcurrentHashMap<ErrorCategory, CategoryStats>()

    /** Circuit breaker states by component. */
    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreakerState>()

    /** Error rate tracking for alerting. */
    private val errorRateWindow = AtomicInteger(0)
    private val lastRateReset = AtomicLong(System.currentTimeMillis())

    /** Global error count. */
    private val totalErrorCount = AtomicLong(0)

    /** Mutex for synchronized error reporting. */
    private val reportMutex = Mutex()

    /** Operational status. */
    private val isOperational = AtomicBoolean(true)

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // CATEGORY STATISTICS CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Per-category error statistics.
     */
    private data class CategoryStats(
        val count: AtomicLong = AtomicLong(0),
        val lastOccurrence: AtomicLong = AtomicLong(0),
        val severityCounts: ConcurrentHashMap<ErrorSeverity, AtomicLong> = ConcurrentHashMap()
    ) {
        fun record(severity: ErrorSeverity) {
            count.incrementAndGet()
            lastOccurrence.set(System.currentTimeMillis())
            severityCounts.getOrPut(severity) { AtomicLong(0) }.incrementAndGet()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // CIRCUIT BREAKER STATE CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Circuit breaker state for preventing cascading failures.
     */
    private data class CircuitBreakerState(
        val component: String,
        val failureThreshold: Int = 5,
        val recoveryTimeoutMs: Long = 30_000,
        val failureCount: AtomicInteger = AtomicInteger(0),
        val lastFailureTime: AtomicLong = AtomicLong(0),
        val isOpen: AtomicBoolean = AtomicBoolean(false)
    ) {
        fun recordFailure(): Boolean {
            val count = failureCount.incrementAndGet()
            lastFailureTime.set(System.currentTimeMillis())

            if (count >= failureThreshold) {
                isOpen.set(true)
                NeoLogger.critical(
                    "CircuitBreaker",
                    "Circuit breaker OPENED for component: $component after $count failures"
                )
                return true
            }
            return false
        }

        fun recordSuccess() {
            if (failureCount.get() > 0) {
                failureCount.set(0)
                if (isOpen.compareAndSet(true, false)) {
                    NeoLogger.i("CircuitBreaker", "Circuit breaker CLOSED for component: $component")
                }
            }
        }

        fun canExecute(): Boolean {
            if (!isOpen.get()) return true

            // Check if recovery timeout has elapsed
            val timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime.get()
            if (timeSinceLastFailure >= recoveryTimeoutMs) {
                // Half-open: allow one request through
                isOpen.set(false)
                failureCount.set(failureThreshold / 2)
                return true
            }
            return false
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Initialize the error handling infrastructure.
     */
    fun initialize() {
        if (!isOperational.compareAndSet(false, true)) {
            NeoLogger.w("ErrorHandler", "Already initialized")
            return
        }

        NeoLogger.i("ErrorHandler", "Error handling infrastructure initialized")
        startErrorRateMonitor()
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // CORE ERROR HANDLING API
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Handle an exception with comprehensive context and automatic recovery.
     *
     * @param throwable The exception to handle
     * @param context Error context providing classification and metadata
     * @param recoveryAction Optional recovery action to attempt
     * @return ErrorReport containing full diagnostic information
     */
    fun handle(
        throwable: Throwable,
        context: ErrorContext = ErrorContext(),
        recoveryAction: (() -> Unit)? = null
    ): ErrorReport {
        if (!isOperational.get()) {
            // Fallback to basic logging if handler not initialized
            NeoLogger.e("ErrorHandler", "Handler not operational: ${throwable.message}", throwable)
            return ErrorReport(throwable = throwable, context = context, stackTrace = throwable.stackTraceToString())
        }

        // Update statistics
        updateStatistics(context.category, context.severity)

        // Generate error report
        val report = ErrorReport(
            throwable = throwable,
            context = context,
            stackTrace = throwable.stackTraceToString()
        )

        // Log with appropriate level
        logError(report)

        // Update circuit breaker if component specified
        if (context.component != "unknown") {
            val breaker = circuitBreakers.getOrPut(context.component) {
                CircuitBreakerState(component = context.component)
            }
            breaker.recordFailure()
        }

        // Execute recovery action if provided and error is recoverable
        if (recoveryAction != null && context.recoverable && context.severity.priority <= ErrorSeverity.RECOVERABLE.priority) {
            attemptRecovery(report, recoveryAction)
        }

        return report
    }

    /**
     * Execute a block with comprehensive error handling and automatic retry.
     *
     * @param context Error context for classification
     * @param block The operation to execute
     * @return Result containing success value or error report
     */
    inline fun <T> executeWithHandling(
        context: ErrorContext = ErrorContext(),
        block: () -> T
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            // Don't handle cancellation as error
            throw e
        } catch (e: Exception) {
            val report = handle(e, context)
            Result.failure(e)
        }
    }

    /**
     * Execute a block with retry logic for transient failures.
     *
     * @param context Error context with retry configuration
     * @param block The operation to execute
     * @return Result containing success value or final error report
     */
    suspend fun <T> executeWithRetry(
        context: ErrorContext = ErrorContext(retryable = true, maxRetries = 3),
        block: suspend () -> T
    ): Result<T> {
        var lastException: Exception? = null

        repeat(context.maxRetries + 1) { attempt ->
            try {
                val result = block()

                // Record success if circuit breaker exists
                if (context.component != "unknown") {
                    circuitBreakers[context.component]?.recordSuccess()
                }

                return Result.success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e

                val attemptContext = context.copy(
                    severity = if (attempt < context.maxRetries) ErrorSeverity.TRANSIENT else context.severity
                )

                handle(e, attemptContext)

                if (attempt < context.maxRetries) {
                    val delayMs = calculateRetryDelay(attempt)
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }

        return Result.failure(lastException ?: RuntimeException("Unknown error after retries"))
    }

    /**
     * Check if a component's circuit breaker allows execution.
     */
    fun canExecute(component: String): Boolean {
        return circuitBreakers[component]?.canExecute() ?: true
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // RECOVERY MECHANISMS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Attempt recovery from an error condition.
     */
    private fun attemptRecovery(report: ErrorReport, recoveryAction: () -> Unit) {
        errorScope.launch {
            try {
                NeoLogger.i(
                    "ErrorRecovery",
                    "Attempting recovery for ${report.context.operation} [${report.context.correlationId}]"
                )

                withContext(Dispatchers.Default) {
                    recoveryAction()
                }

                NeoLogger.i(
                    "ErrorRecovery",
                    "Recovery successful for ${report.context.operation} [${report.context.correlationId}]"
                )

                // Record success on recovery
                if (report.context.component != "unknown") {
                    circuitBreakers[report.context.component]?.recordSuccess()
                }

            } catch (e: Exception) {
                NeoLogger.e(
                    "ErrorRecovery",
                    "Recovery failed for ${report.context.operation} [${report.context.correlationId}]",
                    e
                )
            }
        }
    }

    /**
     * Calculate exponential backoff delay for retry attempts.
     */
    private fun calculateRetryDelay(attempt: Int): Long {
        val baseDelay = 100L
        val maxDelay = 5_000L
        val exponentialDelay = baseDelay * (1 shl attempt)
        return minOf(exponentialDelay, maxDelay)
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // STATISTICS AND MONITORING
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Update error statistics for a category.
     */
    private fun updateStatistics(category: ErrorCategory, severity: ErrorSeverity) {
        totalErrorCount.incrementAndGet()
        errorRateWindow.incrementAndGet()

        errorStatistics.getOrPut(category) { CategoryStats() }
            .record(severity)
    }

    /**
     * Start error rate monitoring coroutine.
     */
    private fun startErrorRateMonitor() {
        errorScope.launch {
            while (isOperational.get()) {
                kotlinx.coroutines.delay(60_000) // 1 minute window

                val currentTime = System.currentTimeMillis()
                val windowErrors = errorRateWindow.getAndSet(0)
                val timeSinceReset = currentTime - lastRateReset.getAndSet(currentTime)

                val errorRatePerMinute = if (timeSinceReset > 0) {
                    windowErrors * 60_000 / timeSinceReset
                } else 0

                // Alert if error rate exceeds threshold
                if (errorRatePerMinute > 100) {
                    NeoLogger.critical(
                        "ErrorRateMonitor",
                        "HIGH ERROR RATE DETECTED: $errorRatePerMinute errors/minute"
                    )
                }

                NeoLogger.d(
                    "ErrorRateMonitor",
                    "Current error rate: $errorRatePerMinute/minute, Total: ${totalErrorCount.get()}"
                )
            }
        }
    }

    /**
     * Get current error statistics.
     */
    fun getStatistics(): ErrorStatistics {
        return ErrorStatistics(
            totalErrors = totalErrorCount.get(),
            categoryBreakdown = errorStatistics.mapValues { it.value.count.get() },
            activeCircuitBreakers = circuitBreakers.count { it.value.isOpen.get() }
        )
    }

    /**
     * Error statistics data class.
     */
    data class ErrorStatistics(
        val totalErrors: Long,
        val categoryBreakdown: Map<ErrorCategory, Long>,
        val activeCircuitBreakers: Int
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // LOGGING
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Log an error report with appropriate severity.
     */
    private fun logError(report: ErrorReport) {
        val message = buildString {
            append("[${report.context.correlationId}] ")
            append("${report.context.operation} in ${report.context.component}: ")
            append("${report.throwable.message ?: "Unknown error"}")
        }

        val metadata = mapOf(
            "category" to report.context.category.name,
            "severity" to report.context.severity.name,
            "retryable" to report.context.retryable,
            "thread" to report.threadName,
            "memory" to "${report.deviceState.availableMemory}/${report.deviceState.totalMemory}"
        )

        when (report.context.severity) {
            ErrorSeverity.TRANSIENT -> NeoLogger.d("ErrorHandler", message, report.throwable, metadata)
            ErrorSeverity.RECOVERABLE -> NeoLogger.w("ErrorHandler", message, report.throwable, metadata)
            ErrorSeverity.WARNING -> NeoLogger.w("ErrorHandler", message, null, metadata)
            ErrorSeverity.CRITICAL -> NeoLogger.e("ErrorHandler", message, report.throwable, metadata)
            ErrorSeverity.FATAL -> NeoLogger.critical("ErrorHandler", message, report.throwable, metadata)
            ErrorSeverity.CATASTROPHIC -> NeoLogger.emergency("ErrorHandler", message, report.throwable, metadata)
        }
    }
}
