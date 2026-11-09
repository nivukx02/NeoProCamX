package com.neoproc.neoproccamx.ui

/**
 * Serializes the short boundary between a shutter-start request and an active recording backend.
 *
 * A stop can arrive before the startup coroutine has registered its cancellation action, while that
 * coroutine is suspended, or after the backend has started but before the coroutine returns. Keeping
 * those cases under one lock prevents a stop from being acknowledged without either cancelling startup
 * or reserving the normal backend stop path.
 */
internal class RecordingStartupHandoff {

    data class StopRequest(
        val handledDuringStartup: Boolean,
        val newlyRequested: Boolean
    )

    data class Completion(
        val stopWasRequested: Boolean,
        val backendStopReserved: Boolean
    )

    private val lock = Any()

    @Volatile
    private var starting = false

    @Volatile
    private var stopRequested = false

    private var cancelStartup: (() -> Unit)? = null

    val isStarting: Boolean
        get() = starting

    val isCancellationRequested: Boolean
        get() = stopRequested

    /**
     * Claims startup and executes [admit] under the same lock used by stop/completion handoff.
     * This lets a higher-level generation coordinator make its admission decision without a gap
     * where stop can cross between that decision and [starting] becoming true.
     */
    fun tryBeginStart(
        retainRestartDuringPendingStop: () -> Unit = {},
        admit: () -> Boolean = { true }
    ): Boolean = synchronized(lock) {
        if (starting) {
            // A stop owner is published before stopRequested becomes true under this same lock.
            // Retaining here closes the rapid start -> stop -> start race without allowing an
            // ordinary duplicate start to create a future recording unexpectedly.
            if (stopRequested) retainRestartDuringPendingStop()
            return@synchronized false
        }
        if (!admit()) return@synchronized false
        starting = true
        stopRequested = false
        cancelStartup = null
        true
    }

    /**
     * Installs the startup cancellation action. If stop won the race before registration, the action is
     * invoked immediately after releasing the lock.
     */
    fun registerCancellationAction(action: () -> Unit) {
        val cancelNow = synchronized(lock) {
            if (!starting) return
            cancelStartup = action
            stopRequested
        }
        if (cancelNow) action()
    }

    /** Returns [StopRequest.handledDuringStartup] false once the active-backend handoff has completed. */
    fun requestStopDuringStart(reserveStopOwner: () -> Unit = {}): StopRequest {
        var action: (() -> Unit)? = null
        val result = synchronized(lock) {
            if (!starting) {
                return@synchronized StopRequest(
                    handledDuringStartup = false,
                    newlyRequested = false
                )
            }
            val firstRequest = !stopRequested
            // Reservation is part of the STARTING -> stop-request transition. It must be visible
            // before cancellation runs and before completeStart can observe stopRequested=true.
            if (firstRequest) reserveStopOwner()
            stopRequested = true
            if (firstRequest) action = cancelStartup
            StopRequest(
                handledDuringStartup = true,
                newlyRequested = firstRequest
            )
        }
        action?.invoke()
        return result
    }

    /**
     * Atomically closes startup. If stop already won and a backend is live, [reserveBackendStop] runs
     * while startup is still marked active, so no new start can enter the handoff gap.
     */
    fun completeStart(
        backendActive: Boolean,
        reserveBackendStop: () -> Boolean
    ): Completion = synchronized(lock) {
        if (!starting) {
            return@synchronized Completion(
                stopWasRequested = false,
                backendStopReserved = false
            )
        }

        val requested = stopRequested
        val reserved = requested && backendActive && reserveBackendStop()
        cancelStartup = null
        stopRequested = false
        starting = false
        Completion(
            stopWasRequested = requested,
            backendStopReserved = reserved
        )
    }
}
