package com.neoproc.neoproccamx.pipeline

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  RAW → LOG ELITE-DETAIL FLAG  (reversible A/B, default ON)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Adds a GRADE-SAFE elite-detail pass to the offline RAW→NeoLog develop — edge-aware denoise +
 * detail sharpen + OmniClarity local-contrast — applied to the LINEAR ACEScg plane BEFORE the NeoLog
 * encode (see [RawDevelopEliteDetailPolicy]). It is deliberately DETAIL-ONLY: it cleans and sharpens
 * the image but does NOT bake any creative colour/film-print look, so the developed LOG stays flat and
 * fully gradeable (the whole point of a LOG deliverable).
 *
 * Because the pass lives in [RawNeoLogDevelop.finishFromAcescg] — the seam shared by BOTH the CPU
 * develop and the GPU-demosaic + CPU-finish tiers — both tiers receive identical detail, so the offline
 * converter's first-frame CPU/GPU parity gate (which tolerates ONLY the demosaic difference) is preserved.
 *
 * No-downgrade / reversible: default ON delivers the cleaner/sharper LOG the user asked for. Set OFF ⇒
 * byte-identical to the prior develop (the pass is a strict no-op — the plane is quantised unchanged).
 *
 * Runtime A/B (debug builds), instant revert (offline conversion re-reads it per run):
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.raw_log_elite_detail true
 */
internal object RawDevelopEliteDetailFlag {

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
