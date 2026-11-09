package com.neoproc.neoproccamx.rendering

/**
 * CP-QV17 — process-global, runtime-flippable gate for the single-frame optical-recovery sharpener
 * (`sharpen.comp`), wired as a GATED foundation next to the CP-QV16 spatial denoiser.
 *
 * Default **ON** (user-directed, committed 8229634, device-validated prior session). When OFF (broadcast
 * `debug.optical_sharpen false`), [VulkanBridge.setOpticalSharpenEnabled] is never called (or is called with
 * `false`), so the native `sharpenOpticalEnabled` flag stays false and the post-denoise sharpen dispatch
 * is unreachable — zero extra GPU dispatch, no changed image state, and the live render path is
 * byte-for-byte identical to today's behavior (the s1-f06 / s1-f10 "gated foundation" no-downgrade rule).
 *
 * When ON, the native path runs `sharpen.comp` once per frame immediately after the CP-QV16 spatial
 * denoise: it reads the just-produced current-frame storage image, writes the rgba16f `omniDeblur`
 * scratch, then copies the sharpened result back into the current frame through the existing OCCS
 * zero-gain passthrough (a compute copy-back, because the scratch images are STORAGE-only, not TRANSFER
 * images). This adds `sharpen.comp`'s unique optical-recovery stages (multi-scale unsharp mask +
 * Richardson-Lucy deconvolution + Fienup HIO / Hermite-Gaussian wavefront phase retrieval) that the live
 * path lacks — see [DormantHdrDenoiseShaderTriageTest].
 *
 * NOTE: forwarded live every render tick via VulkanRuntimeManager -> [VulkanBridge.setOpticalSharpenEnabled].
 * The barrier/copy-back correctness was validated on device (vivo I2405) before this default-ON activation;
 * it affects recorded frames, not just preview, so `debug.optical_sharpen false` is the runtime kill-switch.
 * The shader derives its PSF (`shaderFocalDistance`/`shaderAperture`) heuristically from `radius`/
 * `threshold`, so it is NOT a calibrated lens model — see [SharpenOpticalGoldenTest].
 */
internal object SharpenOpticalFlag {

    // Default ON (user-directed). Broadcast debug.optical_sharpen false to disable.
    @Volatile
    var enabled: Boolean = true
}
