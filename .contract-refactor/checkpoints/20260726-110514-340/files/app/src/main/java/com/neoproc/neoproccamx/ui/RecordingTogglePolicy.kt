package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.GpuRecordingState

/**
 * RecordingTogglePolicy — cinema-oriented recording state machine for the UI layer.
 *
 * Determines the correct action to take when the user presses the record button,
 * based on the current [GpuRecordingState]. Enforces these state machine rules:
 *
 *  IDLE / ERROR → START   (begin recording)
 *  RECORDING   → STOP     (graceful stop)
 *  READY       → START    (pre-armed encoder session is ready to start)
 *  STOPPING    → IGNORE   (stop already in progress — debounce)
 *
 * Also provides contextual button state information for UI rendering,
 * including animation hints, label strings, and haptic feedback intensity.
 */
internal object RecordingTogglePolicy {

    // ── Action Enum ───────────────────────────────────────────────────────────

    /**
     * The action the pipeline should take in response to a user toggle press.
     */
    enum class Action(
        /** Human-readable label for this action. */
        val label: String,
        /** Whether the record button should animate (pulse/spin) after this action. */
        val triggersAnimation: Boolean,
        /** Whether to play a haptic tap (true) or rumble (false). */
        val lightHaptic: Boolean,
        /** Whether to show a brief UI toast after this action. */
        val showFeedbackToast: Boolean
    ) {
        /** Begin a new recording session. */
        START(
            label = "Start Recording",
            triggersAnimation = true,
            lightHaptic = false,  // Strong rumble for record start
            showFeedbackToast = false
        ),

        /** Stop the active recording session gracefully. */
        STOP(
            label = "Stop Recording",
            triggersAnimation = false,
            lightHaptic = false,  // Strong rumble for record stop
            showFeedbackToast = false
        ),

        /** No action — the pipeline state does not permit toggling. */
        IGNORE(
            label = "Busy",
            triggersAnimation = false,
            lightHaptic = true,   // Light tap to indicate the press was received but ignored
            showFeedbackToast = false
        )
    }

    // ── UI State Hint ─────────────────────────────────────────────────────────

    /**
     * Full UI state descriptor for the record button.
     * Provides all the information the UI layer needs to render the button
     * without additional logic.
     */
    data class ButtonUiState(
        val action: Action,
        val isButtonEnabled: Boolean,
        val showRecordingIndicator: Boolean,
        val showStoppingSpinner: Boolean,
        val showReadyIndicator: Boolean,
        val accessibilityDescription: String
    )

    // ── Core Policy ───────────────────────────────────────────────────────────

    /**
     * Determines the action for a user toggle press at the given [GpuRecordingState].
     *
     * @param state Current GPU recording pipeline state.
     */
    fun nextAction(state: GpuRecordingState): Action = when (state) {
        GpuRecordingState.Idle -> Action.START
        is GpuRecordingState.Error -> Action.START     // Allow retry after error
        GpuRecordingState.Starting -> Action.STOP
        GpuRecordingState.Recording -> Action.STOP
        GpuRecordingState.Paused -> Action.STOP
        GpuRecordingState.Ready -> Action.START        // Pre-armed encoder is ready
        GpuRecordingState.Stopping -> Action.IGNORE    // Stop already in progress
        GpuRecordingState.Finalizing -> Action.IGNORE  // Capture is off; lossless file is sealing
    }

    /**
     * Resolves the full [ButtonUiState] for the current recording state.
     * Provides all the context the UI needs to correctly render the record button.
     *
     * @param state Current GPU recording pipeline state.
     * @param thermalEmergency Whether a thermal emergency is active (disables START).
     */
    fun resolveButtonState(
        state: GpuRecordingState,
        thermalEmergency: Boolean = false
    ): ButtonUiState {
        val action = if (thermalEmergency && state == GpuRecordingState.Idle) {
            Action.IGNORE
        } else {
            nextAction(state)
        }

        return ButtonUiState(
            action = action,
            isButtonEnabled = action != Action.IGNORE || state is GpuRecordingState.Error,
            showRecordingIndicator = state == GpuRecordingState.Starting || state == GpuRecordingState.Recording,
            showStoppingSpinner = state == GpuRecordingState.Stopping || state == GpuRecordingState.Finalizing,
            showReadyIndicator = state == GpuRecordingState.Ready,
            accessibilityDescription = buildAccessibilityDescription(state, thermalEmergency)
        )
    }

    /**
     * Returns true if the record button should be visually pulsing.
     * Used to drive the record indicator animation.
     */
    fun shouldPulseRecordIndicator(state: GpuRecordingState): Boolean =
        state == GpuRecordingState.Recording

    /**
     * Returns a contextual label for the record button.
     * Used in accessibility, HUD overlays, and tooltips.
     */
    fun buttonLabel(state: GpuRecordingState): String = when (state) {
        GpuRecordingState.Idle -> "REC"
        GpuRecordingState.Starting -> "CANCEL"
        GpuRecordingState.Recording -> "■ STOP"
        GpuRecordingState.Paused -> "PAUSED"
        GpuRecordingState.Ready -> "REC"
        GpuRecordingState.Stopping -> "STOPPING…"
        GpuRecordingState.Finalizing -> "SAVING…"
        is GpuRecordingState.Error -> "RETRY"
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private fun buildAccessibilityDescription(
        state: GpuRecordingState,
        thermalEmergency: Boolean
    ): String {
        if (thermalEmergency) return "Recording unavailable — device temperature too high"
        return when (state) {
            GpuRecordingState.Idle -> "Start recording"
            GpuRecordingState.Starting -> "Cancel recording startup"
            GpuRecordingState.Recording -> "Stop recording"
            GpuRecordingState.Paused -> "Stop paused recording"
            GpuRecordingState.Ready -> "Start recording"
            GpuRecordingState.Stopping -> "Stopping recording — please wait"
            GpuRecordingState.Finalizing -> "Recording stopped — saving lossless master"
            is GpuRecordingState.Error -> "Recording error — tap to retry. ${state.message}"
        }
    }
}
