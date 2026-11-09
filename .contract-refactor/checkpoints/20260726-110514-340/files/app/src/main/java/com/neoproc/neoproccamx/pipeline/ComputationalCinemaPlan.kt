package com.neoproc.neoproccamx.pipeline

enum class ComputationalCinemaMode {
    PRO_VIDEO,
    CINEMATIC_IMAX,
    AI_SUPER_RESOLUTION_ZOOM,
    NIGHT_VIDEO,
    SMART_HDR
}

enum class ComputationalCinemaQuality {
    FAST,
    BALANCED,
    MAXIMUM
}

enum class ComputationalCinemaDelivery {
    LIVE_REAL_TIME,
    LIVE_WITH_POST_PROCESS_FALLBACK
}

enum class ComputationalCinemaStage {
    MULTI_FRAME_DENOISE,
    HDR_FUSION,
    TEMPORAL_DETAIL_RECOVERY,
    AI_SUPER_RESOLUTION,
    LOCAL_CONTRAST,
    NATURAL_SHARPENING,
    SKIN_TONE_PROTECTION,
    FILMIC_LUT,
    GYRO_STABILIZATION
}

data class ComputationalCinemaRequest(
    val mode: ComputationalCinemaMode,
    val quality: ComputationalCinemaQuality,
    val zoomRatio: Float = 1.0f,
    val recording: Boolean = false
)

data class ComputationalCinemaHardware(
    val supportsRaw: Boolean,
    val supportsYuvReprocessing: Boolean,
    val supportsHdrVideo: Boolean,
    val supportsTenBit: Boolean,
    val supportsHevcMain10: Boolean,
    val supportsOis: Boolean,
    val supportsVideoStabilization: Boolean,
    val supportsVulkan: Boolean,
    val supportsZeroCopyGpuImport: Boolean,
    val supportsAiSuperResolution: Boolean,
    val maxEncoderLongEdge: Int,
    val maxSensorLongEdge: Int
)

data class ComputationalCinemaRuntime(
    val previewFps: Float,
    val targetFps: Int,
    val thermalSeverity: Int,
    val droppedFrames: Int,
    val encoderPressure: Float,
    val estimatedLux: Float,
    val motionScore: Float
)

data class ComputationalCinemaPlan(
    val delivery: ComputationalCinemaDelivery,
    val liveStages: Set<ComputationalCinemaStage>,
    val postProcessStages: Set<ComputationalCinemaStage>,
    val analysisLongEdge: Int,
    val multiFrameCount: Int,
    val enableGpuPreview: Boolean,
    val enableZeroCopyImport: Boolean,
    val enableSmartHdr: Boolean,
    val enableTemporalDenoise: Boolean,
    val enableAiSuperResolution: Boolean,
    val enableSkinToneProtection: Boolean,
    val enableFilmicLut: Boolean,
    val enableGyroStabilization: Boolean,
    val requestRaw: Boolean,
    val requestHevcMain10: Boolean,
    val requestHdrEncoding: Boolean,
    val bitrateMultiplier: Float,
    val temporalDenoiseStrength: Float,
    val spatialDenoiseStrength: Float,
    val chromaDenoiseStrength: Float,
    val sharpeningStrength: Float,
    val localContrastStrength: Float,
    val skinToneProtectionStrength: Float,
    val lutStrength: Float,
    val warnings: List<String>
)

object ComputationalCinemaPlanResolver {

    fun resolve(
        request: ComputationalCinemaRequest,
        hardware: ComputationalCinemaHardware,
        runtime: ComputationalCinemaRuntime
    ): ComputationalCinemaPlan {
        val warnings = mutableListOf<String>()
        val maximum = request.quality == ComputationalCinemaQuality.MAXIMUM
        val night = request.mode == ComputationalCinemaMode.NIGHT_VIDEO || runtime.estimatedLux < 12f
        val imax = request.mode == ComputationalCinemaMode.CINEMATIC_IMAX
        val smartHdrRequested = request.mode == ComputationalCinemaMode.SMART_HDR || imax || maximum
        val zoomSrRequested =
            request.mode == ComputationalCinemaMode.AI_SUPER_RESOLUTION_ZOOM ||
                request.zoomRatio >= 1.45f
        val runtimeUnsafe = runtime.isUnsafeForMaximumLive(request.recording)

        if (maximum && !hardware.supportsRaw) {
            warnings += "RAW unavailable: using best exposed YUV/encoder path instead of fake RAW."
        }
        if (smartHdrRequested && !hardware.supportsHdrVideo) {
            warnings += "HDR video unavailable: using SDR capture with filmic highlight rolloff."
        }
        if (maximum && !hardware.supportsTenBit) {
            warnings += "10-bit unavailable: preserving wide-gamut intent in supported 8-bit output."
        }
        if (maximum && !hardware.supportsZeroCopyGpuImport) {
            warnings += "Zero-copy GPU import unavailable: using copy-backed GPU/CPU fallback."
        }

        val delivery =
            if (maximum && runtimeUnsafe) {
                warnings += "Maximum live pipeline exceeded runtime budget; heavy stages moved to post-processing fallback."
                ComputationalCinemaDelivery.LIVE_WITH_POST_PROCESS_FALLBACK
            } else {
                ComputationalCinemaDelivery.LIVE_REAL_TIME
            }

        val analysisLongEdge = resolveAnalysisLongEdge(request, hardware, runtime, delivery)
        val liveStages = mutableSetOf<ComputationalCinemaStage>()
        val postStages = mutableSetOf<ComputationalCinemaStage>()

        val enableGpuPreview = hardware.supportsVulkan
        val enableZeroCopyImport = hardware.supportsVulkan && hardware.supportsZeroCopyGpuImport
        val enableSmartHdr = smartHdrRequested && hardware.supportsHdrVideo
        val enableTemporalDenoise = request.quality != ComputationalCinemaQuality.FAST || night
        val enableSkinToneProtection = request.quality != ComputationalCinemaQuality.FAST || night || imax
        val enableFilmicLut = request.mode != ComputationalCinemaMode.PRO_VIDEO || request.quality != ComputationalCinemaQuality.FAST
        val enableGyroStabilization = hardware.supportsOis || hardware.supportsVideoStabilization
        val liveSuperResolution =
            zoomSrRequested &&
                hardware.supportsAiSuperResolution &&
                request.quality != ComputationalCinemaQuality.FAST &&
                delivery == ComputationalCinemaDelivery.LIVE_REAL_TIME

        if (enableTemporalDenoise) liveStages += ComputationalCinemaStage.MULTI_FRAME_DENOISE
        if (enableSmartHdr) liveStages += ComputationalCinemaStage.HDR_FUSION
        if (request.quality != ComputationalCinemaQuality.FAST) {
            liveStages += ComputationalCinemaStage.TEMPORAL_DETAIL_RECOVERY
            liveStages += ComputationalCinemaStage.LOCAL_CONTRAST
            liveStages += ComputationalCinemaStage.NATURAL_SHARPENING
        }
        if (liveSuperResolution) liveStages += ComputationalCinemaStage.AI_SUPER_RESOLUTION
        if (enableSkinToneProtection) liveStages += ComputationalCinemaStage.SKIN_TONE_PROTECTION
        if (enableFilmicLut) liveStages += ComputationalCinemaStage.FILMIC_LUT
        if (enableGyroStabilization) liveStages += ComputationalCinemaStage.GYRO_STABILIZATION

        if (zoomSrRequested && !liveSuperResolution && hardware.supportsAiSuperResolution) {
            postStages += ComputationalCinemaStage.AI_SUPER_RESOLUTION
        }
        if (delivery == ComputationalCinemaDelivery.LIVE_WITH_POST_PROCESS_FALLBACK) {
            if (smartHdrRequested) postStages += ComputationalCinemaStage.HDR_FUSION
            if (enableTemporalDenoise) postStages += ComputationalCinemaStage.MULTI_FRAME_DENOISE
            postStages += ComputationalCinemaStage.TEMPORAL_DETAIL_RECOVERY
        }

        val denoiseBase = when (request.quality) {
            ComputationalCinemaQuality.FAST -> 0.28f
            ComputationalCinemaQuality.BALANCED -> 0.52f
            ComputationalCinemaQuality.MAXIMUM -> 0.72f
        }
        val nightBoost = if (night) 0.22f else 0f
        val maximumRecordingNoisePass = maximum && request.recording
        val zeroNoiseBoost = when {
            maximumRecordingNoisePass && night -> 0.12f
            maximumRecordingNoisePass -> 0.06f
            else -> 0f
        }
        val motionPenalty = (runtime.motionScore.coerceIn(0f, 1f) * if (maximumRecordingNoisePass) 0.10f else 0.16f)
        val spatialDenoise = (
            denoiseBase +
                nightBoost +
                zeroNoiseBoost -
                motionPenalty
            ).coerceIn(0.14f, if (maximumRecordingNoisePass) 0.992f else 0.90f)
        val chromaDenoise = (
            denoiseBase +
                nightBoost +
                (if (night) 0.10f else 0.04f) +
                zeroNoiseBoost * 0.78f
            ).coerceIn(0.18f, if (maximumRecordingNoisePass) 0.9995f else 0.96f)
        val temporalDenoise = (
            denoiseBase +
                (if (night) 0.18f else 0.04f) -
                runtime.motionScore.coerceIn(0f, 1f) * (if (maximumRecordingNoisePass) 0.20f else 0.28f) +
                (if (maximumRecordingNoisePass && night) 0.045f else 0f)
            ).coerceIn(0.08f, if (maximumRecordingNoisePass) 0.92f else 0.88f)
        val sharpening = when {
            night -> 0.30f
            imax -> 0.42f
            request.quality == ComputationalCinemaQuality.MAXIMUM -> 0.58f
            request.quality == ComputationalCinemaQuality.BALANCED -> 0.44f
            else -> 0.28f
        }
        val multiFrameCount = resolveFrameCount(request.quality, night, runtime.motionScore, delivery)

        return ComputationalCinemaPlan(
            delivery = delivery,
            liveStages = liveStages,
            postProcessStages = postStages,
            analysisLongEdge = analysisLongEdge,
            multiFrameCount = multiFrameCount,
            enableGpuPreview = enableGpuPreview,
            enableZeroCopyImport = enableZeroCopyImport,
            enableSmartHdr = enableSmartHdr,
            enableTemporalDenoise = enableTemporalDenoise,
            enableAiSuperResolution = liveSuperResolution,
            enableSkinToneProtection = enableSkinToneProtection,
            enableFilmicLut = enableFilmicLut,
            enableGyroStabilization = enableGyroStabilization,
            requestRaw = maximum && hardware.supportsRaw,
            requestHevcMain10 = request.recording && hardware.supportsTenBit && hardware.supportsHevcMain10,
            requestHdrEncoding = request.recording && enableSmartHdr && hardware.supportsTenBit,
            bitrateMultiplier = resolveBitrateMultiplier(request, hardware, runtimeUnsafe),
            temporalDenoiseStrength = temporalDenoise,
            spatialDenoiseStrength = spatialDenoise,
            chromaDenoiseStrength = chromaDenoise,
            sharpeningStrength = sharpening,
            localContrastStrength = if (imax) 0.78f else if (maximum) 0.68f else 0.42f,
            skinToneProtectionStrength = if (enableSkinToneProtection) {
                if (night || imax) 0.88f else 0.72f
            } else {
                0f
            },
            lutStrength = if (enableFilmicLut) {
                if (imax) 0.86f else if (maximum) 0.74f else 0.46f
            } else {
                0f
            },
            warnings = warnings
        )
    }

    private fun resolveAnalysisLongEdge(
        request: ComputationalCinemaRequest,
        hardware: ComputationalCinemaHardware,
        runtime: ComputationalCinemaRuntime,
        delivery: ComputationalCinemaDelivery
    ): Int {
        val base = when (request.quality) {
            ComputationalCinemaQuality.FAST -> 768
            ComputationalCinemaQuality.BALANCED -> 1280
            ComputationalCinemaQuality.MAXIMUM -> if (request.recording) 1920 else 2160
        }
        val cappedForRuntime = when {
            delivery == ComputationalCinemaDelivery.LIVE_WITH_POST_PROCESS_FALLBACK -> minOf(base, 960)
            runtime.previewFps < 28f -> minOf(base, 768)
            runtime.previewFps < 45f -> minOf(base, 1280)
            else -> base
        }
        val hardwareCap = minOf(
            hardware.maxSensorLongEdge.coerceAtLeast(640),
            if (hardware.supportsAiSuperResolution) 2160 else 1280
        )
        return cappedForRuntime.coerceIn(480, hardwareCap)
    }

    private fun resolveFrameCount(
        quality: ComputationalCinemaQuality,
        night: Boolean,
        motionScore: Float,
        delivery: ComputationalCinemaDelivery
    ): Int {
        val base = when (quality) {
            ComputationalCinemaQuality.FAST -> 3
            ComputationalCinemaQuality.BALANCED -> 5
            ComputationalCinemaQuality.MAXIMUM -> if (night) 10 else 8
        }
        val motionPenalty = when {
            motionScore > 0.30f -> 3
            motionScore > 0.14f -> 2
            motionScore > 0.08f -> 1
            else -> 0
        }
        val livePenalty =
            if (delivery == ComputationalCinemaDelivery.LIVE_WITH_POST_PROCESS_FALLBACK) 1 else 0
        return (base - motionPenalty - livePenalty).coerceIn(1, if (quality == ComputationalCinemaQuality.MAXIMUM) 10 else 6)
    }

    private fun resolveBitrateMultiplier(
        request: ComputationalCinemaRequest,
        hardware: ComputationalCinemaHardware,
        runtimeUnsafe: Boolean
    ): Float {
        val qualityMultiplier = when (request.quality) {
            ComputationalCinemaQuality.FAST -> 1.10f
            ComputationalCinemaQuality.BALANCED -> 1.55f
            ComputationalCinemaQuality.MAXIMUM -> 2.25f
        }
        val modeMultiplier = when (request.mode) {
            ComputationalCinemaMode.CINEMATIC_IMAX -> 1.18f
            ComputationalCinemaMode.SMART_HDR -> 1.12f
            ComputationalCinemaMode.NIGHT_VIDEO -> 1.08f
            ComputationalCinemaMode.AI_SUPER_RESOLUTION_ZOOM -> 1.10f
            ComputationalCinemaMode.PRO_VIDEO -> 1.0f
        }
        val codecMultiplier = if (hardware.supportsHevcMain10 && hardware.supportsTenBit) 1.08f else 1.0f
        val thermalMultiplier = if (runtimeUnsafe) 0.82f else 1.0f
        return qualityMultiplier * modeMultiplier * codecMultiplier * thermalMultiplier
    }

    private fun ComputationalCinemaRuntime.isUnsafeForMaximumLive(recording: Boolean): Boolean {
        val target = targetFps.coerceAtLeast(24).toFloat()
        val fpsUnsafe = previewFps < target * if (recording) 0.72f else 0.62f
        return thermalSeverity >= 4 ||
            droppedFrames >= 12 ||
            encoderPressure >= 0.82f ||
            fpsUnsafe
    }
}
