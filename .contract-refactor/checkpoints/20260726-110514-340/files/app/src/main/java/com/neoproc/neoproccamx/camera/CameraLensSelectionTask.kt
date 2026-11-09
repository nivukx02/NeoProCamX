package com.neoproc.neoproccamx.camera

/**
 * s5-f10 / CP-F11 — task-aware lens-selection hint + gate.
 *
 * [CameraLensSelectionPolicy.selectForTask] consults the hint below (and [CameraLensSelectionTaskFlag])
 * to optionally route the camera-open / mode-switch lens pick to one of the otherwise-dormant
 * specialized selectors ([CameraLensSelectionPolicy.selectLowLightCandidate],
 * [CameraLensSelectionPolicy.selectVideoCandidate], [CameraLensSelectionPolicy.selectCandidateForLensType]).
 *
 * NO-DOWNGRADE DEFAULT: the hint defaults to [None] and the gate defaults to OFF, so the opened lens
 * is exactly today's [CameraLensSelectionPolicy.selectPreferredCandidate] result — bit-for-bit — until
 * the owner enables task-aware selection device-side. Proven by the default-unchanged golden in
 * CameraLensSelectionPolicyTest.
 */
internal sealed class LensSelectionTaskHint {
    /** No task hint — use the live preferred-lens selection unchanged. */
    object None : LensSelectionTaskHint()

    /** Low-light scene detected (scene detector / low lux): prefer the brighter / OIS / larger sensor. */
    object LowLight : LensSelectionTaskHint()

    /**
     * Video / record-mode entry: prefer OIS / high-FPS / 10-bit / RAW-capable lens.
     *
     * @param requireHighFps when true, restrict to >=60fps candidates (mirrors
     *   [CameraLensSelectionPolicy.selectVideoCandidate]'s parameter).
     */
    data class Video(val requireHighFps: Boolean = false) : LensSelectionTaskHint()

    /**
     * Lens-switch UI / cinema multi-cam request for a specific focal class. Exact [LensType] match,
     * then closest-focal fallback (handled by [CameraLensSelectionPolicy.selectCandidateForLensType]).
     *
     * @param requireCinemaCapable forwarded to the selector to restrict to cinema-capable lenses.
     */
    data class ByLensType(
        val lensType: LensType,
        val requireCinemaCapable: Boolean = false
    ) : LensSelectionTaskHint()
}

/**
 * Process-global, runtime-flippable gate for task-aware lens selection (s5-f10 / CP-F11). Default
 * **OFF**, so [CameraLensSelectionPolicy.selectForTask] ignores every hint and returns exactly
 * [CameraLensSelectionPolicy.selectPreferredCandidate] — today's opened lens, bit-for-bit — unless the
 * owner explicitly enables it device-side.
 *
 * Mirrors the [CinematicFocusPullFlag] idiom (same subsystem). Flip on a debug build via ADB:
 *
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.lens_task_selection true
 *
 * [set] fires [registerOnChanged] so [CameraController] can re-run lens selection immediately when the
 * flag flips (e.g. to re-open the default lens when it flips back OFF).
 */
internal object CameraLensSelectionTaskFlag {

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    private var onChanged: (() -> Unit)? = null

    /** Sets the flag and, if it changed, notifies the registered listener. */
    fun set(value: Boolean) {
        val changed = enabled != value
        enabled = value
        if (changed) onChanged?.invoke()
    }

    /** Registered by [CameraController] so a flag flip can re-run lens selection. */
    fun registerOnChanged(callback: (() -> Unit)?) {
        onChanged = callback
    }
}
