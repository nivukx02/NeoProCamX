package com.neoproc.neoproccamx.camera

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CameraStateMachine — Formal finite state machine for camera lifecycle.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * PHASE 3: FSM FORMALIZATION
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Enforces a strict transition graph to prevent illegal state changes that
 * cause "preview error refresh timeouts" and session configuration races.
 *
 * State Graph:
 * ```
 *   Closed ──────→ Opening
 *     ↑               │
 *     │          ┌────┤
 *     │          ↓    ↓
 *   Closing ← Open  Error
 *     ↑          │    ↑
 *     │          ↓    │
 *     ├──── Streaming─┤
 *     │          │    │
 *     │          ↓    │
 *     └──── Recording─┘
 * ```
 *
 * Thread safety: All transitions are synchronized. Reads of [state] are
 * lock-free via StateFlow.
 */
class CameraStateMachine(
    private val tag: String = "CameraFSM"
) {

    /**
     * Defines all legal state transitions.
     * Key = source state class name, Value = set of legal target state class names.
     */
    private val transitionTable: Map<String, Set<String>> = mapOf(
        key("Closed") to setOf(
            key("Opening")
        ),
        key("Opening") to setOf(
            key("Configuring"),
            key("Open"),
            key("Error"),
            key("Closing")   // User cancels during open
        ),
        key("Configuring") to setOf(
            key("Opening"), // Device loss while configuring: retry owns a new CameraDevice
            key("Open"),
            key("Error"),
            key("Closing")
        ),
        key("Open") to setOf(
            key("Opening"), // CameraDevice loss/reopen; old device/session ownership was released
            key("Streaming"),
            key("Recording"), // Replacement session restored while capture remains active
            key("PhotoCapture"),
            key("Reconfiguring"),
            key("Error"),
            key("Closing")
        ),
        key("Streaming") to setOf(
            key("Opening"), // CameraDevice loss/reopen; preview resumes on the replacement device
            key("Recording"),
            key("PhotoCapture"),
            key("Reconfiguring"),
            key("Open"),     // Session rebuild (refresh)
            key("Error"),
            key("Closing")
        ),
        key("Recording") to setOf(
            key("Opening"), // CameraDevice loss/reopen; recording recovery remains fail-closed
            key("Streaming"),
            key("Reconfiguring"),
            key("Open"),     // Session rebuild during recording stop
            key("Error"),
            key("Closing")
        ),
        key("PhotoCapture") to setOf(
            key("Opening"), // CameraDevice loss/reopen while an in-flight capture is retired
            key("Open"),
            key("Streaming"),
            key("Reconfiguring"),
            key("Error"),
            key("Closing")
        ),
        key("Reconfiguring") to setOf(
            key("Opening"), // CameraDevice loss/reopen supersedes the old session generation
            key("Open"),
            key("Streaming"),
            key("Recording"),
            key("PhotoCapture"),
            key("Error"),
            key("Closing")
        ),
        key("Closing") to setOf(
            key("Closed"),
            key("Error")     // Close failed
        ),
        key("Error") to setOf(
            key("Opening"),  // Retry
            key("Closing"),  // Clean shutdown after error
            key("Closed")    // Direct reset
        )
    )

    private val _state = MutableStateFlow<CameraState>(CameraState.Closed)
    val state: StateFlow<CameraState> = _state.asStateFlow()

    val current: CameraState get() = _state.value

    private val lock = Any()
    private var stateEnteredAtElapsedMs: Long = nowElapsedMs()
    private var timeoutListener: ((state: CameraState, elapsedMs: Long) -> Unit)? = null
    private val timeoutByStateMs: Map<String, Long> = mapOf(
        key("Opening") to 3_000L,
        key("Configuring") to 3_000L,
        key("Reconfiguring") to 3_000L,
        key("PhotoCapture") to 2_000L,
        key("Closing") to 3_000L
    )

    // ── Transition Listeners ─────────────────────────────────────────────
    private var onTransition: ((from: CameraState, to: CameraState) -> Unit)? = null
    private var onIllegalTransition: ((from: CameraState, to: CameraState) -> Unit)? = null

    /**
     * Register a callback for every successful state transition.
     */
    fun setTransitionListener(listener: (from: CameraState, to: CameraState) -> Unit) {
        onTransition = listener
    }

    /**
     * Register a callback for illegal transition attempts (debugging/telemetry).
     */
    fun setIllegalTransitionListener(listener: (from: CameraState, to: CameraState) -> Unit) {
        onIllegalTransition = listener
    }

    /**
     * Register callback for state timeout telemetry events.
     */
    fun setTimeoutListener(listener: (state: CameraState, elapsedMs: Long) -> Unit) {
        timeoutListener = listener
    }

    // ── State Transition API ─────────────────────────────────────────────

    /**
     * Attempt a state transition. Returns true if the transition was legal
     * and executed, false if rejected.
     *
     * @param target The target state.
     * @param reason Optional reason string for logging.
     */
    fun transition(target: CameraState, reason: String? = null): Boolean {
        synchronized(lock) {
            val from = _state.value
            val fromKey = key(from)
            val toKey = key(target)

            val legalTargets = transitionTable[fromKey]
            if (legalTargets == null || toKey !in legalTargets) {
                val msg = "ILLEGAL transition: $fromKey → $toKey" +
                    (reason?.let { " ($it)" } ?: "")
                Log.e(tag, msg)
                onIllegalTransition?.invoke(from, target)
                return false
            }

            _state.value = target
            stateEnteredAtElapsedMs = nowElapsedMs()
            val msg = "$fromKey → $toKey" + (reason?.let { " ($it)" } ?: "")
            Log.d(tag, msg)
            onTransition?.invoke(from, target)
            return true
        }
    }

    /**
     * Force a state without validation. USE ONLY for error recovery
     * when the FSM is in an inconsistent state.
     */
    fun forceState(target: CameraState, reason: String) {
        synchronized(lock) {
            val from = _state.value
            Log.w(tag, "FORCED: ${key(from)} → ${key(target)} ($reason)")
            _state.value = target
            stateEnteredAtElapsedMs = nowElapsedMs()
            onTransition?.invoke(from, target)
        }
    }

    /**
     * Check whether the current state exceeded its allowed timeout.
     * If timeout is exceeded, transitions to Error and attempts auto recovery.
     *
     * @return true when a timeout condition was detected.
     */
    fun checkTimeoutAndRecover(nowElapsedMs: Long = nowElapsedMs()): Boolean {
        synchronized(lock) {
            val currentState = _state.value
            val stateKey = key(currentState)
            val timeoutMs = timeoutByStateMs[stateKey] ?: return false
            val elapsedMs = (nowElapsedMs - stateEnteredAtElapsedMs).coerceAtLeast(0L)
            if (elapsedMs < timeoutMs) return false

            Log.e(
                tag,
                "State timeout detected: $stateKey elapsed=${elapsedMs}ms threshold=${timeoutMs}ms"
            )
            timeoutListener?.invoke(currentState, elapsedMs)

            val timeoutError = CameraState.Error("State timeout: $stateKey (${elapsedMs}ms)")
            if (!transition(timeoutError, "state-timeout")) {
                forceState(timeoutError, "state-timeout-force")
            }
            attemptAutomaticRecovery("state-timeout-recovery")
            return true
        }
    }

    /**
     * Automatic recovery path from Error state.
     * Error -> Closing -> Closed -> Opening (best effort).
     */
    fun attemptAutomaticRecovery(reason: String = "auto-recovery"): Boolean {
        synchronized(lock) {
            if (_state.value !is CameraState.Error) return false
            val resetContract = resolveSessionResetContract(reason)
            Log.w(tag, "Applying ${resetContract.signature}")

            if (!transition(CameraState.Closing, "$reason:to-closing")) {
                if (!transition(CameraState.Closed, "$reason:to-closed")) {
                    if (!transition(CameraState.Opening, "$reason:restart-open")) {
                        forceState(CameraState.Closed, "$reason:fallback-closed")
                        return false
                    }
                    return true
                }
            }

            if (_state.value is CameraState.Closing) {
                if (!transition(CameraState.Closed, "$reason:closing-complete")) {
                    forceState(CameraState.Closed, "$reason:force-closed")
                }
            }
            if (_state.value is CameraState.Closed) {
                transition(CameraState.Opening, "$reason:restart-open")
            }
            return _state.value !is CameraState.Error
        }
    }

    fun resolveSessionResetContract(reason: String): CameraSessionResetContract =
        CameraSessionResetContract.resolve(
            reason = reason,
            nowElapsedMs = nowElapsedMs()
        )

    // ── Query API ────────────────────────────────────────────────────────

    val isClosed: Boolean get() = _state.value is CameraState.Closed
    val isOpening: Boolean get() = _state.value is CameraState.Opening
    val isConfiguring: Boolean get() = _state.value is CameraState.Configuring
    val isOpen: Boolean get() = _state.value is CameraState.Open
    val isStreaming: Boolean get() = _state.value is CameraState.Streaming
    val isRecording: Boolean get() = _state.value is CameraState.Recording
    val isPhotoCapture: Boolean get() = _state.value is CameraState.PhotoCapture
    val isReconfiguring: Boolean get() = _state.value is CameraState.Reconfiguring
    val isClosing: Boolean get() = _state.value is CameraState.Closing
    val isError: Boolean get() = _state.value is CameraState.Error

    /** True if the camera is in a usable state (Open, Streaming, or Recording). */
    val isActive: Boolean get() = when (_state.value) {
        is CameraState.Configuring,
        is CameraState.Open,
        is CameraState.Streaming,
        is CameraState.Recording,
        is CameraState.PhotoCapture,
        is CameraState.Reconfiguring -> true
        else -> false
    }

    /** True if a transition to [target] would be legal from the current state. */
    fun canTransitionTo(target: CameraState): Boolean {
        val fromKey = key(_state.value)
        val toKey = key(target)
        return transitionTable[fromKey]?.contains(toKey) == true
    }

    // ── Convenience Transition Methods ───────────────────────────────────

    fun toOpening(reason: String? = null) = transition(CameraState.Opening, reason)
    fun toConfiguring(reason: String? = null) = transition(CameraState.Configuring, reason)
    fun toOpen(reason: String? = null) = transition(CameraState.Open, reason)
    fun toStreaming(reason: String? = null) = transition(CameraState.Streaming, reason)
    fun toRecording(reason: String? = null) = transition(CameraState.Recording, reason)
    fun toPhotoCapture(reason: String? = null) = transition(CameraState.PhotoCapture, reason)
    fun toReconfiguring(reason: String? = null) = transition(CameraState.Reconfiguring, reason)
    fun toClosing(reason: String? = null) = transition(CameraState.Closing, reason)
    fun toClosed(reason: String? = null) = transition(CameraState.Closed, reason)
    fun toError(msg: String, reason: String? = null) =
        transition(CameraState.Error(msg), reason ?: msg)

    // ── Internal ─────────────────────────────────────────────────────────

    private fun key(state: CameraState): String = when (state) {
        is CameraState.Closed -> "Closed"
        is CameraState.Opening -> "Opening"
        is CameraState.Configuring -> "Configuring"
        is CameraState.Open -> "Open"
        is CameraState.Streaming -> "Streaming"
        is CameraState.Recording -> "Recording"
        is CameraState.PhotoCapture -> "PhotoCapture"
        is CameraState.Reconfiguring -> "Reconfiguring"
        is CameraState.Closing -> "Closing"
        is CameraState.Error -> "Error"
    }

    private fun key(name: String): String = name

    private fun nowElapsedMs(): Long = System.nanoTime() / 1_000_000L
}
