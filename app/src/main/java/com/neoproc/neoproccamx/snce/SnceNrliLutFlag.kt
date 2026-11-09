package com.neoproc.neoproccamx.snce

/**
 * CP-QV21 — process-global, runtime-flippable gate for the replaceable SNCE Stage-11 NRLI 3D-LUT look.
 *
 * Default **OFF**. When OFF, no live code calls [com.neoproc.neoproccamx.rendering.VulkanBridge.updateNrliLut],
 * so the native NRLI dispatch keeps its CP-locked identity 33^3 LUT at strength 0 — an exact passthrough
 * (`mix(color, graded, 0) == color`), bit-for-bit today's enhance path. There is deliberately **no live
 * caller** of the setter; this flag is the seam a future device-side caller would consult before pushing
 * an analytic look ([SnceNrliAnalyticLut.buildNearIdentityLut]) into the live pass.
 *
 * When ON (device-side, after an on-device quality validation on vivo I2405), the caller may replace the
 * identity LUT with the bounded near-identity analytic look and raise strength; the no-downgrade contract
 * is that strength 0 stays an exact passthrough regardless of LUT contents (proven by the shader `mix` and
 * by [SnceNrliApplicatorEliteTest] / the CP-QV21 golden). The setter is fail-safe: an invalid array or a
 * failed GPU upload leaves the identity LUT + strength 0 intact.
 *
 * Mirrors [com.neoproc.neoproccamx.rendering.OmniClarityMotionTuningFlag]: a plain @Volatile boolean gate
 * with no behavioural side effects of its own.
 */
internal object SnceNrliLutFlag {

    @Volatile
    var enabled: Boolean = true // Default ON (user-directed 2026-07-04): live caller pushes the bounded near-identity analytic look; identity/strength-0 fallback stays fail-safe.
}
