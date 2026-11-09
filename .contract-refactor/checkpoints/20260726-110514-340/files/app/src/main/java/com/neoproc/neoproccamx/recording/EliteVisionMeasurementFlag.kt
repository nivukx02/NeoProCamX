package com.neoproc.neoproccamx.recording

/**
 * s6-f08 — process-global runtime gate for the real-pixel Elite-Vision measurement loop.
 *
 * DEFAULT OFF. When OFF, [EliteVisionProductionMeasurement] injects NOTHING into the live recording
 * evidence request, so the Tier-Omega metric branch stays dormant exactly as today (no `tierOmega*`
 * key is added, `requiresTierOmegaMetricEvidence()` stays false) — the live certificate is
 * byte-for-bit unchanged. When ON (device-gated), the production caller measures decoded frames and
 * feeds the measured `tierOmega*` evidence into the gate (or honest NOT_PROVEN when no real-frame
 * producer is available).
 *
 * Device-gated enable (owner-run, dormant until then):
 * `adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND --ez debug.elite_vision_measure true`
 */
internal object EliteVisionMeasurementFlag {
    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
