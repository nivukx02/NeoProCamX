package com.neoproc.neoproccamx.rendering

/**
 * Reversible A/B gate (default OFF) — preserve an already-running isolated GL monitor compositor
 * (NEOLOG / RAW_MONITOR) WHILE RECORDING, instead of dropping to the raw direct camera surface at
 * record-start.
 *
 * Why: at record-start the preview surface + Camera2 session are rebuilt and
 * recordingStartOrActiveBlocksVisiblePreviewRebind() hard-blocks the compositor from re-engaging for the
 * rest of the clip -> SDR loses its denoise/sharpen enhance and LOG loses its NEOLOG monitor grade
 * (flat/gray preview while recording). The compositor runs in its OWN isolated 8-bit EGL context and
 * NEVER binds the encoder surface (see PreviewGlCompositor), so keeping it bound during recording does
 * not touch the offscreen-EGL record path. HDR/NDR are already excluded by
 * GlVisiblePreviewPolicy.resolveGrade (returns IDENTITY when hdrActive), so this can never 8-bit-
 * downgrade a 10-bit HDR preview.
 *
 * Default OFF for the zero-drop recording profile, with a strict safety rule in CameraViewModel: when
 * enabled it may keep an already-running compositor,
 * but it must not start/rebind the compositor mid-record. That preserves the RAW/LOG monitor look without
 * reopening the EGL already-connected / preview-freeze path.
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.preview_grade_recording true
 */
internal object PreviewGradeWhileRecordingFlag {
    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    private var onChanged: (() -> Unit)? = null

    fun set(value: Boolean) {
        val changed = enabled != value
        enabled = value
        if (changed) onChanged?.invoke()
    }

    fun registerOnChanged(callback: (() -> Unit)?) {
        onChanged = callback
    }
}
