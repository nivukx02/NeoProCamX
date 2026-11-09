package com.neoproc.neoproccamx.rendering

/**
 * Reversible A/B gate (default OFF) — FPS-GATE ALWAYS-SAVE safety net.
 *
 * When the GPU-processed recording's sustained validation fails ONLY on a throughput/fps shortfall
 * (thermal load) but real frames were imported and the encoder surface is bound, ACCEPT the recording
 * and finalize it at the achieved fps instead of aborting + deleting the clip (today's strict behavior:
 * RecordingOutputStore cleanupFailure -> no file). Wave-1 ProcessedGpuRecordFpsPolicy already predictively
 * caps 4096x3072 -> 24fps; this is the net for when even that misses under heat.
 *
 * No-downgrade: it can never save a broken startup (0 frames / encoder-unbound fail
 * AlwaysSaveAtAchievedFpsPolicy). The stop gate now independently keeps real encoded samples and reports
 * any strict frame-count shortfall as evidence instead of deleting gallery output. Default OFF for strict
 * high-end recording startup admission: accepting below the selected FPS is a temporal-quality downgrade.
 * Flip ON only for explicit diagnostic recovery:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.always_save_achieved_fps true
 */
internal object AlwaysSaveAtAchievedFpsFlag {
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
