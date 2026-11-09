package com.neoproc.neoproccamx.ui

/**
 * Reversible A/B gate for the non-high-res throughput ceiling used by [ProcessedGpuRecordFpsPolicy].
 * The verified high-resolution offscreen P010 route (>=7 MP) is always capped to cinema 25 fps; this flag
 * only chooses the measured or conservative pixels/second ceiling for smaller processed GPU routes.
 */
internal object RecordFpsCeilingFlag {
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
