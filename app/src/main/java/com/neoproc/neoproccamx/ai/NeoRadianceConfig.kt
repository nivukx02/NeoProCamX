package com.neoproc.neoproccamx.ai

internal enum class NeoRadiancePipeline {
    V1,
    HELIOS
}

internal enum class NeoRadianceOutputTarget {
    HDR10_PQ,
    HLG,
    SDR_REC709
}

internal data class IctcpColor(
    val i: Float,
    val ct: Float,
    val cp: Float
)

internal data class NeoRadianceHeliosParams(
    val contrast: Float = NeoRadianceConfig.CASCADE_CONTRAST_DEFAULT,
    val shoulderSoft: Float = NeoRadianceConfig.CASCADE_SHOULDER_SOFT_DEFAULT,
    val strength: Float = NeoRadianceConfig.CASCADE_STRENGTH_DEFAULT,
    val outputTarget: NeoRadianceOutputTarget = NeoRadianceOutputTarget.HDR10_PQ
) {
    fun sanitized(): NeoRadianceHeliosParams =
        copy(
            contrast = contrast.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                ?: NeoRadianceConfig.CASCADE_CONTRAST_DEFAULT,
            shoulderSoft = shoulderSoft.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                ?: NeoRadianceConfig.CASCADE_SHOULDER_SOFT_DEFAULT,
            strength = strength.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                ?: 0f
        )
}

internal object NeoRadianceConfig {
    const val EPSILON = 1e-6f // PHYSICAL: divide guard for luminance ratios and matrix inversions.

    const val DIFFUSE_WHITE_NITS = 203f // PHYSICAL: BT.2408 diffuse white anchor for HDR production.
    const val PEAK_NITS = 1000f // PHYSICAL: HDR10 1000-nit mastering target for mobile delivery.

    const val REC2020_LUMA_R = 0.2627f // PHYSICAL: BT.2020 RGB-to-Y coefficient.
    const val REC2020_LUMA_G = 0.6780f // PHYSICAL: BT.2020 RGB-to-Y coefficient.
    const val REC2020_LUMA_B = 0.0593f // PHYSICAL: BT.2020 RGB-to-Y coefficient.
    const val REC709_LUMA_R = 0.2126f // PHYSICAL: Rec.709 RGB-to-Y coefficient for SDR output only.
    const val REC709_LUMA_G = 0.7152f // PHYSICAL: Rec.709 RGB-to-Y coefficient for SDR output only.
    const val REC709_LUMA_B = 0.0722f // PHYSICAL: Rec.709 RGB-to-Y coefficient for SDR output only.

    const val CASCADE_CONTRAST_DEFAULT = 0.35f // AESTHETIC: conservative midtone contrast for HELIOS v2.
    const val CASCADE_SHOULDER_SOFT_DEFAULT = 0.25f // AESTHETIC: prompt-specified shoulder softness.
    const val CASCADE_STRENGTH_DEFAULT = 0.40f // AESTHETIC: prompt-specified blend toward cascade result.

    const val SHADOW_KNEE = 0.08f // AESTHETIC: prompt toe boundary for shadow lift.
    const val SHADOW_BLEND_LOW = 0.06f // AESTHETIC: lower C1 blend boundary around the shadow knee.
    const val SHADOW_BLEND_HIGH = 0.10f // AESTHETIC: upper C1 blend boundary around the shadow knee.

    const val PQ_M1 = 0.1593017578125f // PHYSICAL: SMPTE ST 2084 constant m1.
    const val PQ_M2 = 78.84375f // PHYSICAL: SMPTE ST 2084 constant m2.
    const val PQ_C1 = 0.8359375f // PHYSICAL: SMPTE ST 2084 constant c1.
    const val PQ_C2 = 18.8515625f // PHYSICAL: SMPTE ST 2084 constant c2.
    const val PQ_C3 = 18.6875f // PHYSICAL: SMPTE ST 2084 constant c3.

    const val HLG_A = 0.17883277f // PHYSICAL: BT.2100 HLG OETF constant a.
    const val HLG_B = 0.28466892f // PHYSICAL: BT.2100 HLG OETF constant b.
    const val HLG_C = 0.55991073f // PHYSICAL: BT.2100 HLG OETF constant c.

    const val HIGHLIGHT_DESATURATION = 0.35f // AESTHETIC: prompt highlight chroma rolloff cap.
    const val RADIANCE_MOTION_CONFIDENCE_MIN = 0.5f // AESTHETIC: prompt ghost rejection threshold.
    const val RADIANCE_TEMPORAL_EMA_ALPHA = 0.15f // AESTHETIC: prompt video brightness smoothing.

    const val ACES_RGC_THRESHOLD_CYAN = 0.815f // PHYSICAL: ACES 1.3 reference gamut compression threshold.
    const val ACES_RGC_THRESHOLD_MAGENTA = 0.803f // PHYSICAL: ACES 1.3 reference gamut compression threshold.
    const val ACES_RGC_THRESHOLD_YELLOW = 0.880f // PHYSICAL: ACES 1.3 reference gamut compression threshold.
    const val ACES_RGC_LIMIT_CYAN = 1.147f // PHYSICAL: ACES 1.3 reference gamut compression distance limit.
    const val ACES_RGC_LIMIT_MAGENTA = 1.264f // PHYSICAL: ACES 1.3 reference gamut compression distance limit.
    const val ACES_RGC_LIMIT_YELLOW = 1.312f // PHYSICAL: ACES 1.3 reference gamut compression distance limit.
    const val ACES_RGC_POWER = 1.2f // PHYSICAL: ACES 1.3 reference gamut compression power.
}
