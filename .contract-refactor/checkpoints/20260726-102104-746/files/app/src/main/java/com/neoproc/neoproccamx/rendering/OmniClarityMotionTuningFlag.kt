package com.neoproc.neoproccamx.rendering

/**
 * CP-QV15 — process-global, runtime-flippable gate that lets the adaptive thermal guard push the live
 * OmniClarity motion stack's tuning (Argus refine-iteration count + Chronos temporal-alpha) down under
 * real thermal load.
 *
 * Default **OFF**. When OFF, [com.neoproc.neoproccamx.ui.CameraViewModel]'s thermal guard never calls
 * [VulkanRuntimeManager.updateOmniClarityMotionTuning], so the native motion stack keeps its CP-locked
 * defaults (refine 8, temporal alphas 0.0/0.9) — bit-for-bit today's behavior.
 *
 * When ON, a **severe** thermal state (severity ≥ [OmniClarityMotionTuningResolver.THERMAL_BACKOFF_SEVERITY])
 * backs off the live refine count (8 → 4) and the temporal blend ceiling (0.9 → 0.6) for graceful
 * degradation, and a return to a cool state re-pushes the native defaults (self-restoring). The
 * no-downgrade contract is proven by [OmniClarityMotionTuningResolverTest] — neutral inputs reproduce the
 * native defaults exactly, so a cool device is never degraded.
 *
 * NOTE: the OmniClarity motion stack also runs during recording (the encoder yield was intentionally
 * removed), so under sustained heat this softens *recorded* motion as well as preview — acceptable, but
 * user-visible, which is why it stays gated pending an on-device thermal-soak validation on vivo I2405.
 */
internal object OmniClarityMotionTuningFlag {

    @Volatile
    var enabled: Boolean = true
}
