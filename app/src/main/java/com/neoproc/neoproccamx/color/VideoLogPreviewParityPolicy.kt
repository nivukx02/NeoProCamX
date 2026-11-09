package com.neoproc.neoproccamx.color

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

data class VideoLogPreviewParity(
    val contrastLift: Float = 0f,
    val saturationLift: Float = 0f,
    val shadowBiasScale: Float = 1f,
    val highlightBiasScale: Float = 1f,
    val highLoadPenaltyScale: Float = 1f,
    val lutSaturationBoost: Float = 0f,
    val brightnessLift: Float = 0f
)

data class VideoDisplayCompressedRgb(
    val red: Float,
    val green: Float,
    val blue: Float,
    val inputHueRadians: Float,
    val outputHueRadians: Float,
    val inputChroma: Float,
    val outputChroma: Float,
    val outputLightness: Float
)

internal object VideoLogPreviewParityPolicy {
    private val PROFILE_SEPARATOR_PATTERN = Regex("[^a-z0-9]+")

    fun resolve(profileName: String, logEnabled: Boolean): VideoLogPreviewParity =
        resolve(
            profileName = profileName,
            logEnabled = logEnabled,
            highEndEnabled = HighEndQualityPolicy.enabled,
            strictHighEndOnly = HighEndQualityPolicy.strictHighEndOnly
        )

    internal fun resolve(
        profileName: String,
        logEnabled: Boolean,
        highEndEnabled: Boolean,
        strictHighEndOnly: Boolean
    ): VideoLogPreviewParity {
        val canonicalLogProfile = NeoLogProfilePolicy.effectiveOriginalLogFormat(
            profileName = profileName,
            logEnabled = logEnabled
        )
        val key = normalize(canonicalLogProfile ?: profileName)
        val nLogParity = VideoLogPreviewParity(
            contrastLift = 0.30f,
            saturationLift = 0.24f,
            shadowBiasScale = 0.60f,
            highlightBiasScale = 0.66f,
            highLoadPenaltyScale = 0.10f,
            lutSaturationBoost = 0.030f,
            brightnessLift = 0.12f
        )
        val baseParity = when {
            canonicalLogProfile != null -> nLogParity
            isNLogCurve(key) -> nLogParity

            else -> VideoLogPreviewParity()
        }
        if (!(highEndEnabled && strictHighEndOnly) || baseParity == VideoLogPreviewParity()) {
            return baseParity
        }
        return baseParity.copy(
            contrastLift = (baseParity.contrastLift * 1.65f).coerceAtMost(0.48f),
            saturationLift = (baseParity.saturationLift * 1.55f).coerceAtMost(0.42f),
            shadowBiasScale = (baseParity.shadowBiasScale * 0.85f).coerceAtLeast(0.52f),
            highlightBiasScale = (baseParity.highlightBiasScale * 0.82f).coerceAtLeast(0.58f),
            highLoadPenaltyScale = (baseParity.highLoadPenaltyScale * 0.65f).coerceAtLeast(0.08f),
            lutSaturationBoost = (baseParity.lutSaturationBoost * 2.2f).coerceAtMost(0.060f),
            brightnessLift = (baseParity.brightnessLift * 1.45f + 0.05f).coerceAtMost(0.28f)
        )
    }

    fun compressRec2020PqToDisplayOklab(
        red: Float,
        green: Float,
        blue: Float,
        knee: Float = 0.72f,
        kappa: Float = 5.5f,
        chromaGamma: Float = 0.72f
    ): VideoDisplayCompressedRgb {
        val rec2020Linear = floatArrayOf(
            inversePqToRelativeLinear(red),
            inversePqToRelativeLinear(green),
            inversePqToRelativeLinear(blue)
        )
        val xyz = multiply3x3(REC2020_TO_XYZ, rec2020Linear)
        val displayLinear = multiply3x3(XYZ_TO_SRGB, xyz)
            .map { it.coerceAtLeast(0f) }
            .toFloatArray()
        val oklab = linearRgbToOklab(displayLinear)
        val lightness = oklab[0].coerceIn(0f, 1.5f)
        val a = oklab[1]
        val b = oklab[2]
        val inputHue = atan2(b, a)
        val inputChroma = hypot(a, b)
        val sigmoid = 1f / (1f + exp((-kappa * (lightness - knee)).toDouble()).toFloat())
        val compressedLightness = if (lightness <= knee) {
            lightness
        } else {
            (lightness * (1f - 0.35f * sigmoid)).coerceIn(0f, 1f)
        }
        val lightnessRatio = if (lightness > 1e-6f) {
            (compressedLightness / lightness).coerceIn(0f, 1f)
        } else {
            1f
        }
        val outputChroma = inputChroma *
            lightnessRatio.toDouble().pow(chromaGamma.toDouble()).toFloat()
        val compressedA = outputChroma * cos(inputHue)
        val compressedB = outputChroma * sin(inputHue)
        val outputRgb = oklabToLinearRgb(floatArrayOf(compressedLightness, compressedA, compressedB))
        return VideoDisplayCompressedRgb(
            red = outputRgb[0].coerceIn(0f, 1f),
            green = outputRgb[1].coerceIn(0f, 1f),
            blue = outputRgb[2].coerceIn(0f, 1f),
            inputHueRadians = inputHue,
            outputHueRadians = inputHue,
            inputChroma = inputChroma,
            outputChroma = outputChroma.coerceAtMost(inputChroma),
            outputLightness = compressedLightness.coerceIn(0f, 1f)
        )
    }

    private fun normalize(raw: String): String =
        raw.trim().lowercase().replace(PROFILE_SEPARATOR_PATTERN, " ").trim()

    private fun containsAny(key: String, vararg probes: String): Boolean =
        probes.any { key.contains(it) }

    private fun isNLogCurve(key: String): Boolean =
        containsAny(key, "n log", "nlog", "n-log", "neoproc n log", "neopro n log")

    private fun inversePqToRelativeLinear(value: Float): Float {
        val x = value.coerceIn(0f, 1f).toDouble()
        val m1 = 0.1593017578125
        val m2 = 78.84375
        val c1 = 0.8359375
        val c2 = 18.8515625
        val c3 = 18.6875
        val p = x.pow(1.0 / m2)
        val numerator = max(p - c1, 0.0)
        val denominator = c2 - c3 * p
        return (numerator / denominator)
            .coerceAtLeast(0.0)
            .pow(1.0 / m1)
            .toFloat()
    }

    private fun multiply3x3(matrix: FloatArray, vector: FloatArray): FloatArray =
        floatArrayOf(
            matrix[0] * vector[0] + matrix[1] * vector[1] + matrix[2] * vector[2],
            matrix[3] * vector[0] + matrix[4] * vector[1] + matrix[5] * vector[2],
            matrix[6] * vector[0] + matrix[7] * vector[1] + matrix[8] * vector[2]
        )

    private fun linearRgbToOklab(rgb: FloatArray): FloatArray {
        val lms = multiply3x3(RGB_TO_LMS, rgb)
        val lmsPrime = floatArrayOf(
            cbrt(lms[0].coerceAtLeast(0f).toDouble()).toFloat(),
            cbrt(lms[1].coerceAtLeast(0f).toDouble()).toFloat(),
            cbrt(lms[2].coerceAtLeast(0f).toDouble()).toFloat()
        )
        return multiply3x3(LMS_PRIME_TO_OKLAB, lmsPrime)
    }

    private fun oklabToLinearRgb(oklab: FloatArray): FloatArray {
        val lmsPrime = multiply3x3(OKLAB_TO_LMS_PRIME, oklab)
        val lms = floatArrayOf(
            lmsPrime[0] * lmsPrime[0] * lmsPrime[0],
            lmsPrime[1] * lmsPrime[1] * lmsPrime[1],
            lmsPrime[2] * lmsPrime[2] * lmsPrime[2]
        )
        return multiply3x3(LMS_TO_RGB, lms)
    }

    private val REC2020_TO_XYZ = floatArrayOf(
        0.636958f, 0.144617f, 0.168881f,
        0.262700f, 0.677998f, 0.059302f,
        0.000000f, 0.028073f, 1.060833f
    )

    private val XYZ_TO_SRGB = floatArrayOf(
        3.2404542f, -1.5371385f, -0.4985314f,
        -0.9692660f, 1.8760108f, 0.0415560f,
        0.0556434f, -0.2040259f, 1.0572252f
    )

    private val RGB_TO_LMS = floatArrayOf(
        0.4122214708f, 0.5363325363f, 0.0514459929f,
        0.2119034982f, 0.6806995451f, 0.1073969566f,
        0.0883024619f, 0.2817188376f, 0.6299787005f
    )

    private val LMS_PRIME_TO_OKLAB = floatArrayOf(
        0.2104542553f, 0.7936177850f, -0.0040720468f,
        1.9779984951f, -2.4285922050f, 0.4505937099f,
        0.0259040371f, 0.7827717662f, -0.8086757660f
    )

    private val OKLAB_TO_LMS_PRIME = floatArrayOf(
        1.0000000000f, 0.3963377774f, 0.2158037573f,
        1.0000000000f, -0.1055613458f, -0.0638541728f,
        1.0000000000f, -0.0894841775f, -1.2914855480f
    )

    private val LMS_TO_RGB = floatArrayOf(
        4.0767416621f, -3.3077115913f, 0.2309699292f,
        -1.2684380046f, 2.6097574011f, -0.3413193965f,
        -0.0041960863f, -0.7034186147f, 1.7076147010f
    )
}
