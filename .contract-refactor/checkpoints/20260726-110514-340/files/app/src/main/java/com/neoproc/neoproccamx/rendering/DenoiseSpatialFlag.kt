package com.neoproc.neoproccamx.rendering

/**
 * CP-QV16 — process-global, runtime-flippable gate for the single-frame spatial denoiser
 * (`denoise.comp`), wired as a GATED foundation next to the live nivukx denoise pass.
 *
 * Default **ON** (user-directed, committed 8229634, device-validated prior session). When OFF (broadcast
 * `debug.spatial_denoise false`), [VulkanBridge.setSpatialDenoiseEnabled] is never called (or is called with
 * `false`), so the native `denoiseSpatialEnabled` flag stays false and the post-nivukx spatial-denoise
 * dispatch is unreachable — zero extra GPU dispatch, no changed image state, and the live render path is
 * byte-for-byte identical to today's behavior (the s1-f06 / s1-f10 "gated foundation" no-downgrade rule).
 *
 * When ON, the native path runs `denoise.comp` once per frame immediately after the nivukx denoise: it
 * reads the just-produced current-frame storage image, writes the rgba16f `omniDeblur` scratch, then
 * copies the denoised result back into the current frame through the existing OCCS zero-gain passthrough
 * (a compute copy-back, because the scratch images are STORAGE-only, not TRANSFER images). This adds
 * `denoise.comp`'s flat-region detail-safe stages (wavelet soft-threshold, non-local means, guided filter,
 * Anscombe–Oklab–Bayes VST) that the live nivukx path lacks — see [WaveletDetailSafeTest].
 *
 * NOTE: forwarded live every render tick via VulkanRuntimeManager -> [VulkanBridge.setSpatialDenoiseEnabled].
 * The barrier/copy-back correctness was validated on device (vivo I2405) before this default-ON activation;
 * it affects recorded frames, not just preview, so `debug.spatial_denoise false` is the runtime kill-switch.
 */
internal object DenoiseSpatialFlag {

    // Default ON (user-directed). Broadcast debug.spatial_denoise false to disable.
    @Volatile
    var enabled: Boolean = true
}
