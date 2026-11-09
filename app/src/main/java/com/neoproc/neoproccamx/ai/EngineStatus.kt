package com.neoproc.neoproccamx.ai

/**
 * Represents the operational status of an AI engine component.
 * Used to track initialization, processing, and error states.
 */
sealed class EngineStatus {
    /** Engine is idle */
    class Idle : EngineStatus()

    /** Engine is initializing */
    data class Initializing(
        val operation: String = "",
        val progress: Int = 0,
        val message: String = ""
    ) : EngineStatus()

    /** Engine is ready for processing */
    class Ready : EngineStatus()

    /** Engine is actively processing */
    data class Processing(val operation: String, val startTime: Long = 0) : EngineStatus()

    /** Engine completed successfully */
    data class Success(
        val metadata: String? = null,
        val processingTimeMs: Long? = null,
        val throughputFps: Float? = null,
        val qualityScore: Float? = null
    ) : EngineStatus()

    /** Engine encountered an error */
    data class Failure(
        val message: String = "",
        val throwable: Throwable? = null,
        val canRetry: Boolean = false,
        val failedOperation: String? = null,
        val error: String = "",
        val attemptedDelegates: List<String> = emptyList()
    ) : EngineStatus()

    /** Engine is in degraded mode */
    data class Degraded(val reason: String, val fallbackMode: String = "", val performanceImpact: Float = 0.0f, val qualityImpact: Float = 0.0f) : EngineStatus()

    /** Engine initialization failed */
    data class InitializationFailed(
        val error: String = "",
        val message: String = "",
        val throwable: Throwable? = null,
        val attemptedDelegates: List<String> = emptyList()
    ) : EngineStatus()

    /** Engine is shutting down */
    class ShuttingDown : EngineStatus()

    /** Engine is closed */
    class Closed : EngineStatus()
}

