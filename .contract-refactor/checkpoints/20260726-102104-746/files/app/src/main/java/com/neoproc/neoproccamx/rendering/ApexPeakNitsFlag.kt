package com.neoproc.neoproccamx.rendering

/**
 * CP30 — process-global, runtime-flippable gate for the BT.2390 EETF peak-nits highlight shoulder in
 * knee_curve.comp. Was a bake-time spec constant (SC_APEX_PEAK_NITS, identity when false); now a
 * per-dispatch push constant driven by this flag via [VulkanBridge.setApexPeakNitsEnabled], forwarded
 * on the VulkanRuntimeManager render tick.
 *
 * Default **OFF**. When OFF, peakNitsGate is 0.0, so the knee uses today's SDR-referred
 * modified-Reinhard path bit-for-bit (no-downgrade). When ON, highlights above diffuse white (203 nits
 * = 1.0) are rolled to the explicit `targetPeakNits` display peak via a C1-continuous Hermite shoulder
 * instead of compressed into the normalized SDR range; the EETF is identity below diffuse white, so the
 * only visible change is on super-highlights. Validated on device (vivo I2405) before shipping ON; the
 * effect is best confirmed against an HDR / bright-highlight scene.
 */
internal object ApexPeakNitsFlag {

    // Default ON (user-directed). Broadcast debug.apex_peak_nits false to disable.
    @Volatile
    var enabled: Boolean = true
}
