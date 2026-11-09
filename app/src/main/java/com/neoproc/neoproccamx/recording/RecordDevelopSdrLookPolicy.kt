package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.rendering.FilmPrintLookRecordFlag

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  RECORD-THEN-DEVELOP — OFFLINE SDR CREATIVE-LOOK PARITY POLICY
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Decides the creative FILM-PRINT strength ([VideoDevelopColorPipeline] Stage C) for the OFFLINE SDR
 * develop pass.
 *
 * WHY THIS EXISTS — the SDR quality gap this closes:
 *   Under the record-then-develop engine the live lane records a FLAT LIGHT intermediate on purpose, so the
 *   creative film-print grade the live SDR path bakes ([FilmPrintLookRecordFlag], the offscreen-EGL
 *   creative-LUT stage) is intentionally STRIPPED from the recorded pixels. If the offline develop also
 *   leaves the film-print stage off (its object default is `filmPrintStrength = 0`), the developed SDR
 *   master is only a neutral SNCE-appearance + denoise re-render — cleaner, but it DROPS the creative grade
 *   the pre-engine live look carried, so it is not an upgrade. This policy restores that grade OFFLINE,
 *   where there is no fps budget, so the developed SDR master is the live look PLUS the heavier offline
 *   colour/denoise — a genuine improvement rather than a lateral swap.
 *
 * SINGLE SOURCE OF TRUTH: both the live record path and this offline path apply the SAME analytic
 * ±0.14-bounded show-LUT ([com.neoproc.neoproccamx.snce.SnceNrliAnalyticLut.buildFilmPrintLut]).
 * [FILM_PRINT_DEVELOP_STRENGTH] = full application, matching the live creative-LUT stage (which applies the
 * baked LUT at full weight), so offline SDR == live look by construction, then the offline stack adds on top.
 *
 * NO-DOWNGRADE / REVERSIBLE: gated on the SAME user film-look preference as the live path
 * ([FilmPrintLookRecordFlag], default ON, A/B via `debug.film_print_look`). When the user turns the film
 * look OFF the strength collapses to `0f` ⇒ today's neutral develop, byte-identical. NDR is unaffected:
 * [RecordDevelopMovConverter.buildDevelopPlan] force-zeroes the film strength for any HDR target regardless
 * of what this policy returns, so genuine-HDR delivery can never be double-graded.
 */
internal object RecordDevelopSdrLookPolicy {

    /**
     * Full application of the bounded analytic film-print show-LUT — matches the live record creative-LUT
     * stage, which applies the same baked (±0.14) LUT at full weight. The LUT itself is the bound, so a
     * strength of 1.0 is the intended, not an exaggerated, look.
     */
    const val FILM_PRINT_DEVELOP_STRENGTH: Float = 1f

    /**
     * Pure decision (unit-tested): apply the creative film-print grade only when the user's film-look
     * preference is ON. Kept separate from the flag read so the mapping can be verified deterministically.
     */
    fun filmPrintStrength(filmLookEnabled: Boolean): Float =
        if (filmLookEnabled) FILM_PRINT_DEVELOP_STRENGTH else 0f

    /**
     * Product wiring: reads the live film-look A/B flag so the offline SDR develop tracks the live look and
     * stays reversible through the existing `debug.film_print_look` intent. Call site: the SDR develop card
     * in [com.neoproc.neoproccamx.ui.CameraViewModel].
     */
    fun developFilmPrintStrength(): Float = filmPrintStrength(FilmPrintLookRecordFlag.enabled)
}
