package com.neoproc.neoproccamx.color

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal enum class EliteCalibrationRole {
    NEUTRAL_RAMP,
    SKIN_MEMORY,
    SKY_GRADIENT,
    HIGHLIGHT_ROLLOFF,
    SHADOW_CHROMA,
    FOLIAGE_MEMORY,
    SATURATION_BOUNDARY,
    WB_ILLUMINANT,
    RED_FACE_GUARD,
    CYAN_GREEN_GUARD,
    TEXTURE_DETAIL,
    LOW_NOISE_DARK
}

internal enum class EliteCalibrationMode {
    NDR10,
    NIVUKX_VISION
}

internal data class EliteVirtualCalibrationSample(
    val index: Int,
    val role: EliteCalibrationRole,
    val referenceSrgb: CalibrationRgb
)

internal data class EliteVirtualCalibrationReport(
    val sampleCount: Int,
    val mode: EliteCalibrationMode,
    val stride: Int,
    val meanHueDriftDegrees: Float,
    val maxSkinHueDriftDegrees: Float,
    val skyBlueDominanceFailureRate: Float,
    val highlightClipRate: Float,
    val maxNeutralChannelError: Float,
    val maxChannelBias: Float,
    val passed: Boolean
)

internal data class EliteCalibrationImagePixel(
    val imageIndex: Long,
    val x: Int,
    val y: Int,
    val globalPixelIndex: Long,
    val role: EliteCalibrationRole,
    val referenceSrgb: CalibrationRgb
)

internal data class EliteImageCalibrationReport(
    val requestedImageCount: Long,
    val evaluatedImageCount: Int,
    val pixelSamplesEvaluated: Long,
    val mode: EliteCalibrationMode,
    val imageStride: Long,
    val meanHueDriftDegrees: Float,
    val maxSkinHueDriftDegrees: Float,
    val skyBlueDominanceFailureRate: Float,
    val highlightClipRate: Float,
    val maxNeutralChannelError: Float,
    val maxChannelBias: Float,
    val roleReports: List<EliteRoleCalibrationReport>,
    val passed: Boolean
)

internal data class EliteRoleCalibrationReport(
    val role: EliteCalibrationRole,
    val pixelSamples: Long,
    val meanHueDriftDegrees: Float,
    val maxHueDriftDegrees: Float,
    val skyBlueDominanceFailureRate: Float,
    val highlightClipRate: Float,
    val maxNeutralChannelError: Float,
    val maxChannelBias: Float,
    val passed: Boolean
)

internal object NdrNivukxEliteVirtualCalibrationGenerator {
    const val LAKH_SAMPLE_COUNT = 100_000
    const val ELITE_SAMPLE_COUNT = 1_000_000
    const val CRORE_STRESS_SAMPLE_COUNT = 10_000_000
    const val CRORE_IMAGE_PIXEL_SAMPLE_COUNT = 6_400_000_000L

    private val roles = EliteCalibrationRole.entries.toTypedArray()
    private const val GOLDEN_RATIO_CONJUGATE = 0.6180339887498949

    fun sampleAt(index: Int): EliteVirtualCalibrationSample {
        val safeIndex = index.coerceIn(0, CRORE_STRESS_SAMPLE_COUNT - 1)
        val (role, rgb) = sampleColorAtGlobalIndex(safeIndex.toLong())
        return EliteVirtualCalibrationSample(
            index = safeIndex,
            role = role,
            referenceSrgb = rgb
        )
    }

    internal fun sampleColorAtGlobalIndex(globalIndex: Long): Pair<EliteCalibrationRole, CalibrationRgb> {
        val safeIndex = globalIndex.coerceIn(0L, CRORE_IMAGE_PIXEL_SAMPLE_COUNT - 1L)
        val role = roles[(safeIndex % roles.size.toLong()).toInt()]
        val t = radicalInverseBase2(safeIndex / roles.size.toLong())
        val u = fract((safeIndex + 1L) * GOLDEN_RATIO_CONJUGATE)
        val v = fract((safeIndex + 17L) * 0.4142135623730950)
        return role to colorFor(role, t, u, v).sanitized()
    }

    private fun colorFor(
        role: EliteCalibrationRole,
        t: Float,
        u: Float,
        v: Float
    ): CalibrationRgb =
        when (role) {
            EliteCalibrationRole.NEUTRAL_RAMP -> {
                val gray = t.pow(1.72f)
                CalibrationRgb(gray, gray, gray)
            }
            EliteCalibrationRole.SKIN_MEMORY -> interpolateSkin(t, u)
            EliteCalibrationRole.SKY_GRADIENT -> {
                val haze = u * 0.055f
                CalibrationRgb(
                    r = lerp(0.135f, 0.755f, t) + haze * 0.70f,
                    g = lerp(0.300f, 0.875f, t) + haze * 0.38f,
                    b = max(lerp(0.780f, 1.000f, t) - haze * 0.18f, 0.62f)
                )
            }
            EliteCalibrationRole.HIGHLIGHT_ROLLOFF -> {
                val base = when ((u * 8f).toInt().coerceIn(0, 7)) {
                    0 -> CalibrationRgb(1.000f, 1.000f, 1.000f)
                    1 -> CalibrationRgb(0.960f, 0.970f, 0.975f)
                    2 -> CalibrationRgb(0.900f, 0.915f, 0.920f)
                    3 -> CalibrationRgb(0.980f, 0.920f, 0.820f)
                    4 -> CalibrationRgb(0.850f, 0.920f, 1.000f)
                    5 -> CalibrationRgb(0.980f, 0.560f, 0.500f)
                    6 -> CalibrationRgb(0.720f, 0.950f, 0.940f)
                    else -> CalibrationRgb(0.690f, 0.820f, 0.990f)
                }
                scale(base, lerp(0.72f, 1.00f, t))
            }
            EliteCalibrationRole.SHADOW_CHROMA -> hsvToRgb(
                hue = u,
                saturation = lerp(0.14f, 0.70f, v),
                value = 0.004f + 0.165f * t.pow(1.35f)
            )
            EliteCalibrationRole.FOLIAGE_MEMORY -> CalibrationRgb(
                r = lerp(0.035f, 0.360f, t),
                g = lerp(0.125f, 0.640f, t.pow(0.82f)),
                b = lerp(0.030f, 0.220f, t) * (1f - 0.22f * u)
            )
            EliteCalibrationRole.SATURATION_BOUNDARY -> hsvToRgb(
                hue = u,
                saturation = lerp(0.42f, 1.00f, t),
                value = lerp(0.32f, 1.00f, v)
            )
            EliteCalibrationRole.WB_ILLUMINANT -> {
                val gray = lerp(0.035f, 0.960f, t)
                val cast = when ((u * 5f).toInt().coerceIn(0, 4)) {
                    0 -> CalibrationRgb(1.00f, 0.86f, 0.66f)
                    1 -> CalibrationRgb(1.00f, 0.94f, 0.82f)
                    2 -> CalibrationRgb(1.00f, 1.00f, 1.00f)
                    3 -> CalibrationRgb(0.86f, 0.94f, 1.00f)
                    else -> CalibrationRgb(0.74f, 0.88f, 1.00f)
                }
                multiply(cast, gray)
            }
            EliteCalibrationRole.RED_FACE_GUARD -> CalibrationRgb(
                r = lerp(0.420f, 0.985f, t),
                g = lerp(0.028f, 0.560f, t.pow(0.90f)),
                b = lerp(0.028f, 0.500f, t.pow(1.15f))
            )
            EliteCalibrationRole.CYAN_GREEN_GUARD -> CalibrationRgb(
                r = lerp(0.030f, 0.720f, t),
                g = lerp(0.360f, 0.980f, t),
                b = lerp(0.300f, 1.000f, t.pow(0.82f))
            )
            EliteCalibrationRole.TEXTURE_DETAIL -> {
                val base = lerp(0.070f, 0.900f, t)
                val warm = (u - 0.5f) * 0.050f
                CalibrationRgb(base + warm, base, base - warm)
            }
            EliteCalibrationRole.LOW_NOISE_DARK -> {
                val base = 0.0015f + 0.105f * t.pow(1.55f)
                CalibrationRgb(
                    r = base * (1.0f + u * 0.26f),
                    g = base,
                    b = base * (1.0f + (1.0f - u) * 0.22f)
                )
            }
        }

    private fun interpolateSkin(t: Float, u: Float): CalibrationRgb {
        val anchors = arrayOf(
            CalibrationRgb(0.220f, 0.115f, 0.075f),
            CalibrationRgb(0.360f, 0.210f, 0.145f),
            CalibrationRgb(0.560f, 0.350f, 0.260f),
            CalibrationRgb(0.760f, 0.560f, 0.450f),
            CalibrationRgb(0.930f, 0.720f, 0.610f),
            CalibrationRgb(0.620f, 0.225f, 0.245f)
        )
        val scaled = t * anchors.size
        val index = floor(scaled.toDouble()).toInt().coerceIn(0, anchors.lastIndex)
        val next = (index + 1).coerceAtMost(anchors.lastIndex)
        val local = (scaled - index).coerceIn(0f, 1f)
        val warmth = (u - 0.5f) * 0.045f
        return CalibrationRgb(
            r = lerp(anchors[index].r, anchors[next].r, local) + warmth,
            g = lerp(anchors[index].g, anchors[next].g, local),
            b = lerp(anchors[index].b, anchors[next].b, local) - warmth * 0.45f
        )
    }

    private fun radicalInverseBase2(value: Long): Float {
        var bits = value
        var result = 0f
        var fraction = 0.5f
        while (bits > 0) {
            result += fraction * (bits and 1L).toFloat()
            bits = bits ushr 1
            fraction *= 0.5f
        }
        return result
    }

    private fun fract(value: Double): Float =
        (value - floor(value)).toFloat()
}

internal object NdrNivukxEliteCalibrationImageCorpus {
    const val IMAGE_COUNT = 100_000_000L
    const val IMAGE_WIDTH = 8
    const val IMAGE_HEIGHT = 8
    const val PIXELS_PER_IMAGE = IMAGE_WIDTH * IMAGE_HEIGHT
    const val TOTAL_PIXEL_SAMPLES = IMAGE_COUNT * PIXELS_PER_IMAGE.toLong()

    fun pixelAt(
        imageIndex: Long,
        x: Int,
        y: Int
    ): EliteCalibrationImagePixel {
        val safeImage = imageIndex.coerceIn(0L, IMAGE_COUNT - 1L)
        val safeX = x.coerceIn(0, IMAGE_WIDTH - 1)
        val safeY = y.coerceIn(0, IMAGE_HEIGHT - 1)
        val globalPixelIndex = safeImage * PIXELS_PER_IMAGE.toLong() +
            safeY.toLong() * IMAGE_WIDTH.toLong() +
            safeX.toLong()
        val (role, rgb) = NdrNivukxEliteVirtualCalibrationGenerator
            .sampleColorAtGlobalIndex(globalPixelIndex)
        return EliteCalibrationImagePixel(
            imageIndex = safeImage,
            x = safeX,
            y = safeY,
            globalPixelIndex = globalPixelIndex,
            role = role,
            referenceSrgb = rgb
        )
    }
}

internal object NdrNivukxEliteCalibrationHarness {
    fun validate(
        sampleCount: Int,
        mode: EliteCalibrationMode,
        stride: Int = 37
    ): EliteVirtualCalibrationReport {
        val count = sampleCount.coerceIn(1, NdrNivukxEliteVirtualCalibrationGenerator.CRORE_STRESS_SAMPLE_COUNT)
        val safeStride = stride.coerceAtLeast(1)
        var hueSum = 0f
        var maxSkinHue = 0f
        var skyFailures = 0
        var skyTotal = 0
        var highlightClips = 0
        var highlightTotal = 0
        var maxNeutralError = 0f
        var maxChannelBias = 0f

        for (i in 0 until count) {
            val sampleIndex = ((i.toLong() * safeStride.toLong()) %
                NdrNivukxEliteVirtualCalibrationGenerator.CRORE_STRESS_SAMPLE_COUNT).toInt()
            val sample = NdrNivukxEliteVirtualCalibrationGenerator.sampleAt(sampleIndex)
            val rendered = applyReferenceTone(sample.referenceSrgb, mode)
            val hueDrift = hueDeltaDegrees(sample.referenceSrgb, rendered)
            hueSum += hueDrift
            maxChannelBias = max(maxChannelBias, maxChannelDelta(sample.referenceSrgb, rendered))

            when (sample.role) {
                EliteCalibrationRole.SKIN_MEMORY,
                EliteCalibrationRole.RED_FACE_GUARD -> maxSkinHue = max(maxSkinHue, hueDrift)
                EliteCalibrationRole.SKY_GRADIENT -> {
                    skyTotal += 1
                    if (rendered.b < max(rendered.r, rendered.g) * 1.015f) {
                        skyFailures += 1
                    }
                }
                EliteCalibrationRole.HIGHLIGHT_ROLLOFF -> {
                    highlightTotal += 1
                    if (maxOf(rendered.r, rendered.g, rendered.b) >= 0.9995f &&
                        chroma(rendered) > 0.045f
                    ) {
                        highlightClips += 1
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

        val report = EliteVirtualCalibrationReport(
            sampleCount = count,
            mode = mode,
            stride = safeStride,
            meanHueDriftDegrees = hueSum / count.toFloat(),
            maxSkinHueDriftDegrees = maxSkinHue,
            skyBlueDominanceFailureRate = if (skyTotal == 0) 0f else skyFailures / skyTotal.toFloat(),
            highlightClipRate = if (highlightTotal == 0) 0f else highlightClips / highlightTotal.toFloat(),
            maxNeutralChannelError = maxNeutralError,
            maxChannelBias = maxChannelBias,
            passed = false
        )
        return report.copy(
            passed = report.meanHueDriftDegrees <= 0.50f &&
                report.maxSkinHueDriftDegrees <= 2.0f &&
                report.skyBlueDominanceFailureRate <= 0.002f &&
                report.highlightClipRate <= 0.001f &&
                report.maxNeutralChannelError <= 0.006f
        )
    }

    fun validateImages(
        requestedImageCount: Long,
        mode: EliteCalibrationMode,
        imageStride: Long = 104_729L,
        evaluationBudgetImages: Int = 12_000
    ): EliteImageCalibrationReport {
        val requested = requestedImageCount.coerceIn(1L, NdrNivukxEliteCalibrationImageCorpus.IMAGE_COUNT)
        val evaluated = min(evaluationBudgetImages.coerceAtLeast(1).toLong(), requested).toInt()
        val safeStride = imageStride.coerceAtLeast(1L)
        var hueSum = 0f
        var maxSkinHue = 0f
        var skyFailures = 0
        var skyTotal = 0
        var highlightClips = 0
        var highlightTotal = 0
        var maxNeutralError = 0f
        var maxChannelBias = 0f
        var pixelCount = 0L
        val roleAccumulators = EliteCalibrationRole.entries.associateWith { RoleAccumulator(it) }

        for (imageOrdinal in 0 until evaluated) {
            val imageIndex = (imageOrdinal.toLong() * safeStride) % requested
            for (y in 0 until NdrNivukxEliteCalibrationImageCorpus.IMAGE_HEIGHT) {
                for (x in 0 until NdrNivukxEliteCalibrationImageCorpus.IMAGE_WIDTH) {
                    val pixel = NdrNivukxEliteCalibrationImageCorpus.pixelAt(imageIndex, x, y)
                    val rendered = applyReferenceTone(pixel.referenceSrgb, mode)
                    val hueDrift = hueDeltaDegrees(pixel.referenceSrgb, rendered)
                    hueSum += hueDrift
                    pixelCount += 1L
                    maxChannelBias = max(maxChannelBias, maxChannelDelta(pixel.referenceSrgb, rendered))
                    roleAccumulators.getValue(pixel.role).record(pixel.referenceSrgb, rendered, hueDrift)

                    when (pixel.role) {
                        EliteCalibrationRole.SKIN_MEMORY,
                        EliteCalibrationRole.RED_FACE_GUARD -> maxSkinHue = max(maxSkinHue, hueDrift)
                        EliteCalibrationRole.SKY_GRADIENT -> {
                            skyTotal += 1
                            if (rendered.b < max(rendered.r, rendered.g) * 1.015f) {
                                skyFailures += 1
                            }
                        }
                        EliteCalibrationRole.HIGHLIGHT_ROLLOFF -> {
                            highlightTotal += 1
                            if (maxOf(rendered.r, rendered.g, rendered.b) >= 0.9995f &&
                                chroma(rendered) > 0.045f
                            ) {
                                highlightClips += 1
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

        val roleReports = EliteCalibrationRole.entries
            .map { role -> roleAccumulators.getValue(role).toReport() }
        val report = EliteImageCalibrationReport(
            requestedImageCount = requested,
            evaluatedImageCount = evaluated,
            pixelSamplesEvaluated = pixelCount,
            mode = mode,
            imageStride = safeStride,
            meanHueDriftDegrees = if (pixelCount == 0L) 0f else hueSum / pixelCount.toFloat(),
            maxSkinHueDriftDegrees = maxSkinHue,
            skyBlueDominanceFailureRate = if (skyTotal == 0) 0f else skyFailures / skyTotal.toFloat(),
            highlightClipRate = if (highlightTotal == 0) 0f else highlightClips / highlightTotal.toFloat(),
            maxNeutralChannelError = maxNeutralError,
            maxChannelBias = maxChannelBias,
            roleReports = roleReports,
            passed = false
        )
        return report.copy(
            passed = report.meanHueDriftDegrees <= 0.35f &&
                report.maxSkinHueDriftDegrees <= 1.5f &&
                report.skyBlueDominanceFailureRate <= 0.001f &&
                report.highlightClipRate <= 0.0005f &&
                report.maxNeutralChannelError <= 0.004f &&
                roleReports.all { it.passed }
        )
    }

    private class RoleAccumulator(
        private val role: EliteCalibrationRole
    ) {
        private var hueSum = 0f
        private var skyFailures = 0
        private var skyTotal = 0
        private var highlightClips = 0
        private var highlightTotal = 0
        private var maxNeutralError = 0f
        private var maxChannelBias = 0f
        private var maxHue = 0f
        private var pixelSamples = 0L

        fun record(
            reference: CalibrationRgb,
            rendered: CalibrationRgb,
            hueDrift: Float
        ) {
            pixelSamples += 1L
            hueSum += hueDrift
            maxHue = max(maxHue, hueDrift)
            maxChannelBias = max(maxChannelBias, maxChannelDelta(reference, rendered))
            when (role) {
                EliteCalibrationRole.SKY_GRADIENT -> {
                    skyTotal += 1
                    if (rendered.b < max(rendered.r, rendered.g) * 1.015f) {
                        skyFailures += 1
                    }
                }
                EliteCalibrationRole.HIGHLIGHT_ROLLOFF -> {
                    highlightTotal += 1
                    if (maxOf(rendered.r, rendered.g, rendered.b) >= 0.9995f &&
                        chroma(rendered) > 0.045f
                    ) {
                        highlightClips += 1
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

        fun toReport(): EliteRoleCalibrationReport {
            val meanHue = if (pixelSamples == 0L) 0f else hueSum / pixelSamples.toFloat()
            val skyFailureRate = if (skyTotal == 0) 0f else skyFailures / skyTotal.toFloat()
            val clipRate = if (highlightTotal == 0) 0f else highlightClips / highlightTotal.toFloat()
            val passed = pixelSamples > 0L &&
                meanHue <= 0.60f &&
                when (role) {
                    EliteCalibrationRole.SKIN_MEMORY,
                    EliteCalibrationRole.RED_FACE_GUARD -> maxHue <= 1.5f
                    EliteCalibrationRole.SKY_GRADIENT -> skyFailureRate <= 0.001f
                    EliteCalibrationRole.HIGHLIGHT_ROLLOFF -> clipRate <= 0.0005f
                    EliteCalibrationRole.NEUTRAL_RAMP -> maxNeutralError <= 0.004f
                    else -> true
                }

            return EliteRoleCalibrationReport(
                role = role,
                pixelSamples = pixelSamples,
                meanHueDriftDegrees = meanHue,
                maxHueDriftDegrees = maxHue,
                skyBlueDominanceFailureRate = skyFailureRate,
                highlightClipRate = clipRate,
                maxNeutralChannelError = maxNeutralError,
                maxChannelBias = maxChannelBias,
                passed = passed
            )
        }
    }

    private fun applyReferenceTone(
        rgb: CalibrationRgb,
        mode: EliteCalibrationMode
    ): CalibrationRgb {
        val safe = rgb.sanitized()
        val y = luma2020(safe).coerceAtLeast(1e-6f)
        val shadowLift = if (mode == EliteCalibrationMode.NIVUKX_VISION) 0.014f else 0.010f
        val highlightStart = if (mode == EliteCalibrationMode.NIVUKX_VISION) 0.78f else 0.82f
        val liftedY = y + (1f - smoothstep(0.025f, 0.16f, y)) * shadowLift * (1f - y)
        val highlight = smoothstep(highlightStart, 1.0f, liftedY)
        val compressedY = if (liftedY <= highlightStart) {
            liftedY
        } else {
            highlightStart + (1f - highlightStart) *
                (1f - kotlin.math.exp(-(liftedY - highlightStart) / (1f - highlightStart)))
        }
        val chromaKeep = 1f - highlight * if (mode == EliteCalibrationMode.NIVUKX_VISION) 0.10f else 0.08f
        val scaled = scaleAboutLuma(safe, y, compressedY, chromaKeep)
        return when (mode) {
            EliteCalibrationMode.NDR10 -> measuredGuards(scaled, skyStrength = 0.10f, skinStrength = 0.06f)
            EliteCalibrationMode.NIVUKX_VISION -> measuredGuards(scaled, skyStrength = 0.14f, skinStrength = 0.07f)
        }.sanitized()
    }

    private fun measuredGuards(
        rgb: CalibrationRgb,
        skyStrength: Float,
        skinStrength: Float
    ): CalibrationRgb {
        var out = rgb
        val skin = skinWeight(out)
        if (skin > 0f) {
            val targetR = min(out.r, out.g * 1.26f)
            val targetB = out.b.coerceIn(out.g * 0.68f, out.g * 0.92f)
            out = CalibrationRgb(
                r = lerp(out.r, targetR, skin * skinStrength),
                g = out.g,
                b = lerp(out.b, targetB, skin * skinStrength)
            )
        }

        val sky = skyWeight(out)
        if (sky > 0f) {
            val blueFloor = max(out.b, max(out.r, out.g) * 1.035f)
            out = CalibrationRgb(
                r = lerp(out.r, min(out.r, blueFloor * 0.86f), sky * skyStrength),
                g = lerp(out.g, min(out.g, blueFloor * 0.94f), sky * skyStrength),
                b = lerp(out.b, blueFloor, sky * skyStrength)
            )
        }
        return out
    }

    private fun scaleAboutLuma(
        rgb: CalibrationRgb,
        sourceY: Float,
        targetY: Float,
        chromaKeep: Float
    ): CalibrationRgb {
        val scale = targetY / sourceY.coerceAtLeast(1e-6f)
        val scaled = CalibrationRgb(rgb.r * scale, rgb.g * scale, rgb.b * scale)
        return CalibrationRgb(
            r = targetY + (scaled.r - targetY) * chromaKeep,
            g = targetY + (scaled.g - targetY) * chromaKeep,
            b = targetY + (scaled.b - targetY) * chromaKeep
        )
    }

    private fun skinWeight(rgb: CalibrationRgb): Float {
        val rg = rgb.r - rgb.g
        val gb = rgb.g - rgb.b
        val y = luma709(rgb)
        return (
            smoothstep(0.012f, 0.15f, rg) *
                (1f - smoothstep(0.34f, 0.68f, rg)) *
                smoothstep(-0.015f, 0.13f, gb) *
                (1f - smoothstep(0.30f, 0.62f, gb)) *
                smoothstep(0.08f, 0.26f, y) *
                (1f - smoothstep(1.05f, 1.45f, y))
            ).coerceIn(0f, 1f)
    }

    private fun skyWeight(rgb: CalibrationRgb): Float =
        (
            smoothstep(1.04f, 2.30f, rgb.b / rgb.r.coerceAtLeast(1e-6f)) *
                smoothstep(0.48f, 1.04f, rgb.g / rgb.b.coerceAtLeast(1e-6f)) *
                (1f - skinWeight(rgb))
            ).coerceIn(0f, 1f)
}

private fun hsvToRgb(hue: Float, saturation: Float, value: Float): CalibrationRgb {
    val h = ((hue % 1f) + 1f) % 1f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val c = v * s
    val hp = h * 6f
    val x = c * (1f - abs((hp % 2f) - 1f))
    val (rp, gp, bp) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = v - c
    return CalibrationRgb(rp + m, gp + m, bp + m)
}

private fun scale(rgb: CalibrationRgb, amount: Float): CalibrationRgb =
    CalibrationRgb(rgb.r * amount, rgb.g * amount, rgb.b * amount)

private fun multiply(rgb: CalibrationRgb, amount: Float): CalibrationRgb =
    CalibrationRgb(rgb.r * amount, rgb.g * amount, rgb.b * amount)

private fun lerp(a: Float, b: Float, t: Float): Float =
    a + (b - a) * t.coerceIn(0f, 1f)

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun luma709(rgb: CalibrationRgb): Float =
    rgb.r * 0.2126f + rgb.g * 0.7152f + rgb.b * 0.0722f

private fun luma2020(rgb: CalibrationRgb): Float =
    rgb.r * 0.2627f + rgb.g * 0.6780f + rgb.b * 0.0593f

private fun chroma(rgb: CalibrationRgb): Float =
    maxOf(rgb.r, rgb.g, rgb.b) - minOf(rgb.r, rgb.g, rgb.b)

private fun maxChannelDelta(reference: CalibrationRgb, rendered: CalibrationRgb): Float =
    maxOf(abs(reference.r - rendered.r), abs(reference.g - rendered.g), abs(reference.b - rendered.b))

private fun hueDeltaDegrees(reference: CalibrationRgb, rendered: CalibrationRgb): Float {
    val delta = abs(hueDegrees(reference) - hueDegrees(rendered)) % 360f
    return min(delta, 360f - delta)
}

private fun hueDegrees(rgb: CalibrationRgb): Float {
    val maxValue = maxOf(rgb.r, rgb.g, rgb.b)
    val minValue = minOf(rgb.r, rgb.g, rgb.b)
    val delta = maxValue - minValue
    if (delta <= 1e-6f) return 0f
    val hue = when (maxValue) {
        rgb.r -> 60f * (((rgb.g - rgb.b) / delta) % 6f)
        rgb.g -> 60f * (((rgb.b - rgb.r) / delta) + 2f)
        else -> 60f * (((rgb.r - rgb.g) / delta) + 4f)
    }
    return if (hue < 0f) hue + 360f else hue
}

private fun Float.pow(power: Float): Float =
    toDouble().pow(power.toDouble()).toFloat()
