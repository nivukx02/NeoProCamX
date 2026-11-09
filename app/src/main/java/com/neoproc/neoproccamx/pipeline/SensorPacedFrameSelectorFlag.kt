package com.neoproc.neoproccamx.pipeline

/**
 * s7-f01 / CP92 — single no-downgrade gate for sensor-paced analysis-frame selection,
 * bounded-staleness metadata on the fused bundle, and the per-stage governor drop-order.
 *
 * DEFAULT ON (user-directed activation 2026-07-04, device-verified). When false, reproduces today's behavior bit-for-bit:
 *  - frame selection stays on [FrameRealtimeAnalysisPlan.shouldAnalyze] (count-based,
 *    acquire-latest drop-whatever-arrives);
 *  - [com.neoproc.neoproccamx.ai.AICameraFrameResult] staleness fields stay 0 (the field
 *    defaults), so consumers see today's output;
 *  - [AICameraAdaptiveLoadPolicy] keeps its uniform stride multiplier (no ordered drop).
 *
 * The new paths are dormant until the owner flips this on after on-device validation
 * (vivo I2405 / Adreno, Android 16). No model, no GPU/ABI change.
 */
internal object SensorPacedFrameSelectorFlag {
    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
