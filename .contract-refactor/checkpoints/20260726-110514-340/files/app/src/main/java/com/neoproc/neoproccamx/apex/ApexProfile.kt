package com.neoproc.neoproccamx.apex

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * NIVUKX APEX — Profile Definitions & Shared Data Types
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Elite tier above static HDR delivery profiles.
 * Internal color space: ICtCp. Transport: BT.2020. Precision: FP32 solves.
 *
 * STRICT RULES:
 *   - vendorCertifiedHdr10Plus = false (always)
 *   - vendorCertifiedDolbyVision = false (always)
 *   - Preview and recording MUST use the same selectedProfile
 */

/** APEX processing mode — determines signal shaping and detail enhancement behavior. */
enum class ApexMode(val id: Int, val displayName: String) {
    /** Maximum sharpness, punchy midtones, deep blacks, controlled highlights. */
    SHARP(0, "NIVUKX APEX SHARP"),
    /** Optical truth. Near-linear. No artificial sharpness. No artifacts. */
    ZERO(1, "NIVUKX APEX ZERO"),
    /** Cinematic balance. Silky shadows, film-like highlight rolloff. */
    CINEMA(2, "NIVUKX APEX CINEMA");

    companion object {
        fun fromId(id: Int): ApexMode = entries.firstOrNull { it.id == id } ?: SHARP
    }
}

/**
 * Per-frame scene analysis results. Computed from Camera2 CaptureResult + frame data.
 * All values are normalized [0.0, 1.0] unless otherwise specified.
 */
data class SceneMetrics(
    // ── Histogram percentiles (nits) ──────────────────────────────────────
    val p01Nits: Float = 0f,
    val p50Nits: Float = 0f,
    val p95Nits: Float = 0f,
    val p99Nits: Float = 0f,
    val maxCllNits: Float = 0f,
    val maxFallNits: Float = 0f,

    // ── Scene analysis scores [0.0, 1.0] ──────────────────────────────────
    val dynamicRangeScore: Float = 0f,
    val shadowRatio: Float = 0f,
    val noiseRisk: Float = 0f,
    val exposureStability: Float = 1f,
    val shadowNeed: Float = 0f,
    val motionScore: Float = 0f,
    val chromaNoiseRisk: Float = 0f,
    val highlightRatio: Float = 0f,
    val sceneWhite: Float = 0.18f,
    val localContrastScore: Float = 0f,
    val histogramSpread: Float = 0f,
    val clipRisk: Float = 0f,
    val perceptualStopEstimate: Float = 0f,

    // ── Sensor metadata ───────────────────────────────────────────────────
    val iso: Int = 100,
    val exposureTimeNs: Long = 0L,
    val frameTimestampNs: Long = 0L,

    // ── Measured (real-pixel) quality (CP-Q2) ─────────────────────────────
    /**
     * Robust luma SNR in dB measured from the flattest tiles of this frame's Y plane
     * (ImageQualityMetrics.frameLumaSnrDbFromY). NaN when not measured (no pixels available),
     * in which case consumers fall back to the ISO-derived heuristic.
     */
    val measuredLumaSnrDb: Float = Float.NaN,

    /**
     * Windowed temporal flicker sigma (CP-Q7): population std of recent per-frame mean luma over the
     * analysis window (ImageQualityMetrics.temporalFlickerSigmaFromMeans). NaN when not measured —
     * ImageQualityLiveMetricFlag.flickerEnabled is off (today's default) or the window is not yet
     * filled. Inert telemetry: it is NOT read by ApexProfileParams/ApexQualityMeasurement and is
     * intentionally omitted from ApexPipelineState.smoothMetrics, so it passes through current.copy()
     * unblended exactly like measuredLumaSnrDb.
     */
    val measuredTemporalFlickerSigma: Float = Float.NaN
)

/**
 * APEX profile parameters — computed per-frame from SceneMetrics + ApexMode.
 * Drives tone mapper, detail system, and chroma denoiser uniforms.
 */
data class ApexProfileParams(
    val mode: ApexMode = ApexMode.SHARP,
    val sourcePeakNits: Float = 12000f,
    val targetPeakNits: Float = 2000f,
    val kneeRatio: Float = 0.52f,
    val shoulderStrength: Float = 4.80f,
    val shadowLift: Float = 0.0014f,
    val saturationPreservation: Float = 0.71f,
    val colorVolumeCompression: Float = 0.72f,
    val detailGain1: Float = 0.42f,
    val detailGain2: Float = 0.28f,
    val detailGain3: Float = 0.12f,
    val baseChromaDenoise: Float = 0.18f,
    val toeStrength: Float = 0.026f,
    val midtoneSlope: Float = 1.0f,
    val localToneStrength: Float = 0.0f,
    val motionDetailDamping: Float = 1.0f,
    val noiseDetailGate: Float = 1.0f,
    val temporalDamping: Float = 0.45f
) {
    companion object {
        /**
         * Compute profile parameters from scene analysis + selected mode.
         * The strict APEX v2 model keeps SHARP/ZERO/CINEMA contracts intact,
         * then derives knee, toe, local contrast, and detail gates from the
         * richer scene metrics. ZERO remains near-linear and never amplifies.
         */
        fun compute(metrics: SceneMetrics, mode: ApexMode): ApexProfileParams {
            val drs = refinedDynamicRangeScore(metrics)
            val sn = refinedShadowNeed(metrics)
            val localContrast = metrics.localContrastScore.coerceIn(0f, 1f)
            val clipRisk = refinedClipRisk(metrics)
            val motion = metrics.motionScore.coerceIn(0f, 1f)
            val noise = maxOf(metrics.noiseRisk, metrics.chromaNoiseRisk).coerceIn(0f, 1f)
            val motionDetailDamping = (1f - motion * 0.42f).coerceIn(0.58f, 1f)
            val noiseDetailGate = (1f - noise * 0.84f).coerceIn(0.20f, 1f)
            val cleanDetailGate = motionDetailDamping * noiseDetailGate
            val temporalDamping = (
                0.28f +
                    metrics.exposureStability.coerceIn(0f, 1f) * 0.34f -
                    motion * 0.14f
                ).coerceIn(0.18f, 0.64f)

            return when (mode) {
                ApexMode.SHARP -> ApexProfileParams(
                    mode = mode,
                    sourcePeakNits = 12000f + 4000f * drs * sn,
                    targetPeakNits = 2000f + 400f * drs,
                    kneeRatio = adaptiveKnee(
                        base = 0.52f,
                        drs = drs,
                        sn = sn,
                        localContrast = localContrast,
                        clipRisk = clipRisk,
                        motion = motion
                    ),
                    shoulderStrength = (
                        4.80f + 0.62f * drs + 0.42f * clipRisk + 0.18f * sn - 0.20f * motion
                        ).coerceIn(4.25f, 6.20f),
                    shadowLift = 0.0014f + 0.0018f * drs * sn,
                    saturationPreservation = 0.71f,
                    colorVolumeCompression = 0.72f,
                    detailGain1 = (0.78f + 0.16f * localContrast * cleanDetailGate).coerceIn(0.0f, 0.96f),
                    detailGain2 = (0.28f + 0.07f * localContrast * cleanDetailGate).coerceIn(0.0f, 0.40f),
                    detailGain3 = (0.12f + 0.03f * drs * cleanDetailGate).coerceIn(0.0f, 0.20f),
                    baseChromaDenoise = (0.85f + 0.10f * noise + 0.05f * sn).coerceIn(0.65f, 0.98f),
                    toeStrength = (0.028f + 0.060f * sn * (1f - noise * 0.50f)).coerceIn(0.018f, 0.090f),
                    midtoneSlope = (1.0f + 0.20f * localContrast * cleanDetailGate - 0.08f * motion).coerceIn(0.92f, 1.18f),
                    localToneStrength = localToneStrength(
                        localContrast = localContrast,
                        drs = drs,
                        motion = motion,
                        noise = noise,
                        scale = 1.0f
                    ),
                    motionDetailDamping = motionDetailDamping,
                    noiseDetailGate = noiseDetailGate,
                    temporalDamping = temporalDamping
                )
                ApexMode.ZERO -> ApexProfileParams(
                    mode = mode,
                    sourcePeakNits = 12000f + 1800f * drs * sn,
                    targetPeakNits = 2000f + 400f * drs,
                    kneeRatio = adaptiveKnee(
                        base = 0.58f,
                        drs = drs * 0.55f,
                        sn = sn * 0.50f,
                        localContrast = 0f,
                        clipRisk = clipRisk,
                        motion = motion
                    ),
                    shoulderStrength = (3.35f + 0.30f * drs + 0.18f * clipRisk).coerceIn(3.10f, 4.15f),
                    shadowLift = 0.0008f + 0.0008f * drs * sn,
                    saturationPreservation = 0.82f,
                    colorVolumeCompression = 0.44f,
                    detailGain1 = 0f,   // ZERO: no amplification
                    detailGain2 = 0f,
                    detailGain3 = 0f,
                    baseChromaDenoise = (0.92f + 0.07f * noise).coerceIn(0.88f, 0.995f),
                    toeStrength = 0.012f,
                    midtoneSlope = 1.0f,
                    localToneStrength = 0f,
                    motionDetailDamping = motionDetailDamping,
                    noiseDetailGate = noiseDetailGate,
                    temporalDamping = temporalDamping
                )
                ApexMode.CINEMA -> ApexProfileParams(
                    mode = mode,
                    sourcePeakNits = 11000f + 3600f * drs * (0.68f + 0.32f * sn),
                    targetPeakNits = 1800f + 320f * drs,
                    kneeRatio = adaptiveKnee(
                        base = 0.53f,
                        drs = drs,
                        sn = sn,
                        localContrast = localContrast * 0.65f,
                        clipRisk = clipRisk,
                        motion = motion
                    ),
                    shoulderStrength = (
                        4.40f + 0.56f * drs + 0.34f * clipRisk + 0.20f * sn - 0.15f * motion
                        ).coerceIn(4.0f, 5.85f),
                    shadowLift = 0.0016f + 0.0020f * drs * sn,
                    saturationPreservation = 0.76f,
                    colorVolumeCompression = 0.62f,
                    detailGain1 = (0.48f + 0.10f * localContrast * cleanDetailGate).coerceIn(0.0f, 0.66f),
                    detailGain2 = (0.16f + 0.04f * localContrast * cleanDetailGate).coerceIn(0.0f, 0.26f),
                    detailGain3 = (0.08f + 0.02f * drs * cleanDetailGate).coerceIn(0.0f, 0.14f),
                    baseChromaDenoise = (0.86f + 0.10f * noise + 0.04f * sn).coerceIn(0.72f, 0.98f),
                    toeStrength = (0.034f + 0.070f * sn * (1f - noise * 0.45f)).coerceIn(0.020f, 0.105f),
                    midtoneSlope = (1.0f + 0.12f * localContrast * cleanDetailGate - 0.05f * motion).coerceIn(0.94f, 1.12f),
                    localToneStrength = localToneStrength(
                        localContrast = localContrast,
                        drs = drs,
                        motion = motion,
                        noise = noise,
                        scale = 0.74f
                    ),
                    motionDetailDamping = motionDetailDamping,
                    noiseDetailGate = noiseDetailGate,
                    temporalDamping = temporalDamping
                )
            }
        }

        private fun refinedDynamicRangeScore(metrics: SceneMetrics): Float {
            val p99Score = (metrics.p99Nits / 12_000f).coerceIn(0f, 1f)
            val weighted = (
                metrics.dynamicRangeScore.coerceIn(0f, 1f) * 0.68f +
                    p99Score * 0.12f +
                    metrics.highlightRatio.coerceIn(0f, 1f) * 0.10f +
                    refinedClipRisk(metrics) * 0.06f +
                    metrics.histogramSpread.coerceIn(0f, 1f) * 0.04f
                ).coerceIn(0f, 1f)
            return maxOf(metrics.dynamicRangeScore.coerceIn(0f, 1f), weighted)
        }

        private fun refinedShadowNeed(metrics: SceneMetrics): Float {
            val weighted = (
                metrics.shadowNeed.coerceIn(0f, 1f) * 0.62f +
                    metrics.shadowRatio.coerceIn(0f, 1f) * 0.18f +
                    metrics.noiseRisk.coerceIn(0f, 1f) * 0.10f +
                    (1f - metrics.exposureStability.coerceIn(0f, 1f)) * 0.10f
                ).coerceIn(0f, 1f)
            return maxOf(metrics.shadowNeed.coerceIn(0f, 1f), weighted)
        }

        private fun refinedClipRisk(metrics: SceneMetrics): Float =
            maxOf(
                metrics.clipRisk.coerceIn(0f, 1f),
                (metrics.highlightRatio.coerceIn(0f, 1f) * 0.75f),
                ((metrics.p99Nits - 8_000f) / 4_000f).coerceIn(0f, 1f)
            )

        private fun adaptiveKnee(
            base: Float,
            drs: Float,
            sn: Float,
            localContrast: Float,
            clipRisk: Float,
            motion: Float
        ): Float =
            (
                base -
                    0.055f * drs +
                    0.035f * sn +
                    0.025f * localContrast +
                    0.030f * clipRisk -
                    0.020f * motion
                ).coerceIn(0.46f, 0.64f)

        private fun localToneStrength(
            localContrast: Float,
            drs: Float,
            motion: Float,
            noise: Float,
            scale: Float
        ): Float =
            (
                localContrast *
                    (0.18f + 0.22f * drs) *
                    (1f - motion * 0.50f) *
                    (1f - noise * 0.35f) *
                    scale
                ).coerceIn(0f, 0.36f)
    }
}

/**
 * Thread-safe shared pipeline state enforcing preview/recording parity.
 * Both preview SurfaceTexture and MediaCodec encoder read from this single source.
 */
@Suppress("unused")
class ApexPipelineState {
    @Volatile var activeMode: ApexMode = ApexMode.SHARP
        private set
    @Volatile var currentMetrics: SceneMetrics = SceneMetrics()
        private set
    @Volatile var currentParams: ApexProfileParams = ApexProfileParams()
        private set
    @Volatile var isRecording: Boolean = false
    @Volatile var thermalDowngrade: Boolean = false

    private val lock = Any()

    /** Update scene analysis and recompute profile params. Thread-safe. */
    fun updateScene(metrics: SceneMetrics) {
        synchronized(lock) {
            val smoothedMetrics = smoothMetrics(currentMetrics, metrics)
            currentMetrics = smoothedMetrics
            currentParams = ApexProfileParams.compute(smoothedMetrics, activeMode)
        }
    }

    /** Switch APEX mode. Applies to both preview and recording simultaneously. */
    fun setMode(mode: ApexMode) {
        synchronized(lock) {
            activeMode = mode
            currentParams = ApexProfileParams.compute(currentMetrics, mode)
        }
    }

    /** Thermal auto-downgrade: SHARP → CINEMA → (external NDR10+ fallback). */
    fun thermalDowngrade() {
        synchronized(lock) {
            thermalDowngrade = true
            when (activeMode) {
                ApexMode.SHARP -> {
                    activeMode = ApexMode.CINEMA
                    currentParams = ApexProfileParams.compute(currentMetrics, ApexMode.CINEMA)
                }
                ApexMode.CINEMA, ApexMode.ZERO -> {
                    // Cannot downgrade further within APEX — caller should fallback to NDR10+
                }
            }
        }
    }

    private fun smoothMetrics(previous: SceneMetrics, current: SceneMetrics): SceneMetrics {
        if (previous.frameTimestampNs == 0L && previous.p99Nits == 0f) return current
        val alpha = (
            0.30f +
                current.exposureStability.coerceIn(0f, 1f) * 0.30f -
                current.motionScore.coerceIn(0f, 1f) * 0.12f
            ).coerceIn(0.18f, 0.62f)
        fun blend(old: Float, new: Float): Float = old + (new - old) * alpha
        return current.copy(
            dynamicRangeScore = blend(previous.dynamicRangeScore, current.dynamicRangeScore).coerceIn(0f, 1f),
            shadowRatio = blend(previous.shadowRatio, current.shadowRatio).coerceIn(0f, 1f),
            noiseRisk = blend(previous.noiseRisk, current.noiseRisk).coerceIn(0f, 1f),
            shadowNeed = blend(previous.shadowNeed, current.shadowNeed).coerceIn(0f, 1f),
            chromaNoiseRisk = blend(previous.chromaNoiseRisk, current.chromaNoiseRisk).coerceIn(0f, 1f),
            highlightRatio = blend(previous.highlightRatio, current.highlightRatio).coerceIn(0f, 1f),
            sceneWhite = blend(previous.sceneWhite, current.sceneWhite).coerceIn(0.18f, 0.82f),
            localContrastScore = blend(previous.localContrastScore, current.localContrastScore).coerceIn(0f, 1f),
            histogramSpread = blend(previous.histogramSpread, current.histogramSpread).coerceIn(0f, 1f),
            clipRisk = blend(previous.clipRisk, current.clipRisk).coerceIn(0f, 1f),
            perceptualStopEstimate = blend(previous.perceptualStopEstimate, current.perceptualStopEstimate)
        )
    }
}
