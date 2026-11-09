package com.neoproc.neoproccamx.rendering

/**
 * Reversible A/B gate (object default OFF, product default ON) — RAW debayer-monitor ORIENTATION parity.
 *
 * The RAW live-monitor 2D debayer present (PreviewGlCompositor.drawDebayer2d) draws the CPU-readback
 * RGBA8 buffer with only a fixed V-flip and NO camera-orientation transform, so it can appear rotated
 * relative to the ISP-OES preview (and relative to how the app orients every other surface). This flag
 * routes an EXPLICIT rotation/mirror matrix — derived from the app's own geometry seams
 * (FrameGeometry.previewRotationDegrees / previewMirrored, the same values the OES/record paths use) —
 * into a new uOrient2d uniform in the debayer vertex shader.
 *
 * No-downgrade: object default OFF => uOrient2d is identity and the debayer present is BYTE-IDENTICAL to
 * the old hardcoded V-flip-only path. The shipped product default is ON via ProductDefaultsApplier because
 * on-device A/B confirmed portrait RAW monitor frames otherwise appear rotated 90 degrees. Preview-only;
 * the dedicated RAW_SENSOR recorder + recorded RAW/DNG are untouched. Debug override:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.raw_debayer_orient true
 */
internal object RawDebayerPreviewOrientationParityFlag {
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
