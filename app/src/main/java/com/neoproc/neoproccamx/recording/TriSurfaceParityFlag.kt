package com.neoproc.neoproccamx.recording

/**
 * s6-f06 — gate for emitting the preview=record=export parity as a MEASURED ΔE2000 value instead of
 * the legacy Boolean. DEFAULT OFF: true pixel parity depends on CP80 (export real motion) + CP81
 * (export denoise unification), both unlanded, so the measured path stays dormant until they land
 * and a real tri-surface frame producer is wired. OFF reproduces today's evidence bit-for-bit.
 */
internal object TriSurfaceParityFlag {
    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
