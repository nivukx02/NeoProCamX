package com.neoproc.neoproccamx.rendering

/**
 * Reversible A/B gate (default OFF) — TRUE preview colour parity (WYSIWYG). Feeds the SAME
 * display-referred creative cube the record path grades with (getPreviewLutFloatArray, R-fastest,
 * the exact packing the offscreen-EGL creative-LUT stage consumes) into a new creative-3D-LUT
 * sampler in PreviewGlCompositor's SDR_ENHANCE grade, so the visible 8-bit SDR preview SHOWS the
 * graded profile/curves/gamut/creative-LUT instead of only denoise/sharpen.
 *
 * No-downgrade: 8-bit SDR only (shader-gated to uGrade == GRADE_SDR_ENHANCE); LOG/RAW/HDR paths
 * untouched (compositor not engaged for HDR; NEOLOG/RAW_MONITOR shader branches unchanged). Default
 * OFF => today's preview byte-identical (uLutEnabled stays 0). Reversible on-device:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.preview_color_parity true
 *
 * PAIR WITH [FullColorEngineRecordFlag] for true preview<->FILE parity: this preview stage shows the
 * FULL ColorScience cube (profile/curves/gamut/tonemap/creative-LUT/sat-contrast), which reaches the
 * recorded SDR file only when debug.full_color_record is ALSO on (same getPreviewLutFloatArray cube on
 * both surfaces). Enabled alone it previews the intended graded look, but the default record path bakes
 * only the raw .cube + sat/contrast, so the file will differ until full_color_record is on too.
 */
internal object PreviewColorParityFlag {
    @Volatile
    var enabled: Boolean = true
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
