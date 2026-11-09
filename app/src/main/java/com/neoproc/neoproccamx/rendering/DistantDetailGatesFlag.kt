package com.neoproc.neoproccamx.rendering

/**
 * CP24 — process-global, runtime-flippable gate for the distant-detail elite refinements: the
 * detail_preservation.comp noise-aware + halo-guard mask rolloffs and the guided_upsample.comp
 * edge-tighten. These were bake-time spec constants (identity when false); they are now per-dispatch
 * push constants driven by this flag via [VulkanBridge.setDistantDetailGatesEnabled], forwarded on the
 * VulkanRuntimeManager render tick.
 *
 * Default **ON** (runtime kill-switch: `debug.distant_detail false`). When OFF, every gate
 * push-constant field is 0.0, so the detail mask and guided upsample are byte-for-byte identical to the
 * pre-CP24 baseline (the s1-f06 / s1-f10 "gated foundation" no-downgrade rule). When ON, the
 * noise-aware rolloff prevents downstream denoise/sharpen from
 * amplifying grain in flat/noisy regions, the halo guard rolls the mask off at very strong edges so
 * detail re-injection cannot ring, and the guide edge-tighten snaps the upsampled map to real edges.
 *
 * Validated on device (vivo I2405) before shipping ON; the visual benefit is a high-ISO / high-contrast
 * refinement best confirmed against a low-light or hard-edge test scene.
 */
internal object DistantDetailGatesFlag {

    // Default ON (user-directed). Broadcast debug.distant_detail false to disable.
    @Volatile
    var enabled: Boolean = true
}
