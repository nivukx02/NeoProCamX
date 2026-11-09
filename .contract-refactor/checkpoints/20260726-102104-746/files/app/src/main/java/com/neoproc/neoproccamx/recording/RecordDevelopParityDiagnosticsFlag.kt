package com.neoproc.neoproccamx.recording

/**
 * RecordDevelopParityDiagnosticsFlag — DORMANT (default OFF) on-device A/B harness switch.
 *
 * When ON, the record-develop first-frame GPU-vs-CPU parity probe logs the FULL residual distribution
 * (mean/max/p50/p99/p99.9/p99.99 + >1/>4/>16/>64 bucket counts + worst-N pixels with codes) per output
 * category (SDR_COLOR / NDR_PQ) via [RecordDevelopParityDiagnostics], instead of only logging on
 * rejection. This is the instrument for measuring the true post-fix residual on the vivo so the gate
 * tolerances ([RecordDevelopParityGatePolicy]) can be tightened from real data rather than guessed.
 *
 * ZERO production impact when OFF: the probe already runs once per clip; this only decides whether its
 * stats are logged at INFO. It never alters produced pixels and never changes the accept/reject verdict.
 * NO-DOWNGRADE.
 *
 * adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *   -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.record_parity_diagnostics true
 */
internal object RecordDevelopParityDiagnosticsFlag {

    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
