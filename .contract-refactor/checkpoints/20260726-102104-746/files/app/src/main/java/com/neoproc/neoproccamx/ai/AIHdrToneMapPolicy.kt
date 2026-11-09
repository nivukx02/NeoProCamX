package com.neoproc.neoproccamx.ai

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.recording.HdrImagingMath

internal data class HdrLinearStats(
    val clippedRatio: Float,
    val meanLuma: Float,
    val shadowRatio: Float,
    val peakLuma: Float = meanLuma,
    val dynamicRangeSpan: Float = 0f,
    val midtoneRatio: Float = 0f
)

internal enum class HdrSceneIntent {
    BALANCED,
    HIGHLIGHT_PRIORITY,
    SHADOW_PRIORITY,
    EXTREME_DYNAMIC_RANGE,
    CINEMATIC_WIDE_DR,
    NATURAL_LIGHT_PRESERVE
}

internal data class HdrToneMapProfile(
    val useAcesCurve: Boolean = true,
    val exposureBoost: Float,
    val whitePoint: Float,
    val shadowLift: Float,
    val midtoneContrast: Float,
    val highlightDesaturation: Float,
    val blackFloor: Float,
    val sceneIntent: HdrSceneIntent,
    val useCascadeCurve: Boolean = useAcesCurve,
    val dynamicMetadataEnabled: Boolean = false,
    val maxContentLightLevel: Float = 1000f,
    val maxFrameAverageLightLevel: Float = 400f,
    val localToneMappingStrength: Float = 0.5f,
    val colorGradingLutStrength: Float = 1.0f,
    val useSpectralBlend: Boolean = true,
    val useHybridTonemap: Boolean = useSpectralBlend,
    val gtWeight: Float = 0.35f,
    val agxWeight: Float = 0.15f,
    val shoulderWeight: Float = gtWeight,
    val depthWeight: Float = agxWeight,
    val midtoneBoost: Float = 0f,
    val highlightRecovery: Float = 0f,
    val colorPreservation: Float = 0.85f
)

internal object HdrSkinToneMath {
    fun skinToneWeight(redLinear: Float, greenLinear: Float, blueLinear: Float): Float {
        val red = NeoRadianceMath.linearToSrgbFloat(redLinear.coerceAtLeast(0f))
        val green = NeoRadianceMath.linearToSrgbFloat(greenLinear.coerceAtLeast(0f))
        val blue = NeoRadianceMath.linearToSrgbFloat(blueLinear.coerceAtLeast(0f))
        val rg = red - green
        val gb = green - blue
        val luma = NeoRadianceMath.linearLuma(
            redLinear.coerceAtLeast(0f),
            greenLinear.coerceAtLeast(0f),
            blueLinear.coerceAtLeast(0f)
        )
        val saturation = maxOf(red, green, blue) - minOf(red, green, blue)
        val warmHue = NeoRadianceMath.smoothstep(0.012f, 0.16f, rg) *
            (1f - NeoRadianceMath.smoothstep(0.34f, 0.68f, rg))
        val redDominance = NeoRadianceMath.smoothstep(-0.015f, 0.13f, gb) *
            (1f - NeoRadianceMath.smoothstep(0.30f, 0.62f, gb))
        val exposureWindow = NeoRadianceMath.smoothstep(0.08f, 0.30f, luma) *
            (1f - NeoRadianceMath.smoothstep(1.18f, 1.72f, luma))
        val saturationWindow = NeoRadianceMath.smoothstep(0.035f, 0.34f, saturation)
        return (warmHue * redDominance * exposureWindow * saturationWindow).coerceIn(0f, 1f)
    }
}

internal object AIHdrToneMapPolicy {

    /**
     * NeoRadiance Profile Resolver — scene-adaptive tone map configuration.
     *
     * Derives a [HdrToneMapProfile] from scene statistics using the NeoRadiance
     * perceptual pipeline, standard video transfer behavior, and local scene policy.
     */
    fun resolveProfile(
        stats: HdrLinearStats,
        enableAdvancedHdr: Boolean = true,
        persistedProfile: Map<String, Any>? = null
    ): HdrToneMapProfile {
        if (persistedProfile != null) {
            return rebuildProfileFromPersisted(persistedProfile)
        }
        val strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

        val sceneIntent = when {
            stats.clippedRatio >= 0.07f || stats.peakLuma >= 2.50f || stats.dynamicRangeSpan >= 1.20f ->
                HdrSceneIntent.EXTREME_DYNAMIC_RANGE
            stats.clippedRatio >= 0.06f || stats.peakLuma >= 1.85f || stats.dynamicRangeSpan >= 1.05f ->
                HdrSceneIntent.CINEMATIC_WIDE_DR
            stats.clippedRatio >= 0.015f || stats.peakLuma >= 1.20f ->
                HdrSceneIntent.HIGHLIGHT_PRIORITY
            stats.shadowRatio >= 0.28f || stats.meanLuma <= 0.18f ->
                HdrSceneIntent.SHADOW_PRIORITY
            else -> HdrSceneIntent.BALANCED
        }

        val exposureBoost = if (strictHighEnd) {
            when (sceneIntent) {
                HdrSceneIntent.SHADOW_PRIORITY -> 1.65f
                HdrSceneIntent.EXTREME_DYNAMIC_RANGE -> 1.15f
                else -> 1.25f
            }
        } else 1.15f

        val whitePoint = if (strictHighEnd) {
            when (sceneIntent) {
                HdrSceneIntent.EXTREME_DYNAMIC_RANGE -> 10.0f
                HdrSceneIntent.CINEMATIC_WIDE_DR -> 8.5f
                else -> 7.0f
            }
        } else 6.0f

        val shadowLift = when (sceneIntent) {
            HdrSceneIntent.SHADOW_PRIORITY -> if (strictHighEnd) 0.025f else 0.035f
            else -> if (strictHighEnd) 0.008f else 0.012f
        }

        val highlightDesaturation = when (sceneIntent) {
            HdrSceneIntent.HIGHLIGHT_PRIORITY -> if (strictHighEnd) 0.08f else 0.10f
            HdrSceneIntent.EXTREME_DYNAMIC_RANGE -> if (strictHighEnd) 0.04f else 0.07f
            else -> if (strictHighEnd) 0.02f else 0.05f
        }

        return HdrToneMapProfile(
            useAcesCurve = true,
            useCascadeCurve = true,
            exposureBoost = exposureBoost,
            whitePoint = whitePoint,
            shadowLift = shadowLift,
            midtoneContrast = if (strictHighEnd) 0.09f else 0.05f,
            highlightDesaturation = highlightDesaturation,
            blackFloor = 0.0001f,
            sceneIntent = sceneIntent,
            dynamicMetadataEnabled = true,
            maxContentLightLevel = if (strictHighEnd) 10000f else 1000f,
            maxFrameAverageLightLevel = (stats.meanLuma * 1200f).coerceIn(200f, 1200f),
            localToneMappingStrength = if (strictHighEnd) 0.65f else 0.45f,
            colorGradingLutStrength = 1.0f,
            useSpectralBlend = true,
            useHybridTonemap = true,
            gtWeight = 0.45f,
            agxWeight = 0.35f,
            shoulderWeight = 0.45f,
            depthWeight = 0.35f,
            midtoneBoost = if (stats.meanLuma < 0.3f) 0.15f else 0.05f,
            highlightRecovery = if (stats.clippedRatio > 0.02f) 0.35f else 0.15f,
            colorPreservation = 0.99f
        )
    }

    fun shouldRetoneMapDisplay(stats: HdrLinearStats): Boolean {
        return stats.clippedRatio >= 0.02f ||
            stats.meanLuma !in 0.22f..0.68f ||
            stats.shadowRatio >= 0.32f ||
            stats.dynamicRangeSpan >= 0.88f
    }

    /**
     * BT.2100 linear RGB tone map using a luma-preserving soft shoulder.
     */
    fun toneMapLinearRgb(
        redLinear: Float,
        greenLinear: Float,
        blueLinear: Float,
        profile: HdrToneMapProfile
    ): Int {
        val boostedR = redLinear.coerceAtLeast(0f) * profile.exposureBoost
        val boostedG = greenLinear.coerceAtLeast(0f) * profile.exposureBoost
        val boostedB = blueLinear.coerceAtLeast(0f) * profile.exposureBoost
        val mapped = HdrImagingMath.toneMapBt2100LumaPreservingRgb(
            r = boostedR.toDouble(),
            g = boostedG.toDouble(),
            b = boostedB.toDouble(),
            sourcePeakNits = profile.maxContentLightLevel.coerceAtLeast(4_000f).toDouble(),
            targetPeakNits = 1_000.0,
            shadowLift = profile.shadowLift.toDouble(),
            saturationPreservation = profile.colorPreservation.toDouble()
        )
        val blackFloor = profile.blackFloor.coerceIn(0f, 0.03f)
        val red = NeoRadianceMath.linearToSrgbByte((mapped.r.toFloat() + blackFloor).coerceIn(0f, 1f))
        val green = NeoRadianceMath.linearToSrgbByte((mapped.g.toFloat() + blackFloor).coerceIn(0f, 1f))
        val blue = NeoRadianceMath.linearToSrgbByte((mapped.b.toFloat() + blackFloor).coerceIn(0f, 1f))

        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    // ── s4-f05 / CP30 elite hardening (hysteresis · bounds · GPU-curve drive) ──────────────
    // No-downgrade gate: until the owner enables this device-side, the live GPU knee/shadow curve
    // keeps using its per-mode constants + maxCLL histogram. The CPU resolveProfile/toneMapLinearRgb
    // outputs above are unchanged; everything here is additive.
    const val POLICY_DRIVES_GPU_TONE_DEFAULT = true

    private const val HYST_CLIP_MARGIN = 0.010f
    private const val HYST_SHADOW_MARGIN = 0.06f
    private const val HYST_PEAK_MARGIN = 0.12f

    /**
     * Intent-switch hysteresis: returns the scene intent for [stats] given the [previous] intent,
     * holding the previous unless the scene has moved decisively past a dwell margin. Prevents the
     * per-frame intent flip-flop (tone flicker) that an instantaneous classifier suffers near a
     * threshold on a slowly-changing scene.
     */
    fun selectIntentWithHysteresis(stats: HdrLinearStats, previous: HdrSceneIntent?): HdrSceneIntent {
        val fresh = resolveProfile(stats).sceneIntent
        if (previous == null || fresh == previous) return fresh
        // Re-classify with the governing stats nudged down by the dwell margin: only switch when the
        // scene clears the boundary by more than the wobble band; otherwise hold the previous intent.
        val nudged = resolveProfile(
            stats.copy(
                clippedRatio = (stats.clippedRatio - HYST_CLIP_MARGIN).coerceAtLeast(0f),
                shadowRatio = (stats.shadowRatio - HYST_SHADOW_MARGIN).coerceAtLeast(0f),
                peakLuma = stats.peakLuma * (1f - HYST_PEAK_MARGIN),
                dynamicRangeSpan = stats.dynamicRangeSpan * (1f - HYST_PEAK_MARGIN)
            )
        ).sceneIntent
        return if (nudged == fresh) fresh else previous
    }

    /** Clamp every profile field to a safe range — no NaN, no blow-out, no crush, for ANY stats. */
    fun boundedProfile(p: HdrToneMapProfile): HdrToneMapProfile {
        fun f(v: Float, lo: Float, hi: Float) = if (v.isFinite()) v.coerceIn(lo, hi) else lo
        return p.copy(
            exposureBoost = f(p.exposureBoost, 0.25f, 4.0f),
            whitePoint = f(p.whitePoint, 1.0f, 12.0f),
            shadowLift = f(p.shadowLift, 0.0f, 0.10f),
            midtoneContrast = f(p.midtoneContrast, 0.0f, 0.50f),
            highlightDesaturation = f(p.highlightDesaturation, 0.0f, 0.50f),
            blackFloor = f(p.blackFloor, 0.0f, 0.03f),
            maxContentLightLevel = f(p.maxContentLightLevel, 203f, 10000f),
            maxFrameAverageLightLevel = f(p.maxFrameAverageLightLevel, 50f, 4000f),
            localToneMappingStrength = f(p.localToneMappingStrength, 0.0f, 1.0f),
            colorPreservation = f(p.colorPreservation, 0.0f, 1.0f)
        )
    }

    /**
     * s4-f05 / CP30 param bridge — resolve a bounded [profile] into the s4-f03 knee-curve push
     * constants [maxCLL, kneePoint, kneeHardness, targetPeakNits] so the live GPU peak-nits curve
     * follows the policy's scene intent. The slot order MUST match KneeCurveParams in knee_curve.comp
     * (locked by AIHdrToneMapPolicyEliteTest + KneeCurvePushConstantLayoutTest). Gated by
     * [POLICY_DRIVES_GPU_TONE_DEFAULT]; until enabled the GPU keeps its constants (no-downgrade).
     */
    fun toKneePushConstants(profile: HdrToneMapProfile): FloatArray {
        val bounded = boundedProfile(profile)
        val kneePoint = when (bounded.sceneIntent) {
            HdrSceneIntent.EXTREME_DYNAMIC_RANGE, HdrSceneIntent.CINEMATIC_WIDE_DR -> 0.58f
            HdrSceneIntent.HIGHLIGHT_PRIORITY -> 0.62f
            else -> 0.65f
        }.coerceIn(0.55f, 0.75f)
        val kneeHardness = (1.8f + bounded.midtoneContrast * 4.0f).coerceIn(1.0f, 3.0f)
        val maxCLL = bounded.maxContentLightLevel
        val targetPeakNits = bounded.maxContentLightLevel.coerceIn(203f, 10000f)
        return floatArrayOf(maxCLL, kneePoint, kneeHardness, targetPeakNits)
    }

    private fun rebuildProfileFromPersisted(persistedProfile: Map<String, Any>): HdrToneMapProfile {
        val cascadeEnabled = persistedProfile["sceneIntent"]?.toString()?.let { intent ->
            intent.contains("EXTREME") || intent.contains("CINEMATIC")
        } ?: true
        val hybridEnabled = (persistedProfile["useHybrid"] as? Boolean) ?: true
        val gtWeight = (persistedProfile["gtWeight"] as? Number)?.toFloat() ?: 0.38f
        val agxWeight = (persistedProfile["agxWeight"] as? Number)?.toFloat() ?: 0.15f
        return HdrToneMapProfile(
            useAcesCurve = cascadeEnabled,
            useCascadeCurve = cascadeEnabled,
            exposureBoost = (persistedProfile["exposureBoost"] as? Number)?.toFloat() ?: 1.12f,
            whitePoint = (persistedProfile["whitePoint"] as? Number)?.toFloat() ?: 6.0f,
            shadowLift = (persistedProfile["shadowLift"] as? Number)?.toFloat() ?: 0.014f,
            midtoneContrast = (persistedProfile["midtoneContrast"] as? Number)?.toFloat() ?: 0.05f,
            highlightDesaturation = (persistedProfile["highlightDesaturate"] as? Number)?.toFloat() ?: 0.06f,
            blackFloor = (persistedProfile["blackFloor"] as? Number)?.toFloat() ?: 0.005f,
            sceneIntent = HdrSceneIntent.valueOf(
                persistedProfile["sceneIntent"]?.toString() ?: "BALANCED"
            ),
            dynamicMetadataEnabled = hybridEnabled,
            maxContentLightLevel = 1000f,
            maxFrameAverageLightLevel = 400f,
            localToneMappingStrength = 0.5f,
            colorGradingLutStrength = 1.0f,
            useSpectralBlend = hybridEnabled,
            useHybridTonemap = hybridEnabled,
            gtWeight = gtWeight,
            agxWeight = agxWeight,
            shoulderWeight = gtWeight,
            depthWeight = agxWeight,
            midtoneBoost = (persistedProfile["midtoneBoost"] as? Number)?.toFloat() ?: 0.08f,
            highlightRecovery = (persistedProfile["highlightRecovery"] as? Number)?.toFloat() ?: 0.18f,
            colorPreservation = (persistedProfile["colorPreservation"] as? Number)?.toFloat() ?: 0.85f
        )
    }
}
