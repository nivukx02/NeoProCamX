package com.neoproc.neoproccamx.rendering

/**
 * Reversible A/B gate (default OFF) — deliver the FULL ColorScience creative colour engine (profile
 * look, tone curves, gamut map, filmic tonemap, hue/highlights/shadows/brightness, creative .cube LUT,
 * saturation/contrast) to the RECORDED SDR pixels via the offscreen-EGL creative-LUT stage.
 *
 * Why: today the record path bakes only the raw creative .cube LUT + a sat/contrast grade matrix, while
 * the full getPreviewLutFloatArray() pipeline is computed every frame then DISCARDED (uploaded to the
 * disabled Vulkan preview path) — so the flagship colour engine reaches neither preview nor recorded
 * pixels. When enabled, CameraViewModel.syncGpuLutState pushes the complete display-referred LUT
 * (getPreviewLutFloatArray, R-fastest cube — the exact packing the creative-LUT stage already consumes)
 * into updateRecordCreativeLut and NEUTRALISES the sat/contrast grade matrix (the LUT already contains
 * it) so nothing is applied twice.
 *
 * No-downgrade: SDR-only (the native layer forces creative-LUT strength 0 on HLG/PQ; NDR/NIVUKX PQ colour
 * is owned by NeoNdrTruePqTransportPolicy, untouched). Default OFF = today's recorded look byte-identical.
 * Reversible on-device A/B (its onChanged re-pushes the record LUT immediately):
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.full_color_record true
 */
internal object FullColorEngineRecordFlag {
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
