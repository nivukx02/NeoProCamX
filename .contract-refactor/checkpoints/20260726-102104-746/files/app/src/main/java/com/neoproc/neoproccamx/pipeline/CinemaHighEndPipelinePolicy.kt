package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.camera.Camera2NoiseCleanupProfile
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal enum class CinemaHighEndCaptureMode {
    PRO,
    HDR,
    LOG,
    RAW
}

internal data class CinemaHighEndPipelineRequest(
    val width: Int,
    val height: Int,
    val fps: Int,
    val recording: Boolean,
    val mode: CinemaHighEndCaptureMode,
    val estimatedLux: Float,
    val motionScore: Float,
    val normalizedNoiseFloor: Float,
    val flatRegionStdDev: Float,
    val realIso: Float,
    val edgeDensity: Float,
    val perceptualSharpness: Float,
    val highEndEnabled: Boolean = HighEndQualityPolicy.enabled,
    val strictHighEnd: Boolean = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
)

internal data class CinemaHighEndPipelinePlan(
    val cameraNoiseCleanupProfile: Camera2NoiseCleanupProfile,
    val forcePreviewRecordingParity: Boolean,
    val forceProcessedIspForRaw: Boolean,
    val temporalDenoise: Float,
    val spatialDenoise: Float,
    val chromaDenoise: Float,
    val sharpeningFloor: Float,
    val sharpeningCeiling: Float,
    val shadowLift: Float,
    val highlightRecovery: Float,
    val encoderBitrateMultiplier: Float,
    val rawCalibrationRequired: Boolean,
    val disableSyntheticDither: Boolean
)

internal object CinemaHighEndPipelinePolicy {

    fun resolve(request: CinemaHighEndPipelineRequest): CinemaHighEndPipelinePlan {
        val strict = request.highEndEnabled && request.strictHighEnd
        val recording = request.recording
        val modeBoost = when (request.mode) {
            CinemaHighEndCaptureMode.PRO -> 0f
            CinemaHighEndCaptureMode.HDR -> 0.06f
            CinemaHighEndCaptureMode.LOG -> 0.07f
            CinemaHighEndCaptureMode.RAW -> 0.04f
        }
        val lowLightPressure = ((18f - request.estimatedLux) / 18f).coerceIn(0f, 1f)
        val measuredNoise = request.normalizedNoiseFloor.coerceIn(0f, 1f)
        val flatChromaPressure = ((request.flatRegionStdDev - 2f) / 12f).coerceIn(0f, 1f)
        val isoPressure = ((request.realIso - 400f) / 32_000f).coerceIn(0f, 1f)
        val noisePressure = (
            maxOf(
                lowLightPressure * 0.92f,
                measuredNoise,
                flatChromaPressure,
                isoPressure
            ) + modeBoost
            ).coerceIn(0f, 1f)
        val motion = request.motionScore.coerceIn(0f, 1f)
        val staticness = (1f - motion).coerceIn(0f, 1f)
        val detailConfidence = (
            (request.edgeDensity / 0.32f).coerceIn(0f, 1f) * 0.48f +
                request.perceptualSharpness.coerceIn(0f, 1f) * 0.52f
            ).coerceIn(0f, 1f)
        val cleanTexturedSignal =
            noisePressure <= 0.22f &&
                motion <= 0.18f &&
                detailConfidence >= 0.42f

        val temporalCeiling = when {
            motion >= 0.50f -> 0.54f
            motion >= 0.30f -> 0.54f
            else -> if (strict) 0.78f else 0.62f
        }
        val temporalFloor = if (strict) 0.24f else 0.10f
        val temporalDenoise = (
            0.30f +
                noisePressure * 0.36f * staticness +
                (if (recording) 0.12f * staticness else 0.04f * staticness) +
                (if (request.mode == CinemaHighEndCaptureMode.LOG) 0.03f else 0f)
            ).coerceIn(temporalFloor, temporalCeiling)

        val spatialMinimum = when {
            strict && cleanTexturedSignal -> 0.28f
            strict -> 0.90f
            else -> 0.18f
        }
        val spatialCeiling = when {
            strict && cleanTexturedSignal -> 0.72f
            strict -> 0.995f
            else -> 0.86f
        }
        val spatialDenoise = (
            0.42f +
                noisePressure * 0.52f +
                lowLightPressure * 0.08f +
                (if (recording) 0.06f else 0f) +
                (if (request.mode == CinemaHighEndCaptureMode.HDR || request.mode == CinemaHighEndCaptureMode.LOG) 0.03f else 0f)
            ).coerceIn(spatialMinimum, spatialCeiling)

        val chromaMinimum = when {
            strict && cleanTexturedSignal -> 0.40f
            strict -> 0.99f
            else -> 0.24f
        }
        val chromaCeiling = when {
            strict && cleanTexturedSignal -> 0.86f
            strict -> 0.9998f
            else -> 0.96f
        }
        val chromaDenoise = (
            0.58f +
                noisePressure * 0.42f +
                (if (recording) 0.04f else 0f) +
                (if (request.mode == CinemaHighEndCaptureMode.HDR || request.mode == CinemaHighEndCaptureMode.LOG) 0.03f else 0f)
            ).coerceIn(chromaMinimum, chromaCeiling)

        val isLogMode = request.mode == CinemaHighEndCaptureMode.LOG
        val strictSharpeningFloorBase = if (isLogMode) (if (noisePressure > 0.65f) 0.18f else 0.48f) else 0.58f
        val strictSharpeningCeilingBase = if (isLogMode) (if (noisePressure > 0.65f) 0.34f else 0.88f) else 0.92f
        // Noise-adaptive: noisy scenes reduce sharpening to avoid amplifying grain
        val noiseSharpDamp = noisePressure * (if (isLogMode) 0.06f else 0.14f)
        val strictSharpeningFloorEffective = (strictSharpeningFloorBase + (1f - noisePressure) * (if (isLogMode) 0.08f else 0.18f) - noiseSharpDamp)
            .coerceIn(strictSharpeningFloorBase, strictSharpeningCeilingBase)
        val sharpeningCeiling = (
            0.68f -
                noisePressure * 0.08f -
                lowLightPressure * 0.01f -
                (if (recording) 0.01f else 0f)
            ).coerceIn(if (strict) strictSharpeningFloorBase else 0.24f, if (strict) strictSharpeningCeilingBase else 0.62f)
        val sharpeningFloor = (
            0.28f +
                request.edgeDensity.coerceIn(0f, 1f) * 0.24f +
                detailConfidence * 0.20f -
                noisePressure * 0.03f -
                motion * 0.015f
            ).coerceIn(if (strict) minOf(strictSharpeningFloorEffective, sharpeningCeiling) else 0.14f, sharpeningCeiling)

        val shadowLift = (
            0.18f +
                lowLightPressure * 0.34f +
                noisePressure * 0.14f
            ).coerceIn(0.14f, if (strict) 0.70f else 0.52f)
        val highlightRecovery = (
            0.16f +
                noisePressure * 0.22f +
                (if (request.mode == CinemaHighEndCaptureMode.HDR) 0.10f else 0f) +
                (if (request.mode == CinemaHighEndCaptureMode.LOG) 0.08f else 0f) +
                (if (request.mode == CinemaHighEndCaptureMode.RAW) 0.04f else 0f)
            ).coerceIn(0.12f, if (strict) 0.56f else 0.42f)

        return CinemaHighEndPipelinePlan(
            cameraNoiseCleanupProfile = when (request.mode) {
                CinemaHighEndCaptureMode.RAW -> Camera2NoiseCleanupProfile.RAW_SENSOR_TRUTHFUL
                else -> if (strict || recording) {
                    Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT
                } else {
                    Camera2NoiseCleanupProfile.PREVIEW
                }
            },
            forcePreviewRecordingParity = strict && request.mode != CinemaHighEndCaptureMode.RAW,
            forceProcessedIspForRaw = false,
            temporalDenoise = temporalDenoise,
            spatialDenoise = spatialDenoise,
            chromaDenoise = chromaDenoise,
            sharpeningFloor = sharpeningFloor,
            sharpeningCeiling = sharpeningCeiling,
            shadowLift = shadowLift,
            highlightRecovery = highlightRecovery,
            encoderBitrateMultiplier = encoderMultiplier(request, noisePressure, strict),
            rawCalibrationRequired = request.mode == CinemaHighEndCaptureMode.RAW,
            disableSyntheticDither = strict && recording
        )
    }

    private fun encoderMultiplier(
        request: CinemaHighEndPipelineRequest,
        noisePressure: Float,
        strict: Boolean
    ): Float {
        val pixels = request.width.coerceAtLeast(1).toLong() * request.height.coerceAtLeast(1).toLong()
        val resolutionBoost = when {
            pixels >= 7680L * 4320L -> 0.72f
            pixels >= 3840L * 2160L -> 0.36f
            pixels >= 1920L * 1080L -> 0.18f
            else -> 0f
        }
        val fpsBoost = ((request.fps.coerceAtLeast(24) - 25).coerceAtLeast(0) / 25f * 0.22f)
            .coerceIn(0f, 0.32f)
        val modeBoost = when (request.mode) {
            CinemaHighEndCaptureMode.PRO -> 0.08f
            CinemaHighEndCaptureMode.HDR -> 0.24f
            CinemaHighEndCaptureMode.LOG -> 0.28f
            CinemaHighEndCaptureMode.RAW -> 0.34f
        }
        val base = if (strict) 2.50f else 1.18f
        return (
            base +
                resolutionBoost +
                fpsBoost +
                modeBoost +
                noisePressure * 0.22f
            ).coerceIn(if (strict) 2.50f else 1.0f, if (strict) 4.0f else 2.2f)
    }
}
