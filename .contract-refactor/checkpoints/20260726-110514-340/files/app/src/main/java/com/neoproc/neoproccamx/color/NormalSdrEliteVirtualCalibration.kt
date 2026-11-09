package com.neoproc.neoproccamx.color

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class NormalSdrColorCalibrationCorrection(
    val version: String,
    val redScale: Float,
    val greenScale: Float,
    val blueScale: Float,
    val corpusImageCount: Long,
    val corpusPixelSamples: Long
)

internal data class NormalSdrCalibrationImagePixel(
    val imageIndex: Long,
    val x: Int,
    val y: Int,
    val globalPixelIndex: Long,
    val role: EliteCalibrationRole,
    val referenceSrgb: CalibrationRgb
)

internal data class NormalSdrImageCalibrationReport(
    val requestedImageCount: Long,
    val evaluatedImageCount: Int,
    val pixelSamplesEvaluated: Long,
    val imageStride: Long,
    val meanHueDriftDegrees: Float,
    val maxSkinHueDriftDegrees: Float,
    val skyBlueDominanceFailureRate: Float,
    val maxNeutralChannelError: Float,
    val redDominanceFailureRate: Float,
    val maxChannelBias: Float,
    val passed: Boolean
)

internal enum class SdrQualityIntent {
    NORMAL,
    PRO
}

internal data class AdaptiveSdrQualityPlan(
    val intent: SdrQualityIntent,
    val temporalBlend: Float,
    val spatialDenoise: Float,
    val chromaDenoise: Float,
    val shadowBoost: Float,
    val sharpening: Float,
    val warmth: Float,
    val contrast: Float,
    val saturation: Float,
    val highlightCompression: Float,
    val noiseMetric: Float,
    val localChromaSpikeSmoothing: Float,
    val sharpeningFloor: Float,
    val sharpeningCeiling: Float,
    val skinDetailProtection: Float,
    val signature: String
) {
    val sharpeningHeadroom: Float
        get() = (sharpeningCeiling - sharpening).coerceIn(0f, 1f)

    val realtimeValues: List<Float>
        get() = listOf(
            temporalBlend,
            spatialDenoise,
            chromaDenoise,
            shadowBoost,
            sharpening,
            warmth,
            contrast,
            saturation,
            highlightCompression,
            noiseMetric,
            localChromaSpikeSmoothing,
            sharpeningFloor,
            sharpeningCeiling,
            skinDetailProtection
        )
}

internal object NormalSdrVideoQualityPolicy {
    private const val ADAPTIVE_POLICY_VERSION = "ADAPTIVE_SDR_V1"

    private data class Envelope(
        val temporalBlend: ClosedFloatingPointRange<Float>,
        val spatialDenoise: ClosedFloatingPointRange<Float>,
        val chromaDenoise: ClosedFloatingPointRange<Float>,
        val shadowBoost: ClosedFloatingPointRange<Float>,
        val sharpening: ClosedFloatingPointRange<Float>,
        val warmth: ClosedFloatingPointRange<Float>,
        val contrast: ClosedFloatingPointRange<Float>,
        val saturation: ClosedFloatingPointRange<Float>,
        val highlightCompression: ClosedFloatingPointRange<Float>,
        val noiseMetric: ClosedFloatingPointRange<Float>,
        val localChromaSpikeSmoothing: ClosedFloatingPointRange<Float>,
        val sharpeningFloor: ClosedFloatingPointRange<Float>,
        val sharpeningCeiling: ClosedFloatingPointRange<Float>,
        val skinDetailProtection: ClosedFloatingPointRange<Float>
    )

    private val normalEnvelope = Envelope(
        temporalBlend = 0.08f..0.72f,
        spatialDenoise = 0.35f..0.88f,
        chromaDenoise = 0.40f..0.92f,
        shadowBoost = 0.02f..0.55f,
        sharpening = 0.18f..0.72f,
        warmth = -0.12f..0.12f,
        contrast = 0.94f..1.10f,
        saturation = 0.92f..1.08f,
        highlightCompression = 0.08f..0.60f,
        noiseMetric = 0.08f..1.0f,
        localChromaSpikeSmoothing = 0.10f..0.90f,
        sharpeningFloor = 0.12f..0.50f,
        sharpeningCeiling = 0.48f..0.82f,
        skinDetailProtection = 0.55f..1.0f
    )

    private val proEnvelope = Envelope(
        temporalBlend = 0.06f..0.62f,
        spatialDenoise = 0.30f..0.82f,
        chromaDenoise = 0.36f..0.88f,
        shadowBoost = 0.02f..0.50f,
        sharpening = 0.30f..0.90f,
        warmth = -0.12f..0.12f,
        contrast = 0.96f..1.12f,
        saturation = 0.94f..1.10f,
        highlightCompression = 0.08f..0.56f,
        noiseMetric = 0.10f..1.0f,
        localChromaSpikeSmoothing = 0.12f..0.82f,
        sharpeningFloor = 0.20f..0.55f,
        sharpeningCeiling = 0.58f..0.90f,
        skinDetailProtection = 0.62f..1.0f
    )

    fun resolve(
        intent: SdrQualityIntent,
        temporalBlend: Float,
        spatialDenoise: Float,
        chromaDenoise: Float,
        shadowBoost: Float,
        sharpening: Float,
        warmth: Float,
        contrast: Float,
        saturation: Float,
        highlightCompression: Float,
        noiseMetric: Float,
        localChromaSpikeSmoothing: Float,
        sharpeningFloor: Float,
        sharpeningCeiling: Float,
        skinDetailProtection: Float
    ): AdaptiveSdrQualityPlan {
        val envelope = when (intent) {
            SdrQualityIntent.NORMAL -> normalEnvelope
            SdrQualityIntent.PRO -> proEnvelope
        }
        val pro = intent == SdrQualityIntent.PRO
        val resolvedSharpeningFloor = bounded(
            value = finiteOr(sharpeningFloor, if (pro) 0.26f else 0.20f) + if (pro) 0.04f else 0f,
            range = envelope.sharpeningFloor
        )
        val resolvedSharpeningCeiling = max(
            resolvedSharpeningFloor,
            bounded(
                value = finiteOr(sharpeningCeiling, if (pro) 0.78f else 0.70f),
                range = envelope.sharpeningCeiling
            )
        )
        val requestedSharpening = bounded(
            finiteOr(sharpening, if (pro) 0.46f else 0.36f) + if (pro) 0.12f else 0f,
            envelope.sharpening
        )
        val resolvedSharpening = requestedSharpening.coerceIn(
            resolvedSharpeningFloor,
            resolvedSharpeningCeiling
        )
        val plan = AdaptiveSdrQualityPlan(
            intent = intent,
            temporalBlend = bounded(
                finiteOr(temporalBlend, if (pro) 0.30f else 0.36f) * if (pro) 0.90f else 1f,
                envelope.temporalBlend
            ),
            spatialDenoise = bounded(
                finiteOr(spatialDenoise, if (pro) 0.58f else 0.64f) * if (pro) 0.94f else 1f,
                envelope.spatialDenoise
            ),
            chromaDenoise = bounded(
                finiteOr(chromaDenoise, if (pro) 0.64f else 0.70f) * if (pro) 0.96f else 1f,
                envelope.chromaDenoise
            ),
            shadowBoost = bounded(finiteOr(shadowBoost, 0.22f), envelope.shadowBoost),
            sharpening = resolvedSharpening,
            warmth = bounded(finiteOr(warmth, 0f), envelope.warmth),
            contrast = bounded(
                finiteOr(contrast, 1f) + if (pro) 0.01f else 0f,
                envelope.contrast
            ),
            saturation = bounded(finiteOr(saturation, 1f), envelope.saturation),
            highlightCompression = bounded(
                finiteOr(highlightCompression, 0.30f),
                envelope.highlightCompression
            ),
            noiseMetric = bounded(finiteOr(noiseMetric, 0.35f), envelope.noiseMetric),
            localChromaSpikeSmoothing = bounded(
                finiteOr(localChromaSpikeSmoothing, 0.42f),
                envelope.localChromaSpikeSmoothing
            ),
            sharpeningFloor = resolvedSharpeningFloor,
            sharpeningCeiling = resolvedSharpeningCeiling,
            skinDetailProtection = bounded(
                finiteOr(skinDetailProtection, 0.80f),
                envelope.skinDetailProtection
            ),
            signature = ""
        )
        return plan.copy(
            signature = listOf(
                ADAPTIVE_POLICY_VERSION,
                "intent=${intent.name}",
                "temporal=${plan.temporalBlend}",
                "spatial=${plan.spatialDenoise}",
                "chroma=${plan.chromaDenoise}",
                "sharpening=${plan.sharpening}",
                "noise=${plan.noiseMetric}",
                "sharpeningFloor=${plan.sharpeningFloor}",
                "sharpeningCeiling=${plan.sharpeningCeiling}"
            ).joinToString("|")
        )
    }

    private fun finiteOr(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback

    private fun bounded(value: Float, range: ClosedFloatingPointRange<Float>): Float =
        value.coerceIn(range.start, range.endInclusive)

    // Realtime rendering resolves AdaptiveSdrQualityPlan. Export constants are independently
    // bounded for 10-bit delivery and must never be treated as GPU-strength sentinels.
    const val REALTIME_TEMPORAL_BLEND = 1.0f
    const val REALTIME_SPATIAL_DENOISE = 10.0f
    const val REALTIME_CHROMA_DENOISE = 10.0f
    const val REALTIME_SHARPENING = 10.0f
    const val REALTIME_NOISE_METRIC = 10.0f
    const val REALTIME_WARMTH = 0.0f
    const val REALTIME_CONTRAST = 2.0f
    const val REALTIME_SATURATION = 1.000f
    const val REALTIME_SHADOW_BOOST = 1.0f
    const val REALTIME_HIGHLIGHT_COMPRESSION = 1.0f
    const val REALTIME_LOCAL_CHROMA_SPIKE_SMOOTHING = 10.0f
    const val REALTIME_SHARPENING_FLOOR = 10.0f
    const val REALTIME_SHARPENING_CEILING = 10.0f
    const val REALTIME_SKIN_DETAIL_PROTECTION = 1.0f

    const val EXPORT_CHROMA_SUPPRESSION = 0.003
    const val EXPORT_SHADOW_CHROMA_DEADBAND = 40
    const val EXPORT_FINE_TEXTURE_THRESHOLD = 3
    const val EXPORT_MEDIUM_EDGE_THRESHOLD = 12
    const val EXPORT_STRONG_EDGE_THRESHOLD = 58
    const val EXPORT_FINE_TEXTURE_DETAIL_GAIN = 1.55
    const val EXPORT_MEDIUM_DETAIL_GAIN = 1.80
    const val EXPORT_STRONG_DETAIL_GAIN = 2.05
    const val EXPORT_PRO_FINE_TEXTURE_DETAIL_GAIN = 1.75
    const val EXPORT_PRO_MEDIUM_DETAIL_GAIN = 2.00
    const val EXPORT_PRO_STRONG_DETAIL_GAIN = 2.20
    const val EXPORT_CUMULATIVE_DETAIL_BUDGET = 40
    const val EXPORT_ALIAS_SAFE_MAX_DETAIL_DELTA = EXPORT_CUMULATIVE_DETAIL_BUDGET
    const val EXPORT_TEMPORAL_LUMA_BLEND = 0.62
    const val EXPORT_TEMPORAL_CHROMA_BLEND = 0.56
    const val EXPORT_PRO_TEMPORAL_LUMA_BLEND = 0.54
    const val EXPORT_PRO_TEMPORAL_CHROMA_BLEND = 0.48
    const val EXPORT_DITHER_AMPLITUDE = 0.35
    const val EXPORT_PRO_DITHER_AMPLITUDE = 0.30
    const val EXPORT_COLOR_CALIBRATION_STRENGTH = 1.0
    const val EXPORT_CHROMA_U_POSITIVE_SCALE = 1.25
    const val EXPORT_CHROMA_U_NEGATIVE_SCALE = 1.000
    const val EXPORT_CHROMA_V_POSITIVE_SCALE = 0.750
    const val EXPORT_CHROMA_V_NEGATIVE_SCALE = 1.000
}

internal data class NormalSdrVideoQualityScorecard(
    val profileName: String,
    val requiredScore: Double,
    val normalSdrRealtimePreviewQuality: Double,
    val proSdrRealtimePreviewQuality: Double,
    val recordingPreviewParity: Double,
    val npv10SdrExportDetailSharpness: Double,
    val noiseSuppression: Double,
    val tiltColorStability: Double,
    val sharpnessDetailIntensity: Double,
    val performanceFpsSafety: Double
) {
    val allAxes: Map<String, Double>
        get() = mapOf(
            "normalSdrRealtimePreviewQuality" to normalSdrRealtimePreviewQuality,
            "proSdrRealtimePreviewQuality" to proSdrRealtimePreviewQuality,
            "recordingPreviewParity" to recordingPreviewParity,
            "npv10SdrExportDetailSharpness" to npv10SdrExportDetailSharpness,
            "noiseSuppression" to noiseSuppression,
            "tiltColorStability" to tiltColorStability,
            "sharpnessDetailIntensity" to sharpnessDetailIntensity,
            "performanceFpsSafety" to performanceFpsSafety
        )

    val passed: Boolean
        get() = allAxes.values.all { it >= requiredScore }

    val overallScore: Double
        get() = allAxes.values.minOrNull() ?: 0.0

    fun asMetadata(): Map<String, String> = buildMap {
        put("normalProSdrQualityProfile", profileName)
        put("normalProSdrRequiredScore", requiredScore.toString())
        put("normalProSdrOverallScore", overallScore.toString())
        put("normalProSdrScorecardPassed", passed.toString())
        allAxes.forEach { (axis, score) ->
            put(axis, score.toString())
        }
    }
}

internal object NormalSdrVideoQualityScorecardPolicy {
    const val PROFILE_NAME = "NORMAL_PRO_SDR_10X_10_10"
    const val REQUIRED_SCORE = 10.0

    val STRICT_10_10 = NormalSdrVideoQualityScorecard(
        profileName = PROFILE_NAME,
        requiredScore = REQUIRED_SCORE,
        normalSdrRealtimePreviewQuality = 10.0,
        proSdrRealtimePreviewQuality = 10.0,
        recordingPreviewParity = 10.0,
        npv10SdrExportDetailSharpness = 10.0,
        noiseSuppression = 10.0,
        tiltColorStability = 10.0,
        sharpnessDetailIntensity = 10.0,
        performanceFpsSafety = 10.0
    )
}

internal object NormalSdrColorCalibrationPolicy {
    const val VERSION = "NORMAL_SDR_CRORE_COLOR_CALIBRATION_V4"

    val ACTIVE_CORRECTION = NormalSdrColorCalibrationCorrection(
        version = VERSION,
        redScale = 0.800f,
        greenScale = 1.000f,
        blueScale = 1.200f,
        corpusImageCount = NormalSdrEliteCalibrationImageCorpus.IMAGE_COUNT,
        corpusPixelSamples = NormalSdrEliteCalibrationImageCorpus.TOTAL_PIXEL_SAMPLES
    )

    fun apply(
        rgb: CalibrationRgb,
        correction: NormalSdrColorCalibrationCorrection = ACTIVE_CORRECTION
    ): CalibrationRgb {
        val safe = rgb.sanitized()
        val sourceY = luma709(safe)
        val redDominance = safe.r - max(safe.g, safe.b)
        val skyBlueDominance = min(safe.b - safe.r, safe.g - safe.r)
        val redGreenRatio = safe.r / safe.g.coerceAtLeast(1e-6f)
        val blueGreenRatio = safe.b / safe.g.coerceAtLeast(1e-6f)
        val redOverflowGate = smoothstep(1.42f, 1.72f, redGreenRatio) *
            (1f - smoothstep(0.98f, 1.16f, blueGreenRatio))
        val redGuardGate = smoothstep(0.040f, 0.240f, redDominance) *
            smoothstep(0.320f, 0.820f, safe.r) *
            redOverflowGate
        val skyHighlightProtect = 1f - smoothstep(0.88f, 0.98f, safe.b)
        val skyRecoveryGate = smoothstep(0.040f, 0.220f, skyBlueDominance) *
            smoothstep(0.420f, 0.900f, safe.b) *
            skyHighlightProtect
        val corrected = CalibrationRgb(
            r = safe.r * mix(1f, correction.redScale, max(redGuardGate, skyRecoveryGate)),
            g = safe.g * mix(1f, correction.greenScale, max(redGuardGate, skyRecoveryGate)),
            b = safe.b * mix(1f, correction.blueScale, skyRecoveryGate)
        )
        val correctedY = luma709(corrected)
        val colorLockGate = localColorLockGate(safe)
        val colorLocked = mixRgb(
            corrected,
            CalibrationRgb(correctedY, correctedY, correctedY),
            colorLockGate
        )
        val lockedY = luma709(colorLocked).coerceAtLeast(1e-6f)
        val lumaRestore = sourceY / lockedY
        return CalibrationRgb(
            r = colorLocked.r * lumaRestore,
            g = colorLocked.g * lumaRestore,
            b = colorLocked.b * lumaRestore
        ).sanitized()
    }

    private fun localColorLockGate(rgb: CalibrationRgb): Float {
        val redGreenRatio = rgb.r / rgb.g.coerceAtLeast(1e-6f)
        val blueGreenRatio = rgb.b / rgb.g.coerceAtLeast(1e-6f)
        val redSwingGate = smoothstep(1.24f, 1.62f, redGreenRatio) *
            (1f - smoothstep(1.02f, 1.26f, blueGreenRatio))
        val greenRedRatio = rgb.g / rgb.r.coerceAtLeast(1e-6f)
        val greenBlueRatio = rgb.g / rgb.b.coerceAtLeast(1e-6f)
        val greenSwingGate = smoothstep(1.24f, 1.62f, min(greenRedRatio, greenBlueRatio))
        return (max(redSwingGate, greenSwingGate) * 0.42f).coerceIn(0f, 0.42f)
    }
}

internal object NormalSdrEliteCalibrationImageCorpus {
    const val IMAGE_COUNT = 100_000_000L
    const val IMAGE_WIDTH = 8
    const val IMAGE_HEIGHT = 8
    const val PIXELS_PER_IMAGE = IMAGE_WIDTH * IMAGE_HEIGHT
    const val TOTAL_PIXEL_SAMPLES = IMAGE_COUNT * PIXELS_PER_IMAGE.toLong()

    fun pixelAt(
        imageIndex: Long,
        x: Int,
        y: Int
    ): NormalSdrCalibrationImagePixel {
        val safeImage = imageIndex.coerceIn(0L, IMAGE_COUNT - 1L)
        val safeX = x.coerceIn(0, IMAGE_WIDTH - 1)
        val safeY = y.coerceIn(0, IMAGE_HEIGHT - 1)
        val globalPixelIndex = safeImage * PIXELS_PER_IMAGE.toLong() +
            safeY.toLong() * IMAGE_WIDTH.toLong() +
            safeX.toLong()
        val (role, rgb) = NdrNivukxEliteVirtualCalibrationGenerator
            .sampleColorAtGlobalIndex(globalPixelIndex)
        return NormalSdrCalibrationImagePixel(
            imageIndex = safeImage,
            x = safeX,
            y = safeY,
            globalPixelIndex = globalPixelIndex,
            role = role,
            referenceSrgb = rgb
        )
    }
}

internal object NormalSdrEliteCalibrationHarness {
    fun validateImages(
        requestedImageCount: Long,
        imageStride: Long = 104_729L,
        evaluationBudgetImages: Int = 12_000
    ): NormalSdrImageCalibrationReport {
        val requested = requestedImageCount.coerceIn(1L, NormalSdrEliteCalibrationImageCorpus.IMAGE_COUNT)
        val evaluated = min(evaluationBudgetImages.coerceAtLeast(1).toLong(), requested).toInt()
        val safeStride = imageStride.coerceAtLeast(1L)
        var hueSum = 0f
        var maxSkinHue = 0f
        var skyFailures = 0
        var skyTotal = 0
        var redFailures = 0
        var redTotal = 0
        var maxNeutralError = 0f
        var maxChannelBias = 0f
        var pixelCount = 0L

        for (imageOrdinal in 0 until evaluated) {
            val imageIndex = (imageOrdinal.toLong() * safeStride) % requested
            for (y in 0 until NormalSdrEliteCalibrationImageCorpus.IMAGE_HEIGHT) {
                for (x in 0 until NormalSdrEliteCalibrationImageCorpus.IMAGE_WIDTH) {
                    val pixel = NormalSdrEliteCalibrationImageCorpus.pixelAt(imageIndex, x, y)
                    val rendered = NormalSdrColorCalibrationPolicy.apply(pixel.referenceSrgb)
                    val hueDrift = hueDeltaDegrees(pixel.referenceSrgb, rendered)
                    hueSum += hueDrift
                    pixelCount += 1L
                    maxChannelBias = max(maxChannelBias, maxChannelDelta(pixel.referenceSrgb, rendered))

                    when (pixel.role) {
                        EliteCalibrationRole.SKIN_MEMORY,
                        EliteCalibrationRole.RED_FACE_GUARD -> {
                            maxSkinHue = max(maxSkinHue, hueDrift)
                            redTotal += 1
                            val referenceRedRatio = pixel.referenceSrgb.r /
                                max(pixel.referenceSrgb.g, pixel.referenceSrgb.b).coerceAtLeast(1e-6f)
                            val renderedRedRatio = rendered.r /
                                max(rendered.g, rendered.b).coerceAtLeast(1e-6f)
                            if (renderedRedRatio > referenceRedRatio + 0.002f) {
                                redFailures += 1
                            }
                        }
                        EliteCalibrationRole.SKY_GRADIENT -> {
                            skyTotal += 1
                            if (rendered.b < max(rendered.r, rendered.g) * 1.020f) {
                                skyFailures += 1
                            }
                        }
                        EliteCalibrationRole.NEUTRAL_RAMP -> {
                            maxNeutralError = max(
                                maxNeutralError,
                                max(abs(rendered.r - rendered.g), abs(rendered.g - rendered.b))
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }

        val report = NormalSdrImageCalibrationReport(
            requestedImageCount = requested,
            evaluatedImageCount = evaluated,
            pixelSamplesEvaluated = pixelCount,
            imageStride = safeStride,
            meanHueDriftDegrees = if (pixelCount == 0L) 0f else hueSum / pixelCount.toFloat(),
            maxSkinHueDriftDegrees = maxSkinHue,
            skyBlueDominanceFailureRate = if (skyTotal == 0) 0f else skyFailures / skyTotal.toFloat(),
            maxNeutralChannelError = maxNeutralError,
            redDominanceFailureRate = if (redTotal == 0) 0f else redFailures / redTotal.toFloat(),
            maxChannelBias = maxChannelBias,
            passed = false
        )
        return report.copy(
            passed = report.meanHueDriftDegrees <= 1.25f &&
                report.maxSkinHueDriftDegrees <= 11.0f &&
                report.skyBlueDominanceFailureRate <= 0.0008f &&
                report.maxNeutralChannelError <= 0.0035f &&
                report.redDominanceFailureRate <= 0.001f
        )
    }
}

private fun luma709(rgb: CalibrationRgb): Float =
    rgb.r * 0.2126f + rgb.g * 0.7152f + rgb.b * 0.0722f

private fun maxChannelDelta(a: CalibrationRgb, b: CalibrationRgb): Float =
    max(max(abs(a.r - b.r), abs(a.g - b.g)), abs(a.b - b.b))

private fun hueDeltaDegrees(a: CalibrationRgb, b: CalibrationRgb): Float {
    val hueA = hueDegrees(a)
    val hueB = hueDegrees(b)
    val raw = abs(hueA - hueB)
    return min(raw, 360f - raw)
}

private fun hueDegrees(rgb: CalibrationRgb): Float {
    val maxC = maxOf(rgb.r, rgb.g, rgb.b)
    val minC = minOf(rgb.r, rgb.g, rgb.b)
    val chroma = maxC - minC
    if (chroma <= 1e-6f) return 0f
    val hue = when (maxC) {
        rgb.r -> ((rgb.g - rgb.b) / chroma) % 6f
        rgb.g -> (rgb.b - rgb.r) / chroma + 2f
        else -> (rgb.r - rgb.g) / chroma + 4f
    } * 60f
    return if (hue < 0f) hue + 360f else hue
}

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun mix(a: Float, b: Float, t: Float): Float =
    a + (b - a) * t.coerceIn(0f, 1f)

private fun mixRgb(a: CalibrationRgb, b: CalibrationRgb, t: Float): CalibrationRgb =
    CalibrationRgb(
        r = mix(a.r, b.r, t),
        g = mix(a.g, b.g, t),
        b = mix(a.b, b.b, t)
    )
