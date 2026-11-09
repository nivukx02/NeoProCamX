package com.neoproc.neoproccamx.ui

enum class CameraHapticAction {
    NAVIGATION_TICK,
    BUTTON_CLICK,
    MODE_SWITCH,
    CAMERA_SWITCH,
    CAPTURE_TRIGGER,
    RECORD_START,
    RECORD_PAUSE,
    RECORD_RESUME,
    RECORD_STOP,
    MANUAL_CONTROL_TICK,
    SLIDER_BOUNDARY_STOP,
    SNAP_TO_DEFAULT,
    AI_TARGET_LOCK,
    CRITICAL_PIPELINE_ALERT,
    REJECTED_ACTION
}

internal enum class CameraHapticPrimitive {
    CLOCK_TICK,
    CONFIRM,
    REJECT,
    HEAVY_CLICK,
    DOUBLE_TICK,
    PULSE_ALERT
}

internal data class CameraHapticWaveform(
    val timingsMs: List<Long>,
    val amplitudes: List<Int>? = null,
    val repeat: Int = -1
)

internal data class CameraHapticEvent(
    val action: CameraHapticAction,
    val emittedAtMs: Long
)

internal data class CameraHapticFeedbackDecision(
    val shouldEmit: Boolean,
    val primitive: CameraHapticPrimitive,
    val fallbackDurationMs: Long,
    val minIntervalMs: Long,
    val waveform: CameraHapticWaveform? = null,
    val nextEvent: CameraHapticEvent
)

fun interface CameraHapticPerformer {
    fun performCameraHaptic(action: CameraHapticAction)
}

internal object CameraHapticFeedbackPolicy {
    fun resolve(
        action: CameraHapticAction,
        nowMs: Long,
        lastEvent: CameraHapticEvent?
    ): CameraHapticFeedbackDecision {
        val primitive = primitiveFor(action)
        val minIntervalMs = minIntervalFor(action)
        val elapsedMs = nowMs - (lastEvent?.emittedAtMs ?: Long.MIN_VALUE / 2)
        val repeatedSameAction = lastEvent?.action == action
        val importantTransition = action == CameraHapticAction.RECORD_START ||
            action == CameraHapticAction.RECORD_PAUSE ||
            action == CameraHapticAction.RECORD_RESUME ||
            action == CameraHapticAction.RECORD_STOP ||
            action == CameraHapticAction.CAMERA_SWITCH ||
            action == CameraHapticAction.SLIDER_BOUNDARY_STOP ||
            action == CameraHapticAction.SNAP_TO_DEFAULT ||
            action == CameraHapticAction.AI_TARGET_LOCK ||
            action == CameraHapticAction.CRITICAL_PIPELINE_ALERT ||
            action == CameraHapticAction.CAPTURE_TRIGGER ||
            action == CameraHapticAction.REJECTED_ACTION
        val shouldEmit = lastEvent == null ||
            elapsedMs >= minIntervalMs ||
            (importantTransition && !repeatedSameAction && elapsedMs >= IMPORTANT_TRANSITION_GAP_MS)

        return CameraHapticFeedbackDecision(
            shouldEmit = shouldEmit,
            primitive = primitive,
            fallbackDurationMs = fallbackDurationFor(action),
            minIntervalMs = minIntervalMs,
            waveform = waveformFor(action),
            nextEvent = CameraHapticEvent(action, nowMs)
        )
    }

    private fun primitiveFor(action: CameraHapticAction): CameraHapticPrimitive =
        when (action) {
            CameraHapticAction.NAVIGATION_TICK,
            CameraHapticAction.MODE_SWITCH,
            CameraHapticAction.RECORD_RESUME,
            CameraHapticAction.MANUAL_CONTROL_TICK -> CameraHapticPrimitive.CLOCK_TICK
            CameraHapticAction.CAMERA_SWITCH -> CameraHapticPrimitive.DOUBLE_TICK
            CameraHapticAction.BUTTON_CLICK,
            CameraHapticAction.SNAP_TO_DEFAULT,
            CameraHapticAction.RECORD_START,
            CameraHapticAction.RECORD_PAUSE -> CameraHapticPrimitive.CONFIRM
            CameraHapticAction.CAPTURE_TRIGGER,
            CameraHapticAction.SLIDER_BOUNDARY_STOP,
            CameraHapticAction.RECORD_STOP -> CameraHapticPrimitive.HEAVY_CLICK
            CameraHapticAction.AI_TARGET_LOCK -> CameraHapticPrimitive.DOUBLE_TICK
            CameraHapticAction.CRITICAL_PIPELINE_ALERT -> CameraHapticPrimitive.PULSE_ALERT
            CameraHapticAction.REJECTED_ACTION -> CameraHapticPrimitive.REJECT
        }

    private fun fallbackDurationFor(action: CameraHapticAction): Long =
        when (action) {
            CameraHapticAction.NAVIGATION_TICK,
            CameraHapticAction.MODE_SWITCH,
            CameraHapticAction.RECORD_RESUME,
            CameraHapticAction.MANUAL_CONTROL_TICK -> 8L
            CameraHapticAction.CAMERA_SWITCH -> 22L
            CameraHapticAction.BUTTON_CLICK,
            CameraHapticAction.SNAP_TO_DEFAULT,
            CameraHapticAction.RECORD_START,
            CameraHapticAction.RECORD_PAUSE -> 16L
            CameraHapticAction.CAPTURE_TRIGGER,
            CameraHapticAction.SLIDER_BOUNDARY_STOP,
            CameraHapticAction.RECORD_STOP -> 28L
            CameraHapticAction.AI_TARGET_LOCK -> 18L
            CameraHapticAction.CRITICAL_PIPELINE_ALERT -> 96L
            CameraHapticAction.REJECTED_ACTION -> 34L
        }

    private fun minIntervalFor(action: CameraHapticAction): Long =
        when (action) {
            CameraHapticAction.NAVIGATION_TICK,
            CameraHapticAction.MODE_SWITCH,
            CameraHapticAction.RECORD_RESUME,
            CameraHapticAction.MANUAL_CONTROL_TICK -> 55L
            CameraHapticAction.CAMERA_SWITCH -> 80L
            CameraHapticAction.SNAP_TO_DEFAULT -> 90L
            CameraHapticAction.SLIDER_BOUNDARY_STOP -> 120L
            CameraHapticAction.AI_TARGET_LOCK -> 240L
            CameraHapticAction.CRITICAL_PIPELINE_ALERT -> 2_500L
            CameraHapticAction.BUTTON_CLICK -> 65L
            CameraHapticAction.CAPTURE_TRIGGER,
            CameraHapticAction.RECORD_START,
            CameraHapticAction.RECORD_PAUSE,
            CameraHapticAction.RECORD_STOP,
            CameraHapticAction.REJECTED_ACTION -> 140L
        }

    private fun waveformFor(action: CameraHapticAction): CameraHapticWaveform? =
        when (action) {
            CameraHapticAction.CAMERA_SWITCH,
            CameraHapticAction.AI_TARGET_LOCK -> CameraHapticWaveform(
                timingsMs = listOf(0L, 12L, 38L, 12L),
                amplitudes = listOf(0, 190, 0, 160)
            )
            CameraHapticAction.RECORD_STOP -> CameraHapticWaveform(
                timingsMs = listOf(0L, 14L, 20L, 14L),
                amplitudes = listOf(0, 255, 0, 220)
            )
            CameraHapticAction.CRITICAL_PIPELINE_ALERT -> CameraHapticWaveform(
                timingsMs = listOf(0L, 26L, 48L, 26L, 64L, 36L),
                amplitudes = listOf(0, 220, 0, 190, 0, 255)
            )
            else -> null
        }

    private const val IMPORTANT_TRANSITION_GAP_MS = 28L
}
