package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.color.NeoTenBitDitherMode
import com.neoproc.neoproccamx.color.NeoTenBitHardwareSupport
import com.neoproc.neoproccamx.color.VideoLogPreviewParity
import com.neoproc.neoproccamx.color.VideoLogPreviewParityPolicy
import com.neoproc.neoproccamx.color.NeoLogProfilePolicy
import com.neoproc.neoproccamx.color.AcesColorEngine
import com.neoproc.neoproccamx.camera.DualGainHdrFusionPlan
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.ImaxStrictProfile
import com.neoproc.neoproccamx.core.PerceptualSignalMath
import com.neoproc.neoproccamx.core.PerceptualSignalSample

data class SceneRenderProfile(
    val temporalBlend: Float,
    val spatialDenoise: Float,
    val chromaDenoise: Float,
    val shadowBoost: Float,
    val sharpening: Float,
    val warmth: Float,
    val contrast: Float,
    val saturation: Float,
    val highlightCompression: Float,
    val logStrength: Float,
    val ditherStrength: Float,
    val videoHdrFusionStrength: Float,
    val logPreviewParity: VideoLogPreviewParity = VideoLogPreviewParity(),
    val noiseMetrics: VideoNoiseMetrics = VideoNoiseMetrics.EMPTY,
    val noiseReductionSignature: String = "",
    val detailSharpeningMetadata: Map<String, String> = emptyMap(),
    val detailSharpeningSignature: String = "",
    val subjectDetailPlan: SubjectDetailEnhancementPlan? = null,
    val acesColorSignature: String = "",
    val dualGainFusionEnabled: Boolean = false,
    val dualGainMotionMaskEnabled: Boolean = false,
    val dualGainKGain: Float = 1f,
    val dualGainNoiseFloor: Float = 0f,
    val dualGainSaturationThreshold: Float = 0.94f,
    val dualGainTimestampSkewNs: Long = Long.MAX_VALUE,
    val dualGainFusionSignature: String = "dualGain=inactive|externalProfile=false|vendor=false",
    // AI Motion Compensation temporal blend — non-zero ONLY when the user enabled AI Motion
    // (heuristic/neural active). Carried separately from [temporalBlend] so the base scene
    // temporal denoise never leaks into the record temporal path.
    val aiMotionTemporalBlend: Float = 0f
)

class SceneRenderProfileEngine {

    fun build(
        scene: SceneAnalysis?,
        recommendation: CaptureRecommendation?,
        logEnabled: Boolean,
        hdrEnabled: Boolean,
        colorProfile: String,
        aiEnhancementEnabled: Boolean = true,
        simulatedTenBitFallback: Boolean = false,
        tenBitHardwareSupport: NeoTenBitHardwareSupport = NeoTenBitHardwareSupport.none(),
        lutEnabled: Boolean = false,
        lutProfile: String = "OFF",
        hdrMode: String = "OFF",
        previewFps: Float = HighEndQualityPolicy.targetPreviewFps.toFloat(),
        recording: Boolean = false,
        computationalCinemaPlan: ComputationalCinemaPlan? = null,
        dualGainFusionPlan: DualGainHdrFusionPlan? = null,
        outputWidth: Int = 3840,
        outputHeight: Int = 2160,
        sourceWidth: Int = outputWidth,
        sourceHeight: Int = outputHeight,
        videoDetailProfile: VideoDetailProfile? = null,
        subjectDetailPlan: SubjectDetailEnhancementPlan? = null,
        aiMotionCompensationEnabled: Boolean = false
    ): SceneRenderProfile {
        val runtimeTier = HighEndQualityPolicy.runtimeTier(
            previewFps = previewFps,
            recording = recording,
            enabledOverride = HighEndQualityPolicy.enabled
        )
        val strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        if (scene == null || recommendation == null) {
            return defaultProfile(
                logEnabled = logEnabled,
                hdrEnabled = hdrEnabled,
                colorProfile = colorProfile,
                aiEnhancementEnabled = aiEnhancementEnabled,
                simulatedTenBitFallback = simulatedTenBitFallback,
                tenBitHardwareSupport = tenBitHardwareSupport,
                lutEnabled = lutEnabled,
                lutProfile = lutProfile,
                hdrMode = hdrMode,
                previewFps = previewFps,
                recording = recording,
                dualGainFusionPlan = dualGainFusionPlan,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                videoDetailProfile = videoDetailProfile,
                subjectDetailPlan = subjectDetailPlan
            )
        }

        val lowLightFactor = when {
            scene.estimatedLux < 8f -> 1f
            scene.isLowLight -> 0.72f
            else -> 0.15f
        }
        val motionFactor = scene.motionScore.coerceIn(0f, 1f)
        val brightLightFactor = ((scene.estimatedLux - 45f) / 180f).coerceIn(0f, 1f)
        val textureRichFactor = (scene.edgeDensity / 0.28f).coerceIn(0f, 1f)
        val flatRegionFactor = (1f - textureRichFactor).coerceIn(0f, 1f)
        val brightNoiseRisk = (brightLightFactor * (0.35f + flatRegionFactor * 0.65f)).coerceIn(0f, 1f)
        val measuredNoiseFactor = scene.normalizedNoiseFloor.coerceIn(0f, 1f)
        val flatChromaVarianceFactor = ((scene.flatRegionStdDev - 3f) / 14f).coerceIn(0f, 1f)
        val isoSpeckleFactor = ((scene.realIsoNoiseFloor - 800f) / 8_000f).coerceIn(0f, 1f)
        val rgbSpeckleRisk = (
            measuredNoiseFactor * 0.45f +
                flatChromaVarianceFactor * 0.25f +
                isoSpeckleFactor * 0.22f +
                lowLightFactor * 0.08f
            ).coerceIn(0f, 1f)
        val profileKey = colorProfile.trim().lowercase()
        val lutKey = lutProfile.trim().lowercase()
        val hdrModeKey = hdrMode.trim().lowercase()
        val logLook = NeoLogProfilePolicy.resolve(colorProfile, logEnabled)
        val logPreviewParity = VideoLogPreviewParityPolicy.resolve(colorProfile, logEnabled)
        val inferredHardwareSupport =
            if (
                tenBitHardwareSupport == NeoTenBitHardwareSupport.none() &&
                    !simulatedTenBitFallback &&
                    runtimeTier.tenBitDisplay
            ) {
                NeoTenBitHardwareSupport(
                    hdr10Supported = runtimeTier.hdr10Capable,
                    hlgSupported = runtimeTier.tenBitDisplay
                )
            } else {
                tenBitHardwareSupport
            }
        val isVideoHdrMode =
            hdrModeKey == "hdr" ||
                hdrModeKey == "hlg" ||
                hdrModeKey == "hdr10" ||
                hdrModeKey == "hdr10+" ||
                hdrModeKey == "dv" ||
                hdrModeKey.contains("ndr") ||
                hdrModeKey.contains("nivukx")
        val isWideGamutVideoProfile =
            profileKey.contains("2020") ||
                profileKey.contains("neo") ||
                profileKey.contains("wide")
        val activeVideoHdrLook =
            isVideoHdrMode &&
                (isWideGamutVideoProfile || hdrEnabled) &&
                !simulatedTenBitFallback
        val hdrVersionRenderProfile = HdrVersionRenderPolicy.resolve(
            hdrMode = hdrMode,
            hdrEnabled = hdrEnabled || isVideoHdrMode,
            recording = recording
        )
        val tenBitRequested = logEnabled || hdrEnabled || isVideoHdrMode
        val tenBitRenderPath = NeoLogProfilePolicy.resolveTenBitRenderPath(
            profileName = colorProfile,
            logEnabled = logEnabled,
            hardwareSupport = inferredHardwareSupport,
            tenBitRequested = tenBitRequested
        )
        val effectiveSimulatedTenBitFallback =
            simulatedTenBitFallback ||
                (
                    tenBitRequested &&
                        !tenBitRenderPath.useNativeHardwareBuffer &&
                        tenBitRenderPath.ditherMode == NeoTenBitDitherMode.DYNAMIC_BLUE_NOISE_TEMPORAL
                )
        val hdrFactor =
            if (scene.highDynamicRange || (hdrEnabled && (!isVideoHdrMode || activeVideoHdrLook))) {
                1f
            } else {
                0f
            }
        val ditherSignal = PerceptualSignalSample(
            normalizedNoiseFloor = scene.normalizedNoiseFloor,
            flatRegionStdDev = scene.flatRegionStdDev,
            flatRegionCoverage = flatRegionFactor,
            perceptualSharpness = scene.perceptualSharpness,
            laplacianEnergy = scene.perceptualSharpness * 96f,
            realIsoNoiseFloor = scene.realIsoNoiseFloor
        )
        // ── QUALITY UPGRADE: Reduced denoise ceilings to preserve micro-detail ──
        // Previous values (0.98/0.99) obliterated texture. New ceilings retain
        // detail while still controlling noise in low-light.
        var temporalBlend = (0.16f + lowLightFactor * 0.36f - motionFactor * 0.32f + rgbSpeckleRisk * 0.08f)
            .coerceIn(0.03f, 0.55f)
        var spatialDenoise = (
            0.14f +
                lowLightFactor * 0.42f +
                scene.shadowRatio * 0.18f +
                brightNoiseRisk * 0.06f +
                rgbSpeckleRisk * 0.14f
            )
            .coerceIn(0.10f, 0.72f)
        var chromaDenoise = (
            0.20f +
                lowLightFactor * 0.44f +
                hdrFactor * 0.08f +
                brightNoiseRisk * 0.10f +
                rgbSpeckleRisk * 0.20f
            )
            .coerceIn(0.18f, 0.75f)
        var shadowBoost = (0.12f + lowLightFactor * 0.50f + scene.shadowRatio * 0.20f)
            .coerceIn(0.10f, 0.96f)
        // ── QUALITY UPGRADE: Raised sharpening floor and ceiling for crisp output ──
        var sharpening = (
            0.08f +
                recommendation.sharpeningBoost * 0.82f +
                scene.edgeDensity * 0.12f -
                lowLightFactor * 0.03f -
                hdrFactor * 0.012f +
                motionFactor * 0.016f -
                brightNoiseRisk * 0.008f -
                rgbSpeckleRisk * 0.05f
            ).coerceIn(0.06f, 0.62f)

        val isNeoCinemaProfile = profileKey.contains("neo") || profileKey.contains("wide")
        val isProSdrWideGamutProfile =
            isNeoCinemaProfile &&
                (
                    profileKey.contains("wide") ||
                        profileKey.contains("gamut") ||
                        profileKey.contains("2020")
                    )
        val isLogProfile = logLook.enabled
        val isNaturalProfile = profileKey.contains("natural")
        val isRec709Profile = profileKey.contains("709")
        val isRec2020FamilyProfile =
            profileKey.contains("2020") || profileKey.contains("2100")
        val isGeneralColorProfile =
            !isLogProfile && (isNaturalProfile || isRec709Profile || isRec2020FamilyProfile)
        var warmth = when {
            isNeoCinemaProfile -> 0.026f
            profileKey.contains("2020") -> 0.01f
            profileKey.contains("709") -> 0.012f
            profileKey.contains("warm") -> 0.06f
            profileKey.contains("cinem") -> 0.035f
            profileKey.contains("teal") || profileKey.contains("cool") -> -0.04f
            profileKey.contains("bleach") -> -0.015f
            else -> 0.015f
        }
        // ── MODE DIFFERENTIATION: Each mode gets its distinct look ──────────
        var contrast = when {
            isNeoCinemaProfile -> 0.92f
            profileKey.contains("2020") && (hdrEnabled || isVideoHdrMode) -> 1.00f
            profileKey.contains("2020") -> 1.02f
            profileKey.contains("709") -> 1.0f
            logEnabled -> 0.72f   // LOG: flat, low-contrast (cinema grading ready)
            hdrFactor > 0f -> 1.00f
            profileKey.contains("bleach") -> 1.08f
            profileKey.contains("faded") -> 0.92f
            else -> 1.04f          // PRO: clean, sharp, natural contrast
        }
        var saturation = when {
            isNeoCinemaProfile -> 0.90f
            profileKey.contains("2020") && (hdrEnabled || isVideoHdrMode) -> 1.00f
            profileKey.contains("2020") -> 1.02f
            profileKey.contains("709") -> 1.0f
            logEnabled -> 0.68f    // LOG: desaturated for grading headroom
            profileKey.contains("natural") -> 1.0f
            profileKey.contains("cinem") -> 0.92f
            profileKey.contains("teal") -> 0.98f
            profileKey.contains("night") -> 0.72f
            profileKey.contains("bleach") -> 0.78f
            else -> 1.02f          // PRO: slightly boosted for vivid output
        }
        var highlightCompression = (0.06f + hdrFactor * 0.28f + scene.highlightRatio * 0.22f)
            .coerceIn(0.04f, 0.82f)
        // ── MODE DIFFERENTIATION: logStrength controls LOG tone curve ──
        // PRO = 0.0 (clean normal video), LOG = high (flat curve),
        // HDR = moderate (tone mapping), RAW = 0.0 (raw sensor data)
        val isRawMode = profileKey.contains("raw")
        var logStrength = when {
            isRawMode -> 0.0f      // RAW: no tone mapping
            isLogProfile -> maxOf(logLook.logStrengthFloor, 0.68f) // LOG: strong flat curve
            isNeoCinemaProfile -> 0.36f
            else -> 0.0f           // PRO/HDR: no LOG tone curve
        }
        var ditherStrength = when {
            tenBitRenderPath.useNativeHardwareBuffer -> 0f
            tenBitRenderPath.ditherMode == NeoTenBitDitherMode.DYNAMIC_BLUE_NOISE_TEMPORAL ->
                PerceptualSignalMath.dynamicBlueNoiseTemporalDitherFloor(
                    baseFloor = tenBitRenderPath.ditherFloor,
                    signal = ditherSignal,
                    motionScore = motionFactor,
                    hdrActive = hdrFactor > 0f || isVideoHdrMode
                )
            !effectiveSimulatedTenBitFallback -> 0f
            isLogProfile -> logLook.simulatedTenBitDitherFloor
            else -> 0f
        }
        // ── HDR FUSION: Only active in HDR mode, strong enough to look HDR ──
        var videoHdrFusionStrength = if (activeVideoHdrLook) {
            (
                0.82f +
                    hdrFactor * 0.92f +
                    scene.dynamicRangeScore * 0.52f +
                    scene.highlightRatio * 0.18f -
                    motionFactor * 0.06f
                ).coerceIn(0.65f, 1.0f)
        } else {
            0f
        }

        val recommendationTone = recommendation.localToneMapStrength.coerceIn(0f, 1f)
        val recommendationShadow = recommendation.shadowDenoiseStrength.coerceIn(0f, 1f)
        val recommendationSpatialFloor = recommendation.spatialDenoiseFloor.coerceIn(0.14f, 0.98f)
        val recommendationDetail = recommendation.detailPreservationStrength.coerceIn(0f, 1f)
        val recommendationSky = recommendation.skyProtectionStrength.coerceIn(0f, 1f)
        val hdrFrameDensityBoost =
            ((recommendation.resolvedHdrFrameCount - 3).coerceAtLeast(0).toFloat() / 8f)
                .coerceIn(0f, 1f)

        temporalBlend = (
            temporalBlend +
                recommendationShadow * 0.05f -
                recommendationDetail * 0.025f
            ).coerceIn(0.03f, 0.62f)
        spatialDenoise = (
            spatialDenoise +
                recommendationShadow * 0.14f -
                recommendationDetail * 0.03f
            ).coerceIn(0.14f, 0.96f)
        spatialDenoise = maxOf(spatialDenoise, recommendationSpatialFloor)
        chromaDenoise = (
            chromaDenoise +
                recommendationShadow * 0.10f +
                recommendationTone * 0.04f
            ).coerceIn(0.24f, 0.98f)
        shadowBoost = (
            shadowBoost +
                recommendationShadow * 0.16f +
                recommendationTone * 0.06f
            ).coerceIn(0.10f, 0.92f)
        sharpening = (
            sharpening +
                recommendationDetail * 0.08f -
                recommendationShadow * 0.02f
            ).coerceIn(0.08f, 0.55f)
        highlightCompression = (
            highlightCompression +
                recommendationTone * 0.14f +
                recommendationSky * 0.18f +
                hdrFrameDensityBoost * 0.03f
            ).coerceIn(0.07f, 0.72f)
        contrast = (
            contrast -
                recommendationTone * 0.08f -
                recommendationSky * 0.04f
            ).coerceIn(0.58f, 1.15f)
        if (!isLogProfile) {
            saturation = (
                saturation +
                    recommendationDetail * 0.04f -
                    recommendationTone * 0.05f
                ).coerceIn(0.56f, 1.08f)
        }
        if (isGeneralColorProfile) {
            val baselineDenoiseBoost = when {
                lowLightFactor > 0.6f -> 0.04f
                brightNoiseRisk > 0.35f -> 0.03f
                else -> 0.015f
            }
            spatialDenoise = (spatialDenoise + baselineDenoiseBoost).coerceIn(0.12f, 0.68f)
            chromaDenoise = (
                chromaDenoise +
                    baselineDenoiseBoost * (if (isRec2020FamilyProfile) 1.15f else 1.0f)
                ).coerceIn(0.20f, 0.72f)
            val detailBias =
                (scene.edgeDensity * 0.045f - brightNoiseRisk * 0.015f).coerceIn(-0.008f, 0.035f)
            sharpening = (sharpening + detailBias).coerceIn(0.10f, 0.48f)
            val contrastFloor = when {
                isRec2020FamilyProfile && hdrFactor > 0f -> 0.96f
                hdrFactor > 0f -> 1.0f
                else -> 0.98f
            }
            contrast = contrast.coerceAtLeast(contrastFloor)
            val saturationFloor = if (isRec2020FamilyProfile) 0.98f else 1.0f
            saturation = saturation.coerceAtLeast(saturationFloor)
            val generalHighlightLift = recommendationSky * 0.015f +
                if (hdrFactor > 0f && !scene.highDynamicRange) 0.01f else 0f
            highlightCompression = (
                highlightCompression +
                    generalHighlightLift
                ).coerceIn(0.06f, 0.62f)
        }
        if (recommendation.wbLockSuggested && !isLogProfile && lowLightFactor > 0.6f) {
            warmth += 0.006f
        }
        if (recommendation.shouldUseHdrFusion) {
            logStrength = maxOf(logStrength, 0.12f + recommendationTone * 0.18f)
            if (isLogProfile && effectiveSimulatedTenBitFallback) {
                ditherStrength = maxOf(ditherStrength, logLook.simulatedTenBitDitherFloor)
            }
        }
        if (brightNoiseRisk > 0f) {
            spatialDenoise = (spatialDenoise + brightNoiseRisk * 0.05f).coerceIn(0.12f, 0.70f)
            chromaDenoise = (chromaDenoise + brightNoiseRisk * 0.08f).coerceIn(0.20f, 0.72f)
            sharpening = (
                sharpening -
                    brightNoiseRisk * 0.008f +
                    motionFactor * 0.006f
                ).coerceIn(0.08f, 0.40f)
            if (brightLightFactor > 0.45f && flatRegionFactor > 0.40f) {
                highlightCompression = (highlightCompression + brightNoiseRisk * 0.03f).coerceIn(0.07f, 0.52f)
            }
        }

        // ── HDR MODE: neutral BT.2100 transport, highlight rolloff, controlled chroma ──────────────
        if (hdrFactor > 0f && !isLogProfile) {
            contrast = contrast.coerceIn(0.92f, 1.02f)
            saturation = saturation.coerceIn(0.92f, 1.04f)
            highlightCompression = maxOf(highlightCompression, 0.22f)
            shadowBoost = (shadowBoost + 0.006f).coerceAtMost(0.42f)
            sharpening = maxOf(sharpening, 0.10f)
            if (!isLogProfile) {
                ditherStrength = 0f
            }
            // Do NOT inject logStrength into HDR — HDR is not LOG
        }

        if (activeVideoHdrLook) {
            contrast = contrast.coerceIn(0.94f, 1.02f)
            saturation = saturation.coerceIn(0.94f, 1.04f)
            highlightCompression = maxOf(highlightCompression, 0.30f)
            shadowBoost = maxOf(shadowBoost, 0.28f).coerceAtMost(0.42f)
            sharpening = sharpening.coerceIn(0.10f, 0.35f)
            temporalBlend = minOf(temporalBlend, 0.18f)
            spatialDenoise = spatialDenoise.coerceIn(0.15f, 0.62f)
            chromaDenoise = chromaDenoise.coerceIn(0.22f, 0.70f)
            // Do NOT inject logStrength — HDR preview should look HDR, not LOG
            if (!isLogProfile) {
                ditherStrength = 0f
            }
        }

        if (isNeoCinemaProfile) {
            highlightCompression = maxOf(highlightCompression, 0.24f)
            sharpening = minOf(sharpening, 0.10f)
            spatialDenoise = minOf(spatialDenoise, 0.30f)
            chromaDenoise = minOf(chromaDenoise, 0.44f)
            temporalBlend = minOf(temporalBlend, 0.26f)
        }

        if (isLogProfile) {
            contrast = minOf(contrast, logLook.contrastCap)
            saturation = minOf(saturation, logLook.saturationCap)
            warmth *= logLook.warmthMultiplier
            highlightCompression = maxOf(highlightCompression, logLook.highlightCompressionFloor)
            shadowBoost = maxOf(shadowBoost, logLook.shadowBoostFloor)
            sharpening = minOf(sharpening, logLook.sharpeningCap)
            sharpening = maxOf(sharpening, minOf(logLook.sharpeningCap, 0.04f))
            temporalBlend = minOf(temporalBlend, logLook.temporalBlendCap)
            spatialDenoise = minOf(spatialDenoise, logLook.spatialDenoiseCap)
            chromaDenoise = minOf(chromaDenoise, logLook.chromaDenoiseCap)
            logStrength = maxOf(logStrength, logLook.logStrengthFloor)
            
            // Dynamic temporal dither keeps 8-bit fallback preview closer to the requested log look.
            ditherStrength = maxOf(
                ditherStrength,
                if (effectiveSimulatedTenBitFallback) {
                    PerceptualSignalMath.dynamicBlueNoiseTemporalDitherFloor(
                        baseFloor = logLook.simulatedTenBitDitherFloor,
                        signal = ditherSignal,
                        motionScore = motionFactor,
                        hdrActive = true
                    )
                } else {
                    logLook.nativeTenBitDitherFloor
                }
            )
        }
        if (isLogProfile && isNaturalProfile) {
            sharpening = sharpening.coerceIn(0.05f, 0.08f)
        }
        if (isNeoCinemaProfile && isLogProfile) {
            warmth = 0.026f
            contrast = minOf(contrast, 0.88f)
            saturation = minOf(saturation, 0.84f)
            highlightCompression = maxOf(highlightCompression, 0.27f)
            shadowBoost = maxOf(shadowBoost, 0.20f)
            sharpening = minOf(sharpening, 0.07f)
            spatialDenoise = minOf(spatialDenoise, 0.28f)
            chromaDenoise = minOf(chromaDenoise, 0.40f)
        }

        if (lutEnabled) {
            when {
                lutKey.contains("neutral") -> {
                    contrast = minOf(contrast, 0.96f)
                    saturation = minOf(saturation, 0.94f)
                    highlightCompression = maxOf(highlightCompression, 0.17f)
                }
                lutKey.contains("warm") -> {
                    warmth += 0.018f
                    contrast = minOf(contrast, 0.94f)
                    saturation = minOf(saturation, 0.92f)
                    highlightCompression = maxOf(highlightCompression, 0.19f)
                }
                lutKey.contains("teal") -> {
                    warmth -= 0.012f
                    contrast = minOf(contrast, 0.92f)
                    saturation = minOf(saturation, 0.95f)
                    highlightCompression = maxOf(highlightCompression, 0.21f)
                }
            }
        }

        val aiEnhancementActive = aiEnhancementEnabled || strictHighEnd
        if (aiEnhancementActive) {
            val aiHighlightCompressionCap = (
                0.46f +
                    recommendationTone * 0.12f +
                    recommendationSky * 0.12f +
                    hdrFrameDensityBoost * 0.08f
                ).coerceIn(0.46f, 0.68f)
            temporalBlend = (
                temporalBlend +
                    lowLightFactor * 0.04f +
                    hdrFactor * 0.015f +
                    brightNoiseRisk * 0.015f
                ).coerceIn(0.04f, 0.45f)
            spatialDenoise = (
                spatialDenoise +
                    lowLightFactor * 0.04f +
                    brightNoiseRisk * 0.02f
                ).coerceIn(0.12f, 0.68f)
            chromaDenoise = (
                chromaDenoise +
                    0.02f +
                    hdrFactor * 0.015f +
                    brightNoiseRisk * 0.02f
                ).coerceIn(0.18f, 0.72f)
            shadowBoost = (shadowBoost + 0.03f + scene.shadowRatio * 0.02f).coerceIn(0.10f, 0.78f)
            highlightCompression = (
                highlightCompression +
                    0.018f +
                    hdrFactor * 0.008f +
                    brightNoiseRisk * 0.006f
                ).coerceIn(0.07f, aiHighlightCompressionCap)
            warmth += if (isNeoCinemaProfile) 0.004f else 0.006f
            if (activeVideoHdrLook) {
                highlightCompression = (highlightCompression + 0.008f).coerceIn(0.09f, 0.42f)
                shadowBoost = (shadowBoost + 0.006f).coerceIn(0.12f, 0.42f)
            }
            if (!isLogProfile && (hdrEnabled || isVideoHdrMode || activeVideoHdrLook)) {
                saturation = saturation.coerceIn(0.90f, 1.04f)
                contrast = contrast.coerceIn(0.86f, 1.02f)
            } else if (!isLogProfile) {
                saturation = (saturation + 0.02f).coerceAtMost(1.18f)
                contrast = (contrast * 1.005f).coerceAtLeast(0.85f)
            }
        } else {
            temporalBlend = (temporalBlend * 0.88f).coerceIn(0.02f, 0.48f)
            spatialDenoise = (spatialDenoise * 0.86f).coerceIn(0.10f, 0.78f)
            chromaDenoise = (chromaDenoise * 0.86f).coerceIn(0.18f, 0.80f)
            shadowBoost = (shadowBoost * 0.86f).coerceIn(0.06f, 0.76f)
            highlightCompression = (highlightCompression * 0.90f).coerceIn(0.04f, 0.48f)
            warmth *= 0.82f
            if (!isLogProfile) {
                saturation = (saturation * 0.98f).coerceIn(0.56f, 1.0f)
                contrast = (contrast * 1.01f).coerceIn(0.58f, 1.08f)
            }
        }

        when {
            hdrModeKey.contains("pro") -> {
                shadowBoost = (shadowBoost + 0.05f).coerceAtMost(0.80f)
                highlightCompression = maxOf(highlightCompression, 0.30f)
                val proHdrFilmicLook =
                    lutKey.contains("warm") ||
                        profileKey.contains("cinem") ||
                        isNeoCinemaProfile
                contrast =
                    if (proHdrFilmicLook) {
                        minOf(contrast, 0.88f)
                    } else {
                        maxOf(contrast, 0.98f)
                    }
                // Do NOT inject logStrength — HDR Pro is not LOG
            }
            hdrModeKey.contains("smart") -> {
                shadowBoost = (shadowBoost + 0.02f).coerceAtMost(0.76f)
                highlightCompression = maxOf(highlightCompression, 0.21f)
                contrast = maxOf(contrast, 0.98f)
            }
        }

        when (runtimeTier) {
            HighEndRuntimeTier.CINEMA -> {
                temporalBlend = (temporalBlend + 0.02f).coerceIn(0.06f, 0.50f)
                spatialDenoise = (spatialDenoise + 0.02f).coerceIn(0.14f, 0.70f)
                chromaDenoise = (chromaDenoise + 0.02f).coerceIn(0.20f, 0.72f)
                shadowBoost = (shadowBoost + 0.04f).coerceIn(0.12f, 0.98f)
                val cinemaSharpenFloor = if (isLogProfile) 0.05f else 0.10f
                sharpening = (sharpening + 0.025f).coerceIn(cinemaSharpenFloor, 0.55f)
                highlightCompression = (highlightCompression + 0.015f).coerceIn(0.08f, 0.72f)
                // Do NOT inject logStrength here — respect mode selection
                if (isLogProfile && effectiveSimulatedTenBitFallback) {
                    ditherStrength = maxOf(ditherStrength, logLook.simulatedTenBitDitherFloor)
                }
                videoHdrFusionStrength = (videoHdrFusionStrength + 0.08f).coerceIn(0f, 1.0f)
            }
            HighEndRuntimeTier.ULTRA -> {
                temporalBlend = (temporalBlend + 0.015f).coerceIn(0.05f, 0.48f)
                spatialDenoise = (spatialDenoise + 0.02f).coerceIn(0.12f, 0.68f)
                chromaDenoise = (chromaDenoise + 0.015f).coerceIn(0.18f, 0.70f)
                shadowBoost = (shadowBoost + 0.02f).coerceIn(0.10f, 0.90f)
                val ultraSharpenFloor = if (isLogProfile) 0.05f else 0.10f
                sharpening = (sharpening + 0.018f).coerceIn(ultraSharpenFloor, 0.48f)
                highlightCompression = (highlightCompression + 0.012f).coerceIn(0.08f, 0.58f)
                // Do NOT inject logStrength
                if (isLogProfile && effectiveSimulatedTenBitFallback) {
                    ditherStrength = maxOf(ditherStrength, logLook.simulatedTenBitDitherFloor)
                }
                videoHdrFusionStrength = (videoHdrFusionStrength + 0.04f).coerceIn(0f, 0.96f)
            }
            HighEndRuntimeTier.BALANCED -> Unit
            HighEndRuntimeTier.SUSTAINED -> {
                temporalBlend = (temporalBlend * 0.96f).coerceIn(0.03f, 0.48f)
                spatialDenoise = (spatialDenoise * 0.96f).coerceIn(0.14f, 0.84f)
                chromaDenoise = (chromaDenoise * 0.96f).coerceIn(0.22f, 0.88f)
                sharpening = (sharpening * 0.94f).coerceIn(0.03f, 0.16f)
                highlightCompression = (highlightCompression * 0.97f).coerceIn(0.08f, 0.52f)
                videoHdrFusionStrength = (videoHdrFusionStrength * 0.94f).coerceIn(0f, 0.90f)
            }
            HighEndRuntimeTier.RECOVERY -> {
                temporalBlend = (temporalBlend * 0.90f).coerceIn(0.02f, 0.44f)  
                spatialDenoise = (spatialDenoise * 0.90f).coerceIn(0.12f, 0.78f)
                chromaDenoise = (chromaDenoise * 0.90f).coerceIn(0.20f, 0.82f)  
                sharpening = (sharpening * 0.88f).coerceIn(0.02f, 0.14f)        
                highlightCompression = (highlightCompression * 0.93f).coerceIn(0.06f, 0.44f)
                videoHdrFusionStrength = (videoHdrFusionStrength * 0.90f).coerceIn(0f, 0.84f)
            }
            else -> Unit
        }

        if (strictHighEnd) {
            val strictBrightNoiseBoost =
                brightNoiseRisk * 0.05f + if (brightLightFactor > 0.45f) 0.015f else 0f
            temporalBlend = (
                temporalBlend +
                    0.02f +
                    brightNoiseRisk * 0.015f
                ).coerceIn(0.08f, 0.52f)
            spatialDenoise = (
                spatialDenoise +
                    0.02f +
                    strictBrightNoiseBoost
                ).coerceIn(0.18f, 0.72f)
            chromaDenoise = (
                chromaDenoise +
                    0.02f +
                    strictBrightNoiseBoost * 1.0f
                ).coerceIn(0.24f, 0.75f)
            shadowBoost = (shadowBoost + 0.04f).coerceIn(0.18f, 0.98f)
            highlightCompression = (
                highlightCompression +
                    0.018f +
                    brightNoiseRisk * 0.01f
                ).coerceIn(0.14f, 0.82f)
            // CRITICAL: Do NOT inject logStrength into non-LOG modes
            if (isLogProfile) {
                logStrength = maxOf(logStrength, 0.28f)
            }
            if (isLogProfile && effectiveSimulatedTenBitFallback) {
                ditherStrength = maxOf(ditherStrength, logLook.simulatedTenBitDitherFloor)
            } else if (!isLogProfile) {
                ditherStrength = 0f
            }
            videoHdrFusionStrength = (videoHdrFusionStrength + 0.06f).coerceIn(0f, 1.0f)
            if (isLogProfile) {
                contrast = contrast.coerceIn(0.48f, 0.78f)    // LOG: stay flat
                saturation = saturation.coerceIn(0.52f, 0.82f) // LOG: stay desaturated
                sharpening = if (strictHighEnd) {
                    sharpening.coerceIn(0.40f, 0.56f)
                } else {
                    sharpening.coerceIn(0.06f, 0.22f)
                }
                highlightCompression = highlightCompression.coerceAtLeast(0.38f)
            } else {
                val preserveCinematicProfileLook =
                    isNeoCinemaProfile ||
                        lutKey.contains("warm")
                if (preserveCinematicProfileLook) {
                    sharpening = sharpening.coerceIn(0.05f, 0.16f)
                    contrast = contrast.coerceAtMost(0.94f)
                    saturation = saturation.coerceAtMost(0.92f)
                } else if (hdrEnabled || isVideoHdrMode || activeVideoHdrLook) {
                    sharpening = (
                        sharpening +
                            0.016f +
                            recommendationDetail * 0.012f
                        ).coerceIn(0.10f, 0.42f)
                    contrast = contrast.coerceIn(0.86f, 1.03f)
                    saturation = saturation.coerceIn(0.90f, 1.04f)
                } else {
                    sharpening = (
                        sharpening +
                            0.02f +
                            recommendationDetail * 0.015f
                        ).coerceIn(0.12f, 0.52f)
                    contrast = contrast.coerceIn(0.90f, 1.28f)
                    saturation = saturation.coerceIn(0.94f, 1.28f)
                }
            }
            spatialDenoise = maxOf(spatialDenoise, recommendationSpatialFloor.coerceAtMost(0.68f))
            chromaDenoise = maxOf(chromaDenoise, (recommendationSpatialFloor * 0.95f).coerceIn(0.24f, 0.72f))
            if (recording) {
                val retainRecordingTexture =
                    scene.normalizedNoiseFloor < 0.18f &&
                        scene.flatRegionStdDev < 3.0f &&
                        scene.realIsoNoiseFloor < 800f &&
                        motionFactor < 0.18f &&
                        scene.edgeDensity >= 0.10f
                val staticness = (1f - motionFactor.coerceIn(0f, 1f)).coerceIn(0f, 1f)
                val staticGate = ((staticness - 0.5f) * 2f).coerceIn(0f, 1f)
                val temporalCeiling = when {
                    motionFactor >= 0.50f -> 0.54f
                    motionFactor >= 0.30f -> 0.54f
                    else -> 0.72f
                }
                val temporalFloor = (0.48f + staticGate * 0.24f).coerceIn(0.48f, temporalCeiling)
                val spatialFloor = if (retainRecordingTexture) {
                    (0.30f + recommendationSpatialFloor * 0.24f).coerceIn(0.30f, 0.54f)
                } else {
                    (
                        0.74f +
                            staticness * 0.08f
                        ).coerceIn(0.74f, 0.82f)
                }
                val chromaFloor = if (retainRecordingTexture) 0.44f else 0.99f
                temporalBlend = maxOf(temporalBlend, temporalFloor)
                spatialDenoise = maxOf(spatialDenoise, maxOf(spatialFloor, recommendationSpatialFloor))
                chromaDenoise = maxOf(chromaDenoise, chromaFloor)
                sharpening = maxOf(
                    sharpening,
                    (0.12f + recommendationDetail * 0.06f).coerceAtMost(0.42f)
                )
            }
        }
        if (rgbSpeckleRisk > 0.66f) {
            val staticness = (1f - motionFactor.coerceIn(0f, 1f)).coerceIn(0f, 1f)
            val extremeIsoPressure = ((scene.realIsoNoiseFloor - 12_800f) / 48_000f).coerceIn(0f, 1f)
            val chromaFloor = (
                0.72f +
                    rgbSpeckleRisk * 0.16f +
                    extremeIsoPressure * 0.12f
                ).coerceIn(0.72f, 0.98f)
            val spatialFloor = (
                0.50f +
                    rgbSpeckleRisk * 0.22f +
                    extremeIsoPressure * 0.08f
                ).coerceIn(0.50f, 0.82f)
            val temporalFloor = (
                0.18f +
                    rgbSpeckleRisk * 0.18f * staticness
                ).coerceIn(0.18f, 0.44f)
            val sharpeningCeiling = (
                0.34f -
                    rgbSpeckleRisk * 0.10f -
                    extremeIsoPressure * 0.08f
                ).coerceIn(0.12f, 0.28f)

            temporalBlend = maxOf(temporalBlend, temporalFloor)
            spatialDenoise = maxOf(spatialDenoise, spatialFloor)
            chromaDenoise = maxOf(chromaDenoise, chromaFloor)
            sharpening = minOf(sharpening, sharpeningCeiling)
            if (!isLogProfile) {
                saturation = minOf(
                    saturation,
                    (1.04f - rgbSpeckleRisk * 0.08f - extremeIsoPressure * 0.04f).coerceIn(0.88f, 1.04f)
                )
                contrast = minOf(
                    contrast,
                    (1.10f - rgbSpeckleRisk * 0.10f - extremeIsoPressure * 0.04f).coerceIn(0.82f, 1.08f)
                )
                ditherStrength = 0f
            }
        }
        if ((hdrEnabled || isVideoHdrMode || activeVideoHdrLook) && !isLogProfile) {
            ditherStrength = 0f
            chromaDenoise = maxOf(
                chromaDenoise,
                when {
                    rgbSpeckleRisk > 0.66f -> 0.92f
                    effectiveSimulatedTenBitFallback -> 0.60f
                    else -> 0.52f
                }
            )
            spatialDenoise = maxOf(
                spatialDenoise,
                when {
                    rgbSpeckleRisk > 0.66f -> 0.70f
                    effectiveSimulatedTenBitFallback -> 0.40f
                    else -> 0.32f
                }
            )
        }
        if (tenBitRenderPath.useNativeHardwareBuffer) {
            ditherStrength = 0f
        }
        if (recording) {
            ditherStrength = 0f
            if (rgbSpeckleRisk > 0.50f || scene.normalizedNoiseFloor > 0.55f || scene.realIsoNoiseFloor > 1600f) {
                chromaDenoise = maxOf(chromaDenoise, 0.96f)
                spatialDenoise = maxOf(spatialDenoise, 0.78f)
                sharpening = minOf(sharpening, 0.22f)
            }
        }
        val zeroNoisePressure = maxOf(
            lowLightFactor,
            scene.normalizedNoiseFloor.coerceIn(0f, 1f),
            ((scene.realIsoNoiseFloor - 1_600f) / 32_000f).coerceIn(0f, 1f),
            rgbSpeckleRisk
        )
        val zeroNoiseActive =
            scene.estimatedLux <= 12f ||
                scene.realIsoNoiseFloor >= 3_200f ||
                (recording && scene.normalizedNoiseFloor >= 0.34f) ||
                (recording && scene.flatRegionStdDev >= 7.0f) ||
                (recording && zeroNoisePressure >= 0.58f) ||
                (recording && scene.realIsoNoiseFloor >= 1_600f)
        if (zeroNoiseActive) {
            val staticness = (1f - motionFactor.coerceIn(0f, 1f)).coerceIn(0f, 1f)
            val chromaFloor = (
                0.58f +
                    zeroNoisePressure * 0.27f +
                    if (recording) 0.03f else 0f
                ).coerceIn(0.56f, 0.88f)
            val spatialFloor = (
                0.42f +
                    zeroNoisePressure * 0.28f +
                    staticness * 0.03f +
                    if (recording) 0.04f else 0f
                ).coerceIn(0.40f, 0.78f)
            val temporalCeiling = when {
                motionFactor >= 0.50f -> 0.54f
                motionFactor >= 0.30f -> 0.60f
                else -> 0.72f
            }
            val temporalFloor = (
                0.30f +
                    zeroNoisePressure * 0.18f * staticness +
                    if (recording) 0.075f * staticness else 0f
                ).coerceIn(0.22f, temporalCeiling)
            val sharpeningCeiling = if (strictHighEnd) {
                (
                    0.88f -
                        zeroNoisePressure * 0.045f -
                        if (recording) 0.010f else 0f
                ).coerceIn(0.48f, 0.88f)
            } else {
                (
                    0.30f -
                        zeroNoisePressure * 0.045f -
                        if (recording) 0.010f else 0f
                ).coerceIn(0.18f, 0.30f)
            }
            val edgeAwareSharpeningFloor = if (strictHighEnd) {
                (
                    0.48f +
                        recommendationDetail * 0.08f +
                        scene.edgeDensity * 0.115f -
                        zeroNoisePressure * 0.014f -
                        motionFactor * 0.018f
                ).coerceIn(0.48f, sharpeningCeiling)
            } else {
                (
                    0.17f +
                        recommendationDetail * 0.08f +
                        scene.edgeDensity * 0.115f -
                        zeroNoisePressure * 0.014f -
                        motionFactor * 0.018f
                ).coerceIn(0.17f, sharpeningCeiling)
            }

            temporalBlend = maxOf(temporalBlend, temporalFloor)
            spatialDenoise = maxOf(spatialDenoise, spatialFloor)
            chromaDenoise = maxOf(chromaDenoise, chromaFloor)
            sharpening = maxOf(minOf(sharpening, sharpeningCeiling), edgeAwareSharpeningFloor)
            ditherStrength = 0f
        }
        if (recording && !zeroNoiseActive) {
            val isoCleanFactor = (1f - ((scene.realIsoNoiseFloor - 400f) / 3_600f).coerceIn(0f, 1f))
            val signalCleanliness = (
                (1f - scene.normalizedNoiseFloor.coerceIn(0f, 1f)) * 0.48f +
                    (1f - (scene.flatRegionStdDev / 10f).coerceIn(0f, 1f)) * 0.28f +
                    isoCleanFactor * 0.24f
                ).coerceIn(0f, 1f)
            val edgeConfidence = (
                (scene.edgeDensity / 0.30f).coerceIn(0f, 1f) * 0.44f +
                    recommendationDetail * 0.34f +
                    scene.perceptualSharpness.coerceIn(0f, 1f) * 0.22f
                ).coerceIn(0f, 1f)
            if (signalCleanliness >= 0.54f && edgeConfidence >= 0.42f) {
                val modeFloor = when {
                    isLogProfile -> 0.16f
                    isRawMode -> 0.22f
                    hdrEnabled || isVideoHdrMode || activeVideoHdrLook -> 0.21f
                    else -> 0.22f
                }
                val modeCeiling = when {
                    isLogProfile -> 0.28f
                    isRawMode -> 0.36f
                    hdrEnabled || isVideoHdrMode || activeVideoHdrLook -> 0.35f
                    else -> 0.36f
                }
                val detailLift = (
                    modeFloor +
                        (edgeConfidence - 0.42f) * 0.10f +
                        signalCleanliness * 0.018f -
                        motionFactor * 0.025f
                    ).coerceIn(modeFloor, modeCeiling)
                sharpening = maxOf(sharpening, detailLift).coerceAtMost(modeCeiling)
            }
        }
        computationalCinemaPlan?.let { plan ->
            val spatialCeiling = if (zeroNoiseActive) 0.992f else 0.96f
            val chromaCeiling = if (zeroNoiseActive) 0.9995f else 0.99f
            temporalBlend = maxOf(temporalBlend, plan.temporalDenoiseStrength).coerceIn(0f, 0.86f)
            spatialDenoise = maxOf(spatialDenoise, plan.spatialDenoiseStrength).coerceIn(0f, spatialCeiling)
            chromaDenoise = maxOf(chromaDenoise, plan.chromaDenoiseStrength).coerceIn(0f, chromaCeiling)
            sharpening = minOf(sharpening, plan.sharpeningStrength).coerceIn(0.02f, 0.55f)
            highlightCompression = maxOf(
                highlightCompression,
                0.18f + plan.localContrastStrength * 0.22f
            ).coerceIn(0.06f, 0.82f)
            if (!isLogProfile) {
                contrast = maxOf(
                    contrast,
                    0.96f + plan.localContrastStrength * 0.22f
                ).coerceIn(0.58f, 1.28f)
                saturation = minOf(
                    maxOf(saturation, 0.94f + plan.lutStrength * 0.08f),
                    1.16f
                )
            }
        }
        if ((hdrEnabled || isVideoHdrMode || activeVideoHdrLook) && !isLogProfile) {
            var versionProfile = hdrVersionRenderProfile
            if (versionProfile != null) {
                val cinematicValue = when (videoDetailProfile) {
                    VideoDetailProfile.FOCUSED_DETAIL -> 0.95f
                    VideoDetailProfile.NATURAL_DETAIL -> 0.50f
                    else -> 0.30f
                }
                versionProfile = versionProfile.scaleForFocus(cinematicValue)
                if (isNeoCinemaProfile) {
                    contrast = contrast.coerceAtMost(versionProfile.contrastMax)
                    saturation = saturation.coerceAtMost(versionProfile.saturationMax)
                } else {
                    contrast = contrast.coerceIn(versionProfile.contrastMin, versionProfile.contrastMax)
                    saturation = saturation.coerceIn(versionProfile.saturationMin, versionProfile.saturationMax)
                }
                shadowBoost = shadowBoost.coerceAtMost(versionProfile.shadowBoostMax)
                highlightCompression = maxOf(
                    highlightCompression,
                    versionProfile.highlightCompressionFloor
                ).coerceIn(0.18f, 0.62f)
                videoHdrFusionStrength = maxOf(
                    videoHdrFusionStrength,
                    versionProfile.fusionFloor
                ).coerceIn(0f, 1f)
                spatialDenoise = maxOf(spatialDenoise, versionProfile.spatialDenoiseFloor)
                chromaDenoise = maxOf(chromaDenoise, versionProfile.chromaDenoiseFloor)
            } else {
                contrast = contrast.coerceIn(0.86f, 1.04f)
                saturation = saturation.coerceIn(0.90f, 1.04f)
                shadowBoost = shadowBoost.coerceAtMost(if (recording) 0.58f else 0.42f)
                highlightCompression = highlightCompression.coerceIn(0.18f, 0.62f)
            }
        }
        val strictCinemaNoisePassActive = recording ||
            scene.estimatedLux <= 12f ||
            scene.normalizedNoiseFloor >= 0.34f ||
            scene.flatRegionStdDev >= 7.0f ||
            scene.realIsoNoiseFloor >= 1_600f
        if (strictHighEnd && strictCinemaNoisePassActive) {
            val cinemaPlan = CinemaHighEndPipelinePolicy.resolve(
                CinemaHighEndPipelineRequest(
                    width = outputWidth,
                    height = outputHeight,
                    fps = previewFps.toInt().coerceAtLeast(24),
                    recording = recording,
                    mode = when {
                        isRawMode -> CinemaHighEndCaptureMode.RAW
                        isLogProfile -> CinemaHighEndCaptureMode.LOG
                        hdrEnabled || isVideoHdrMode || activeVideoHdrLook -> CinemaHighEndCaptureMode.HDR
                        else -> CinemaHighEndCaptureMode.PRO
                    },
                    estimatedLux = scene.estimatedLux,
                    motionScore = scene.motionScore,
                    normalizedNoiseFloor = scene.normalizedNoiseFloor,
                    flatRegionStdDev = scene.flatRegionStdDev,
                    realIso = scene.realIsoNoiseFloor,
                    edgeDensity = scene.edgeDensity,
                    perceptualSharpness = scene.perceptualSharpness,
                    highEndEnabled = HighEndQualityPolicy.enabled,
                    strictHighEnd = strictHighEnd
                )
            )
            temporalBlend = maxOf(temporalBlend, cinemaPlan.temporalDenoise)
            spatialDenoise = maxOf(spatialDenoise, cinemaPlan.spatialDenoise)
            chromaDenoise = maxOf(chromaDenoise, cinemaPlan.chromaDenoise)
            shadowBoost = maxOf(shadowBoost, cinemaPlan.shadowLift)
            highlightCompression = maxOf(highlightCompression, cinemaPlan.highlightRecovery)
            sharpening = sharpening.coerceIn(cinemaPlan.sharpeningFloor, cinemaPlan.sharpeningCeiling)
            if (cinemaPlan.disableSyntheticDither) {
                ditherStrength = 0f
            }
        }
        if (recording) {
            val temporalMotionCeiling = when {
                motionFactor >= 0.50f -> 0.54f
                motionFactor >= 0.30f -> 0.54f
                strictHighEnd || computationalCinemaPlan != null -> 0.92f
                else -> 0.72f
            }
            temporalBlend = temporalBlend.coerceAtMost(temporalMotionCeiling)
        }
        val phase4SignalFamily = when {
            isRawMode -> VideoImageSignalFamily.RAW
            isLogProfile && activeVideoHdrLook && hdrModeKey == "hlg" && isNeoCinemaProfile ->
                VideoImageSignalFamily.HDR
            isLogProfile -> VideoImageSignalFamily.LOG
            hdrEnabled || isVideoHdrMode || activeVideoHdrLook -> VideoImageSignalFamily.HDR
            else -> VideoImageSignalFamily.SDR
        }
        val phase4Plan = VideoImageQualityPipelinePolicy.resolve(
            VideoImageQualityPipelineRequest(
                signalFamily = phase4SignalFamily,
                recording = recording,
                width = outputWidth,
                height = outputHeight,
                fps = previewFps.toInt().coerceAtLeast(24),
                estimatedLux = scene.estimatedLux,
                motionScore = scene.motionScore,
                normalizedNoiseFloor = scene.normalizedNoiseFloor,
                flatRegionStdDev = scene.flatRegionStdDev,
                realIsoNoiseFloor = scene.realIsoNoiseFloor,
                edgeDensity = scene.edgeDensity,
                perceptualSharpness = scene.perceptualSharpness,
                dynamicRangeScore = scene.dynamicRangeScore,
                highlightRatio = scene.highlightRatio,
                strictHighEnd = strictHighEnd,
                videoDetailProfile = videoDetailProfile
            )
        )
        temporalBlend = maxOf(temporalBlend, phase4Plan.temporalDenoiseFloor)
            .coerceAtMost(phase4Plan.temporalDenoiseCeiling)
        spatialDenoise = maxOf(spatialDenoise, phase4Plan.spatialDenoiseFloor)
        chromaDenoise = maxOf(chromaDenoise, phase4Plan.chromaDenoiseFloor)
        sharpening = sharpening.coerceIn(phase4Plan.sharpeningFloor, phase4Plan.sharpeningCeiling)
        val preserveCinematicProfileColor =
            isNeoCinemaProfile &&
                phase4SignalFamily != VideoImageSignalFamily.HDR
        contrast = if (preserveCinematicProfileColor) {
            contrast.coerceAtMost(phase4Plan.contrastCeiling)
        } else {
            contrast.coerceIn(phase4Plan.contrastFloor, phase4Plan.contrastCeiling)
        }
        saturation = if (preserveCinematicProfileColor) {
            saturation.coerceAtMost(phase4Plan.saturationCeiling)
        } else {
            saturation.coerceIn(phase4Plan.saturationFloor, phase4Plan.saturationCeiling)
        }
        shadowBoost = shadowBoost.coerceAtMost(phase4Plan.shadowBoostCeiling)
        highlightCompression = maxOf(highlightCompression, phase4Plan.highlightCompressionFloor)
        videoHdrFusionStrength = videoHdrFusionStrength
            .coerceIn(phase4Plan.videoHdrFusionFloor, phase4Plan.videoHdrFusionCeiling)
        logStrength = if (phase4Plan.logTransferRequired) {
            maxOf(logStrength, phase4Plan.logStrengthFloor)
        } else {
            0f
        }
        ditherStrength = ditherStrength.coerceAtMost(phase4Plan.ditherCeiling)
        val noiseMetrics = VideoNoiseMetrics.fromScene(scene)
        val noisePlan = VideoNoiseReductionPolicy.resolve(
            VideoNoiseReductionRequest(
                signalFamily = phase4SignalFamily,
                recording = recording,
                lumaLevel = scene.averageLuma,
                estimatedIso = scene.realIsoNoiseFloor,
                motionScore = scene.motionScore,
                skinToneConfidence = 0f,
                textureConfidence = (scene.edgeDensity / 0.32f).coerceIn(0f, 1f),
                edgeDensity = scene.edgeDensity,
                metrics = noiseMetrics,
                strictHighEnd = strictHighEnd,
                aiMotionCompensation = AiMotionCompensationRuntimeHints.requested(aiMotionCompensationEnabled),
                videoDetailProfile = videoDetailProfile
            )
        )
        temporalBlend = if (noisePlan.motionProtection >= 0.75f) {
            minOf(temporalBlend, noisePlan.temporalBlend)
                .coerceAtMost(noisePlan.motionSafeTemporalCeiling)
        } else {
            maxOf(temporalBlend, noisePlan.temporalBlend)
        }
        spatialDenoise = maxOf(spatialDenoise, noisePlan.lumaDenoise)
        chromaDenoise = maxOf(chromaDenoise, noisePlan.chromaDenoise)
        // AI Motion Compensation: only surface a temporal blend when the plan is actively
        // compensating (user toggle ON). Otherwise 0 so the record path stays identity.
        val aiMotionTemporalBlend = when (noisePlan.motionCompensationPlan.state) {
            AiMotionCompensationState.HEURISTIC_ACTIVE,
            AiMotionCompensationState.NEURAL_FLOW_ACTIVE ->
                noisePlan.motionCompensationPlan.effectiveTemporalBlend.coerceIn(0f, 1f)
            else -> 0f
        }
        val detailPlan = VideoDetailSharpeningPolicy.resolve(
            VideoDetailSharpeningRequest(
                signalFamily = phase4SignalFamily,
                recording = recording,
                lumaLevel = scene.averageLuma,
                edgeDensity = scene.edgeDensity,
                perceptualSharpness = scene.perceptualSharpness,
                skinToneConfidence = 0f,
                textureConfidence = (scene.edgeDensity / 0.32f).coerceIn(0f, 1f),
                selectedWidth = outputWidth,
                selectedHeight = outputHeight,
                nativeWidth = sourceWidth,
                nativeHeight = sourceHeight,
                noiseMetrics = noisePlan.metrics,
                strictHighEnd = strictHighEnd,
                videoDetailProfile = videoDetailProfile,
                subjectDetailPlan = subjectDetailPlan
            )
        )
        val detailSharpeningTarget = if (isLogProfile && !strictHighEnd) {
            minOf(detailPlan.sharpeningStrength, logLook.sharpeningCap)
        } else {
            detailPlan.sharpeningStrength
        }
        sharpening = if (videoDetailProfile != null) {
            detailSharpeningTarget
        } else if (detailPlan.noisyShadowSharpeningSuppression >= 0.70f) {
            minOf(sharpening, detailSharpeningTarget.coerceAtLeast(0.18f))
        } else {
            maxOf(sharpening, detailSharpeningTarget)
        }

        val cleanStrictDetailScene =
            strictHighEnd &&
                !zeroNoiseActive &&
                scene.normalizedNoiseFloor <= 0.10f &&
                scene.flatRegionStdDev <= 2.50f &&
                scene.realIsoNoiseFloor <= 520f &&
                motionFactor <= 0.08f &&
                scene.edgeDensity >= 0.32f &&
                scene.perceptualSharpness >= 0.78f
        if (isLogProfile) {
            val logCeiling = if (strictHighEnd) 0.56f else 0.22f
            val logFloor = when {
                strictHighEnd && !zeroNoiseActive -> 0.40f
                strictHighEnd -> 0.18f
                else -> 0.14f
            }
            sharpening = sharpening.coerceIn(minOf(logFloor, logCeiling), logCeiling)
        } else if (recording && cleanStrictDetailScene) {
            val parityFloor = when {
                hdrModeKey.contains("nivukx") -> 0.86f
                hdrEnabled || isVideoHdrMode || activeVideoHdrLook -> 0.86f
                else -> 0.88f
            }
            sharpening = maxOf(sharpening, parityFloor).coerceAtMost(ImaxStrictProfile.SHARPENING_STRICT_CEILING)
        } else if (!recording && !zeroNoiseActive && motionFactor in 0.10f..0.30f && scene.edgeDensity >= 0.18f) {
            sharpening = (sharpening + motionFactor * 0.20f).coerceAtMost(ImaxStrictProfile.SHARPENING_STRICT_CEILING)
        }

        if (strictHighEnd) {
            val strictQualityNoisePressure = maxOf(
                lowLightFactor,
                scene.normalizedNoiseFloor.coerceIn(0f, 1f),
                (scene.flatRegionStdDev / 8f).coerceIn(0f, 1f),
                ((scene.realIsoNoiseFloor - 400f) / 3_200f).coerceIn(0f, 1f),
                rgbSpeckleRisk
            )
            val cleanTexturedSignal =
                    scene.normalizedNoiseFloor <= 0.10f &&
                    scene.flatRegionStdDev <= 2.5f &&
                    scene.realIsoNoiseFloor <= 520f &&
                    motionFactor <= 0.10f &&
                    scene.edgeDensity >= 0.16f
            val aggressiveDetailSignal =
                scene.normalizedNoiseFloor >= 0.25f ||
                    scene.flatRegionStdDev >= 4f ||
                    scene.realIsoNoiseFloor >= 1_200f ||
                    rgbSpeckleRisk >= 0.22f
            val strictSpatialFloor = if (recording) {
                if (cleanTexturedSignal) {
                    (0.20f + strictQualityNoisePressure * 0.20f).coerceIn(0.18f, 0.42f)
                } else {
                    (0.26f + strictQualityNoisePressure * 0.44f).coerceIn(0.24f, 0.72f)
                }
            } else {
                (0.20f + strictQualityNoisePressure * 0.38f).coerceIn(0.18f, 0.64f)
            }
            val strictChromaFloor = if (recording) {
                if (cleanTexturedSignal) {
                    (0.28f + strictQualityNoisePressure * 0.26f).coerceIn(0.26f, 0.58f)
                } else {
                    (0.38f + strictQualityNoisePressure * 0.46f).coerceIn(0.34f, 0.88f)
                }
            } else {
                (0.30f + strictQualityNoisePressure * 0.42f).coerceIn(0.28f, 0.80f)
            }
            val strictDetailFloor = if (cleanTexturedSignal || !aggressiveDetailSignal) {
                when {
                    isLogProfile -> if (recording) 0.50f else 0.40f
                    hdrEnabled || isVideoHdrMode || activeVideoHdrLook -> if (recording) 0.58f else 0.46f
                    isRawMode -> if (recording) 0.58f else 0.44f
                    else -> if (recording) 0.58f else 0.46f
                }
            } else {
                when {
                    isLogProfile -> if (recording) 0.62f else 0.52f
                    hdrEnabled || isVideoHdrMode || activeVideoHdrLook -> if (recording) 0.72f else 0.58f
                    isRawMode -> if (recording) 0.72f else 0.58f
                    else -> if (recording) 0.72f else 0.58f
                }
            }
            val strictDetailCeiling = (
                ImaxStrictProfile.SHARPENING_STRICT_CEILING -
                    strictQualityNoisePressure * 0.10f -
                    motionFactor * 0.05f
                ).coerceIn(strictDetailFloor, ImaxStrictProfile.SHARPENING_STRICT_CEILING)

            spatialDenoise = maxOf(spatialDenoise, strictSpatialFloor)
            chromaDenoise = maxOf(chromaDenoise, strictChromaFloor)
            sharpening = maxOf(sharpening, strictDetailFloor).coerceAtMost(strictDetailCeiling)
            if (
                videoDetailProfile == VideoDetailProfile.FOCUSED_DETAIL &&
                detailPlan.subjectDetailPlan?.subjectDetailApplied == true
            ) {
                sharpening = maxOf(sharpening, detailPlan.sharpeningStrength)
            }
        }

        val strictNormalOrProSdrVideo =
            strictHighEnd &&
                phase4SignalFamily == VideoImageSignalFamily.SDR &&
                !isLogProfile &&
                !isRawMode &&
                !hdrEnabled &&
                !isVideoHdrMode &&
                !activeVideoHdrLook &&
                (isNaturalProfile || isRec709Profile || isRec2020FamilyProfile || isProSdrWideGamutProfile)
        if (strictNormalOrProSdrVideo) {
            val sdrNoisePressure = maxOf(
                scene.normalizedNoiseFloor.coerceIn(0f, 1f),
                (scene.flatRegionStdDev / 8f).coerceIn(0f, 1f),
                ((scene.realIsoNoiseFloor - 400f) / 3_200f).coerceIn(0f, 1f),
                rgbSpeckleRisk
            )
            val sdrTextureConfidence = maxOf(
                scene.edgeDensity.coerceIn(0f, 1f),
                scene.perceptualSharpness.coerceIn(0f, 1f),
                recommendationDetail
            )
            val sdrSharpeningTarget = (
                0.86f +
                    sdrTextureConfidence * 0.10f -
                    sdrNoisePressure * 0.18f
                ).coerceIn(if (zeroNoiseActive) 0.72f else 0.82f, 0.92f)
            val sdrSpatialFloor = (
                0.18f +
                    sdrNoisePressure * 0.52f
                ).coerceIn(0.16f, if (zeroNoiseActive) 0.76f else 0.70f)
            val sdrChromaFloor = (
                0.26f +
                    sdrNoisePressure * 0.58f
                ).coerceIn(0.24f, if (zeroNoiseActive) 0.90f else 0.84f)
            val sdrSpatialCeiling = (
                0.46f +
                    sdrNoisePressure * 0.34f
                ).coerceIn(0.48f, 0.82f)
            val sdrChromaCeiling = (
                0.58f +
                    sdrNoisePressure * 0.34f
                ).coerceIn(0.62f, 0.92f)
            val stableContrast = (
                (if (isRec709Profile) 1.000f else if (isRec2020FamilyProfile) 1.010f else 1.018f) +
                    scene.dynamicRangeScore.coerceIn(0f, 1f) * 0.018f +
                    scene.shadowRatio.coerceIn(0f, 1f) * 0.012f -
                    sdrNoisePressure * 0.012f
                ).coerceIn(0.985f, 1.055f)
            val stableSaturation = (
                (if (isRec709Profile) 1.000f else if (isRec2020FamilyProfile) 1.006f else 1.010f) +
                    sdrTextureConfidence * 0.018f -
                    sdrNoisePressure * 0.018f
                ).coerceIn(0.985f, 1.050f)
            val stableWarmth = if (isRec709Profile) 0.012f else 0.015f
            val stableHighlightCompression = (
                0.132f +
                    scene.highlightRatio.coerceIn(0f, 1f) * 0.080f +
                    scene.dynamicRangeScore.coerceIn(0f, 1f) * 0.018f
                ).coerceIn(0.12f, 0.20f)

            // This is the final SDR texture guard. Earlier specialist plans may ask
            // for stronger cleanup, but near-unity spatial filtering destroys real
            // micro-texture before the edge-aware reconstruction pass can use it.
            spatialDenoise = maxOf(spatialDenoise, sdrSpatialFloor).coerceAtMost(sdrSpatialCeiling)
            chromaDenoise = maxOf(chromaDenoise, sdrChromaFloor).coerceAtMost(sdrChromaCeiling)
            if (videoDetailProfile == null && subjectDetailPlan == null) {
                sharpening = maxOf(sharpening, sdrSharpeningTarget)
                    .coerceAtMost(sdrSharpeningTarget)
                    .coerceAtMost(ImaxStrictProfile.SHARPENING_STRICT_CEILING)
            }
            contrast = stableContrast
            saturation = stableSaturation
            warmth = stableWarmth
            highlightCompression = stableHighlightCompression
            videoHdrFusionStrength = 0f
            logStrength = 0f
            ditherStrength = 0f
        }

        if (strictHighEnd && phase4SignalFamily != VideoImageSignalFamily.RAW) {
            val finalNoisePressure = maxOf(
                scene.normalizedNoiseFloor.coerceIn(0f, 1f),
                (scene.flatRegionStdDev / 8f).coerceIn(0f, 1f),
                ((scene.realIsoNoiseFloor - 400f) / 3_200f).coerceIn(0f, 1f),
                rgbSpeckleRisk,
                lowLightFactor
            )
            val finalCleanTexture =
                scene.normalizedNoiseFloor <= 0.10f &&
                    scene.flatRegionStdDev <= 2.5f &&
                    scene.realIsoNoiseFloor <= 520f &&
                    scene.edgeDensity >= 0.16f
            val finalSpatialCeiling = if (finalCleanTexture) {
                when (phase4SignalFamily) {
                    VideoImageSignalFamily.SDR -> 0.38f
                    VideoImageSignalFamily.HDR -> 0.44f
                    VideoImageSignalFamily.LOG -> 0.34f
                    VideoImageSignalFamily.RAW -> 1f
                }
            } else {
                when (phase4SignalFamily) {
                    VideoImageSignalFamily.SDR -> (0.46f + finalNoisePressure * 0.36f).coerceIn(0.48f, 0.82f)
                    VideoImageSignalFamily.HDR -> (0.48f + finalNoisePressure * 0.36f).coerceIn(0.50f, 0.84f)
                    VideoImageSignalFamily.LOG -> (0.42f + finalNoisePressure * 0.36f).coerceIn(0.44f, 0.78f)
                    VideoImageSignalFamily.RAW -> 1f
                }
            }
            val finalChromaCeiling = if (finalCleanTexture) {
                when (phase4SignalFamily) {
                    VideoImageSignalFamily.SDR -> 0.58f
                    VideoImageSignalFamily.HDR -> 0.64f
                    VideoImageSignalFamily.LOG -> 0.52f
                    VideoImageSignalFamily.RAW -> 1f
                }
            } else {
                when (phase4SignalFamily) {
                    VideoImageSignalFamily.SDR -> (0.58f + finalNoisePressure * 0.34f).coerceIn(0.62f, 0.92f)
                    VideoImageSignalFamily.HDR -> (0.60f + finalNoisePressure * 0.34f).coerceIn(0.64f, 0.94f)
                    VideoImageSignalFamily.LOG -> (0.54f + finalNoisePressure * 0.34f).coerceIn(0.58f, 0.90f)
                    VideoImageSignalFamily.RAW -> 1f
                }
            }

            spatialDenoise = spatialDenoise.coerceAtMost(finalSpatialCeiling)
            chromaDenoise = chromaDenoise.coerceAtMost(finalChromaCeiling)
        } else if (phase4SignalFamily == VideoImageSignalFamily.RAW) {
            // RAW monitoring must not inherit processed-video noise floors. The RAW
            // container remains untouched and the preview keeps sensor texture.
            temporalBlend = 0f
            spatialDenoise = 0f
            chromaDenoise = 0f
        }

        val acesPlan = AcesColorEngine.resolvePlan(
            selectedColorSpace = colorProfile,
            selectedColorScience = when {
                hdrModeKey.contains("nivukx") -> "NIVUKX Vision"
                hdrModeKey.contains("ndr10") -> "NDR10+"
                isLogProfile -> "N-Log"
                else -> colorProfile
            },
            hdrTransport = hdrEnabled || isVideoHdrMode || activeVideoHdrLook,
            logTransport = isLogProfile
        )
        val activeDualGainPlan = dualGainFusionPlan?.takeIf { it.supported }
        val dualGainSignature = activeDualGainPlan?.signature?.let { signature ->
            "$signature|externalProfile=false|vendor=false"
        } ?: (dualGainFusionPlan?.signature ?: "dualGain=inactive|externalProfile=false|vendor=false")

        return SceneRenderProfile(
            temporalBlend = temporalBlend,
            aiMotionTemporalBlend = aiMotionTemporalBlend,
            spatialDenoise = spatialDenoise.coerceIn(0f, 1f),
            chromaDenoise = chromaDenoise.coerceIn(0f, 1f),
            shadowBoost = shadowBoost,
            sharpening = sharpening,
            warmth = warmth,
            contrast = contrast,
            saturation = saturation,
            highlightCompression = highlightCompression,
            logStrength = logStrength.coerceIn(0f, 0.9f),
            ditherStrength = ditherStrength.coerceIn(0f, 0.08f),
            videoHdrFusionStrength = videoHdrFusionStrength,
            logPreviewParity = logPreviewParity,
            noiseMetrics = noisePlan.metrics,
            noiseReductionSignature = noisePlan.signature,
            detailSharpeningMetadata = detailPlan.toMetadata(),
            detailSharpeningSignature = detailPlan.signature,
            subjectDetailPlan = subjectDetailPlan,
            acesColorSignature = acesPlan.signature,
            dualGainFusionEnabled = activeDualGainPlan != null,
            dualGainMotionMaskEnabled = activeDualGainPlan?.motionMaskEnabled == true,
            dualGainKGain = activeDualGainPlan?.kGain?.coerceIn(1f, 64f) ?: 1f,
            dualGainNoiseFloor = activeDualGainPlan?.noiseFloor?.coerceIn(0f, 0.20f) ?: 0f,
            dualGainSaturationThreshold = activeDualGainPlan?.saturationThreshold?.coerceIn(0.72f, 0.995f) ?: 0.94f,
            dualGainTimestampSkewNs = activeDualGainPlan?.timestampSkewNs ?: Long.MAX_VALUE,
            dualGainFusionSignature = dualGainSignature
        )
    }

    private fun defaultProfile(
        logEnabled: Boolean,
        hdrEnabled: Boolean,
        colorProfile: String,
        aiEnhancementEnabled: Boolean = true,
        simulatedTenBitFallback: Boolean = false,
        tenBitHardwareSupport: NeoTenBitHardwareSupport = NeoTenBitHardwareSupport.none(),
        lutEnabled: Boolean = false,
        lutProfile: String = "OFF",
        hdrMode: String = "OFF",
        previewFps: Float = HighEndQualityPolicy.targetPreviewFps.toFloat(),
        recording: Boolean = false,
        dualGainFusionPlan: DualGainHdrFusionPlan? = null,
        outputWidth: Int = 3840,
        outputHeight: Int = 2160,
        sourceWidth: Int = outputWidth,
        sourceHeight: Int = outputHeight,
        videoDetailProfile: VideoDetailProfile? = null,
        subjectDetailPlan: SubjectDetailEnhancementPlan? = null
    ): SceneRenderProfile {
        val strictRecordingFallback = recording && HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        val fallbackScene = SceneAnalysis(
            averageLuma = 0.45f,
            estimatedLux = if (strictRecordingFallback) 42f else 90f,
            motionScore = 0.02f,
            edgeDensity = 0.18f,
            dynamicRangeScore = if (hdrEnabled) 0.62f else 0.32f,
            shadowRatio = if (strictRecordingFallback) 0.18f else 0.10f,
            highlightRatio = if (hdrEnabled) 0.08f else 0.03f,
            timestamp = 0L,
            normalizedNoiseFloor = if (strictRecordingFallback) 0.42f else 0f,
            flatRegionStdDev = if (strictRecordingFallback) 8.0f else 0f,
            realIsoNoiseFloor = if (strictRecordingFallback) 1_800f else 100f,
            perceptualSharpness = if (strictRecordingFallback) 0.26f else 0f
        )
        return build(
            scene = fallbackScene,
            recommendation = CaptureRecommendation(
                scene = fallbackScene,
                isoCeiling = 200,
                targetExposureNs = 16_666_667L,
                stackFrameCount = 1,
                temporalNoiseReduction = true,
                hdrBracketEvs = if (hdrEnabled) listOf(-2, -1, 0, 1, 2) else emptyList(),
                sharpeningBoost = 0.09f
            ),
            logEnabled = logEnabled,
            hdrEnabled = hdrEnabled,
            colorProfile = colorProfile,
            aiEnhancementEnabled = aiEnhancementEnabled,
            simulatedTenBitFallback = simulatedTenBitFallback,
            tenBitHardwareSupport = tenBitHardwareSupport,
            lutEnabled = lutEnabled,
            lutProfile = lutProfile,
            hdrMode = hdrMode,
            previewFps = previewFps,
            recording = recording,
            dualGainFusionPlan = dualGainFusionPlan,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            videoDetailProfile = videoDetailProfile,
            subjectDetailPlan = subjectDetailPlan
        )
    }
}
