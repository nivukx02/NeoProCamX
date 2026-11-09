package com.neoproc.neoproccamx.ai

/**
 * ProcessingState — Neural pipeline processing state machine.
 *
 * Tracks the lifecycle state of each AI/ML processing stage in the
 * NeoProCamX neural imaging pipeline. Used for:
 *  - Pipeline stage coordination (preventing concurrent inference)
 *  - UI state binding (showing processing indicators)
 *  - Telemetry and diagnostics (tracking state transitions)
 *  - Error recovery (determining safe retry points)
 */
enum class ProcessingState(
    /** Whether the engine is available for new inference requests. */
    val isAvailable: Boolean,
    /** Whether the engine is actively consuming GPU/NPU resources. */
    val isConsuming: Boolean,
    /** Whether the engine can accept new work (queueable). */
    val canAcceptWork: Boolean,
    /** Human-readable label for UI display. */
    val displayLabel: String,
    /** Severity level for diagnostics (0=normal, 1=warning, 2=error). */
    val severity: Int
) {
    Idle(false, false, false, "Idle", 0),
    Initializing(false, true, false, "Loading AI…", 0),
    Ready(true, false, true, "AI Ready", 0),
    Processing(false, true, false, "Processing…", 0),
    Success(true, false, true, "Complete", 0),
    Failure(false, false, true, "Error", 2),
    Degraded(true, false, true, "Degraded", 1),
    InitializationFailed(false, false, false, "Init Failed", 2),
    Recovery(false, true, false, "Recovering…", 1),
    WarmingUp(false, true, false, "Warming Up…", 0),
    ShuttingDown(false, false, false, "Shutting Down", 0);

    val isError: Boolean get() = severity >= 2
    val isWarning: Boolean get() = severity >= 1
    val isInitialized: Boolean get() = this != Idle && this != Initializing && this != InitializationFailed
    val canDispatchInference: Boolean get() = this == Ready || this == Success || this == Degraded
    val requiresReinit: Boolean get() = this == InitializationFailed

    companion object {
        val healthyStates: Set<ProcessingState> = setOf(Ready, Success, Processing, WarmingUp)
        val problemStates: Set<ProcessingState> = setOf(Failure, Degraded, InitializationFailed)
    }
}
