package com.neoproc.neoproccamx.rendering

/**
 * Protects recorded-clip colour from the export enhance shader's chroma deadband.
 *
 * The recorded P010 stream is post-processed by `neo_p010_export_enhance.comp`, whose
 * `applyChromaDeadband` pulls each chroma sample toward neutral (512) by a factor of
 * `retain = clamp(1 - chromaDenoise, 0.0005, 1.0)`. The strict-high-end SDR path drives
 * `chromaDenoise` very high — `V20ZeroGrainClarityPolicy` floors it to `0.90..0.99998` and
 * `STRICT_GPU_SDR_CHROMA_DENOISE_FLOOR` is `0.9997` — so `retain` collapses to `<= 0.10` and real
 * colour is crushed toward grayscale. The result: the recorded clip looks black-and-white even
 * though the live preview (a different shader, `ycbcr_preview.frag`, which never runs this deadband)
 * stays fully colourful.
 *
 * A chroma DEADBAND is meant to smooth chroma NOISE in flat/dark regions, not to remove chroma
 * SIGNAL. This policy caps ONLY the recorder's chroma-denoise request so the deadband keeps a
 * meaningful `retain` and real colour survives, while other stages (spatial/temporal denoise, local
 * chroma-spike smoothing) still suppress genuine chroma noise. Preview uniforms are never touched —
 * they already render correctly — and luma detail / bit depth are unaffected (no quality downgrade).
 */
internal object RecordChromaFidelityPolicy {

    /**
     * Maximum chroma-denoise the recorder may request. With the shader's
     * `retain = 1 - chromaDenoise`, a cap of `0.40` guarantees `retain >= 0.60`, so at least 60% of
     * genuine chroma is preserved in the recording (clearly colourful, not grayscale) while still
     * allowing up to 40% chroma-noise smoothing via the deadband.
     */
    const val RECORD_CHROMA_DENOISE_CAP = 0.40f

    /** Lower bound the shader clamps `retain` to; kept here so tests can mirror the shader exactly. */
    const val SHADER_DEADBAND_RETAIN_FLOOR = 0.0005f

    /**
     * Cap a recorder chroma-denoise request. Non-finite input falls back to the cap (safe neutral),
     * so a corrupt uniform can never disable the clamp and re-introduce the grayscale collapse.
     */
    fun capRecordChromaDenoise(chromaDenoise: Float): Float =
        if (chromaDenoise.isFinite()) minOf(chromaDenoise, RECORD_CHROMA_DENOISE_CAP) else RECORD_CHROMA_DENOISE_CAP

    /**
     * The chroma `retain` the export deadband will use for a given (already-capped) chromaDenoise,
     * mirroring `neo_p010_export_enhance.comp::applyChromaDeadband`. Used by tests to prove that a
     * capped request preserves real colour.
     */
    fun deadbandRetainFor(chromaDenoise: Float): Float =
        (1f - chromaDenoise).coerceIn(SHADER_DEADBAND_RETAIN_FLOOR, 1f)
}
