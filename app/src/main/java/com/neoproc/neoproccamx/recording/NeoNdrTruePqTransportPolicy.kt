package com.neoproc.neoproccamx.recording

/**
 * Fail-closed output-transport authority for project-owned NDR10+/NIVUKX/APEX frames.
 *
 * The active NDR pipeline starts from the app's RAW-derived scene-linear ACEScg frame and its
 * project-owned tone operator writes ST.2084 output. HLG is therefore not an interchangeable safety
 * fallback: selecting it at the EGL surface, encoder, container, or publication layer would describe
 * a different transfer than the pixels produced by that pipeline. Generic camera HLG modes remain
 * supported by their own policies; this authority is deliberately scoped to proprietary NDR output.
 *
 * [set] is retained for binary/source compatibility with old debug commands, but a request to disable
 * PQ is rejected. Keeping the effective value immutable makes every existing transport consumer fail
 * closed together instead of allowing one layer to relabel sensor-linear NDR as HLG.
 */
object NeoNdrTruePqTransportPolicy {
    private const val FAIL_CLOSED_SENSOR_LINEAR_NDR = true

    /** Proprietary sensor-linear NDR output is always BT.2100 PQ/ST.2084. */
    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value || FAIL_CLOSED_SENSOR_LINEAR_NDR
        }

    /** Compatibility entry point for historical A/B commands; the effective state remains true. */
    fun set(value: Boolean) {
        enabled = value
    }
}
