package com.neoproc.neoproccamx.rendering

/**
 * Live-record side of the record-then-develop workflow.
 *
 * When [RecordDevelopModeFlag] is enabled, live recording must write a high-fidelity flat master and leave
 * the heavy colour/detail stack to the offline SDR/NDR develop pass. This policy centralizes the neutral
 * packets so the Vulkan path, offscreen-EGL bridge, and ByteBuffer P010 fallback cannot drift.
 */
internal object RecordDevelopLivePipelinePolicy {
    val NEUTRAL_ENCODER_GRADE: FloatArray = floatArrayOf(
        1f, 1f, 0f, 0f, // contrast, saturation, shadow lift, highlight compression
        1f, 1f, 1f, 0f, // RGB scales, log strength
        0f, 0f, 0f, 0f, // RGB bias, HDR strength
        0f, 0f, 0f, 0f  // tone mode, LUT size, shadow/highlight bias scales
    )

    private val DISABLED_COLOR_SCIENCE: FloatArray = VulkanColorScienceUniforms.disabled().pack()

    fun active(videoSurface: Boolean): Boolean =
        videoSurface && RecordDevelopModeFlag.enabled

    fun encoderGrade(videoSurface: Boolean, candidate: FloatArray): FloatArray =
        if (active(videoSurface)) NEUTRAL_ENCODER_GRADE.copyOf() else candidate

    fun encoderColorScience(videoSurface: Boolean, candidate: FloatArray): FloatArray =
        if (active(videoSurface)) DISABLED_COLOR_SCIENCE.copyOf() else candidate

    fun enhancementUniforms(
        videoSurface: Boolean,
        candidate: GpuImageEnhancementUniforms
    ): GpuImageEnhancementUniforms =
        if (!active(videoSurface)) {
            candidate
        } else {
            candidate.copy(
                temporalBlend = 0f,
                spatialDenoise = 0f,
                chromaDenoise = 0f,
                shadowBoost = 0f,
                sharpening = 0f,
                warmth = 0f,
                contrast = 1f,
                saturation = 1f,
                highlightCompression = 0f,
                logStrength = 0f,
                videoHdrFusionStrength = 0f,
                shadowChromaDeadband = 0f,
                aliasSafeMaxDetailDelta = 0f,
                fineTextureThreshold = 0f,
                mediumEdgeThreshold = 0f,
                strongEdgeThreshold = 0f,
                fineTextureDetailGain = 0f,
                mediumDetailGain = 0f,
                strongDetailGain = 0f,
                temporalLumaBlend = 0f,
                temporalChromaBlend = 0f,
                noiseMetric = 0f,
                localChromaSpikeSmoothing = 0f,
                sharpeningFloor = 0f,
                sharpeningCeiling = 0f,
                skinDetailProtection = 0f,
                dualGainFusionEnabled = false,
                dualGainMotionMaskEnabled = false,
                dualGainKGain = 1f,
                dualGainNoiseFloor = 0f,
                dualGainSaturationThreshold = 0.72f,
                dualGainTimestampSkew = 0f,
                sensorShotNoiseCoefficient = 0f,
                sensorReadNoiseCoefficient = 0f,
                detailProfileId = 0f,
                subjectMaskAvailable = 0f,
                subjectDetailGain = 0f,
                subjectMaskFeather = 0f,
                v20ZeroGrainClarityActive = false,
                v20ZeroGrainLock = 0f,
                v20RgbGrainQuarantine = 0f,
                v20MotionBlurRejection = 0f,
                v20MicroDetailHonesty = 0f,
                v20ZeroGrainClaritySignature = "record-develop-live-flat",
                aiMotionTemporalBlend = 0f,
                signature = "gpu-v1|recordDevelopLightMaster=true|packet=flat-offline-develop"
            )
        }

    fun recordCreativeLut(videoSurface: Boolean, candidate: Pair<FloatArray, Int>?): Pair<FloatArray, Int>? =
        if (active(videoSurface)) null else candidate

    fun liveEnhanceUniforms(videoSurface: Boolean, candidate: FloatArray): FloatArray? =
        if (active(videoSurface)) null else candidate
}
