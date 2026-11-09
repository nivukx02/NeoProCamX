package com.neoproc.neoproccamx.ui

/**
 * Reversible A/B gate for the non-high-res throughput ceiling used by [ProcessedGpuRecordFpsPolicy].
 * Strict high-end recording preserves the requested cadence. This fallback-only flag chooses the measured
 * or conservative throughput estimate when strict no-silent-downgrade mode is disabled.
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
