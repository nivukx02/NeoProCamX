package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.snce.SnceNrliAnalyticLut

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  FILM-PRINT RECORD-LOOK FLAG  (reversible A/B, default ON)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Delivers the analytic FILM-PRINT show-LUT (SNCE Stage-11: filmic contrast + teal/orange split-tone +
 * skin-hue lock, bounded ±0.14) to RECORDED SDR pixels through the offscreen-EGL creative-LUT stage —
 * the actual vivo record pipeline.
 *
 * Why this exists: on vivo the processed-video record path is OFFSCREEN_AHB_EGL_ENCODER, not the Vulkan
 * hardware-import path, so the Vulkan NRLI pass that normally carries this film-print grade
 * (updateNrliLut) never executes — the look uploads but never reaches recorded pixels. The offscreen-EGL
 * bridge has its OWN creative-3D-LUT stage (fed by [VulkanRuntimeManager.updateRecordCreativeLut]); this
 * gate lets the film-print LUT ride that same, already-shipped stage. The LUT is R-fastest 33³ — the
 * exact packing the creative-LUT stage expects — so it drops straight in.
 *
 * No-downgrade / reversible: default ON (the elite record look); set OFF ⇒ today's neutral recorded look
 * exactly. Applied ONLY when the user has NOT selected their own creative LUT, and ONLY for SDR record
 * delivery (the record LUT stage forces strength 0 on HLG/PQ, protecting the proven HDR transport). Its
 * [onChanged] re-pushes the record LUT so the on-device A/B takes effect on the next recording. The OFFLINE
 * SDR develop consults this SAME flag (see RecordDevelopSdrLookPolicy) so live and developed looks match.
 *
 * Runtime A/B (debug builds), instant revert:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.film_print_look true
 */
internal object FilmPrintLookRecordFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    private var onChanged: (() -> Unit)? = null

    /** Sets the gate and, if it changed, notifies the listener to re-push the record creative LUT. */
    fun set(value: Boolean) {
        val changed = enabled != value
        enabled = value
        if (changed) onChanged?.invoke()
    }

    /** Registered by the ViewModel to re-push the record LUT when the gate flips (on-device A/B). */
    fun registerOnChanged(callback: (() -> Unit)?) {
        onChanged = callback
    }

    /**
     * The film-print show-LUT as (rgb, size) for the record creative-LUT stage. Scene-independent, so it
     * is built ONCE and reused (33³ R-fastest cube, channels in [0,1]).
     */
    val recordLut: Pair<FloatArray, Int> by lazy {
        SnceNrliAnalyticLut.buildFilmPrintLut().let { it.rgb to it.size }
    }
}
