package com.neoproc.neoproccamx.recording

/**
 * RecordDevelopEnhanceParallelismPolicy — reversible master gate for the CPU+multi-core OVERLAP of the
 * offline SDR/NDR develop enhance stage (the ~12 heavy full-frame kernel passes in
 * [Npv10ExportEnhancementPolicy.enhanceP010]).
 *
 * DEFAULT ON: on the flagship full-sensor develop the enhance stage previously ran single-threaded on one
 * CPU core (the develop floors to one frame-worker under the memory budget), which is the bulk of the
 * multi-hour offline convert. With this ON the single develop worker splits each pass across all CPU cores
 * via [EnhanceRowScheduler] — BYTE-IDENTICAL output (no look change, a pure scheduling win). It only
 * engages when the develop is actually in the single-worker / row-parallel regime; multi-worker (smaller
 * resolution) clips already parallelise across frames and are untouched.
 *
 * Reversible via `debug.record_enhance_parallel` (see [com.neoproc.neoproccamx.ui.DebugCommandReceiver]).
 * Flip OFF to fall back to the proven sequential enhance if a device ever shows pressure during a convert.
 */
internal object RecordDevelopEnhanceParallelismPolicy {
    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
