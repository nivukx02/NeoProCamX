package com.neoproc.neoproccamx.recording

/**
 * s6-f05 / CP81 — single source of truth for the nivukx denoise coefficients, extracted **verbatim**
 * from the live `nivukx_denoise.comp` kernel so the NPV10→MOV export denoise and the live preview/
 * record kernel can read the same numbers (the WYSIWYG-unification keystone).
 *
 * These values are pinned against the shader source by `Npv10ExportNivukxDenoiseCoeffsTest`. This is
 * a PURE data object with NO live-path caller until `Npv10ExportUnifiedDenoiseFlag` is enabled
 * (default OFF, device-gated), so it cannot change any current export or preview output.
 *
 * Source: `app/src/main/cpp/shaders/vulkan/nivukx_denoise.comp`
 *  - GAT_SIGMA_SHOT (constant_id=4) = 0.0012, GAT_SIGMA_READ_SQ (constant_id=3) = 0.00022 (:23-24, :178)
 *  - JzAzBz luma weights vec3(0.2627, 0.6780, 0.0593) (:29)
 *  - bilateral spatial term exp(-d²/18.0) (:56, :112)
 *  - temporal Tukey biweight cutoff = noiseSigma * 4.0 (:237)
 *  - microtexture reinject 0.35 (:69)
 */
internal object Npv10ExportNivukxDenoiseCoeffs {
    /** Shot-noise gain α (variance per unit signal) — GAT_SIGMA_SHOT. */
    const val GAT_SIGMA_SHOT: Double = 0.0012

    /** Read-noise variance σ_read² — GAT_SIGMA_READ_SQ. */
    const val GAT_SIGMA_READ_SQ: Double = 0.00022

    /** Bilateral spatial Gaussian denominator: exp(-(dx²+dy²)/SPATIAL_SIGMA_SQ). */
    const val BILATERAL_SPATIAL_SIGMA_SQ: Double = 18.0

    /** Temporal Tukey biweight cutoff multiplier (noiseSigma * this). */
    const val TEMPORAL_TUKEY_SIGMA_MULTIPLIER: Double = 4.0

    /** Microtexture reinjection strength. */
    const val MICROTEXTURE_REINJECT: Double = 0.35

    /** JzAzBz / BT.2020 luma weights. */
    const val LUMA_WEIGHT_R: Double = 0.2627
    const val LUMA_WEIGHT_G: Double = 0.6780
    const val LUMA_WEIGHT_B: Double = 0.0593

    /** Poisson-Gaussian variance model used by the kernel: α·clamp(y,0,1) + σ_read². */
    fun heteroscedasticVariance(luma: Double): Double =
        GAT_SIGMA_SHOT * luma.coerceIn(0.0, 1.0) + GAT_SIGMA_READ_SQ

    /** Stable signature of the coefficient set for export-vs-live evidence. */
    fun coefficientSignature(): String =
        "gatShot=$GAT_SIGMA_SHOT;gatRead=$GAT_SIGMA_READ_SQ;spatial=$BILATERAL_SPATIAL_SIGMA_SQ;" +
            "tukey=$TEMPORAL_TUKEY_SIGMA_MULTIPLIER;micro=$MICROTEXTURE_REINJECT;" +
            "luma=$LUMA_WEIGHT_R,$LUMA_WEIGHT_G,$LUMA_WEIGHT_B"
}
