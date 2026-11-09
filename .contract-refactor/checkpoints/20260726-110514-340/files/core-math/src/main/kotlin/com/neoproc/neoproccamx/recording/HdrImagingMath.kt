package com.neoproc.neoproccamx.recording

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Math-only HDR imaging model used by the camera/recording pipeline.
 * Contains radiance fusion, tone mapping, Neo dynamic-range modeling,
 * and a compact end-to-end RAW -> encoded PQ transform.
 */
object HdrImagingMath {

    // ST.2084 (PQ) constants
    private const val PQ_M1 = 2610.0 / 16384.0
    private const val PQ_M2 = 2523.0 / 32.0
    private const val PQ_C1 = 3424.0 / 4096.0
    private const val PQ_C2 = 2413.0 / 128.0
    private const val PQ_C3 = 2392.0 / 128.0
    private const val PQ_L_MAX_NITS = 10_000.0
    private const val HLG_A = 0.17883277
    private const val HLG_B = 0.28466892
    private const val HLG_C = 0.55991073
    private const val EPS = 1e-8

    // BT.2020 RGB -> XYZ
    private val BT2020_TO_XYZ = arrayOf(
        doubleArrayOf(0.6369580, 0.1446169, 0.1688810),
        doubleArrayOf(0.2627002, 0.6779981, 0.0593017),
        doubleArrayOf(0.0000000, 0.0280727, 1.0609851)
    )

    // XYZ -> BT.2020
    private val XYZ_TO_BT2020 = arrayOf(
        doubleArrayOf(1.7166512, -0.3556708, -0.2533663),
        doubleArrayOf(-0.6666844, 1.6164812, 0.0157685),
        doubleArrayOf(0.0176399, -0.0427706, 0.9421031)
    )

    // ACEScg AP1 (D60) transforms.
    private val XYZ_TO_ACESCG = arrayOf(
        doubleArrayOf(1.6410233797, -0.3248032942, -0.2364246952),
        doubleArrayOf(-0.6636628587, 1.6153315917, 0.0167563477),
        doubleArrayOf(0.0117218943, -0.0082844420, 0.9883948585)
    )

    private val ACESCG_TO_XYZ = arrayOf(
        doubleArrayOf(0.6624541811, 0.1340042065, 0.1561876870),
        doubleArrayOf(0.2722287168, 0.6740817658, 0.0536895174),
        doubleArrayOf(-0.0055746495, 0.0040607335, 1.0103391003)
    )

    enum class OutputFormat {
        GENERIC_HDR,
        HDR10,
        HDR10_PLUS,
        NDR10_PLUS,
        NIVUKX_VISION,
        DOLBY_VISION
    }

    enum class HdrDeliveryVersion {
        HLG,
        HDR10,
        HDR10_PLUS,
        NDR10_PLUS,
        NIVUKX_VISION,
        DOLBY_VISION
    }

    data class ExposureSample(
        val intensity: Double,       // normalized [0,1]
        val exposureSeconds: Double, // > 0
        val contrast: Double = 1.0,  // >= 0
        val saturation: Double = 1.0 // >= 0
    )

    data class RawSensorSample(
        val normalizedSignal: Double,  // normalized [0,1]
        val exposureSeconds: Double,   // > 0
        val analogGain: Double = 1.0,  // > 0
        val blackLevel: Double = 0.0,  // normalized
        val readNoiseVariance: Double = 1e-6
    )

    data class Hdr10StaticMetadata(
        val maxCllNits: Double,
        val maxFallNits: Double,
        val masteringPeakNits: Double = 1_000.0
    )

    data class Hdr10PlusDynamicMetadata(
        val frameIndex: Long,
        val p01Nits: Double,
        val p50Nits: Double,
        val p95Nits: Double,
        val p99Nits: Double,
        val kneeNits: Double,
        val shoulderStrength: Double,
        val toeStrength: Double
    )

    data class NeoHdrDynamicMetadata(
        val frameIndex: Long,
        val version: HdrDeliveryVersion,
        val masteringPeakNits: Double,
        val targetPeakNits: Double,
        val p10Nits: Double,
        val p50Nits: Double,
        val p90Nits: Double,
        val p99Nits: Double,
        val kneeNits: Double,
        val shoulderStrength: Double,
        val toeStrength: Double,
        val colorVolumeCompression: Double
    )

    data class DolbyVisionDynamicMetadata(
        val contentPeakNits: Double,
        val targetDisplayPeakNits: Double,
        val kneeNits: Double,
        val shoulderStrength: Double,
        val toeStrength: Double,
        val colorVolumeCompression: Double,
        val enhancementLayerGain: Double
    )

    data class EncodedHdrPixel(
        val codeR: Int,
        val codeG: Int,
        val codeB: Int,
        val bitDepth: Int,
        val luminanceNits: Double
    )

    data class LinearBt2020Rgb(
        val r: Double,
        val g: Double,
        val b: Double
    )

    data class LinearAcesCgRgb(
        val r: Double,
        val g: Double,
        val b: Double
    )

    data class Oklab(
        val l: Double,
        val a: Double,
        val b: Double
    )

    data class SensorNoiseCoefficients(
        val shotNoiseCoefficient: Double,
        val readNoiseCoefficient: Double
    )

    data class NdrRawRgbCalibration(
        val redBlackLevel: Double = 0.0,
        val greenBlackLevel: Double = 0.0,
        val blueBlackLevel: Double = 0.0,
        val redResponse: Double = 1.18,
        val greenResponse: Double = 1.0,
        val blueResponse: Double = 0.96,
        val matrixRr: Double = 1.00,
        val matrixRg: Double = -0.015,
        val matrixRb: Double = -0.008,
        val matrixGr: Double = -0.010,
        val matrixGg: Double = 1.00,
        val matrixGb: Double = -0.006,
        val matrixBr: Double = -0.004,
        val matrixBg: Double = -0.010,
        val matrixBb: Double = 1.00
    )

    data class UltraHdrVersionProfile(
        val version: HdrDeliveryVersion,
        val transferName: String,
        val sourcePeakNits: Double,
        val targetPeakNits: Double,
        val kneeRatio: Double,
        val shoulderStrength: Double,
        val shadowLift: Double,
        val saturationPreservation: Double,
        val colorVolumeCompression: Double,
        val dynamicMetadata: Boolean,
        val encoderBitDepth: Int
    )

    data class PipelineInput(
        val r: List<RawSensorSample>,
        val g: List<RawSensorSample>,
        val b: List<RawSensorSample>
    )

    fun exposureFusionWeight(
        intensity: Double,
        contrast: Double,
        saturation: Double,
        alpha: Double = 1.0,
        beta: Double = 1.0,
        gamma: Double = 1.0,
        sigma: Double = 0.2
    ): Double {
        val i = intensity.coerceIn(0.0, 1.0)
        val c = contrast.coerceAtLeast(0.0).pow(alpha)
        val s = saturation.coerceAtLeast(0.0).pow(beta)
        val e = exp(-((i - 0.5).pow(2.0)) / (2.0 * sigma * sigma)).pow(gamma)
        return (c * s * e).coerceAtLeast(0.0)
    }

    fun fuseExposureIntensity(samples: List<ExposureSample>): Double {
        if (samples.isEmpty()) return 0.0
        var weightedSum = 0.0
        var weightSum = 0.0
        for (sample in samples) {
            val w = exposureFusionWeight(
                intensity = sample.intensity,
                contrast = sample.contrast,
                saturation = sample.saturation
            )
            weightedSum += w * sample.intensity.coerceIn(0.0, 1.0)
            weightSum += w
        }
        return if (weightSum <= EPS) {
            samples.map { it.intensity }.average().coerceIn(0.0, 1.0)
        } else {
            (weightedSum / weightSum).coerceIn(0.0, 1.0)
        }
    }

    fun recoverRadiance(
        samples: List<ExposureSample>,
        inverseResponse: (Double) -> Double = { it.coerceIn(0.0, 1.0) }
    ): Double {
        if (samples.isEmpty()) return 0.0
        var weightedSum = 0.0
        var weightSum = 0.0
        for (sample in samples) {
            val t = sample.exposureSeconds.coerceAtLeast(EPS)
            val linearIntensity = inverseResponse(sample.intensity).coerceAtLeast(0.0)
            val radiance = linearIntensity / t
            val w = exposureFusionWeight(
                intensity = sample.intensity,
                contrast = sample.contrast,
                saturation = sample.saturation
            )
            weightedSum += w * radiance
            weightSum += w
        }
        return if (weightSum <= EPS) 0.0 else (weightedSum / weightSum).coerceAtLeast(0.0)
    }

    fun recoverLogRadiance(
        intensity: Double,
        exposureSeconds: Double,
        inverseResponse: (Double) -> Double = { it.coerceIn(0.0, 1.0) }
    ): Double {
        val t = exposureSeconds.coerceAtLeast(EPS)
        val linear = inverseResponse(intensity).coerceAtLeast(EPS)
        return ln(linear) - ln(t)
    }

    fun reinhardGlobal(luminance: Double, key: Double, logAverageLuminance: Double): Double {
        val l = luminance.coerceAtLeast(0.0)
        val lAvg = logAverageLuminance.coerceAtLeast(EPS)
        val mapped = (key / lAvg) * l
        return (mapped / (1.0 + mapped)).coerceIn(0.0, 1.0)
    }

    fun reinhardWhitePreserving(
        luminance: Double,
        key: Double,
        logAverageLuminance: Double,
        whitePoint: Double
    ): Double {
        val l = luminance.coerceAtLeast(0.0)
        val lAvg = logAverageLuminance.coerceAtLeast(EPS)
        val w = whitePoint.coerceAtLeast(1.0 + EPS)
        val mapped = (key / lAvg) * l
        val numerator = mapped * (1.0 + mapped / (w * w))
        return (numerator / (1.0 + mapped)).coerceIn(0.0, 1.0)
    }

    fun localToneMapLogDomain(
        luminance: Double,
        baseLuminance: Double,
        compression: Double = 0.62,
        detailGain: Double = 1.0
    ): Double {
        val l = luminance.coerceAtLeast(EPS)
        val base = baseLuminance.coerceAtLeast(EPS)
        val logL = ln(l)
        val logBase = ln(base)
        val detail = logL - logBase
        val compressed = compression.coerceIn(0.05, 0.95) * logBase + detailGain * detail
        return exp(compressed).coerceAtLeast(0.0)
    }

    fun dynamicRangeStops(maxLuminance: Double, minLuminance: Double): Double {
        val maxL = maxLuminance.coerceAtLeast(EPS)
        val minL = minLuminance.coerceAtLeast(EPS)
        return ln(maxL / minL) / ln(2.0)
    }

    fun compressDynamicRangeLog(
        luminance: Double,
        minLuminance: Double,
        maxLuminance: Double,
        targetStops: Double
    ): Double {
        val l = luminance.coerceIn(minLuminance.coerceAtLeast(EPS), maxLuminance.coerceAtLeast(EPS))
        val minL = minLuminance.coerceAtLeast(EPS)
        val maxL = maxLuminance.coerceAtLeast(minL + EPS)
        val sourceStops = dynamicRangeStops(maxL, minL).coerceAtLeast(EPS)
        val gain = targetStops.coerceAtLeast(EPS) / sourceStops
        val mapped = ((ln(l) - ln(minL)) * gain) + ln(minL)
        return exp(mapped).coerceAtLeast(0.0)
    }

    fun pqEncodeFromNits(luminanceNits: Double): Double {
        val y = (luminanceNits / PQ_L_MAX_NITS).coerceIn(0.0, 1.0)
        val yPowM1 = y.pow(PQ_M1)
        val numerator = PQ_C1 + PQ_C2 * yPowM1
        val denominator = 1.0 + PQ_C3 * yPowM1
        return (numerator / denominator).pow(PQ_M2).coerceIn(0.0, 1.0)
    }

    fun pqDecodeToNits(signal: Double): Double {
        val n = signal.coerceIn(0.0, 1.0)
        val nPow = n.pow(1.0 / PQ_M2)
        val numerator = (nPow - PQ_C1).coerceAtLeast(0.0)
        val denominator = (PQ_C2 - PQ_C3 * nPow).coerceAtLeast(EPS)
        val y = (numerator / denominator).pow(1.0 / PQ_M1).coerceIn(0.0, 1.0)
        return y * PQ_L_MAX_NITS
    }

    fun bt2100HlgOetf(sceneLinear: Double): Double {
        val e = sceneLinear.coerceIn(0.0, 1.0)
        return if (e <= 1.0 / 12.0) {
            sqrt(3.0 * e)
        } else {
            HLG_A * ln(12.0 * e - HLG_B) + HLG_C
        }.coerceIn(0.0, 1.0)
    }

    fun bt2100HlgInverseOetf(signal: Double): Double {
        val ep = signal.coerceIn(0.0, 1.0)
        return if (ep <= 0.5) {
            ep * ep / 3.0
        } else {
            (exp((ep - HLG_C) / HLG_A) + HLG_B) / 12.0
        }.coerceIn(0.0, 1.0)
    }

    fun quantize(signal: Double, bits: Int): Int {
        val maxCode = (1 shl bits) - 1
        return (signal.coerceIn(0.0, 1.0) * maxCode.toDouble()).toInt().coerceIn(0, maxCode)
    }

    fun bt2020Luminance(r: Double, g: Double, b: Double): Double =
        0.2627 * r + 0.6780 * g + 0.0593 * b

    fun resolveUltraHdrVersionProfile(
        version: HdrDeliveryVersion,
        averageLuma: Double = 0.42,
        highlightRatio: Double = 0.12,
        dynamicRangeScore: Double = 0.72
    ): UltraHdrVersionProfile {
        val avg = averageLuma.coerceIn(0.0, 1.0)
        val highlights = highlightRatio.coerceIn(0.0, 1.0)
        val dr = dynamicRangeScore.coerceIn(0.0, 1.0)
        val sceneBoost = (highlights * 0.45 + dr * 0.55).coerceIn(0.0, 1.0)
        val shadowNeed = (1.0 - avg).coerceIn(0.0, 1.0)
        return when (version) {
            HdrDeliveryVersion.HLG -> UltraHdrVersionProfile(
                version = version,
                transferName = "HLG",
                sourcePeakNits = 2_400.0 + sceneBoost * 800.0,
                targetPeakNits = 700.0 + sceneBoost * 120.0,
                kneeRatio = 0.78,
                shoulderStrength = 2.05 + sceneBoost * 0.25,
                shadowLift = 0.010 + shadowNeed * 0.010,
                saturationPreservation = 0.94,
                colorVolumeCompression = 0.18,
                dynamicMetadata = false,
                encoderBitDepth = 10
            )
            HdrDeliveryVersion.HDR10 -> UltraHdrVersionProfile(
                version = version,
                transferName = "PQ",
                sourcePeakNits = 4_000.0 + sceneBoost * 1_000.0,
                targetPeakNits = 1_000.0,
                kneeRatio = 0.72,
                shoulderStrength = 2.60 + sceneBoost * 0.25,
                shadowLift = 0.006 + shadowNeed * 0.006,
                saturationPreservation = 0.92,
                colorVolumeCompression = 0.25,
                dynamicMetadata = false,
                encoderBitDepth = 10
            )
            HdrDeliveryVersion.HDR10_PLUS -> UltraHdrVersionProfile(
                version = version,
                transferName = "PQ",
                sourcePeakNits = 5_200.0 + sceneBoost * 1_600.0,
                targetPeakNits = 1_120.0 + sceneBoost * 120.0,
                kneeRatio = 0.68,
                shoulderStrength = 3.00 + sceneBoost * 0.35,
                shadowLift = 0.004 + shadowNeed * 0.004,
                saturationPreservation = 0.88,
                colorVolumeCompression = 0.34,
                dynamicMetadata = true,
                encoderBitDepth = 10
            )
            HdrDeliveryVersion.NDR10_PLUS -> UltraHdrVersionProfile(
                version = version,
                transferName = "NeoPQ",
                sourcePeakNits = 8_400.0 + sceneBoost * 1_600.0,
                targetPeakNits = 1_420.0 + sceneBoost * 220.0,
                kneeRatio = 0.59,
                shoulderStrength = 3.72 + sceneBoost * 0.48,
                shadowLift = 0.0022 + shadowNeed * 0.0028,
                saturationPreservation = 0.79,
                colorVolumeCompression = 0.54,
                dynamicMetadata = true,
                encoderBitDepth = 10
            )
            HdrDeliveryVersion.NIVUKX_VISION -> UltraHdrVersionProfile(
                version = version,
                transferName = "NeoVisionPQ",
                sourcePeakNits = 9_400.0 + sceneBoost * 600.0,
                targetPeakNits = 1_680.0 + sceneBoost * 260.0,
                kneeRatio = 0.55,
                shoulderStrength = 4.12 + sceneBoost * 0.54,
                shadowLift = 0.0018 + shadowNeed * 0.0022,
                saturationPreservation = 0.74,
                colorVolumeCompression = 0.64,
                dynamicMetadata = true,
                encoderBitDepth = 10
            )
            HdrDeliveryVersion.DOLBY_VISION -> UltraHdrVersionProfile(
                version = version,
                transferName = "PQ",
                sourcePeakNits = 6_400.0 + sceneBoost * 2_000.0,
                targetPeakNits = 1_220.0 + sceneBoost * 160.0,
                kneeRatio = 0.64,
                shoulderStrength = 3.25 + sceneBoost * 0.40,
                shadowLift = 0.003 + shadowNeed * 0.003,
                saturationPreservation = 0.84,
                colorVolumeCompression = 0.42,
                dynamicMetadata = true,
                encoderBitDepth = 10
            )
        }
    }

    fun toneMapUltraHdrVersionRgb(
        r: Double,
        g: Double,
        b: Double,
        version: HdrDeliveryVersion,
        averageLuma: Double = 0.42,
        highlightRatio: Double = 0.12,
        dynamicRangeScore: Double = 0.72
    ): LinearBt2020Rgb {
        if (version == HdrDeliveryVersion.NDR10_PLUS ||
            version == HdrDeliveryVersion.NIVUKX_VISION
        ) {
            return toneMapNaturalDynamicRangeRgb(
                r = r,
                g = g,
                b = b,
                version = version,
                averageLuma = averageLuma,
                highlightRatio = highlightRatio,
                dynamicRangeScore = dynamicRangeScore
            )
        }
        val profile = resolveUltraHdrVersionProfile(
            version = version,
            averageLuma = averageLuma,
            highlightRatio = highlightRatio,
            dynamicRangeScore = dynamicRangeScore
        )
        val mapped = toneMapBt2100LumaPreservingRgb(
            r = r,
            g = g,
            b = b,
            sourcePeakNits = profile.sourcePeakNits,
            targetPeakNits = profile.targetPeakNits,
            shadowLift = profile.shadowLift,
            saturationPreservation = profile.saturationPreservation,
            kneeRatio = profile.kneeRatio,
            shoulderStrength = profile.shoulderStrength,
            colorVolumeCompression = profile.colorVolumeCompression
        )
        return mapped
    }

    fun toneMapNaturalDynamicRangeRgb(
        r: Double,
        g: Double,
        b: Double,
        version: HdrDeliveryVersion,
        averageLuma: Double = 0.42,
        highlightRatio: Double = 0.12,
        dynamicRangeScore: Double = 0.72
    ): LinearBt2020Rgb {
        val profile = resolveUltraHdrVersionProfile(
            version = version,
            averageLuma = averageLuma,
            highlightRatio = highlightRatio,
            dynamicRangeScore = dynamicRangeScore
        )
        val nivukx = version == HdrDeliveryVersion.NIVUKX_VISION
        val sourceR = r.coerceAtLeast(0.0)
        val sourceG = g.coerceAtLeast(0.0)
        val sourceB = b.coerceAtLeast(0.0)
        val sourceY = bt2020Luminance(sourceR, sourceG, sourceB).coerceAtLeast(EPS)
        val maxSourceRatio = maxOf(sourceR, sourceG, sourceB) / sourceY
        val sceneHighlight = highlightRatio.coerceIn(0.0, 1.0)
        val sceneDr = dynamicRangeScore.coerceIn(0.0, 1.0)
        val toeY = if (nivukx) 0.018 else 0.020
        val kneeY = (if (nivukx) 0.56 else 0.60) *
            (1.0 - sceneHighlight * 0.10) *
            (1.0 - sceneDr * 0.035)
        val whiteY = if (nivukx) 0.940 else 0.960
        val kappa = (if (nivukx) 1.92 else 1.68) + sceneHighlight * 0.38 + sceneDr * 0.24
        var targetY = when {
            sourceY <= toeY -> {
                val normalizedToe = (sourceY / toeY.coerceAtLeast(EPS)).coerceIn(0.0, 1.0)
                val lifted = normalizedToe.pow(if (nivukx) 0.74 else 0.78) * toeY
                val floorLift = (if (nivukx) 0.0032 else 0.0028) *
                    (1.0 - averageLuma.coerceIn(0.0, 1.0))
                lifted + floorLift * (1.0 - normalizedToe)
            }
            sourceY <= kneeY -> sourceY
            else -> {
                val headroom = (whiteY - kneeY).coerceAtLeast(0.001)
                // C1-continuous highlight shoulder. Earlier this was
                // kneeY + headroom*tanh(kappa*(Y-knee)/headroom), whose slope at
                // the knee was kappa (~2.1) — a contrast kick that EXPANDED
                // upper-midtones (output exceeded input just above the knee) and
                // printed a Mach band on smooth sky/skin gradients. The rational
                // shoulder below has unit slope at the knee join (no kink, no
                // expansion), is monotone, and asymptotes to whiteY. kappa is
                // retained as scene-adaptive far-field compression via the
                // quadratic term WITHOUT breaking slope==1 continuity.
                val t = (sourceY - kneeY) / headroom
                val far = (kappa - 1.0).coerceAtLeast(0.0)
                kneeY + headroom * (1.0 - 1.0 / (1.0 + t + far * t * t))
            }
        }.coerceIn(0.0, whiteY)
        val shadowWindow = 1.0 - smoothstep(0.035, 0.22, targetY)
        val shadowLift = (if (nivukx) 0.006 else 0.005) * (1.0 - averageLuma.coerceIn(0.0, 1.0))
        targetY = (targetY + shadowLift * shadowWindow * (1.0 - targetY)).coerceIn(0.0, if (nivukx) 0.945 else 0.965)
        if (nivukx) {
            val midtoneProfileSeparation = smoothstep(0.18, 0.72, targetY) *
                (1.0 - smoothstep(0.88, 0.98, targetY)) *
                0.0035
            targetY = (targetY * (1.0 - midtoneProfileSeparation)).coerceIn(0.0, 0.945)
        }
        val displayCeiling = if (nivukx) 0.945 else 0.965
        val chromaHeadroomBlend = smoothstep(1.015, 1.28, maxSourceRatio) * smoothstep(0.54, displayCeiling, targetY)
        val chromaHeadroomY = (displayCeiling / (1.0 + (maxSourceRatio - 1.0).coerceAtLeast(0.0) * 1.10))
            .coerceIn(0.52, displayCeiling)
        targetY = targetY + (chromaHeadroomY - targetY) * chromaHeadroomBlend

        val lumaScale = targetY / sourceY
        var outR = sourceR * lumaScale
        var outG = sourceG * lumaScale
        var outB = sourceB * lumaScale
        val mappedY = bt2020Luminance(outR, outG, outB).coerceAtLeast(EPS)
        val highlightWindow = smoothstep(0.52, if (nivukx) 0.88 else 0.92, targetY)
        val gamutPressure = smoothstep(1.08, 2.30, maxSourceRatio)
        val compression = (
            highlightWindow * (if (nivukx) 0.26 else 0.15) +
                gamutPressure * profile.colorVolumeCompression.coerceIn(0.0, 0.75) *
                if (nivukx) 0.70 else 0.56
            ).coerceIn(0.0, 0.82)
        val chromaPreserve = (profile.saturationPreservation * (1.0 - compression)).coerceIn(0.18, 1.0)
        outR = mappedY + (outR - mappedY) * chromaPreserve
        outG = mappedY + (outG - mappedY) * chromaPreserve
        outB = mappedY + (outB - mappedY) * chromaPreserve

        val coolOverRed = maxOf(outG, outB) / outR.coerceAtLeast(EPS)
        val cyanDominance = smoothstep(1.18, 2.90, coolOverRed)
        val cyanGuard = (highlightWindow * cyanDominance * if (nivukx) 0.46 else 0.38).coerceIn(0.0, 0.56)
        if (cyanGuard > EPS) {
            outR += (mappedY - outR) * cyanGuard * 0.76
            outG = mappedY + (outG - mappedY) * (1.0 - cyanGuard * 0.44)
            outB = mappedY + (outB - mappedY) * (1.0 - cyanGuard * 0.54)
            val adjustedY = bt2020Luminance(outR, outG, outB).coerceAtLeast(EPS)
            val rescale = mappedY / adjustedY
            outR *= rescale
            outG *= rescale
            outB *= rescale
        }

        val skinSafe = protectBt2100SkinRedRatio(outR, outG, outB)
        val distantSkinSafe = protectNaturalNdrDistantSkin(
            r = skinSafe.r,
            g = skinSafe.g,
            b = skinSafe.b,
            nivukx = nivukx
        )
        val shadowSafe = protectBt2100CyanShadowBias(distantSkinSafe.r, distantSkinSafe.g, distantSkinSafe.b)
        return fitBt2100RgbToUnitCube(shadowSafe.r, shadowSafe.g, shadowSafe.b, if (nivukx) 0.945 else 0.965)
    }

    fun normalizeRawBayerChannelsForNdr(
        r: Double,
        g: Double,
        b: Double,
        calibration: NdrRawRgbCalibration = NdrRawRgbCalibration()
    ): LinearBt2020Rgb {
        val sourceR = (r - calibration.redBlackLevel).coerceAtLeast(0.0)
        val sourceG = (g - calibration.greenBlackLevel).coerceAtLeast(0.0)
        val sourceB = (b - calibration.blueBlackLevel).coerceAtLeast(0.0)
        val sourceY = bt2020Luminance(sourceR, sourceG, sourceB).coerceAtLeast(EPS)

        val normalizedR = sourceR / calibration.redResponse.coerceIn(0.35, 4.0)
        val normalizedG = sourceG / calibration.greenResponse.coerceIn(0.35, 4.0)
        val normalizedB = sourceB / calibration.blueResponse.coerceIn(0.35, 4.0)

        var calibratedR = (
            calibration.matrixRr * normalizedR +
                calibration.matrixRg * normalizedG +
                calibration.matrixRb * normalizedB
            ).coerceAtLeast(0.0)
        var calibratedG = (
            calibration.matrixGr * normalizedR +
                calibration.matrixGg * normalizedG +
                calibration.matrixGb * normalizedB
            ).coerceAtLeast(0.0)
        var calibratedB = (
            calibration.matrixBr * normalizedR +
                calibration.matrixBg * normalizedG +
                calibration.matrixBb * normalizedB
            ).coerceAtLeast(0.0)

        val calibratedY = bt2020Luminance(calibratedR, calibratedG, calibratedB).coerceAtLeast(EPS)
        val luminanceRestore = (sourceY / calibratedY).coerceIn(0.25, 4.0)
        calibratedR *= luminanceRestore
        calibratedG *= luminanceRestore
        calibratedB *= luminanceRestore

        val neutralY = bt2020Luminance(calibratedR, calibratedG, calibratedB).coerceAtLeast(EPS)
        val redOverGreen = calibratedR / calibratedG.coerceAtLeast(EPS)
        val blueUnderGreen = calibratedB / calibratedG.coerceAtLeast(EPS)
        val skinWindow = smoothstep(0.14, 1.15, neutralY) * (1.0 - smoothstep(5.2, 12.0, neutralY))
        val redOverflow = smoothstep(1.42, 2.25, redOverGreen) * skinWindow
        if (redOverflow > EPS) {
            val targetR = calibratedG * 1.42
            val targetB = maxOf(calibratedB, calibratedG * 0.70)
            calibratedR += (targetR - calibratedR) * redOverflow.coerceIn(0.0, 0.88)
            calibratedB += (targetB - calibratedB) * redOverflow.coerceIn(0.0, 0.62)
        }

        val cyanDominance = smoothstep(1.65, 3.60, maxOf(calibratedG, calibratedB) / calibratedR.coerceAtLeast(EPS))
        if (cyanDominance > EPS) {
            val y = bt2020Luminance(calibratedR, calibratedG, calibratedB).coerceAtLeast(EPS)
            calibratedR += (y - calibratedR) * cyanDominance * 0.42
            calibratedG = y + (calibratedG - y) * (1.0 - cyanDominance * 0.28)
            calibratedB = y + (calibratedB - y) * (1.0 - cyanDominance * 0.34)
        }

        val whiteBalanced = recoverNdrNeutralWhite(calibratedR, calibratedG, calibratedB)
        val finalY = bt2020Luminance(whiteBalanced.r, whiteBalanced.g, whiteBalanced.b).coerceAtLeast(EPS)
        val finalRestore = sourceY / finalY
        return LinearBt2020Rgb(
            r = (whiteBalanced.r * finalRestore).coerceAtLeast(0.0),
            g = (whiteBalanced.g * finalRestore).coerceAtLeast(0.0),
            b = (whiteBalanced.b * finalRestore).coerceAtLeast(0.0)
        )
    }

    private fun recoverNdrNeutralWhite(r: Double, g: Double, b: Double): LinearBt2020Rgb {
        val y = bt2020Luminance(r, g, b).coerceAtLeast(EPS)
        val highlight = smoothstep(0.72, 6.0, y)
        val maxChannel = maxOf(r, g, b).coerceAtLeast(EPS)
        val minChannel = minOf(r, g, b).coerceAtLeast(0.0)
        val chromaSpread = ((maxChannel - minChannel) / maxChannel).coerceIn(0.0, 1.0)
        val neutralBlend = (highlight * chromaSpread * 0.30).coerceIn(0.0, 0.30)
        return LinearBt2020Rgb(
            r = y + (r - y) * (1.0 - neutralBlend),
            g = y + (g - y) * (1.0 - neutralBlend),
            b = y + (b - y) * (1.0 - neutralBlend)
        )
    }

    fun encodeUltraHdrVersionSignal(sceneLinear: Double, version: HdrDeliveryVersion): Double {
        val profile = resolveUltraHdrVersionProfile(version)
        val linear = sceneLinear.coerceIn(0.0, 1.0)
        return when (version) {
            HdrDeliveryVersion.HLG -> bt2100HlgOetf(linear)
            HdrDeliveryVersion.HDR10,
            HdrDeliveryVersion.HDR10_PLUS,
            HdrDeliveryVersion.DOLBY_VISION -> pqEncodeFromNits(linear * profile.targetPeakNits)
            HdrDeliveryVersion.NDR10_PLUS -> pqEncodeFromNits(ndr10PlusSignal(linear) * profile.targetPeakNits)
            HdrDeliveryVersion.NIVUKX_VISION -> pqEncodeFromNits(nivukxVisionSignal(linear) * profile.targetPeakNits)
        }
    }

    private fun ndr10PlusSignal(sceneLinear: Double): Double {
        val x = sceneLinear.coerceIn(0.0, 1.0)
        val toe = smoothstep(0.0, 0.20, x) * 0.018 * (1.0 - x)
        return (x.pow(0.92) + toe).coerceIn(0.0, 1.0)
    }

    private fun nivukxVisionSignal(sceneLinear: Double): Double {
        val x = sceneLinear.coerceIn(0.0, 1.0)
        val toe = smoothstep(0.0, 0.24, x) * 0.026 * (1.0 - x)
        return (x.pow(0.88) + toe).coerceIn(0.0, 1.0)
    }

    fun toneMapBt2100LumaPreservingRgb(
        r: Double,
        g: Double,
        b: Double,
        sourcePeakNits: Double = 4_000.0,
        targetPeakNits: Double = 1_000.0,
        shadowLift: Double = 0.0,
        saturationPreservation: Double = 0.92,
        kneeRatio: Double = 0.72,
        shoulderStrength: Double = 2.6,
        colorVolumeCompression: Double = 0.30,
        displayCeiling: Double = 0.965
    ): LinearBt2020Rgb {
        val sourcePeak = sourcePeakNits.coerceAtLeast(100.0)
        val targetPeak = targetPeakNits.coerceIn(100.0, sourcePeak)
        val safeDisplayCeiling = displayCeiling.coerceIn(0.72, 0.995)
        val sourceR = r.coerceAtLeast(0.0)
        val sourceG = g.coerceAtLeast(0.0)
        val sourceB = b.coerceAtLeast(0.0)
        val sourceY = bt2020Luminance(sourceR, sourceG, sourceB).coerceAtLeast(EPS)
        val preserve = saturationPreservation.coerceIn(0.0, 1.0)
        val sourceNits = sourceY * sourcePeak
        val mappedNits = bt2390StyleSoftShoulder(
            inputNits = sourceNits,
            sourcePeakNits = sourcePeak,
            targetPeakNits = targetPeak,
            kneeRatio = kneeRatio,
            shoulderStrength = shoulderStrength
        )
        var targetY = (mappedNits / targetPeak).coerceIn(0.0, safeDisplayCeiling)
        val maxSourceRatio = maxOf(sourceR, sourceG, sourceB) / sourceY
        if (maxSourceRatio > 1.04) {
            val chromaHeadroomY = (safeDisplayCeiling / (1.0 + (maxSourceRatio - 1.0) * preserve))
                .coerceIn(0.18, safeDisplayCeiling)
            val chromaLimitBlend = smoothstep(1.04, 1.35, maxSourceRatio)
            targetY = minOf(targetY, targetY + (chromaHeadroomY - targetY) * chromaLimitBlend)
        }
        val lift = shadowLift.coerceIn(0.0, 0.08)
        if (lift > 0.0) {
            val shadowWindow = 1.0 - smoothstep(0.035, 0.24, targetY)
            targetY = (targetY + lift * shadowWindow * (1.0 - targetY)).coerceIn(0.0, 1.0)
        }

        val lumaScale = targetY / sourceY
        val shadowChromaCompression = 1.0 - smoothstep(0.08, 0.72, targetY)
        val highlightChromaCompression = smoothstep(0.56, 0.92, targetY)
        val gamutChromaCompression = smoothstep(1.06, 1.75, maxSourceRatio)
        val chromaCompression = maxOf(
            shadowChromaCompression * 0.96,
            highlightChromaCompression * 0.23,
            gamutChromaCompression * colorVolumeCompression.coerceIn(0.0, 0.75)
        )
        val effectivePreserve = (preserve * (1.0 - chromaCompression)).coerceIn(0.0, 1.0)
        var outR = sourceR * lumaScale
        var outG = sourceG * lumaScale
        var outB = sourceB * lumaScale
        val mappedY = bt2020Luminance(outR, outG, outB)
        outR = mappedY + (outR - mappedY) * effectivePreserve
        outG = mappedY + (outG - mappedY) * effectivePreserve
        outB = mappedY + (outB - mappedY) * effectivePreserve

        val skinSafe = protectBt2100SkinRedRatio(outR, outG, outB)
        val shadowSafe = protectBt2100CyanShadowBias(skinSafe.r, skinSafe.g, skinSafe.b)
        return fitBt2100RgbToUnitCube(shadowSafe.r, shadowSafe.g, shadowSafe.b, safeDisplayCeiling)
    }

    private fun applyNeoHdrPerceptualColorVolume(
        mapped: LinearBt2020Rgb,
        profile: UltraHdrVersionProfile
    ): LinearBt2020Rgb {
        val bt2020ChromaRange = maxOf(mapped.r, mapped.g, mapped.b) -
            minOf(mapped.r, mapped.g, mapped.b)
        if (bt2020ChromaRange <= 1e-7) {
            return mapped
        }
        val aces = bt2020ToAcesCgLinear(mapped.r, mapped.g, mapped.b)
        val lab = linearAcesCgToOklab(
            aces.r.coerceAtLeast(0.0),
            aces.g.coerceAtLeast(0.0),
            aces.b.coerceAtLeast(0.0)
        )
        val highlightWindow = smoothstep(profile.kneeRatio, 1.0, lab.l)
        val compression = (
            highlightWindow * profile.colorVolumeCompression.coerceIn(0.0, 0.75)
            ).coerceIn(0.0, 0.75)
        if (compression <= EPS) {
            return mapped
        }

        val chromaScale = (1.0 - compression).coerceIn(0.25, 1.0)
        val compressedAces = oklabToLinearAcesCg(
            lab.l,
            lab.a * chromaScale,
            lab.b * chromaScale
        )
        val compressed = acesCgLinearToBt2020(
            compressedAces.r,
            compressedAces.g,
            compressedAces.b
        )
        val skinSafe = protectBt2100SkinRedRatio(compressed.r, compressed.g, compressed.b)
        val shadowSafe = protectBt2100CyanShadowBias(skinSafe.r, skinSafe.g, skinSafe.b)
        return fitBt2100RgbToUnitCube(shadowSafe.r, shadowSafe.g, shadowSafe.b, 0.965)
    }

    private fun bt2390StyleSoftShoulder(
        inputNits: Double,
        sourcePeakNits: Double,
        targetPeakNits: Double,
        kneeRatio: Double,
        shoulderStrength: Double
    ): Double {
        val input = inputNits.coerceAtLeast(0.0)
        val target = targetPeakNits.coerceAtLeast(100.0)
        val source = sourcePeakNits.coerceAtLeast(target)
        val knee = (target * kneeRatio.coerceIn(0.45, 0.90)).coerceIn(target * 0.45, target * 0.92)
        if (input <= knee) return input.coerceAtMost(target)
        val sourceRange = (source - knee).coerceAtLeast(EPS)
        val headroom = (target - knee).coerceAtLeast(EPS)
        val shoulderX = ((input - knee) / sourceRange).coerceAtLeast(0.0)
        val shoulder = 1.0 - exp(-shoulderStrength.coerceIn(1.0, 5.0) * shoulderX)
        return (knee + headroom * shoulder).coerceIn(0.0, target)
    }

    private fun fitBt2100RgbToUnitCube(
        r: Double,
        g: Double,
        b: Double,
        displayCeiling: Double
    ): LinearBt2020Rgb {
        val y = bt2020Luminance(r, g, b).coerceIn(0.0, 1.0)
        var rr = r
        var gg = g
        var bb = b

        val maxChannel = maxOf(rr, gg, bb)
        if (maxChannel > displayCeiling) {
            val scale = ((displayCeiling - y) / (maxChannel - y).coerceAtLeast(EPS)).coerceIn(0.0, 1.0)
            rr = y + (rr - y) * scale
            gg = y + (gg - y) * scale
            bb = y + (bb - y) * scale
        }

        val minChannel = minOf(rr, gg, bb)
        if (minChannel < 0.0) {
            val scale = (y / (y - minChannel).coerceAtLeast(EPS)).coerceIn(0.0, 1.0)
            rr = y + (rr - y) * scale
            gg = y + (gg - y) * scale
            bb = y + (bb - y) * scale
        }

        return LinearBt2020Rgb(
            r = rr.coerceIn(0.0, 1.0),
            g = gg.coerceIn(0.0, 1.0),
            b = bb.coerceIn(0.0, 1.0)
        )
    }

    private fun protectBt2100SkinRedRatio(
        r: Double,
        g: Double,
        b: Double
    ): LinearBt2020Rgb {
        if (r <= g || g < b) {
            return LinearBt2020Rgb(r, g, b)
        }
        val y = bt2020Luminance(r, g, b).coerceAtLeast(EPS)
        val redGreenRatio = r / g.coerceAtLeast(EPS)
        val warmSkinWindow = smoothstep(1.12, 1.55, redGreenRatio) *
            (1.0 - smoothstep(2.65, 3.20, redGreenRatio))
        val midtoneWindow = smoothstep(0.08, 0.32, y) *
            (1.0 - smoothstep(0.78, 0.96, y))
        val redExcess = smoothstep(1.38, 1.82, redGreenRatio)
        val guardStrength = (warmSkinWindow * midtoneWindow * redExcess * 0.42).coerceIn(0.0, 0.34)
        if (guardStrength <= EPS) {
            return LinearBt2020Rgb(r, g, b)
        }

        var rr = r + (minOf(r, g * 1.42) - r) * guardStrength
        val gg = g
        var bb = b + (maxOf(b, g * 0.70) - b) * guardStrength * 0.45
        val adjustedY = bt2020Luminance(rr, gg, bb).coerceAtLeast(EPS)
        val lumaScale = y / adjustedY
        rr *= lumaScale
        bb *= lumaScale
        return LinearBt2020Rgb(rr, gg * lumaScale, bb)
    }

    private fun protectNaturalNdrDistantSkin(
        r: Double,
        g: Double,
        b: Double,
        nivukx: Boolean
    ): LinearBt2020Rgb {
        if (r <= g || g < b) {
            return LinearBt2020Rgb(r, g, b)
        }
        val y = bt2020Luminance(r, g, b).coerceAtLeast(EPS)
        val redGreenRatio = r / g.coerceAtLeast(EPS)
        val skinWindow = smoothstep(1.34, 1.70, redGreenRatio) *
            smoothstep(0.10, 0.60, y) *
            (1.0 - smoothstep(0.78, 0.96, y))
        val guardStrength = (skinWindow * if (nivukx) 0.95 else 0.88).coerceIn(0.0, 0.94)
        if (guardStrength <= EPS) {
            return LinearBt2020Rgb(r, g, b)
        }

        var rr = r + (minOf(r, g * if (nivukx) 1.34 else 1.38) - r) * guardStrength
        val gg = g
        var bb = b + (maxOf(b, g * if (nivukx) 0.76 else 0.72) - b) * guardStrength
        rr = rr.coerceAtMost(gg * 1.42)
        bb = bb.coerceAtLeast(gg * 0.70)

        val adjustedY = bt2020Luminance(rr, gg, bb).coerceAtLeast(EPS)
        val lumaScale = y / adjustedY
        return LinearBt2020Rgb(
            r = rr * lumaScale,
            g = gg * lumaScale,
            b = bb * lumaScale
        )
    }

    private fun protectBt2100CyanShadowBias(
        r: Double,
        g: Double,
        b: Double
    ): LinearBt2020Rgb {
        val y = bt2020Luminance(r, g, b).coerceAtLeast(EPS)
        val maxCool = maxOf(g, b)
        if (y >= 0.32 || maxCool <= r * 1.12) {
            return LinearBt2020Rgb(r, g, b)
        }
        val shadowWindow = 1.0 - smoothstep(0.05, 0.32, y)
        val cyanDominance = smoothstep(1.18, 2.85, maxCool / r.coerceAtLeast(EPS))
        val guardStrength = (shadowWindow * cyanDominance * 0.58).coerceIn(0.0, 0.48)
        if (guardStrength <= EPS) {
            return LinearBt2020Rgb(r, g, b)
        }

        var rr = r + (maxOf(r, maxCool * 0.52) - r) * guardStrength
        var gg = y + (g - y) * (1.0 - guardStrength * 0.42)
        var bb = y + (b - y) * (1.0 - guardStrength * 0.58)
        val adjustedY = bt2020Luminance(rr, gg, bb).coerceAtLeast(EPS)
        val lumaScale = y / adjustedY
        rr *= lumaScale
        gg *= lumaScale
        bb *= lumaScale
        return LinearBt2020Rgb(rr, gg, bb)
    }

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        if (edge0 == edge1) return if (value >= edge1) 1.0 else 0.0
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun neoHdrMasteringPeakNits(version: HdrDeliveryVersion): Double =
        when (version) {
            HdrDeliveryVersion.NIVUKX_VISION -> 10_000.0
            HdrDeliveryVersion.NDR10_PLUS -> 8_400.0
            else -> resolveUltraHdrVersionProfile(version).sourcePeakNits.coerceAtMost(10_000.0)
        }

    private fun neoHdrTargetPeakNits(version: HdrDeliveryVersion): Double =
        when (version) {
            HdrDeliveryVersion.NIVUKX_VISION -> 1_680.0
            HdrDeliveryVersion.NDR10_PLUS -> 1_420.0
            else -> resolveUltraHdrVersionProfile(version).targetPeakNits
        }

    private fun neoHdrColorVolumeCompression(version: HdrDeliveryVersion): Double =
        when (version) {
            HdrDeliveryVersion.NIVUKX_VISION -> 0.64
            HdrDeliveryVersion.NDR10_PLUS -> 0.54
            else -> resolveUltraHdrVersionProfile(version).colorVolumeCompression
        }

    private fun nitsToMetadataByte(nits: Double): Byte =
        ((nits.coerceIn(0.0, 10_000.0) / 10_000.0) * 255.0)
            .roundToInt()
            .coerceIn(0, 255)
            .toByte()

    private fun writeUInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }

    fun bt2020ToXyz(r: Double, g: Double, b: Double): DoubleArray {
        val x = BT2020_TO_XYZ[0][0] * r + BT2020_TO_XYZ[0][1] * g + BT2020_TO_XYZ[0][2] * b
        val y = BT2020_TO_XYZ[1][0] * r + BT2020_TO_XYZ[1][1] * g + BT2020_TO_XYZ[1][2] * b
        val z = BT2020_TO_XYZ[2][0] * r + BT2020_TO_XYZ[2][1] * g + BT2020_TO_XYZ[2][2] * b
        return doubleArrayOf(x, y, z)
    }

    fun xyzToBt2020(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val r = XYZ_TO_BT2020[0][0] * x + XYZ_TO_BT2020[0][1] * y + XYZ_TO_BT2020[0][2] * z
        val g = XYZ_TO_BT2020[1][0] * x + XYZ_TO_BT2020[1][1] * y + XYZ_TO_BT2020[1][2] * z
        val b = XYZ_TO_BT2020[2][0] * x + XYZ_TO_BT2020[2][1] * y + XYZ_TO_BT2020[2][2] * z
        return Triple(r, g, b)
    }

    fun bt2020ToAcesCgLinear(r: Double, g: Double, b: Double): LinearAcesCgRgb {
        val xyz = bt2020ToXyz(r, g, b)
        return LinearAcesCgRgb(
            r = multiply3(XYZ_TO_ACESCG[0], xyz[0], xyz[1], xyz[2]),
            g = multiply3(XYZ_TO_ACESCG[1], xyz[0], xyz[1], xyz[2]),
            b = multiply3(XYZ_TO_ACESCG[2], xyz[0], xyz[1], xyz[2])
        )
    }

    fun acesCgLinearToBt2020(r: Double, g: Double, b: Double): LinearBt2020Rgb {
        val x = multiply3(ACESCG_TO_XYZ[0], r, g, b)
        val y = multiply3(ACESCG_TO_XYZ[1], r, g, b)
        val z = multiply3(ACESCG_TO_XYZ[2], r, g, b)
        val bt2020 = xyzToBt2020(x, y, z)
        return LinearBt2020Rgb(bt2020.first, bt2020.second, bt2020.third)
    }

    fun linearAcesCgToOklab(r: Double, g: Double, b: Double): Oklab {
        val l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
        val m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
        val s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
        val lRoot = Math.cbrt(l)
        val mRoot = Math.cbrt(m)
        val sRoot = Math.cbrt(s)
        return Oklab(
            l = 0.2104542553 * lRoot + 0.7936177850 * mRoot - 0.0040720468 * sRoot,
            a = 1.9779984951 * lRoot - 2.4285922050 * mRoot + 0.4505937099 * sRoot,
            b = 0.0259040371 * lRoot + 0.7827717662 * mRoot - 0.8086757660 * sRoot
        )
    }

    fun oklabToLinearAcesCg(l: Double, a: Double, b: Double): LinearAcesCgRgb {
        val lPrime = l + 0.3963377774 * a + 0.2158037573 * b
        val mPrime = l - 0.1055613458 * a - 0.0638541728 * b
        val sPrime = l - 0.0894841775 * a - 1.2914855480 * b
        val lmsL = lPrime * lPrime * lPrime
        val lmsM = mPrime * mPrime * mPrime
        val lmsS = sPrime * sPrime * sPrime
        return LinearAcesCgRgb(
            r = 4.0767416621 * lmsL - 3.3077115913 * lmsM + 0.2309699292 * lmsS,
            g = -1.2684380046 * lmsL + 2.6097574011 * lmsM - 0.3413193965 * lmsS,
            b = -0.0041960863 * lmsL - 0.7034186147 * lmsM + 1.7076147010 * lmsS
        )
    }

    fun jndBlueNoiseDitherAmplitude(
        normalizedLuma: Double,
        noiseMetric: Double,
        abrBackpressureScale: Double = 1.0
    ): Double {
        val luma = normalizedLuma.coerceIn(0.0, 1.0)
        val noise = noiseMetric.coerceIn(0.0, 1.0)
        val backpressure = abrBackpressureScale.coerceIn(0.0, 1.0)
        val shadowSensitivity = 1.0 - smoothstep(0.02, 0.32, luma) * 0.38
        val highlightGuard = 1.0 - smoothstep(0.78, 1.0, luma) * 0.72
        return ((0.18 + sqrt(luma.coerceAtLeast(0.0)) * 0.42 + noise * 0.18) *
            shadowSensitivity *
            highlightGuard *
            backpressure).coerceIn(0.0, 1.0)
    }

    fun sensorNoiseCoefficientsForIso(
        iso: Double,
        baseIso: Double = 100.0,
        baseShotNoiseCoefficient: Double = 0.0048,
        baseReadNoiseCoefficient: Double = 0.00075
    ): SensorNoiseCoefficients {
        val gain = (iso.coerceAtLeast(1.0) / baseIso.coerceAtLeast(1.0)).coerceIn(0.01, 256.0)
        return SensorNoiseCoefficients(
            shotNoiseCoefficient = (baseShotNoiseCoefficient * sqrt(gain)).coerceIn(0.00001, 0.25),
            readNoiseCoefficient = (baseReadNoiseCoefficient * gain.pow(0.72)).coerceIn(0.000001, 0.10)
        )
    }

    private fun multiply3(row: DoubleArray, x: Double, y: Double, z: Double): Double =
        row[0] * x + row[1] * y + row[2] * z

    fun estimateHdr10StaticMetadata(
        averageLuma: Double,
        highlightRatio: Double,
        dynamicRangeScore: Double
    ): Hdr10StaticMetadata {
        val avg = averageLuma.coerceIn(0.0, 1.0)
        val hi = highlightRatio.coerceIn(0.0, 1.0)
        val dr = dynamicRangeScore.coerceIn(0.0, 1.0)
        val maxCll = (400.0 + 2_200.0 * hi + 2_000.0 * dr).coerceIn(400.0, 4_000.0)
        val maxFall = (120.0 + 700.0 * avg + 250.0 * dr).coerceIn(100.0, maxCll)
        val masteringPeak = if (maxCll > 1_500.0) 4_000.0 else 1_000.0
        return Hdr10StaticMetadata(
            maxCllNits = maxCll,
            maxFallNits = maxFall,
            masteringPeakNits = masteringPeak
        )
    }

    fun estimateUltraHdrStaticMetadata(
        version: HdrDeliveryVersion,
        averageLuma: Double,
        highlightRatio: Double,
        dynamicRangeScore: Double
    ): Hdr10StaticMetadata {
        val base = estimateHdr10StaticMetadata(
            averageLuma = averageLuma,
            highlightRatio = highlightRatio,
            dynamicRangeScore = dynamicRangeScore
        )
        val profile = resolveUltraHdrVersionProfile(
            version = version,
            averageLuma = averageLuma,
            highlightRatio = highlightRatio,
            dynamicRangeScore = dynamicRangeScore
        )
        val hi = highlightRatio.coerceIn(0.0, 1.0)
        val dr = dynamicRangeScore.coerceIn(0.0, 1.0)
        val avg = averageLuma.coerceIn(0.0, 1.0)
        val sceneSeverity = (hi * 0.52 + dr * 0.48).coerceIn(0.0, 1.0)
        val versionGain = when (version) {
            HdrDeliveryVersion.HLG -> 0.82
            HdrDeliveryVersion.HDR10 -> 1.00
            HdrDeliveryVersion.HDR10_PLUS -> 1.12
            HdrDeliveryVersion.NDR10_PLUS -> 1.30
            HdrDeliveryVersion.NIVUKX_VISION -> 1.40
            HdrDeliveryVersion.DOLBY_VISION -> 1.22
        }
        val targetDrivenPeak = profile.targetPeakNits * when (version) {
            HdrDeliveryVersion.HLG -> 1.05
            HdrDeliveryVersion.HDR10 -> 1.18
            HdrDeliveryVersion.HDR10_PLUS -> 1.32
            HdrDeliveryVersion.NDR10_PLUS -> 1.50
            HdrDeliveryVersion.NIVUKX_VISION -> 1.62
            HdrDeliveryVersion.DOLBY_VISION -> 1.45
        }
        val rawMaxCll = maxOf(
            base.maxCllNits * versionGain,
            targetDrivenPeak,
            520.0 + sceneSeverity * 3_450.0
        )
        val masteringPeak = if (
            version == HdrDeliveryVersion.HDR10_PLUS ||
            version == HdrDeliveryVersion.NDR10_PLUS ||
            version == HdrDeliveryVersion.NIVUKX_VISION ||
            version == HdrDeliveryVersion.DOLBY_VISION ||
            rawMaxCll >= 1_350.0 ||
            sceneSeverity >= 0.58
        ) {
            4_000.0
        } else {
            1_000.0
        }
        val maxCll = rawMaxCll.coerceIn(400.0, masteringPeak)
        val fallGain = when (version) {
            HdrDeliveryVersion.HLG -> 0.92
            HdrDeliveryVersion.HDR10 -> 1.00
            HdrDeliveryVersion.HDR10_PLUS -> 1.10
            HdrDeliveryVersion.NDR10_PLUS -> 1.22
            HdrDeliveryVersion.NIVUKX_VISION -> 1.30
            HdrDeliveryVersion.DOLBY_VISION -> 1.18
        }
        val maxFall = (
            base.maxFallNits * fallGain +
                sceneSeverity * 110.0 +
                avg * 80.0
            ).coerceIn(100.0, maxCll * 0.78)
        return Hdr10StaticMetadata(
            maxCllNits = maxCll,
            maxFallNits = maxFall,
            masteringPeakNits = masteringPeak
        )
    }

    fun toneMapHdr10Static(
        inputNits: Double,
        metadata: Hdr10StaticMetadata,
        displayPeakNits: Double
    ): Double {
        val displayPeak = displayPeakNits.coerceAtLeast(80.0)
        val input = inputNits.coerceAtLeast(0.0)
        val contentPeak = metadata.maxCllNits.coerceAtLeast(displayPeak)
        val knee = (metadata.maxFallNits * 1.1).coerceIn(displayPeak * 0.5, contentPeak * 0.95)
        if (input <= knee) return input.coerceAtMost(displayPeak)
        val x = ((input - knee) / (contentPeak - knee).coerceAtLeast(EPS)).coerceIn(0.0, 1.0)
        val shoulder = 1.0 - exp(-3.2 * x)
        return (knee + (displayPeak - knee) * shoulder).coerceIn(0.0, displayPeak)
    }

    fun histogramPercentile(histogram: IntArray, fraction: Double): Int {
        if (histogram.isEmpty()) return 0
        val total = histogram.sum().coerceAtLeast(1)
        val target = (total * fraction.coerceIn(0.0, 1.0)).toInt()
        var running = 0
        for (i in histogram.indices) {
            running += histogram[i]
            if (running >= target) return i
        }
        return histogram.lastIndex
    }

    fun deriveHdr10PlusDynamicMetadata(
        histogram: IntArray,
        frameIndex: Long,
        masteringPeakNits: Double = 1_000.0
    ): Hdr10PlusDynamicMetadata {
        val p01 = histogramPercentile(histogram, 0.01) / 255.0 * masteringPeakNits
        val p50 = histogramPercentile(histogram, 0.50) / 255.0 * masteringPeakNits
        val p95 = histogramPercentile(histogram, 0.95) / 255.0 * masteringPeakNits
        val p99 = histogramPercentile(histogram, 0.99) / 255.0 * masteringPeakNits
        val knee = (0.65 * p95 + 0.35 * p99).coerceAtLeast(p50 + 1.0)
        val shoulder = (1.15 + (p99 - p95) / masteringPeakNits * 4.0).coerceIn(1.05, 2.4)
        val toe = (0.75 + p01 / masteringPeakNits * 2.2).coerceIn(0.7, 1.6)
        return Hdr10PlusDynamicMetadata(
            frameIndex = frameIndex,
            p01Nits = p01,
            p50Nits = p50,
            p95Nits = p95,
            p99Nits = p99,
            kneeNits = knee,
            shoulderStrength = shoulder,
            toeStrength = toe
        )
    }

    fun deriveNeoHdrDynamicMetadata(
        histogram: IntArray,
        frameIndex: Long,
        version: HdrDeliveryVersion,
        masteringPeakNits: Double = neoHdrMasteringPeakNits(version),
        targetPeakNits: Double = neoHdrTargetPeakNits(version)
    ): NeoHdrDynamicMetadata {
        val peak = masteringPeakNits.coerceIn(100.0, 10_000.0)
        val p10 = histogramPercentile(histogram, 0.10) / 255.0 * peak
        val p50 = histogramPercentile(histogram, 0.50) / 255.0 * peak
        val p90 = histogramPercentile(histogram, 0.90) / 255.0 * peak
        val p99 = histogramPercentile(histogram, 0.99) / 255.0 * peak
        val knee = (0.65 * p90 + 0.35 * p99)
            .coerceAtLeast(p50 + 1.0)
            .coerceAtMost(4_000.0)
        val shoulder = (1.15 + ((p99 - p90).coerceAtLeast(0.0) / peak) * 4.0)
            .coerceIn(1.05, 4.12)
        val toe = (0.75 + (p10 / peak) * 2.2).coerceIn(0.7, 1.6)
        return NeoHdrDynamicMetadata(
            frameIndex = frameIndex,
            version = version,
            masteringPeakNits = peak,
            targetPeakNits = targetPeakNits.coerceIn(100.0, peak),
            p10Nits = p10,
            p50Nits = p50,
            p90Nits = p90,
            p99Nits = p99,
            kneeNits = knee,
            shoulderStrength = shoulder,
            toeStrength = toe,
            colorVolumeCompression = neoHdrColorVolumeCompression(version)
        )
    }

    fun buildNeoHdrMetadataPayload32(metadata: NeoHdrDynamicMetadata): ByteArray {
        val payload = ByteArray(32)
        writeUInt16(payload, 0, metadata.masteringPeakNits.roundToInt().coerceIn(0, 65_535))
        writeUInt16(payload, 2, metadata.targetPeakNits.roundToInt().coerceIn(0, 65_535))
        payload[4] = nitsToMetadataByte(metadata.p10Nits)
        payload[5] = nitsToMetadataByte(metadata.p50Nits)
        payload[6] = nitsToMetadataByte(metadata.p90Nits)
        payload[7] = nitsToMetadataByte(metadata.p99Nits)
        writeUInt16(payload, 8, metadata.kneeNits.roundToInt().coerceIn(0, 65_535))
        payload[10] = (metadata.shoulderStrength * 50.0).roundToInt().coerceIn(0, 255).toByte()
        payload[11] = (metadata.toeStrength * 50.0).roundToInt().coerceIn(0, 255).toByte()
        payload[12] = (metadata.colorVolumeCompression * 100.0).roundToInt().coerceIn(0, 255).toByte()
        return payload
    }

    fun buildNeoHdrItutT35Payload(metadata: NeoHdrDynamicMetadata): ByteArray =
        byteArrayOf(
            0xB5.toByte(),
            0x00,
            0x3C,
            0x00,
            0x01
        ) + buildNeoHdrMetadataPayload32(metadata)

    fun nivukxQuantumPqSignal(
        luminanceNits: Double,
        version: HdrDeliveryVersion = HdrDeliveryVersion.NIVUKX_VISION
    ): Double {
        val peak = neoHdrMasteringPeakNits(version)
        val normalized = (luminanceNits / peak).coerceIn(0.0, 1.0)
        val powered = normalized.pow(PQ_M1)
        return ((PQ_C1 + PQ_C2 * powered) / (1.0 + PQ_C3 * powered)).pow(PQ_M2)
            .coerceIn(0.0, 1.0)
    }

    fun quantumJndBlueNoiseAmplitude(pqLuma: Double): Double {
        val y = pqLuma.coerceIn(0.0, 1.0)
        val highlightGuard = 1.0 - smoothstep(0.78, 1.0, y)
        return (1.0 / 1023.0) * (0.18 + 0.42 * sqrt(y)) * highlightGuard
    }

    fun toneMapHdr10PlusAdaptive(
        inputNits: Double,
        metadata: Hdr10PlusDynamicMetadata,
        displayPeakNits: Double
    ): Double {
        val displayPeak = displayPeakNits.coerceAtLeast(80.0)
        val x = inputNits.coerceAtLeast(0.0)
        val knee = metadata.kneeNits.coerceAtLeast(1.0)
        if (x <= knee) {
            val toeNorm = (x / knee).coerceIn(0.0, 1.0)
            return (knee * toeNorm.pow(1.0 / metadata.toeStrength)).coerceIn(0.0, displayPeak)
        }
        val normalized = ((x - knee) / (metadata.p99Nits - knee).coerceAtLeast(EPS)).coerceIn(0.0, 1.0)
        val shoulder = 1.0 - exp(-metadata.shoulderStrength * normalized)
        return (knee + (displayPeak - knee) * shoulder).coerceIn(0.0, displayPeak)
    }

    fun dolbyVisionReconstructSignal(
        baseLayerSignal: Double,
        enhancementLayerSignal: Double,
        gain: Double,
        offset: Double = 0.0
    ): Double =
        (baseLayerSignal + gain * enhancementLayerSignal + offset).coerceIn(0.0, 1.0)

    fun toneMapDolbyVision(
        inputNits: Double,
        metadata: DolbyVisionDynamicMetadata,
        displayPeakNits: Double
    ): Double {
        val x = inputNits.coerceAtLeast(0.0)
        val displayPeak = displayPeakNits.coerceAtLeast(80.0)
        val contentPeak = metadata.contentPeakNits.coerceAtLeast(displayPeak)
        val knee = metadata.kneeNits.coerceIn(displayPeak * 0.4, contentPeak * 0.95)
        if (x <= knee) {
            val toeNorm = (x / knee).coerceIn(0.0, 1.0)
            return (knee * toeNorm.pow(1.0 / metadata.toeStrength)).coerceIn(0.0, displayPeak)
        }
        val shoulderX = ((x - knee) / (contentPeak - knee).coerceAtLeast(EPS)).coerceIn(0.0, 1.0)
        val shoulder = 1.0 - exp(-metadata.shoulderStrength * shoulderX)
        return (knee + (displayPeak - knee) * shoulder).coerceIn(0.0, displayPeak)
    }

    fun colorVolumeMapBt2020(
        r: Double,
        g: Double,
        b: Double,
        inputPeakNits: Double,
        outputPeakNits: Double,
        compression: Double
    ): Triple<Double, Double, Double> {
        val luminanceIn = bt2020Luminance(r, g, b).coerceAtLeast(EPS)
        val peakScale = (outputPeakNits / inputPeakNits.coerceAtLeast(EPS)).coerceIn(0.05, 1.0)
        val chromaCompression = compression.coerceIn(0.0, 1.0)
        val targetLuminance = luminanceIn * peakScale
        val scale = targetLuminance / luminanceIn
        val rr = r * scale
        val gg = g * scale
        val bb = b * scale
        val gray = bt2020Luminance(rr, gg, bb)
        return Triple(
            gray + (rr - gray) * (1.0 - chromaCompression * (1.0 - peakScale)),
            gray + (gg - gray) * (1.0 - chromaCompression * (1.0 - peakScale)),
            gray + (bb - gray) * (1.0 - chromaCompression * (1.0 - peakScale))
        )
    }

    fun processRawToHdrEncoded(
        input: PipelineInput,
        format: OutputFormat,
        displayPeakNits: Double,
        hdr10StaticMetadata: Hdr10StaticMetadata = Hdr10StaticMetadata(1_000.0, 400.0, 1_000.0),
        hdr10PlusDynamicMetadata: Hdr10PlusDynamicMetadata? = null,
        dolbyMetadata: DolbyVisionDynamicMetadata? = null
    ): EncodedHdrPixel {
        val linearR = recoverRadianceFromRaw(input.r)
        val linearG = recoverRadianceFromRaw(input.g)
        val linearB = recoverRadianceFromRaw(input.b)

        if (format == OutputFormat.NDR10_PLUS || format == OutputFormat.NIVUKX_VISION) {
            val normalizedRaw = normalizeRawBayerChannelsForNdr(
                r = linearR,
                g = linearG,
                b = linearB,
                calibration = NdrRawRgbCalibration()
            )
            val sceneLuma = bt2020Luminance(normalizedRaw.r, normalizedRaw.g, normalizedRaw.b)
                .coerceAtLeast(EPS)
            val version = if (format == OutputFormat.NIVUKX_VISION) {
                HdrDeliveryVersion.NIVUKX_VISION
            } else {
                HdrDeliveryVersion.NDR10_PLUS
            }
            val mapped = toneMapNaturalDynamicRangeRgb(
                r = normalizedRaw.r,
                g = normalizedRaw.g,
                b = normalizedRaw.b,
                version = version,
                averageLuma = (sceneLuma / 8.0).coerceIn(0.05, 0.72),
                highlightRatio = (sceneLuma / 4.0).coerceIn(0.0, 1.0),
                dynamicRangeScore = (sceneLuma / 3.0).coerceIn(0.38, 1.0)
            )
            val encodedLift = if (version == HdrDeliveryVersion.NIVUKX_VISION) 1.10 else 1.06
            val encodedMapped = fitBt2100RgbToUnitCube(
                r = mapped.r * encodedLift,
                g = mapped.g * encodedLift,
                b = mapped.b * encodedLift,
                displayCeiling = 1.0
            )
            return EncodedHdrPixel(
                codeR = quantize(pqEncodeFromNits(encodedMapped.r.coerceAtLeast(0.0) * displayPeakNits), 10),
                codeG = quantize(pqEncodeFromNits(encodedMapped.g.coerceAtLeast(0.0) * displayPeakNits), 10),
                codeB = quantize(pqEncodeFromNits(encodedMapped.b.coerceAtLeast(0.0) * displayPeakNits), 10),
                bitDepth = 10,
                luminanceNits = bt2020Luminance(encodedMapped.r, encodedMapped.g, encodedMapped.b) * displayPeakNits
            )
        }

        val sceneLuma = bt2020Luminance(linearR, linearG, linearB).coerceAtLeast(EPS)
        val sceneNits = (sceneLuma * hdr10StaticMetadata.masteringPeakNits).coerceAtLeast(0.0)

        val mappedNits = when (format) {
            OutputFormat.GENERIC_HDR -> {
                val logAvg = 0.18
                val mapped = reinhardWhitePreserving(
                    luminance = sceneLuma,
                    key = 0.18,
                    logAverageLuminance = logAvg,
                    whitePoint = 4.0
                )
                mapped * displayPeakNits
            }
            OutputFormat.HDR10 -> toneMapHdr10Static(
                inputNits = sceneNits,
                metadata = hdr10StaticMetadata,
                displayPeakNits = displayPeakNits
            )
            OutputFormat.HDR10_PLUS -> toneMapHdr10PlusAdaptive(
                inputNits = sceneNits,
                metadata = hdr10PlusDynamicMetadata ?: Hdr10PlusDynamicMetadata(
                    frameIndex = 0L,
                    p01Nits = 1.0,
                    p50Nits = 120.0,
                    p95Nits = 700.0,
                    p99Nits = 950.0,
                    kneeNits = 640.0,
                    shoulderStrength = 1.5,
                    toeStrength = 1.0
                ),
                displayPeakNits = displayPeakNits
            )
            OutputFormat.NDR10_PLUS -> (
                maxOf(
                    toneMapHdr10Static(
                        inputNits = sceneNits,
                        metadata = hdr10StaticMetadata,
                        displayPeakNits = displayPeakNits
                    ),
                    toneMapHdr10PlusAdaptive(
                        inputNits = sceneNits,
                        metadata = hdr10PlusDynamicMetadata ?: Hdr10PlusDynamicMetadata(
                            frameIndex = 0L,
                            p01Nits = 0.8,
                            p50Nits = 110.0,
                            p95Nits = 760.0,
                            p99Nits = 1_120.0,
                            kneeNits = 610.0,
                            shoulderStrength = 1.85,
                            toeStrength = 1.08
                        ),
                        displayPeakNits = displayPeakNits
                    )
                ) * 1.04
                ).coerceAtMost(displayPeakNits)
            OutputFormat.NIVUKX_VISION -> (
                maxOf(
                    toneMapHdr10Static(
                        inputNits = sceneNits,
                        metadata = hdr10StaticMetadata,
                        displayPeakNits = displayPeakNits
                    ),
                    toneMapHdr10PlusAdaptive(
                        inputNits = sceneNits,
                        metadata = hdr10PlusDynamicMetadata ?: Hdr10PlusDynamicMetadata(
                            frameIndex = 0L,
                            p01Nits = 0.6,
                            p50Nits = 104.0,
                            p95Nits = 820.0,
                            p99Nits = 1_220.0,
                            kneeNits = 580.0,
                            shoulderStrength = 2.15,
                            toeStrength = 1.12
                        ),
                        displayPeakNits = displayPeakNits
                    )
                ) * 1.08
                ).coerceAtMost(displayPeakNits)
            OutputFormat.DOLBY_VISION -> toneMapDolbyVision(
                inputNits = sceneNits,
                metadata = dolbyMetadata ?: DolbyVisionDynamicMetadata(
                    contentPeakNits = hdr10StaticMetadata.maxCllNits,
                    targetDisplayPeakNits = displayPeakNits,
                    kneeNits = displayPeakNits * 0.65,
                    shoulderStrength = 1.6,
                    toeStrength = 1.0,
                    colorVolumeCompression = 0.28,
                    enhancementLayerGain = 0.2
                ),
                displayPeakNits = displayPeakNits
            )
        }.coerceAtLeast(0.0)

        val outLuma = (mappedNits / displayPeakNits.coerceAtLeast(EPS)).coerceIn(0.0, 1.0)
        val channelScale = outLuma / sceneLuma
        var outR = linearR * channelScale
        var outG = linearG * channelScale
        var outB = linearB * channelScale

        if (format == OutputFormat.DOLBY_VISION || format == OutputFormat.NIVUKX_VISION) {
            val mapped = colorVolumeMapBt2020(
                r = outR,
                g = outG,
                b = outB,
                inputPeakNits = hdr10StaticMetadata.masteringPeakNits,
                outputPeakNits = displayPeakNits,
                compression = if (format == OutputFormat.NIVUKX_VISION) {
                    0.36
                } else {
                    dolbyMetadata?.colorVolumeCompression ?: 0.28
                }
            )
            outR = mapped.first
            outG = mapped.second
            outB = mapped.third
        }

        val pqR = pqEncodeFromNits(outR.coerceAtLeast(0.0) * displayPeakNits)
        val pqG = pqEncodeFromNits(outG.coerceAtLeast(0.0) * displayPeakNits)
        val pqB = pqEncodeFromNits(outB.coerceAtLeast(0.0) * displayPeakNits)
        val bitDepth = 10

        return EncodedHdrPixel(
            codeR = quantize(pqR, bitDepth),
            codeG = quantize(pqG, bitDepth),
            codeB = quantize(pqB, bitDepth),
            bitDepth = bitDepth,
            luminanceNits = mappedNits
        )
    }

    private fun recoverRadianceFromRaw(samples: List<RawSensorSample>): Double {
        if (samples.isEmpty()) return 0.0
        var weighted = 0.0
        var weightSum = 0.0
        for (sample in samples) {
            val gain = sample.analogGain.coerceAtLeast(EPS)
            val t = sample.exposureSeconds.coerceAtLeast(EPS)
            val linear = ((sample.normalizedSignal - sample.blackLevel) / gain).coerceAtLeast(0.0)
            val radiance = linear / t
            val shotVariance = linear.coerceAtLeast(EPS)
            val variance = shotVariance + sample.readNoiseVariance.coerceAtLeast(EPS)
            val weight = 1.0 / variance
            weighted += weight * radiance
            weightSum += weight
        }
        return if (weightSum <= EPS) 0.0 else weighted / weightSum
    }
}

