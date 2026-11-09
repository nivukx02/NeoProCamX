package com.neoproc.neoproccamx.color

import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.createBitmap
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class ColorSpaceColorProfile(
    val name: String,
    val primaries: Map<String, Pair<Float, Float>>,
    val whitePoint: String,
    val transfer: String,
    val matrix: String,
    val lumaCoefficients: FloatArray
)

/**
 * ColorScience — Professional cinema colour pipeline.
 *
 * Features:
 *  1. .cube LUT parser (any size up to 64^3)
 *  2. Tetrahedral interpolation for CPU LUT apply
 *  3. GPU LUT data upload via CameraGLSurfaceView
 *  4. NeoProCamX N-Log encoding and standards-based Rec.709/Rec.2020 color
 *  5. Built-in looks: Cinematic, Bleach Bypass, Warm Vintage, Cool Teal, Night
 *  6. Tone curve control per channel (spline-interpolated)
 *  7. HSL adjustments (saturation, hue rotation, luminance)
 */
class ColorScience {

    companion object {
        private const val TAG = "ColorScience"
        private val COLOR_SPACE_KEY_PATTERN = Regex("[^A-Z0-9]")

        private data class Chromaticity(val x: Float, val y: Float)

        private data class Matrix3(
            val m00: Float, val m01: Float, val m02: Float,
            val m10: Float, val m11: Float, val m12: Float,
            val m20: Float, val m21: Float, val m22: Float
        ) {
            fun multiply(r: Float, g: Float, b: Float): Triple<Float, Float, Float> =
                Triple(
                    m00 * r + m01 * g + m02 * b,
                    m10 * r + m11 * g + m12 * b,
                    m20 * r + m21 * g + m22 * b
                )

            fun times(other: Matrix3): Matrix3 =
                Matrix3(
                    m00 * other.m00 + m01 * other.m10 + m02 * other.m20,
                    m00 * other.m01 + m01 * other.m11 + m02 * other.m21,
                    m00 * other.m02 + m01 * other.m12 + m02 * other.m22,
                    m10 * other.m00 + m11 * other.m10 + m12 * other.m20,
                    m10 * other.m01 + m11 * other.m11 + m12 * other.m21,
                    m10 * other.m02 + m11 * other.m12 + m12 * other.m22,
                    m20 * other.m00 + m21 * other.m10 + m22 * other.m20,
                    m20 * other.m01 + m21 * other.m11 + m22 * other.m21,
                    m20 * other.m02 + m21 * other.m12 + m22 * other.m22
                )

            fun inverse(): Matrix3 {
                val c00 = m11 * m22 - m12 * m21
                val c01 = m02 * m21 - m01 * m22
                val c02 = m01 * m12 - m02 * m11
                val c10 = m12 * m20 - m10 * m22
                val c11 = m00 * m22 - m02 * m20
                val c12 = m02 * m10 - m00 * m12
                val c20 = m10 * m21 - m11 * m20
                val c21 = m01 * m20 - m00 * m21
                val c22 = m00 * m11 - m01 * m10
                val det = (m00 * c00 + m01 * c10 + m02 * c20).takeUnless { abs(it) < 1e-7f }
                    ?: 1e-7f
                val invDet = 1f / det
                return Matrix3(
                    c00 * invDet, c01 * invDet, c02 * invDet,
                    c10 * invDet, c11 * invDet, c12 * invDet,
                    c20 * invDet, c21 * invDet, c22 * invDet
                )
            }
        }

        // 7-decimal mirror of the authoritative NeoWideGamut2.NEO_TO_XYZ (which carries full
        // precision). Kept as a literal here to preserve the exact shipped CPU/preview transform;
        // ColorScienceNeoWideGamutMatrixPinTest pins this transform and asserts the two definitions
        // still agree to display precision so a recalibration of NeoWideGamut2 cannot silently
        // diverge this path from the AcesColorEngine / NeoGamutCat16Math path and the shaders.
        private val NEO_WIDE_GAMUT_TO_XYZ_MATRIX = Matrix3(
            0.774888889f, 0.098962557f, 0.082970662f,
            0.279812198f, 0.709575275f, 0.010612527f,
            0.000000000f, 0.050512138f, 0.871191949f
        )

        val BUILT_IN_PROFILES = listOf(
            // Standards and NeoProCamX-owned profiles.
            "Natural", "Rec.709", "Rec.2020", "Rec.2100 (HDR)",
            "HDR10 Native", "HLG Native",
            "Neo Wide Gamut", "Neo ACES High End", "Neo ACES AP1", "Neo ACES AP0",
            "NDR10+", "NIVUKX Vision",

            // Original NeoProCamX cinema log family
            "N-Log Base", "N-Log Shadow", "N-Log Highlight", "N-Log Cinema",

            // Creative Looks
            "Cinematic", "Bleach Bypass",
            "Warm Vintage", "Cool Teal", "Night Vision", "Faded Film",
            "Film Noir", "Cyberpunk", "Nordic", "Desert"
        )
        val BUILT_IN_LUTS = listOf(
            "Neutral Film",
            "Warm Cinema",
            "Teal & Orange",
            "Neo 709 Clean",
            "Neo HDR Soft",
            "Neo Cinema Gold",
            "Bleach Bypass",
            "Cross Process",
            "Vintage Chrome",
            "Modern Blockbuster",
            "Sci-Fi Cool",
            "Fantasy Warm"
        )

        val COLOR_SPACE_PROFILES: List<ColorSpaceColorProfile> = listOf(
            colorSpaceProfile(
                name = "Rec.709",
                red = 0.640f to 0.330f,
                green = 0.300f to 0.600f,
                blue = 0.150f to 0.060f,
                whitePoint = "D65",
                transfer = "BT.1886/sRGB",
                matrix = "BT.709"
            ),
            colorSpaceProfile(
                name = "Rec.2020",
                red = 0.708f to 0.292f,
                green = 0.170f to 0.797f,
                blue = 0.131f to 0.046f,
                whitePoint = "D65",
                transfer = "BT.1886/HLG/PQ",
                matrix = "BT.2020 non-constant luminance"
            ),
            colorSpaceProfile(
                name = "Neo ACES AP1",
                red = 0.713f to 0.293f,
                green = 0.165f to 0.830f,
                blue = 0.128f to 0.044f,
                whitePoint = "D60",
                transfer = "Neo ACES scene-linear high-end",
                matrix = "Neo ACES AP1 to CIE XYZ"
            ),
            colorSpaceProfile(
                name = "Neo ACES AP0",
                red = 0.7347f to 0.2653f,
                green = 0.0000f to 1.0000f,
                blue = 0.0001f to -0.0770f,
                whitePoint = "D60",
                transfer = "Neo ACES archive-linear high-end",
                matrix = "Neo ACES AP0 to CIE XYZ"
            ),
            colorSpaceProfile(
                name = "Neo Wide Gamut",
                red = 0.7347f to 0.2653f,
                green = 0.1152f to 0.8260f,
                blue = 0.0860f to 0.0110f,
                whitePoint = "D55",
                transfer = "Scene-linear Neo WG2 / N-Log v3.0 / NDR10+ / NIVUKX Vision",
                matrix = "Neo WG2 D55-normalized to CIE XYZ",
                lumaOverride = NeoWideGamut2.LUMA.copyOf()
            )
        )

        private val rec709ToProfileMatrices: Map<String, Matrix3> by lazy {
            val rec709 = COLOR_SPACE_PROFILES.first { it.name == "Rec.709" }
            val rec709ToXyz = rgbToXyzMatrix(rec709)
            COLOR_SPACE_PROFILES.associate { profile ->
                val profileToXyz = if (profile.name == "Neo Wide Gamut") {
                    NEO_WIDE_GAMUT_TO_XYZ_MATRIX
                } else {
                    rgbToXyzMatrix(profile)
                }
                profile.name to profileToXyz.inverse().times(rec709ToXyz)
            }
        }

        fun resolveColorSpaceProfile(name: String?): ColorSpaceColorProfile? {
            val key = normalizeColorSpaceKey(name)
            return when (key) {
                "REC709", "BT709", "SRGB" ->
                    COLOR_SPACE_PROFILES.first { it.name == "Rec.709" }
                "REC2020", "BT2020", "REC2100", "BT2100",
                "REC2020NLOG", "REC2020NDR10", "REC2020NIVUKX",
                "NDR10", "NDR10PLUS", "NIVUKX", "NIVUKXVISION",
                // "HDR10 Native"/"HLG Native" are BT.2020 standards (already-encoded PQ/HLG) spaces.
                // Without these keys they resolved to null => treated as non-standards => the SDR grade
                // stages ran on PQ/HLG code values and even fed them through srgbToLinear, lifting
                // blacks/mids into a washed-out low-contrast image (#16). Classifying them as standards
                // makes the encoded-domain guard skip those stages, matching Rec.2020/NDR10/NIVUKX.
                "HDR10NATIVE", "HDR10", "HLGNATIVE", "HLG" ->
                    COLOR_SPACE_PROFILES.first { it.name == "Rec.2020" }
                "NEOACESAP1", "NEOACESCG", "ACESCG", "ACESAP1", "ACES" ->
                    COLOR_SPACE_PROFILES.first { it.name == "Neo ACES AP1" }
                "NEOACESAP0", "ACESAP0" ->
                    COLOR_SPACE_PROFILES.first { it.name == "Neo ACES AP0" }
                "NEOWIDEGAMUT", "NWG", "WIDEGAMUT",
                "P3", "DISPLAYP3", "DCIP3" ->
                    COLOR_SPACE_PROFILES.first { it.name == "Neo Wide Gamut" }
                else -> if (key.contains("GAMUT") || key.contains("LOG") || key.contains("FILM")) {
                    COLOR_SPACE_PROFILES.first { it.name == "Neo Wide Gamut" }
                } else {
                    null
                }
            }
        }

        private fun normalizeColorSpaceKey(name: String?): String =
            name
                ?.trim()
                ?.uppercase()
                ?.replace(COLOR_SPACE_KEY_PATTERN, "")
                .orEmpty()

        private fun colorSpaceProfile(
            name: String,
            red: Pair<Float, Float>,
            green: Pair<Float, Float>,
            blue: Pair<Float, Float>,
            whitePoint: String,
            transfer: String,
            matrix: String,
            lumaOverride: FloatArray? = null
        ): ColorSpaceColorProfile {
            val primaries = mapOf(
                "red" to red,
                "green" to green,
                "blue" to blue
            )
            val rgbToXyz = rgbToXyzMatrix(primaries, whitePointChromaticity(whitePoint))
            return ColorSpaceColorProfile(
                name = name,
                primaries = primaries,
                whitePoint = whitePoint,
                transfer = transfer,
                matrix = matrix,
                lumaCoefficients = lumaOverride ?: floatArrayOf(rgbToXyz.m10, rgbToXyz.m11, rgbToXyz.m12)
            )
        }

        private fun rgbToXyzMatrix(profile: ColorSpaceColorProfile): Matrix3 =
            rgbToXyzMatrix(profile.primaries, whitePointChromaticity(profile.whitePoint))

        private fun rgbToXyzMatrix(
            primaries: Map<String, Pair<Float, Float>>,
            white: Chromaticity
        ): Matrix3 {
            val red = primaryToXyz(primaries.getValue("red"))
            val green = primaryToXyz(primaries.getValue("green"))
            val blue = primaryToXyz(primaries.getValue("blue"))
            val base = Matrix3(
                red.first, green.first, blue.first,
                red.second, green.second, blue.second,
                red.third, green.third, blue.third
            )
            val whiteXyz = chromaticityToXyz(white)
            val scale = base.inverse().multiply(whiteXyz.first, whiteXyz.second, whiteXyz.third)
            return Matrix3(
                red.first * scale.first, green.first * scale.second, blue.first * scale.third,
                red.second * scale.first, green.second * scale.second, blue.second * scale.third,
                red.third * scale.first, green.third * scale.second, blue.third * scale.third
            )
        }

        private fun primaryToXyz(primary: Pair<Float, Float>): Triple<Float, Float, Float> {
            val x = primary.first
            val y = primary.second.takeUnless { abs(it) < 1e-7f } ?: 1e-7f
            return Triple(x / y, 1f, (1f - x - y) / y)
        }

        private fun chromaticityToXyz(chromaticity: Chromaticity): Triple<Float, Float, Float> {
            val y = chromaticity.y.takeUnless { abs(it) < 1e-7f } ?: 1e-7f
            return Triple(chromaticity.x / y, 1f, (1f - chromaticity.x - y) / y)
        }

        private fun whitePointChromaticity(whitePoint: String): Chromaticity =
            when {
                whitePoint.contains("D60", ignoreCase = true) -> Chromaticity(0.32168f, 0.33767f)
                whitePoint.contains("D55", ignoreCase = true) -> Chromaticity(0.3324f, 0.3474f)
                else -> Chromaticity(0.3127f, 0.3290f)
            }

        fun applyHueLockedOklabGrade(
            r: Float,
            g: Float,
            b: Float,
            saturationScale: Float,
            contrastScale: Float,
            brightnessOffset: Float,
            hueShiftDegrees: Float
        ): Triple<Float, Float, Float> {
            val lab = rgbToOklab(r, g, b)
            val baseChroma = sqrt(lab.a * lab.a + lab.b * lab.b)
            if (baseChroma < 1e-6f) {
                val lightness = gradeOklabLightness(lab.l, contrastScale, brightnessOffset)
                return oklabToRgb(OklabColor(lightness, 0f, 0f)).boundedRgb()
            }

            val theta = atan2(lab.b, lab.a) + Math.toRadians(hueShiftDegrees.toDouble()).toFloat()
            val targetChroma = (baseChroma * saturationScale.coerceIn(0f, 4f)).coerceAtLeast(0f)
            val lightness = gradeOklabLightness(lab.l, contrastScale, brightnessOffset)
            var low = 0f
            var high = targetChroma
            repeat(14) {
                val mid = (low + high) * 0.5f
                val candidate = oklabToRgb(
                    OklabColor(
                        l = lightness,
                        a = mid * cos(theta),
                        b = mid * sin(theta)
                    )
                )
                if (candidate.first in 0f..1f && candidate.second in 0f..1f && candidate.third in 0f..1f) {
                    low = mid
                } else {
                    high = mid
                }
            }
            return oklabToRgb(
                OklabColor(
                    l = lightness,
                    a = low * cos(theta),
                    b = low * sin(theta)
                )
            ).boundedRgb()
        }

        fun oklabHueDegrees(r: Float, g: Float, b: Float): Float {
            val lab = rgbToOklab(r, g, b)
            val hue = Math.toDegrees(atan2(lab.b, lab.a).toDouble()).toFloat()
            return if (hue < 0f) hue + 360f else hue
        }

        private data class OklabColor(val l: Float, val a: Float, val b: Float)

        private fun gradeOklabLightness(lightness: Float, contrastScale: Float, brightnessOffset: Float): Float {
            val l = lightness.coerceIn(0f, 1f)
            val contrast = contrastScale.coerceIn(0.05f, 4f)
            val contrasted = if (abs(contrast - 1f) < 1e-5f) {
                l
            } else {
                val k = 4f * contrast
                val lo = 1f / (1f + exp(k * 0.5f))
                val hi = 1f / (1f + exp(-k * 0.5f))
                val raw = 1f / (1f + exp(-k * (l - 0.5f)))
                ((raw - lo) / (hi - lo).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
            }
            return (contrasted + brightnessOffset).coerceIn(0f, 1f)
        }

        private fun rgbToOklab(r: Float, g: Float, b: Float): OklabColor {
            val lr = r.coerceAtLeast(0f)
            val lg = g.coerceAtLeast(0f)
            val lb = b.coerceAtLeast(0f)
            val l = 0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb
            val m = 0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb
            val s = 0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb
            val lp = signedCubeRoot(l)
            val mp = signedCubeRoot(m)
            val sp = signedCubeRoot(s)
            return OklabColor(
                l = 0.2104542553f * lp + 0.7936177850f * mp - 0.0040720468f * sp,
                a = 1.9779984951f * lp - 2.4285922050f * mp + 0.4505937099f * sp,
                b = 0.0259040371f * lp + 0.7827717662f * mp - 0.8086757660f * sp
            )
        }

        private fun oklabToRgb(lab: OklabColor): Triple<Float, Float, Float> {
            val lp = lab.l + 0.3963377774f * lab.a + 0.2158037573f * lab.b
            val mp = lab.l - 0.1055613458f * lab.a - 0.0638541728f * lab.b
            val sp = lab.l - 0.0894841775f * lab.a - 1.2914855480f * lab.b
            val l = lp * lp * lp
            val m = mp * mp * mp
            val s = sp * sp * sp
            return Triple(
                4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
                -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
                -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
            )
        }

        private fun signedCubeRoot(value: Float): Float =
            Math.cbrt(value.toDouble()).toFloat()

        private fun Triple<Float, Float, Float>.boundedRgb(): Triple<Float, Float, Float> =
            Triple(first.coerceIn(0f, 1f), second.coerceIn(0f, 1f), third.coerceIn(0f, 1f))

        private fun rec709ToProfileColorSpace(
            profileName: String,
            r: Float,
            g: Float,
            b: Float
        ): Triple<Float, Float, Float>? {
            val profile = resolveColorSpaceProfile(profileName) ?: return null
            val matrix = rec709ToProfileMatrices[profile.name] ?: return null
            return matrix.multiply(r, g, b)
        }
    }

    private data class ColorConfigurationSnapshot(
        val version: Long,
        val profileName: String,
        val creativeLut: CubeLUT?,
        val lutEnabled: Boolean,
        val logEnabled: Boolean,
        val saturation: Float,
        val contrast: Float,
        val brightness: Float,
        val hueShift: Float,
        val highlights: Float,
        val shadows: Float,
        val curveR: FloatArray,
        val curveG: FloatArray,
        val curveB: FloatArray,
        val highEndEnabled: Boolean,
        val strictHighEndOnly: Boolean
    )

    private data class ResolvedColorPipeline(
        val configuration: ColorConfigurationSnapshot,
        val logLook: NeoLogLookProfile,
        val profileDeclaresLog: Boolean,
        val logPreviewParity: VideoLogPreviewParity,
        val useProfileLook: Boolean,
        val usesHumanVisionBase: Boolean,
        val standardsColorSpaceProfile: Boolean
    )

    private data class PreviewLutCacheKey(
        val configurationVersion: Long,
        val size: Int,
        val logLook: NeoLogLookProfile,
        val profileDeclaresLog: Boolean,
        val logPreviewParity: VideoLogPreviewParity,
        val highEndEnabled: Boolean,
        val strictHighEndOnly: Boolean
    )

    private data class PreviewLutCacheEntry(
        val key: PreviewLutCacheKey,
        val packed: Pair<FloatArray, Int>
    )

    // ── Active LUT ────────────────────────────────────────────────────────────
    private val colorConfigurationLock = Any()
    private val previewLutCacheLock = Any()
    @Volatile
    private var colorConfigurationVersion = 0L
    @Volatile
    private var previewLutCacheEntry: PreviewLutCacheEntry? = null
    @Volatile
    private var activeCreativeLut: CubeLUT? = null
    @Volatile
    var activeProfileName = "Natural"
        private set(value) {
            synchronized(colorConfigurationLock) {
                if (field != value) {
                    field = value
                    markColorConfigurationChangedLocked()
                }
            }
        }
    @Volatile
    var activeLutName: String? = null
        private set
    @Volatile
    var lutEnabled = false
        set(value) {
            synchronized(colorConfigurationLock) {
                if (field != value) {
                    field = value
                    markColorConfigurationChangedLocked()
                }
            }
        }
    @Volatile
    var logEnabled = false
        set(value) {
            synchronized(colorConfigurationLock) {
                if (field != value) {
                    field = value
                    markColorConfigurationChangedLocked()
                }
            }
        }
    @Volatile
    var saturation = 1.0f
        set(value) {
            synchronized(colorConfigurationLock) {
                if (field.toBits() != value.toBits()) {
                    field = value
                    markColorConfigurationChangedLocked()
                }
            }
        }
    @Volatile
    var contrast = 1.0f
        set(value) {
            synchronized(colorConfigurationLock) {
                if (field.toBits() != value.toBits()) {
                    field = value
                    markColorConfigurationChangedLocked()
                }
            }
        }
    @Volatile
    var brightness = 0f
        set(value) {
            synchronized(colorConfigurationLock) {
                if (field.toBits() != value.toBits()) {
                    field = value
                    markColorConfigurationChangedLocked()
                }
            }
        }
    @Volatile
    var hueShift = 0f
        set(value) {
            synchronized(colorConfigurationLock) {
                if (field.toBits() != value.toBits()) {
                    field = value
                    markColorConfigurationChangedLocked()
                }
            }
        }
    @Volatile
    var highlights = 0f
        set(value) {
            synchronized(colorConfigurationLock) {
                if (field.toBits() != value.toBits()) {
                    field = value
                    markColorConfigurationChangedLocked()
                }
            }
        }
    @Volatile
    var shadows = 0f
        set(value) {
            synchronized(colorConfigurationLock) {
                if (field.toBits() != value.toBits()) {
                    field = value
                    markColorConfigurationChangedLocked()
                }
            }
        }

    // Tone curves per channel (256 entries, 0-255 → 0-255)
    private var curveR = buildLinearCurve()
    private var curveG = buildLinearCurve()
    private var curveB = buildLinearCurve()

    private fun markColorConfigurationChangedLocked() {
        colorConfigurationVersion++
        previewLutCacheEntry = null
    }

    private fun snapshotColorConfiguration(): ColorConfigurationSnapshot =
        synchronized(colorConfigurationLock) {
            ColorConfigurationSnapshot(
                version = colorConfigurationVersion,
                profileName = activeProfileName,
                creativeLut = activeCreativeLut,
                lutEnabled = lutEnabled,
                logEnabled = logEnabled,
                saturation = saturation,
                contrast = contrast,
                brightness = brightness,
                hueShift = hueShift,
                highlights = highlights,
                shadows = shadows,
                curveR = curveR,
                curveG = curveG,
                curveB = curveB,
                highEndEnabled = HighEndQualityPolicy.enabled,
                strictHighEndOnly = HighEndQualityPolicy.strictHighEndOnly
            )
        }

    private fun resolveColorPipeline(
        configuration: ColorConfigurationSnapshot
    ): ResolvedColorPipeline {
        val logLook = NeoLogProfilePolicy.resolve(
            profileName = configuration.profileName,
            logEnabled = configuration.logEnabled,
            highEndEnabled = configuration.highEndEnabled,
            strictHighEndOnly = configuration.strictHighEndOnly
        )
        val profileDeclaresLog = NeoLogProfilePolicy.resolve(
            profileName = configuration.profileName,
            logEnabled = false,
            highEndEnabled = configuration.highEndEnabled,
            strictHighEndOnly = configuration.strictHighEndOnly
        ).enabled
        return ResolvedColorPipeline(
            configuration = configuration,
            logLook = logLook,
            profileDeclaresLog = profileDeclaresLog,
            logPreviewParity = VideoLogPreviewParityPolicy.resolve(
                profileName = configuration.profileName,
                logEnabled = logLook.enabled,
                highEndEnabled = configuration.highEndEnabled,
                strictHighEndOnly = configuration.strictHighEndOnly
            ),
            useProfileLook = usesBuiltInProfileTransform(configuration.profileName),
            usesHumanVisionBase =
                configuration.profileName.equals("Natural", ignoreCase = true) &&
                    !configuration.logEnabled,
            standardsColorSpaceProfile =
                logLook.enabled || isStandardsColorSpaceProfile(configuration.profileName)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LUT LOADING
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun loadCubeLUT(file: File): Boolean = withContext(Dispatchers.IO) {
        loadCubeLUTBlocking(file)
    }

    fun loadCubeLUTBlocking(file: File): Boolean {
        return try {
            val parsedLut = parseCube(file)
            synchronized(colorConfigurationLock) {
                activeCreativeLut = parsedLut
                activeLutName = file.nameWithoutExtension.ifBlank { "Custom" }
                lutEnabled = true
                markColorConfigurationChangedLocked()
            }
            Log.i(TAG, "Loaded LUT: ${file.name} size=${parsedLut.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "LUT load failed: ${file.name}", e)
            false
        }
    }

    fun loadBuiltInProfile(name: String) {
        activeProfileName = name
    }

    fun setBuiltInLut(name: String) {
        val normalized = BUILT_IN_LUTS.firstOrNull { it.equals(name, ignoreCase = true) } ?: return
        val generatedLut = generateCreativeLut(normalized)
        synchronized(colorConfigurationLock) {
            activeCreativeLut = generatedLut
            activeLutName = normalized
            lutEnabled = true
            markColorConfigurationChangedLocked()
        }
    }

    fun clearLUT() {
        synchronized(colorConfigurationLock) {
            activeCreativeLut = null
            activeLutName = null
            lutEnabled = false
            markColorConfigurationChangedLocked()
        }
    }

    fun activeLutLabel(): String? = activeLutName

    fun setLutToggleEnabled(enabled: Boolean) {
        lutEnabled = enabled && activeCreativeLut != null
    }

    private fun isStandardsColorSpaceProfile(profileName: String): Boolean =
        resolveColorSpaceProfile(profileName) != null

    fun isNeutralLook(): Boolean {
        val configuration = snapshotColorConfiguration()
        return configuration.profileName.equals("Natural", ignoreCase = true) &&
            !configuration.lutEnabled &&
            !configuration.logEnabled &&
            configuration.saturation == 1.0f &&
            configuration.contrast == 1.0f &&
            configuration.brightness == 0f &&
            configuration.hueShift == 0f &&
            configuration.highlights == 0f &&
            configuration.shadows == 0f
    }

    /** Get flat float array (R,G,B interleaved) for GPU upload */
    fun getLutFloatArray(): Pair<FloatArray, Int>? {
        val lut = synchronized(colorConfigurationLock) { activeCreativeLut } ?: return null
        val size = lut.size
        val arr  = FloatArray(size * size * size * 3)
        for (i in lut.data.indices) {
            arr[i * 3    ] = lut.data[i][0]
            arr[i * 3 + 1] = lut.data[i][1]
            arr[i * 3 + 2] = lut.data[i][2]
        }
        return Pair(arr, size)
    }

    /**
     * Build a combined preview LUT for the live GPU renderer.
     * This keeps the selected base color profile visible in preview and then
     * layers the optional creative LUT on top so preview stays closer to still save.
     */
    fun getPreviewLutFloatArray(
        previewFps: Float = HighEndQualityPolicy.maxPreviewFps(60).toFloat(),
        recording: Boolean = false
    ): Pair<FloatArray, Int>? {
        val pipeline = resolveColorPipeline(snapshotColorConfiguration())
        val configuration = pipeline.configuration
        val creativeLut = configuration.creativeLut.takeIf { configuration.lutEnabled }
        val needsPreviewPipeline =
            pipeline.useProfileLook ||
                pipeline.logLook.enabled ||
                creativeLut != null ||
                configuration.saturation != 1f ||
                configuration.hueShift != 0f ||
                configuration.contrast != 1f ||
                configuration.brightness != 0f ||
                configuration.highlights != 0f ||
                configuration.shadows != 0f
        if (!needsPreviewPipeline) return null

        val highFidelityPipeline =
            pipeline.useProfileLook ||
                pipeline.logLook.enabled ||
                creativeLut != null ||
                configuration.contrast != 1f ||
                configuration.saturation != 1f ||
                configuration.highlights != 0f ||
                configuration.shadows != 0f
        val size = HighEndQualityPolicy.lutSizeForPreview(
            highFidelity = highFidelityPipeline,
            previewFps = previewFps,
            recording = recording
        ).coerceAtLeast(pipeline.logLook.preferredLutCubeSize)
        val cacheKey = PreviewLutCacheKey(
            configurationVersion = configuration.version,
            size = size,
            logLook = pipeline.logLook,
            profileDeclaresLog = pipeline.profileDeclaresLog,
            logPreviewParity = pipeline.logPreviewParity,
            highEndEnabled = configuration.highEndEnabled,
            strictHighEndOnly = configuration.strictHighEndOnly
        )
        previewLutCacheEntry?.takeIf { it.key == cacheKey }?.let { return it.packed }

        return synchronized(previewLutCacheLock) {
            previewLutCacheEntry?.takeIf { it.key == cacheKey }?.packed ?: run {
                val arr = FloatArray(size * size * size * 3)
                for (bi in 0 until size) for (gi in 0 until size) for (ri in 0 until size) {
                    val processed = applyActiveColorPipeline(
                        inputR = ri / (size - 1f),
                        inputG = gi / (size - 1f),
                        inputB = bi / (size - 1f),
                        pipeline = pipeline
                    )
                    val idx = (ri + gi * size + bi * size * size) * 3
                    arr[idx] = processed.first.coerceIn(0f, 1f)
                    arr[idx + 1] = processed.second.coerceIn(0f, 1f)
                    arr[idx + 2] = processed.third.coerceIn(0f, 1f)
                }
                Pair(arr, size).also { packed ->
                    previewLutCacheEntry = PreviewLutCacheEntry(cacheKey, packed)
                }
            }
        }
    }

    private fun applyActiveColorPipeline(
        inputR: Float,
        inputG: Float,
        inputB: Float,
        pipeline: ResolvedColorPipeline
    ): Triple<Float, Float, Float> {
        val configuration = pipeline.configuration
        val logLook = pipeline.logLook
        val logAwareProfileName = if (logLook.enabled) logLook.label else configuration.profileName
        val creativeLut = configuration.creativeLut.takeIf { configuration.lutEnabled }
        var r = inputR.coerceIn(0f, 1f)
        var g = inputG.coerceIn(0f, 1f)
        var b = inputB.coerceIn(0f, 1f)

        r = sampleCurve(configuration.curveR, r)
        g = sampleCurve(configuration.curveG, g)
        b = sampleCurve(configuration.curveB, b)

        if (pipeline.usesHumanVisionBase) {
            val humanVision = applyHumanVisionRec709(r, g, b)
            r = humanVision.first
            g = humanVision.second
            b = humanVision.third
        }

        if (
            configuration.saturation != 1f ||
            configuration.hueShift != 0f ||
            configuration.contrast != 1f ||
            configuration.brightness != 0f
        ) {
            val graded = applyHueLockedOklabGrade(
                r = r,
                g = g,
                b = b,
                saturationScale = configuration.saturation,
                contrastScale = configuration.contrast,
                brightnessOffset = configuration.brightness,
                hueShiftDegrees = configuration.hueShift
            )
            r = graded.first
            g = graded.second
            b = graded.third
        }

        if (configuration.highlights != 0f || configuration.shadows != 0f) {
            r = applyHighlightShadowTonalBalance(r, configuration.highlights, configuration.shadows)
            g = applyHighlightShadowTonalBalance(g, configuration.highlights, configuration.shadows)
            b = applyHighlightShadowTonalBalance(b, configuration.highlights, configuration.shadows)
        }

        if (pipeline.useProfileLook && !pipeline.profileDeclaresLog) {
            val profiled = applyBuiltInProfileLook(configuration.profileName, r, g, b)
            r = profiled.first
            g = profiled.second
            b = profiled.third
        } else if (logLook.enabled) {
            r = applySelectedLogCurve(logLook.curve, r)
            g = applySelectedLogCurve(logLook.curve, g)
            b = applySelectedLogCurve(logLook.curve, b)
        }
        if (logLook.enabled) {
            val parityAdjusted = applyLogPreviewParity(r, g, b, logLook, pipeline.logPreviewParity)
            r = parityAdjusted.first
            g = parityAdjusted.second
            b = parityAdjusted.third
        }

        val filmicStrength = when {
            logLook.enabled -> 0.0f
            configuration.profileName.equals("Natural", ignoreCase = true) -> 0.18f
            configuration.profileName.equals("Cinematic", ignoreCase = true) -> 0.12f
            pipeline.standardsColorSpaceProfile -> 0.0f
            configuration.profileName.equals("Rec.2020", ignoreCase = true) -> 0.06f
            else -> 0.20f
        }
        if (filmicStrength > 0f) {
            r = filmicCurve(r, filmicStrength)
            g = filmicCurve(g, filmicStrength)
            b = filmicCurve(b, filmicStrength)
        }

        if (creativeLut != null) {
            val originalR = r
            val originalG = g
            val originalB = b
            val looked = sampleLut(creativeLut, r, g, b)
            val skinProtection = skinToneProtectionStrength(originalR, originalG, originalB)
            val creativeMix = (1f - skinProtection).coerceIn(0.18f, 1f)
            r = originalR + (looked.first - originalR) * creativeMix
            g = originalG + (looked.second - originalG) * creativeMix
            b = originalB + (looked.third - originalB) * creativeMix
        }

        if (
            configuration.highEndEnabled &&
            !logLook.enabled &&
            !pipeline.standardsColorSpaceProfile
        ) {
            val refined = applyHighEndColorRefinement(
                r = r,
                g = g,
                b = b,
                profileName = configuration.profileName
            )
            r = refined.first
            g = refined.second
            b = refined.third
        }

        if (!pipeline.standardsColorSpaceProfile) {
            val exposureCompensated = applyDisplayExposureCompensation(
                r = r,
                g = g,
                b = b,
                logEnabled = logLook.enabled,
                profileName = logAwareProfileName
            )
            r = exposureCompensated.first
            g = exposureCompensated.second
            b = exposureCompensated.third

            val stabilityAdjusted = applyCrossModeColorStability(
                r = r,
                g = g,
                b = b,
                profileName = logAwareProfileName,
                logEnabled = logLook.enabled
            )
            r = stabilityAdjusted.first
            g = stabilityAdjusted.second
            b = stabilityAdjusted.third

            val vibranceAdjusted = applyPerceptualVibrance(
                r = r,
                g = g,
                b = b,
                profileName = logAwareProfileName,
                logEnabled = logLook.enabled
            )
            r = vibranceAdjusted.first
            g = vibranceAdjusted.second
            b = vibranceAdjusted.third
        }
        return Triple(
            r.coerceIn(0f, 1f),
            g.coerceIn(0f, 1f),
            b.coerceIn(0f, 1f)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CPU APPLY (for still capture post-processing)
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun applyToBitmap(src: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val w = src.width; val h = src.height
        val pix = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val previewPipelineLut = buildPreviewPipelineLut()
        val directPipeline = if (previewPipelineLut == null) {
            resolveColorPipeline(snapshotColorConfiguration())
        } else {
            null
        }

        for (i in pix.indices) {
            val px = pix[i]
            val inputR = ((px shr 16) and 0xFF) / 255f
            val inputG = ((px shr 8) and 0xFF) / 255f
            val inputB = (px and 0xFF) / 255f
            val (r, g, b) = if (previewPipelineLut != null) {
                sampleLut(previewPipelineLut, inputR, inputG, inputB)
            } else {
                applyActiveColorPipeline(
                    inputR = inputR,
                    inputG = inputG,
                    inputB = inputB,
                    pipeline = checkNotNull(directPipeline)
                )
            }

            pix[i] = (0xFF shl 24) or
                ((r*255f).toInt().coerceIn(0,255) shl 16) or
                ((g*255f).toInt().coerceIn(0,255) shl  8) or
                (b*255f).toInt().coerceIn(0,255)
        }

        val out = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pix, 0, w, 0, 0, w, h)
        out
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TONE CURVE
    // ─────────────────────────────────────────────────────────────────────────

    fun setToneCurve(ch: ColorChannel, points: List<Pair<Float,Float>>) {
        val curve = splineFromPoints(points)
        synchronized(colorConfigurationLock) {
            when(ch) {
                ColorChannel.R -> curveR = curve
                ColorChannel.G -> curveG = curve
                ColorChannel.B -> curveB = curve
                ColorChannel.ALL -> {
                    curveR = curve
                    curveG = curve.clone()
                    curveB = curve.clone()
                }
            }
            markColorConfigurationChangedLocked()
        }
    }

    fun resetCurves() {
        synchronized(colorConfigurationLock) {
            curveR = buildLinearCurve()
            curveG = buildLinearCurve()
            curveB = buildLinearCurve()
            markColorConfigurationChangedLocked()
        }
    }

    private fun buildLinearCurve() = FloatArray(256) { it / 255f }

    private fun sampleCurve(c: FloatArray, v: Float): Float {
        val idx = (v * 255f).coerceIn(0f, 255f)
        val i0  = idx.toInt().coerceIn(0, 254)
        return c[i0] + (idx - i0) * (c[i0+1] - c[i0])
    }

    private fun filmicCurve(v: Float, shoulder: Float): Float {
        val x = (v + 0.015f).coerceIn(0f, 1.5f)
        val shoulderStrength = shoulder.coerceIn(0.02f, 1.0f)
        val numerator = x * (2.20f * x + 0.03f)
        val denominator = x * ((2.05f + shoulderStrength * 0.55f) * x + 0.58f) + 0.16f
        return (numerator / denominator).coerceIn(0f, 1f)
    }

    private fun splineFromPoints(pts: List<Pair<Float,Float>>): FloatArray {
        val sorted = pts.sortedBy { it.first }
        return FloatArray(256) { i ->
            val x = i / 255f
            val lo = sorted.lastOrNull { it.first <= x }
            val hi = sorted.firstOrNull { it.first > x }
            val value = when {
                lo == null && hi != null -> hi.second
                hi == null && lo != null -> lo.second
                lo != null && hi != null ->
                    lo.second + (x - lo.first) / (hi.first - lo.first) * (hi.second - lo.second)
                else -> x
            }
            value.coerceIn(0f, 1f)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEO RADIANCE SPECTRAL CURVE GAMMA
    // ─────────────────────────────────────────────────────────────────────────

    private fun nLog(
        r: Float,
        g: Float,
        b: Float,
        variant: com.neoproc.neoproccamx.ai.NLogVariant = com.neoproc.neoproccamx.ai.NLogVariant.BASE
    ): Triple<Float, Float, Float> =
        Triple(
            com.neoproc.neoproccamx.ai.NLogMath.encode(r, variant),
            com.neoproc.neoproccamx.ai.NLogMath.encode(g, variant),
            com.neoproc.neoproccamx.ai.NLogMath.encode(b, variant)
        )

    private fun usesBuiltInProfileTransform(profileName: String): Boolean =
        !profileName.equals("Natural", ignoreCase = true)

    private fun applyBuiltInProfileLook(profileName: String, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        return when (profileName.trim().lowercase()) {
            "natural" -> Triple(r, g, b)
            "rec.709" -> rec709(r, g, b)
            "rec.2020" -> rec2020(r, g, b)
            "acescg" -> acesCg(r, g, b)
            "rec.2020-n-log", "rec.2020 n-log", "rec.2020-ndr10+", "rec.2020 ndr10+",
            "rec.2020-nivukx", "rec.2020 nivukx", "neo wide gamut" -> neoWideGamut(r, g, b)
            "rec.2100 (hdr)" -> nLog(r, g, b, com.neoproc.neoproccamx.ai.NLogVariant.BASE)
            "n-log", "n log", "nlog", "n-log base", "n log base", "nlog base" ->
                nLog(r, g, b, com.neoproc.neoproccamx.ai.NLogVariant.BASE)
            "n-log shadow", "n log shadow", "nlog shadow" ->
                nLog(r, g, b, com.neoproc.neoproccamx.ai.NLogVariant.SHADOW)
            "n-log highlight", "n log highlight", "nlog highlight" ->
                nLog(r, g, b, com.neoproc.neoproccamx.ai.NLogVariant.HIGHLIGHT)
            "n-log cinema", "n log cinema", "nlog cinema" ->
                nLog(r, g, b, com.neoproc.neoproccamx.ai.NLogVariant.CINEMA)
            "neo legacy curve" -> nLog(r, g, b, com.neoproc.neoproccamx.ai.NLogVariant.CINEMA)
            "neo flex curve", "neo flex" -> nLog(r, g, b, com.neoproc.neoproccamx.ai.NLogVariant.CINEMA)
            "neo shallow curve" -> nLog(r, g, b, com.neoproc.neoproccamx.ai.NLogVariant.CINEMA)
            "neo standard curve" -> nLog(r, g, b, com.neoproc.neoproccamx.ai.NLogVariant.CINEMA)
            "neo vision curve" -> nLog(r, g, b, com.neoproc.neoproccamx.ai.NLogVariant.CINEMA)
            "neo film curve" -> nLog(r, g, b, com.neoproc.neoproccamx.ai.NLogVariant.CINEMA)
            "neo artistic curve" -> nLog(r, g, b, com.neoproc.neoproccamx.ai.NLogVariant.CINEMA)
            "neo intermediate curve" -> nLog(r, g, b, com.neoproc.neoproccamx.ai.NLogVariant.CINEMA)
            "hdr10 native" -> hdr10Native(r, g, b)
            "hlg native" -> hlgNative(r, g, b)
            "ndr10+", "nivukx vision" -> neoWideGamut(r, g, b)
            "cinematic" -> cinematic(r, g, b)
            "bleach bypass" -> bleachBypass(r, g, b)
            "warm vintage" -> warmVintage(r, g, b)
            "cool teal" -> coolTeal(r, g, b)
            "night vision" -> nightVision(r, g, b)
            "faded film" -> fadedFilm(r, g, b)
            "film noir" -> filmNoir(r, g, b)
            "cyberpunk" -> cyberpunk(r, g, b)
            "nordic" -> nordic(r, g, b)
            "desert" -> desert(r, g, b)
            else -> when (resolveColorSpaceProfile(profileName)?.name) {
                "Rec.709" -> rec709(r, g, b)
                "Rec.2020" -> rec2020(r, g, b)
                "Neo Wide Gamut" -> neoWideGamut(r, g, b)
                else -> Triple(r, g, b)
            }
        }
    }

    private fun applySelectedLogCurve(curve: NeoLogCurve, value: Float): Float =
        when (curve) {
            NeoLogCurve.N_LOG_BASE,
            NeoLogCurve.N_LOG -> com.neoproc.neoproccamx.ai.NLogMath.encode(
                value,
                com.neoproc.neoproccamx.ai.NLogVariant.BASE
            )
            NeoLogCurve.N_LOG_SHADOW -> com.neoproc.neoproccamx.ai.NLogMath.encode(
                value,
                com.neoproc.neoproccamx.ai.NLogVariant.SHADOW
            )
            NeoLogCurve.N_LOG_HIGHLIGHT -> com.neoproc.neoproccamx.ai.NLogMath.encode(
                value,
                com.neoproc.neoproccamx.ai.NLogVariant.HIGHLIGHT
            )
            NeoLogCurve.N_LOG_CINEMA -> com.neoproc.neoproccamx.ai.NLogMath.encode(
                value,
                com.neoproc.neoproccamx.ai.NLogVariant.CINEMA
            )
            NeoLogCurve.NONE -> value
        }

    private fun sampleLut(lut: CubeLUT, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        if (lut.size < 2) {
            return Triple(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        }
        val maxIndex = (lut.size - 1).toFloat().coerceAtLeast(1f)
        val ri = r.coerceIn(0f, 1f) * maxIndex
        val gi = g.coerceIn(0f, 1f) * maxIndex
        val bi = b.coerceIn(0f, 1f) * maxIndex
        val r0 = ri.toInt().coerceIn(0, lut.size - 2)
        val g0 = gi.toInt().coerceIn(0, lut.size - 2)
        val b0 = bi.toInt().coerceIn(0, lut.size - 2)
        val rf = ri - r0
        val gf = gi - g0
        val bf = bi - b0

        val r1 = r0 + 1
        val g1 = g0 + 1
        val b1 = b0 + 1

        fun c(rr: Int, gg: Int, bb: Int) = lut.get(rr, gg, bb)

        val c000 = c(r0, g0, b0)
        val c100 = c(r1, g0, b0)
        val c010 = c(r0, g1, b0)
        val c110 = c(r1, g1, b0)
        val c001 = c(r0, g0, b1)
        val c101 = c(r1, g0, b1)
        val c011 = c(r0, g1, b1)
        val c111 = c(r1, g1, b1)

        fun tetrahedral(channel: Int): Float {
            val v000 = c000[channel]
            val v100 = c100[channel]
            val v010 = c010[channel]
            val v110 = c110[channel]
            val v001 = c001[channel]
            val v101 = c101[channel]
            val v011 = c011[channel]
            val v111 = c111[channel]
            return when {
                rf >= gf -> {
                    if (gf >= bf) {
                        v000 +
                            rf * (v100 - v000) +
                            gf * (v110 - v100) +
                            bf * (v111 - v110)
                    } else if (rf >= bf) {
                        v000 +
                            rf * (v100 - v000) +
                            bf * (v101 - v100) +
                            gf * (v111 - v101)
                    } else {
                        v000 +
                            bf * (v001 - v000) +
                            rf * (v101 - v001) +
                            gf * (v111 - v101)
                    }
                }
                else -> {
                    if (bf >= gf) {
                        v000 +
                            bf * (v001 - v000) +
                            gf * (v011 - v001) +
                            rf * (v111 - v011)
                    } else if (bf >= rf) {
                        v000 +
                            gf * (v010 - v000) +
                            bf * (v011 - v010) +
                            rf * (v111 - v011)
                    } else {
                        v000 +
                            gf * (v010 - v000) +
                            rf * (v110 - v010) +
                            bf * (v111 - v110)
                    }
                }
            }
        }

        return Triple(
            tetrahedral(0).coerceIn(0f, 1f),
            tetrahedral(1).coerceIn(0f, 1f),
            tetrahedral(2).coerceIn(0f, 1f)
        )
    }

    private fun buildPreviewPipelineLut(): CubeLUT? {
        val packed = getPreviewLutFloatArray() ?: return null
        val (rgb, size) = packed
        if (size <= 1) return null
        val entryCount = size * size * size
        if (rgb.size < entryCount * 3) return null
        val data = Array(entryCount) { index ->
            val base = index * 3
            floatArrayOf(
                rgb[base].coerceIn(0f, 1f),
                rgb[base + 1].coerceIn(0f, 1f),
                rgb[base + 2].coerceIn(0f, 1f)
            )
        }
        return CubeLUT("preview-pipeline", size, data, FloatArray(3), FloatArray(3) { 1f })
    }

    private fun skinMask(r: Float, g: Float, b: Float): Float {
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val redLead = ((r - g) * 1.6f + (r - b) * 0.9f + 0.18f).coerceIn(0f, 1f)
        val greenBalance = (1f - kotlin.math.abs(g - 0.52f) * 1.7f).coerceIn(0f, 1f)
        val brightness = (luma * 1.5f).coerceIn(0f, 1f)
        return redLead * greenBalance * brightness
    }

    // s2-f01: internal (not private) so the elite skin-protection monotonicity/continuity lock can
    // exercise it directly. Behaviorally unchanged.
    internal fun skinToneProtectionStrength(r: Float, g: Float, b: Float): Float {
        val skin = skinMask(r, g, b)
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val midtonePreference = (1f - kotlin.math.abs(luma - 0.52f) * 1.6f).coerceIn(0f, 1f)
        return (skin * (0.35f + midtonePreference * 0.55f)).coerceIn(0f, 0.82f)
    }

    private fun skyMask(r: Float, g: Float, b: Float): Float {
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val blueLead = ((b - g) * 1.5f + (b - r) * 0.85f + 0.08f).coerceIn(0f, 1f)
        val brightness = ((luma - 0.26f) * 1.8f).coerceIn(0f, 1f)
        return (blueLead * brightness).coerceIn(0f, 1f)
    }

    private fun foliageMask(r: Float, g: Float, b: Float): Float {
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val greenLead = ((g - r) * 1.15f + (g - b) * 0.65f + 0.06f).coerceIn(0f, 1f)
        val midtoneWeight = (1f - kotlin.math.abs(luma - 0.42f) * 1.8f).coerceIn(0f, 1f)
        return (greenLead * midtoneWeight).coerceIn(0f, 1f)
    }

    private fun softRollOff(value: Float, pivot: Float, strength: Float): Float {
        val clamped = value.coerceAtLeast(0f)
        if (clamped <= pivot) return clamped.coerceIn(0f, 1f)
        val excess = clamped - pivot
        return (pivot + excess / (1f + excess * strength)).coerceIn(0f, 1f)
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge0 == edge1) return if (value >= edge1) 1f else 0f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun applyHighlightShadowTonalBalance(
        value: Float,
        highlights: Float,
        shadows: Float
    ): Float {
        var adjusted = value.coerceIn(0f, 1f)

        if (highlights != 0f) {
            val highlightStrength = highlights.coerceIn(-1f, 1f)
            val highlightMask = smoothstep(0.52f, 1f, adjusted)
            adjusted = if (highlightStrength > 0f) {
                // Preserve bright-scene energy, then only soften the top end.
                val lifted = (
                    adjusted +
                        (1f - adjusted) * highlightStrength * 0.10f
                    ).coerceIn(0f, 1f)
                val rolled = softRollOff(
                    value = lifted,
                    pivot = (0.84f - highlightStrength * 0.08f).coerceIn(0.72f, 0.88f),
                    strength = 0.30f + highlightStrength * 0.75f
                )
                (adjusted + (rolled - adjusted) * highlightMask).coerceIn(0f, 1f)
            } else {
                val punch = -highlightStrength
                val expanded = (
                    adjusted +
                        (1f - adjusted) * punch * highlightMask * 0.16f +
                        (adjusted - 0.50f).coerceAtLeast(0f) * punch * highlightMask * 0.08f
                    ).coerceIn(0f, 1f)
                (adjusted + (expanded - adjusted) * highlightMask).coerceIn(0f, 1f)
            }
        }

        if (shadows != 0f) {
            val shadowStrength = shadows.coerceIn(-1f, 1f)
            val shadowMask = 1f - smoothstep(0.12f, 0.58f, adjusted)
            adjusted = if (shadowStrength > 0f) {
                val lifted = (
                    adjusted +
                        (1f - adjusted) * shadowStrength * 0.20f +
                        shadowMask * shadowStrength * 0.025f
                    ).coerceIn(0f, 1f)
                (adjusted + (lifted - adjusted) * shadowMask).coerceIn(0f, 1f)
            } else {
                val depth = -shadowStrength
                val deepened = (
                    adjusted * (1f - shadowMask * depth * 0.22f) -
                        shadowMask * depth * 0.020f
                    ).coerceIn(0f, 1f)
                (adjusted + (deepened - adjusted) * shadowMask).coerceIn(0f, 1f)
            }
        }

        return adjusted.coerceIn(0f, 1f)
    }

    /**
     * Display-referred → scene-linear via the standard sRGB / Rec.1886 EOTF.
     *
     * Defined piecewise (IEC 61966-2-1, ITU-R BT.1886):
     *   v ≤ 0.04045 → linear = v / 12.92
     *   else        → linear = ((v + 0.055) / 1.055)^2.4
     *
     * The previous `pow(2.05f)` approximation was wrong both at the linear
     * toe and at the gamma value, biasing shadows up by several percent and
     * highlights down. Delegated to [AIHdrMath.srgbToLinear] which is the
     * single source of truth and is unit-tested.
     */
    private fun linearize709(value: Float): Float =
        com.neoproc.neoproccamx.ai.NeoRadianceMath.srgbToLinear(value)

    /**
     * Scene-linear → display-referred sRGB / Rec.1886 OETF (inverse of
     * [linearize709]). Same piecewise definition; delegated to
     * [AIHdrMath.linearToSrgbFloat] for centralization.
     */
    private fun encode709(value: Float): Float =
        com.neoproc.neoproccamx.ai.NeoRadianceMath.linearToSrgbFloat(value)

    private fun toneMapHuman(value: Float): Float {
        val lifted = value + (1f - value).coerceIn(0f, 1f) * 0.018f
        val curved = lifted / (1f + lifted * 0.16f)
        return softRollOff(curved, 0.74f, 1.55f)
    }

    private fun reinhardToneMap(value: Float, whitePoint: Float = 1.4f): Float {
        val v = value.coerceAtLeast(0f)
        val wp = whitePoint.coerceAtLeast(1.01f)
        val numerator = v * (1f + (v / (wp * wp)))
        return (numerator / (1f + v)).coerceIn(0f, 1f)
    }

    private fun applyHumanVisionRec709(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val lr = linearize709(r)
        val lg = linearize709(g)
        val lb = linearize709(b)

        val skin = skinMask(r, g, b)
        val sky = skyMask(r, g, b)
        val foliage = foliageMask(r, g, b)

        val coneL = 0.3811f * lr + 0.5783f * lg + 0.0402f * lb
        val coneM = 0.1967f * lr + 0.7244f * lg + 0.0782f * lb
        val coneS = 0.0241f * lr + 0.1288f * lg + 0.8444f * lb

        var rr = (4.4679f * coneL - 3.5873f * coneM + 0.1193f * coneS).coerceAtLeast(0f)
        var gg = (-1.2186f * coneL + 2.3809f * coneM - 0.1624f * coneS).coerceAtLeast(0f)
        var bb = (0.0497f * coneL - 0.2439f * coneM + 1.2045f * coneS).coerceAtLeast(0f)

        val luma = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
        val midtoneBoost = 1f + ((0.58f - kotlin.math.abs(luma - 0.44f)) * 0.045f).coerceAtLeast(0f)
        val saturationScale = (
            midtoneBoost *
                (1f + skin * 0.05f + foliage * 0.025f - sky * 0.03f)
            ).coerceIn(0.92f, 1.08f)

        rr = (luma + (rr - luma) * saturationScale + skin * 0.015f).coerceAtLeast(0f)
        gg = (luma + (gg - luma) * (saturationScale * (1f - foliage * 0.05f))).coerceAtLeast(0f)
        bb = (luma + (bb - luma) * (saturationScale * (1f - sky * 0.06f))).coerceAtLeast(0f)

        val warmth = ((rr - bb) * 0.075f + skin * 0.02f).coerceIn(-0.01f, 0.04f)
        rr = (rr + warmth * 0.30f).coerceAtLeast(0f)
        gg = (gg + warmth * 0.06f).coerceAtLeast(0f)
        bb = (bb - warmth * 0.20f).coerceAtLeast(0f)

        val shadowLift = 0.012f + skin * 0.006f
        rr += shadowLift
        gg += shadowLift * 0.9f
        bb += shadowLift * 0.8f

        rr = reinhardToneMap(rr, whitePoint = 1.28f - sky * 0.08f)
        gg = reinhardToneMap(gg, whitePoint = 1.30f - sky * 0.06f)
        bb = reinhardToneMap(bb, whitePoint = 1.18f - sky * 0.12f)

        rr = softRollOff(rr, 0.80f, 0.82f + sky * 0.18f)
        gg = softRollOff(gg, 0.80f, 0.84f + sky * 0.16f)
        bb = softRollOff(bb, 0.82f, 0.88f + sky * 0.24f)

        return Triple(
            encode709(rr),
            encode709(gg),
            encode709(bb)
        )
    }

    private fun applyCinematicBase(
        r: Float,
        g: Float,
        b: Float,
        warmthBias: Float,
        highlightStrength: Float,
        saturationScale: Float
    ): Triple<Float, Float, Float> {
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val skin = skinMask(r, g, b)
        var rr = (luma + (r - luma) * saturationScale + warmthBias * 0.75f + skin * 0.020f).coerceIn(0f, 1f)
        var gg = (luma + (g - luma) * (saturationScale * 0.98f) + skin * 0.010f).coerceIn(0f, 1f)
        var bb = (luma + (b - luma) * (saturationScale * 0.94f) - warmthBias * 0.32f).coerceIn(0f, 1f)
        rr = softRollOff(rr, 0.70f, highlightStrength)
        gg = softRollOff(gg, 0.72f, highlightStrength * 0.95f)
        bb = softRollOff(bb, 0.74f, highlightStrength * 0.88f)
        return Triple(rr, gg, bb)
    }

    private fun applyLogPreviewParity(
        r: Float,
        g: Float,
        b: Float,
        logLook: NeoLogLookProfile,
        parity: VideoLogPreviewParity
    ): Triple<Float, Float, Float> {
        val parityStrength = (1f - parity.highLoadPenaltyScale * 0.22f).coerceIn(0.74f, 1f)

        val luma0 = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val contrastScale = (
            1f +
                parity.contrastLift *
                    (0.80f + logLook.contrastCap * 0.35f) *
                    parityStrength
            ).coerceIn(1f, 1.36f)
        var rr = (luma0 + (r - luma0) * contrastScale).coerceAtLeast(0f)
        var gg = (luma0 + (g - luma0) * contrastScale).coerceAtLeast(0f)
        var bb = (luma0 + (b - luma0) * contrastScale).coerceAtLeast(0f)

        val luma1 = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
        val saturationScale = (
            1f +
                (parity.saturationLift + parity.lutSaturationBoost) *
                    (0.92f + logLook.saturationCap * 0.20f) *
                    parityStrength
            ).coerceIn(1f, 1.30f)
        rr = (luma1 + (rr - luma1) * saturationScale).coerceAtLeast(0f)
        gg = (luma1 + (gg - luma1) * saturationScale).coerceAtLeast(0f)
        bb = (luma1 + (bb - luma1) * saturationScale).coerceAtLeast(0f)
        
        if (parity.brightnessLift > 0f) {
            val bl = parity.brightnessLift * parityStrength
            rr = (rr + (1f - rr) * bl).coerceIn(0f, 1f)
            gg = (gg + (1f - gg) * bl).coerceIn(0f, 1f)
            bb = (bb + (1f - bb) * bl).coerceIn(0f, 1f)
        }

        val shadowLift = (
            logLook.shadowBoostFloor *
                parity.shadowBiasScale *
                0.16f *
                parityStrength
            ).coerceIn(0f, 0.14f)
        rr += (1f - rr) * shadowLift
        gg += (1f - gg) * shadowLift * 0.95f
        bb += (1f - bb) * shadowLift * 0.90f

        val highlightStrength = (
            (0.90f + logLook.highlightCompressionFloor * 1.45f) *
                parity.highlightBiasScale
            ).coerceIn(0.55f, 2.20f)
        rr = softRollOff(rr, 0.80f, highlightStrength)
        gg = softRollOff(gg, 0.80f, highlightStrength)
        bb = softRollOff(bb, 0.82f, highlightStrength * 1.04f)

        return Triple(
            rr.coerceIn(0f, 1f),
            gg.coerceIn(0f, 1f),
            bb.coerceIn(0f, 1f)
        )
    }

    private fun applyDisplayExposureCompensation(
        r: Float,
        g: Float,
        b: Float,
        logEnabled: Boolean,
        profileName: String
    ): Triple<Float, Float, Float> {
        val wideGamutOrHdr = isWideGamutOrHdrProfile(profileName)
        var rr = r.coerceIn(0f, 1f)
        var gg = g.coerceIn(0f, 1f)
        var bb = b.coerceIn(0f, 1f)
        val luma = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb

        val baseLift = when {
            logEnabled -> 0.26f
            wideGamutOrHdr -> 0.20f
            else -> 0.08f
        }
        val daylightRecoveryLift = when {
            (logEnabled || wideGamutOrHdr) && luma in 0.16f..0.62f -> 0.02f
            else -> 0f
        }
        val shadowMask = 1f - smoothstep(0.08f, 0.48f, luma)
        val midtoneMask = 1f - smoothstep(0.38f, 0.86f, luma)
        val lift = (
            (baseLift + daylightRecoveryLift) *
                (shadowMask * 0.62f + midtoneMask * 0.38f)
            ).coerceIn(0f, 0.22f)
        if (lift <= 0f) {
            return Triple(rr, gg, bb)
        }

        rr = (rr + (1f - rr) * lift).coerceIn(0f, 1f)
        gg = (gg + (1f - gg) * (lift * 0.98f)).coerceIn(0f, 1f)
        bb = (bb + (1f - bb) * (lift * 0.95f)).coerceIn(0f, 1f)

        val rollOffStrength = if (logEnabled || wideGamutOrHdr) 1.00f else 0.92f
        rr = softRollOff(rr, 0.86f, rollOffStrength)
        gg = softRollOff(gg, 0.86f, rollOffStrength * 0.96f)
        bb = softRollOff(bb, 0.88f, rollOffStrength * 1.05f)

        return Triple(
            rr.coerceIn(0f, 1f),
            gg.coerceIn(0f, 1f),
            bb.coerceIn(0f, 1f)
        )
    }

    private fun applyCrossModeColorStability(
        r: Float,
        g: Float,
        b: Float,
        profileName: String,
        logEnabled: Boolean
    ): Triple<Float, Float, Float> {
        var rr = r.coerceIn(0f, 1f)
        var gg = g.coerceIn(0f, 1f)
        var bb = b.coerceIn(0f, 1f)
        val luma = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
        val skin = skinMask(rr, gg, bb)
        val sky = skyMask(rr, gg, bb)
        val foliage = foliageMask(rr, gg, bb)
        val chroma = (maxOf(rr, gg, bb) - minOf(rr, gg, bb)).coerceIn(0f, 1f)
        val castRisk = smoothstep(0.14f, 0.58f, chroma)
        val highlightMask = smoothstep(0.62f, 1f, luma)
        val shadowMask = 1f - smoothstep(0.06f, 0.34f, luma)
        val protection = (skin * 0.72f + sky * 0.34f + foliage * 0.22f).coerceIn(0f, 0.86f)
        val baseMix = when {
            logEnabled -> 0.055f
            isWideGamutOrHdrProfile(profileName) -> 0.054f
            else -> 0.036f
        }
        val neutralMix = (
            baseMix +
                castRisk * 0.10f +
                highlightMask * 0.03f +
                shadowMask * 0.02f
            ) * (1f - protection * 0.60f)
        val neutral = luma
        rr += (neutral - rr) * neutralMix
        gg += (neutral - gg) * neutralMix
        bb += (neutral - bb) * neutralMix

        val redDominance = (rr - maxOf(gg, bb)).coerceAtLeast(0f)
        if (redDominance > 0f) {
            val redTrim = (
                0.018f +
                    castRisk * 0.026f +
                    highlightMask * 0.014f
                ).coerceIn(0f, 0.08f) * (1f - skin * 0.78f)
            rr -= redDominance * redTrim
            gg += redDominance * redTrim * 0.08f
            bb += redDominance * redTrim * 0.34f
        }

        val blueDominance = (bb - maxOf(rr, gg)).coerceAtLeast(0f)
        if (blueDominance > 0f) {
            val blueTrim = (
                0.012f +
                    castRisk * 0.018f +
                    shadowMask * 0.012f
                ).coerceIn(0f, 0.06f) * (1f - sky * 0.66f)
            bb -= blueDominance * blueTrim
            rr += blueDominance * blueTrim * 0.15f
            gg += blueDominance * blueTrim * 0.10f
        }

        val balancedLuma = (0.2126f * rr + 0.7152f * gg + 0.0722f * bb).coerceAtLeast(1e-4f)
        val lumaCompensation = (luma / balancedLuma).coerceIn(0.92f, 1.08f)
        rr *= lumaCompensation
        gg *= lumaCompensation
        bb *= lumaCompensation

        return Triple(
            rr.coerceIn(0f, 1f),
            gg.coerceIn(0f, 1f),
            bb.coerceIn(0f, 1f)
        )
    }

    private fun applyPerceptualVibrance(
        r: Float,
        g: Float,
        b: Float,
        profileName: String,
        logEnabled: Boolean
    ): Triple<Float, Float, Float> {
        var rr = r.coerceIn(0f, 1f)
        var gg = g.coerceIn(0f, 1f)
        var bb = b.coerceIn(0f, 1f)
        val luma = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
        val chroma = (maxOf(rr, gg, bb) - minOf(rr, gg, bb)).coerceIn(0f, 1f)
        if (chroma >= 0.72f) {
            return Triple(rr, gg, bb)
        }

        val skin = skinMask(rr, gg, bb)
        val sky = skyMask(rr, gg, bb)
        val foliage = foliageMask(rr, gg, bb)
        val midtoneMask = (1f - abs(luma - 0.48f) / 0.48f).coerceIn(0f, 1f)
        val lowChromaMask = (1f - chroma / 0.52f).coerceIn(0f, 1f)
        val profileBoost = when {
            logEnabled -> 0.024f
            isWideGamutOrHdrProfile(profileName) -> 0.036f
            else -> 0.038f
        }
        val protection = (skin * 0.62f + sky * 0.42f).coerceIn(0f, 0.85f)
        val boost = (
            profileBoost *
                midtoneMask *
                lowChromaMask *
                (1f - protection * 0.70f) +
                foliage * 0.010f * (1f - lowChromaMask * 0.20f)
            ).coerceIn(0f, if (logEnabled) 0.040f else 0.072f)
        if (boost <= 1e-4f) {
            return Triple(rr, gg, bb)
        }

        val satScale = 1f + boost
        rr = luma + (rr - luma) * satScale
        gg = luma + (gg - luma) * satScale
        bb = luma + (bb - luma) * satScale

        if (skin > 0f) {
            rr += skin * boost * 0.08f
            bb -= skin * boost * 0.05f
        }

        return Triple(
            rr.coerceIn(0f, 1f),
            gg.coerceIn(0f, 1f),
            bb.coerceIn(0f, 1f)
        )
    }

    private fun isWideGamutOrHdrProfile(profileName: String): Boolean {
        val profileKey = profileName.trim().lowercase()
        return profileKey.contains("2020") ||
            profileKey.contains("2100") ||
            profileKey.contains("hdr") ||
            profileKey.contains("hlg") ||
            profileKey.contains("ndr10") ||
            profileKey.contains("nivukx") ||
            profileKey.contains("n-log") ||
            profileKey.contains("aces") ||
            profileKey.contains("widegamut") ||
            profileKey.contains("wide gamut")
    }

    private fun applyHighEndColorRefinement(
        r: Float,
        g: Float,
        b: Float,
        profileName: String
    ): Triple<Float, Float, Float> {
        val profileKey = profileName.trim().lowercase()
        val hdrLike =
            profileKey.contains("hdr") ||
                profileKey.contains("2100") ||
                profileKey.contains("hlg") ||
                profileKey.contains("ndr10") ||
                profileKey.contains("nivukx")
        val wideGamut = isWideGamutOrHdrProfile(profileName)

        val linearR = com.neoproc.neoproccamx.ai.NeoRadianceMath.srgbToLinear(r.coerceIn(0f, 1f))
        val linearG = com.neoproc.neoproccamx.ai.NeoRadianceMath.srgbToLinear(g.coerceIn(0f, 1f))
        val linearB = com.neoproc.neoproccamx.ai.NeoRadianceMath.srgbToLinear(b.coerceIn(0f, 1f))

        val recovered = com.neoproc.neoproccamx.ai.NeoRadianceMath.spectralRecovery(
            r = linearR,
            g = linearG,
            b = linearB,
            strength = if (hdrLike) 1.20f else 0.85f
        )
        val shadowBalanced = com.neoproc.neoproccamx.ai.NeoRadianceMath.shadowBloom(
            r = recovered.first,
            g = recovered.second,
            b = recovered.third,
            strength = if (hdrLike) 0.10f else 0.06f,
            threshold = if (hdrLike) 0.10f else 0.08f
        )

        val luma = com.neoproc.neoproccamx.ai.NeoRadianceMath.linearLuma(
            shadowBalanced.first,
            shadowBalanced.second,
            shadowBalanced.third
        )
        val safeLuma = luma.coerceAtLeast(1e-4f)
        val ratioR = (shadowBalanced.first / safeLuma).coerceIn(0f, 4f)
        val ratioG = (shadowBalanced.second / safeLuma).coerceIn(0f, 4f)
        val ratioB = (shadowBalanced.third / safeLuma).coerceIn(0f, 4f)

        val cascadeLuma = com.neoproc.neoproccamx.ai.NeoRadianceMath.cascadeFold(luma, 0.35f, 0.55f)
        val shoulderLuma = com.neoproc.neoproccamx.ai.NeoRadianceMath.spectralShoulder(
            luma,
            maxNits = if (hdrLike) 1000f else 400f
        )
        val depthLuma = com.neoproc.neoproccamx.ai.NeoRadianceMath.depthCurve(
            luma,
            strength = if (hdrLike) 1.2f else 0.8f
        )

        val highlightBlend = com.neoproc.neoproccamx.ai.NeoRadianceMath.smoothstep(0.40f, 1.45f, luma)
        val cascadeWeight = (0.38f + highlightBlend * 0.20f).coerceIn(0.25f, 0.65f)
        val shoulderWeight = if (wideGamut) 0.22f else 0.14f
        val depthWeight = (
            (if (hdrLike) 0.24f else 0.34f) *
                (1f - highlightBlend * 0.28f)
            ).coerceAtLeast(0.12f)
        val baseWeight = (1f - cascadeWeight - shoulderWeight - depthWeight).coerceAtLeast(0.06f)
        val totalWeight = cascadeWeight + shoulderWeight + depthWeight + baseWeight
        val mappedLuma = (
            cascadeLuma * cascadeWeight +
                shoulderLuma * shoulderWeight +
                depthLuma * depthWeight +
                cascadeLuma * baseWeight
            ) / totalWeight

        val chromaPreserve = if (hdrLike) 0.96f else 0.92f
        var mappedR = mappedLuma + (ratioR * mappedLuma - mappedLuma) * chromaPreserve
        var mappedG = mappedLuma + (ratioG * mappedLuma - mappedLuma) * chromaPreserve
        var mappedB = mappedLuma + (ratioB * mappedLuma - mappedLuma) * chromaPreserve

        val channelPeak = maxOf(mappedR, mappedG, mappedB)
        if (channelPeak > 1f) {
            val compression = 1f / channelPeak
            val shoulderBlend = smoothstep(1f, 1.45f, channelPeak)
            val compressionScale = 1f - shoulderBlend + shoulderBlend * compression
            mappedR *= compressionScale
            mappedG *= compressionScale
            mappedB *= compressionScale
        }

        if (wideGamut) {
            val gamutMapped = AcesColorEngine.compressNeoWideGamutRgb(
                AcesRgb(mappedR, mappedG, mappedB)
            )
            mappedR = gamutMapped.r
            mappedG = gamutMapped.g
            mappedB = gamutMapped.b
        }

        if (profileKey.contains("ndr10")) {
            val mappedPeak = maxOf(mappedR, mappedG, mappedB, 1f)
            val ndrLuma = AcesColorEngine.applyNdr10DynamicToneMap(
                linearSceneLuma = luma.coerceAtLeast(0.0001f),
                sceneMin = 0.0004f,
                sceneMax = mappedPeak * 4.0f
            ).coerceAtLeast(0.0001f)
            val lumaScale = (ndrLuma / safeLuma).coerceIn(0.12f, 4.0f)
            mappedR *= lumaScale
            mappedG *= lumaScale
            mappedB *= lumaScale
        }

        if (profileKey.contains("nivukx")) {
            val shaped = AcesColorEngine.applyNivukxVisionRgb(
                rgb = AcesRgb(mappedR, mappedG, mappedB),
                sceneLux = if (hdrLike) 650f else 420f,
                kelvin = if (hdrLike) 5_600f else 5_200f
            )
            mappedR = shaped.r
            mappedG = shaped.g
            mappedB = shaped.b
        }

        if (hdrLike || wideGamut) {
            val density = AcesColorEngine.applySubtractiveFilmDensity(
                AcesRgb(mappedR, mappedG, mappedB)
            )
            val highlightSafe = AcesColorEngine.highlightSaturationRolloff(density)
            mappedR = highlightSafe.r
            mappedG = highlightSafe.g
            mappedB = highlightSafe.b
        }

        val desaturation = smoothstep(0.78f, 1.02f, maxOf(mappedR, mappedG, mappedB)) *
            if (hdrLike) 0.10f else 0.06f
        if (desaturation > 0f) {
            val neutralized = com.neoproc.neoproccamx.ai.NeoRadianceMath.blendTowardNeutral(
                mappedR,
                mappedG,
                mappedB,
                desaturation
            )
            mappedR = neutralized.first
            mappedG = neutralized.second
            mappedB = neutralized.third
        }

        var outR = com.neoproc.neoproccamx.ai.NeoRadianceMath.linearToSrgbFloat(mappedR.coerceIn(0f, 1f))
        var outG = com.neoproc.neoproccamx.ai.NeoRadianceMath.linearToSrgbFloat(mappedG.coerceIn(0f, 1f))
        var outB = com.neoproc.neoproccamx.ai.NeoRadianceMath.linearToSrgbFloat(mappedB.coerceIn(0f, 1f))

        if (wideGamut) {
            val gamutMapped = com.neoproc.neoproccamx.ai.NeoRadianceMath.perceptualGamutFold(outR, outG, outB)
            val gamutMix = if (hdrLike) 0.55f else 0.35f
            outR += (gamutMapped.first - outR) * gamutMix
            outG += (gamutMapped.second - outG) * gamutMix
            outB += (gamutMapped.third - outB) * gamutMix
        }

        return Triple(
            outR.coerceIn(0f, 1f),
            outG.coerceIn(0f, 1f),
            outB.coerceIn(0f, 1f)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BUILT-IN PROFILES
    // ─────────────────────────────────────────────────────────────────────────

    private fun generateBuiltIn(name: String): CubeLUT {
        val sz = if (HighEndQualityPolicy.enabled) {
            HighEndQualityPolicy.highPrecisionLutSize
        } else {
            HighEndQualityPolicy.standardLutSize
        }
        val data = Array(sz*sz*sz) { FloatArray(3) }
        for (bi in 0 until sz) for (gi in 0 until sz) for (ri in 0 until sz) {
            val r = ri / (sz-1f); val g = gi / (sz-1f); val b = bi / (sz-1f)
            val (or, og, ob) = applyBuiltInProfileLook(name, r, g, b)
            val idx = ri + gi * sz + bi * sz * sz
            data[idx] = floatArrayOf(or.coerceIn(0f,1f), og.coerceIn(0f,1f), ob.coerceIn(0f,1f))
        }
        return CubeLUT(name, sz, data, FloatArray(3), FloatArray(3) { 1f })
    }

    private fun generateCreativeLut(name: String): CubeLUT {
        val technicalLook = name.startsWith("Neo") ||
            name.equals("Neutral Film", ignoreCase = true)
        val sz = if (HighEndQualityPolicy.enabled && technicalLook) {
            HighEndQualityPolicy.highPrecisionLutSize
        } else {
            HighEndQualityPolicy.standardLutSize
        }
        val data = Array(sz * sz * sz) { FloatArray(3) }
        for (bi in 0 until sz) for (gi in 0 until sz) for (ri in 0 until sz) {
            val r = ri / (sz - 1f)
            val g = gi / (sz - 1f)
            val b = bi / (sz - 1f)
            val (or, og, ob) = when (name) {
                "Neutral Film" -> neutralFilm(r, g, b)
                "Warm Cinema" -> warmCinema(r, g, b)
                "Teal & Orange" -> tealAndOrange(r, g, b)
                "Neo 709 Clean" -> rec709(r, g, b)
                "Neo HDR Soft" -> hlgNative(r, g, b)
                "Neo Cinema Gold" -> warmCinema(r, g, b)
                "Kodak 2383" -> kodak2383(r, g, b)
                "Fuji 3510" -> fuji3510(r, g, b)
                "Bleach Bypass" -> bleachBypass(r, g, b)
                "Cross Process" -> crossProcess(r, g, b)
                "Vintage Chrome" -> vintageKodachrome(r, g, b)
                "Vintage Kodachrome" -> vintageKodachrome(r, g, b)
                "Modern Blockbuster" -> modernBlockbuster(r, g, b)
                "Sci-Fi Cool" -> sciFiCool(r, g, b)
                "Fantasy Warm" -> fantasyWarm(r, g, b)
                else -> Triple(r, g, b)
            }
            val idx = ri + gi * sz + bi * sz * sz
            data[idx] = floatArrayOf(
                or.coerceIn(0f, 1f),
                og.coerceIn(0f, 1f),
                ob.coerceIn(0f, 1f)
            )
        }
        return CubeLUT(name, sz, data, FloatArray(3), FloatArray(3) { 1f })
    }

    private fun rec709(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        return Triple(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    private fun rec2020(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        return com.neoproc.neoproccamx.ai.NeoRadianceMath
            .srgbToRec2020(r, g, b)
            .eliteBoundedRgb()
    }

    private fun acesCg(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        return com.neoproc.neoproccamx.ai.NeoRadianceMath
            .srgbToAcesAp1(r, g, b)
            .eliteBoundedRgb()
    }

    private fun neoWideGamut(r: Float, g: Float, b: Float): Triple<Float, Float, Float> =
        colorSpaceTransform("Neo Wide Gamut", r, g, b)

    private fun neutralFilm(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val rr = filmicCurve(r * 0.985f + luma * 0.010f, 0.22f)
        val gg = filmicCurve(g * 0.985f + luma * 0.008f, 0.22f)
        val bb = filmicCurve(b * 0.980f + luma * 0.006f, 0.20f)
        return Triple(rr, gg, bb)
    }

    private fun warmCinema(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val rr = filmicCurve((r * 1.03f + 0.015f).coerceIn(0f, 1f), 0.24f)
        val gg = filmicCurve((g * 0.99f + 0.010f).coerceIn(0f, 1f), 0.23f)
        val bb = filmicCurve((b * 0.93f + 0.006f).coerceIn(0f, 1f), 0.22f)
        return Triple(rr, gg, bb)
    }

    private fun tealAndOrange(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val warmMask = ((r - b) * 1.8f + 0.5f).coerceIn(0f, 1f)
        val coolMask = ((b - r) * 1.8f + 0.5f).coerceIn(0f, 1f)
        val rr = (r + warmMask * 0.045f - coolMask * 0.010f + luma * 0.010f).coerceIn(0f, 1f)
        val gg = (g * 0.985f + coolMask * 0.010f).coerceIn(0f, 1f)
        val bb = (b + coolMask * 0.040f - warmMask * 0.018f).coerceIn(0f, 1f)
        return Triple(filmicCurve(rr, 0.23f), filmicCurve(gg, 0.22f), filmicCurve(bb, 0.21f))
    }

    private fun cinematic(r:Float,g:Float,b:Float): Triple<Float,Float,Float> {
        val luma = 0.2126f*r + 0.7152f*g + 0.0722f*b
        return Triple(r*0.9f+0.05f+luma*0.03f, g*0.88f+0.04f, b*0.80f+0.06f)
    }
    private fun bleachBypass(r:Float,g:Float,b:Float): Triple<Float,Float,Float> {
        val gray = 0.2126f*r + 0.7152f*g + 0.0722f*b
        return Triple(r*0.5f+gray*0.5f, g*0.5f+gray*0.5f, b*0.5f+gray*0.5f)
    }
    private fun warmVintage(r:Float,g:Float,b:Float) = Triple(r*1.12f+0.02f, g*0.94f+0.01f, b*0.72f+0.02f)
    private fun coolTeal(r:Float,g:Float,b:Float) = Triple(r*0.72f, g*1.06f+0.02f, b*1.18f+0.02f)
    private fun nightVision(r:Float,g:Float,b:Float): Triple<Float,Float,Float> {
        val luma = 0.2126f*r + 0.7152f*g + 0.0722f*b; return Triple(0f, luma, 0f)
    }
    private fun fadedFilm(r:Float,g:Float,b:Float) = Triple(r*0.85f+0.08f, g*0.83f+0.07f, b*0.80f+0.09f)

    private fun rec2100Hdr(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val rec2020 = com.neoproc.neoproccamx.ai.NeoRadianceMath.srgbToRec2020(r, g, b)
        return Triple(
            com.neoproc.neoproccamx.ai.NeoRadianceMath.neutralGamutFold(rec2020.first),
            com.neoproc.neoproccamx.ai.NeoRadianceMath.neutralGamutFold(rec2020.second),
            com.neoproc.neoproccamx.ai.NeoRadianceMath.neutralGamutFold(rec2020.third)
        )
    }

    private fun Triple<Float, Float, Float>.boundedRgb(): Triple<Float, Float, Float> =
        eliteBoundedRgb()

    private fun Triple<Float, Float, Float>.eliteBoundedRgb(): Triple<Float, Float, Float> {
        var rr = first
        var gg = second
        var bb = third
        val luma = (0.2627f * rr + 0.6780f * gg + 0.0593f * bb).coerceIn(0f, 1f)
        val maxC = maxOf(rr, gg, bb)
        if (maxC > 1f) {
            val scale = ((1f - luma) / (maxC - luma).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
            rr = luma + (rr - luma) * scale
            gg = luma + (gg - luma) * scale
            bb = luma + (bb - luma) * scale
        }
        val minC = minOf(rr, gg, bb)
        if (minC < 0f) {
            val scale = (luma / (luma - minC).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
            rr = luma + (rr - luma) * scale
            gg = luma + (gg - luma) * scale
            bb = luma + (bb - luma) * scale
        }
        return Triple(
            rr.coerceIn(0f, 1f),
            gg.coerceIn(0f, 1f),
            bb.coerceIn(0f, 1f)
        )
    }

    private fun colorSpaceTransform(
        targetProfileName: String,
        r: Float,
        g: Float,
        b: Float
    ): Triple<Float, Float, Float> =
        rec709ToProfileColorSpace(targetProfileName, r, g, b)
            ?.eliteBoundedRgb()
            ?: Triple(
                r.coerceIn(0f, 1f),
                g.coerceIn(0f, 1f),
                b.coerceIn(0f, 1f)
            )

    @Suppress("unused")
    private fun Triple<Float, Float, Float>.legacyClipRgb(): Triple<Float, Float, Float> =
        Triple(
            first.coerceIn(0f, 1f),
            second.coerceIn(0f, 1f),
            third.coerceIn(0f, 1f)
        )

    private fun hdr10Native(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val rec2020 = com.neoproc.neoproccamx.ai.NeoRadianceMath.srgbToRec2020(r, g, b)
        return Triple(
            com.neoproc.neoproccamx.ai.NeoRadianceMath.pqInvEotf(rec2020.first.coerceIn(0f, 1f)),
            com.neoproc.neoproccamx.ai.NeoRadianceMath.pqInvEotf(rec2020.second.coerceIn(0f, 1f)),
            com.neoproc.neoproccamx.ai.NeoRadianceMath.pqInvEotf(rec2020.third.coerceIn(0f, 1f))
        )
    }

    private fun hlgNative(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val rec2020 = com.neoproc.neoproccamx.ai.NeoRadianceMath.srgbToRec2020(r, g, b)
        return Triple(
            com.neoproc.neoproccamx.ai.NeoRadianceMath.hlgOetf(rec2020.first.coerceIn(0f, 1f)),
            com.neoproc.neoproccamx.ai.NeoRadianceMath.hlgOetf(rec2020.second.coerceIn(0f, 1f)),
            com.neoproc.neoproccamx.ai.NeoRadianceMath.hlgOetf(rec2020.third.coerceIn(0f, 1f))
        )
    }

    private fun filmNoir(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val gray = (0.2126f * r + 0.7152f * g + 0.0722f * b)
        val contrast = filmicCurve(gray, 0.34f)
        return Triple(contrast * 1.02f, contrast * 1.02f, contrast * 1.05f)
    }

    private fun cyberpunk(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val cool = (b * 1.12f + g * 0.08f).coerceIn(0f, 1f)
        return Triple(
            (r * 0.86f + cool * 0.08f).coerceIn(0f, 1f),
            (g * 0.92f + 0.02f).coerceIn(0f, 1f),
            (cool + 0.06f).coerceIn(0f, 1f)
        )
    }

    private fun nordic(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return Triple(
            (luma + (r - luma) * 0.90f).coerceIn(0f, 1f),
            (luma + (g - luma) * 0.94f).coerceIn(0f, 1f),
            (luma + (b - luma) * 1.08f + 0.01f).coerceIn(0f, 1f)
        )
    }

    private fun desert(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return Triple(
            (luma + (r - luma) * 1.10f + 0.02f).coerceIn(0f, 1f),
            (luma + (g - luma) * 1.00f + 0.01f).coerceIn(0f, 1f),
            (luma + (b - luma) * 0.84f).coerceIn(0f, 1f)
        )
    }

    private fun kodak2383(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val warm = warmCinema(r, g, b)
        return Triple(
            filmicCurve((warm.first * 1.02f + 0.01f).coerceIn(0f, 1f), 0.26f),
            filmicCurve((warm.second * 0.99f + 0.005f).coerceIn(0f, 1f), 0.25f),
            filmicCurve((warm.third * 0.93f).coerceIn(0f, 1f), 0.24f)
        )
    }

    private fun fuji3510(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val base = neutralFilm(r, g, b)
        return Triple(
            filmicCurve((base.first * 0.98f).coerceIn(0f, 1f), 0.23f),
            filmicCurve((base.second * 1.01f + 0.01f).coerceIn(0f, 1f), 0.23f),
            filmicCurve((base.third * 1.05f + 0.01f).coerceIn(0f, 1f), 0.22f)
        )
    }

    private fun crossProcess(r: Float, g: Float, b: Float): Triple<Float, Float, Float> =
        Triple(
            (g * 0.85f + r * 0.20f).coerceIn(0f, 1f),
            (b * 0.55f + g * 0.45f + 0.03f).coerceIn(0f, 1f),
            (r * 0.45f + b * 0.65f + 0.02f).coerceIn(0f, 1f)
        )

    private fun vintageKodachrome(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val warm = warmVintage(r, g, b)
        return Triple(
            filmicCurve(warm.first.coerceIn(0f, 1f), 0.30f),
            filmicCurve((warm.second * 0.96f).coerceIn(0f, 1f), 0.30f),
            filmicCurve((warm.third * 0.82f).coerceIn(0f, 1f), 0.28f)
        )
    }

    private fun modernBlockbuster(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val base = tealAndOrange(r, g, b)
        return Triple(
            filmicCurve(base.first, 0.25f),
            filmicCurve(base.second, 0.24f),
            filmicCurve(base.third, 0.22f)
        )
    }

    private fun sciFiCool(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val cool = coolTeal(r, g, b)
        return Triple(
            (cool.first * 0.92f).coerceIn(0f, 1f),
            (cool.second * 1.02f).coerceIn(0f, 1f),
            (cool.third * 1.08f + 0.02f).coerceIn(0f, 1f)
        )
    }

    private fun fantasyWarm(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val warm = warmCinema(r, g, b)
        return Triple(
            (warm.first * 1.05f + 0.02f).coerceIn(0f, 1f),
            (warm.second * 1.01f + 0.01f).coerceIn(0f, 1f),
            (warm.third * 0.90f + 0.01f).coerceIn(0f, 1f)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CUBE FILE PARSER
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseCube(file: File): CubeLUT {
        return file.inputStream().use { stream ->
            LUTProcessor.parseCube(stream, sourceName = file.name)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // COLOUR SPACE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private fun rgbToHsl(r: Float, g: Float, b: Float): Triple<Float,Float,Float> {
        val max = maxOf(r,g,b); val min = minOf(r,g,b); val d = max-min
        val l = (max+min)/2f
        if (d == 0f) return Triple(0f, 0f, l)
        val s = if (l > 0.5f) d/(2f-max-min) else d/(max+min)
        val h = when(max) {
            r -> ((g-b)/d + (if(g<b) 6f else 0f)) / 6f
            g -> ((b-r)/d + 2f) / 6f
            else -> ((r-g)/d + 4f) / 6f
        }
        return Triple(h, s, l)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        if (s == 0f) return floatArrayOf(l, l, l)
        fun hue2rgb(p: Float, q: Float, t: Float): Float {
            val tt = (t + 1f) % 1f
            return when { tt < 1f/6 -> p+(q-p)*6f*tt; tt < 0.5f -> q
                tt < 2f/3 -> p+(q-p)*(2f/3-tt)*6f; else -> p }
        }
        val q = if (l < 0.5f) l*(1f+s) else l+s-l*s
        val p = 2f*l-q
        return floatArrayOf(hue2rgb(p,q,h+1f/3), hue2rgb(p,q,h), hue2rgb(p,q,h-1f/3))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADVANCED PROFESSIONAL COLOR SCIENCE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Apply full ACES pipeline to RGB values
     * sRGB → ACES AP0 → RRT → ODT → sRGB
     */
    fun applyAcesPipeline(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        return com.neoproc.neoproccamx.ai.NeoRadianceMath.fullAcesPipeline(r, g, b)
    }
    
    /**
     * Apply ACES tone mapping only (without color space conversion)
     */
    fun applyAcesToneMapping(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val (rrtR, rrtG, rrtB) = com.neoproc.neoproccamx.ai.NeoRadianceMath.acesRrt(r, g, b)
        return com.neoproc.neoproccamx.ai.NeoRadianceMath.acesOdtSrgb(rrtR, rrtG, rrtB)
    }
    
    /**
     * Convert to OKLab and apply perceptual gamut mapping
     */
    fun applyPerceptualGamutMapping(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        return com.neoproc.neoproccamx.ai.NeoRadianceMath.perceptualGamutFold(r, g, b)
    }
    
    /**
     * Apply cinema-style color grading with color wheels
     * @param shadowsTint Tint for shadow regions (RGB offset)
     * @param midtonesTint Tint for midtone regions (RGB offset)
     * @param highlightsTint Tint for highlight regions (RGB offset)
     */
    fun applyCinematicColorGrade(
        r: Float, g: Float, b: Float,
        shadowsTint: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
        midtonesTint: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
        highlightsTint: Triple<Float, Float, Float> = Triple(0f, 0f, 0f)
    ): Triple<Float, Float, Float> {
        return com.neoproc.neoproccamx.ai.NeoRadianceMath.cinematicColorGrade(
            r, g, b, shadowsTint, midtonesTint, highlightsTint
        )
    }
    
    /**
     * Recover blown-out highlights using perceptual luminance
     */
    fun applyHighlightRecovery(r: Float, g: Float, b: Float, strength: Float = 1.0f): Triple<Float, Float, Float> {
        return com.neoproc.neoproccamx.ai.NeoRadianceMath.spectralRecovery(r, g, b, strength)
    }
    
    /**
     * Lift shadows with noise awareness
     */
    fun applyShadowRecovery(r: Float, g: Float, b: Float, strength: Float = 0.5f): Triple<Float, Float, Float> {
        return com.neoproc.neoproccamx.ai.NeoRadianceMath.shadowBloom(r, g, b, strength)
    }
    
    /**
     * Convert sRGB to various professional color spaces
     */
    fun convertToColorSpace(r: Float, g: Float, b: Float, targetSpace: String): Triple<Float, Float, Float> {
        resolveColorSpaceProfile(targetSpace)?.let { profile ->
            return colorSpaceTransform(profile.name, r, g, b)
        }
        return when (targetSpace.uppercase()) {
            "OKLAB" -> com.neoproc.neoproccamx.ai.NeoRadianceMath.srgbToOklab(r, g, b)
            "XYZ" -> com.neoproc.neoproccamx.ai.NeoRadianceMath.srgbToXyz(r, g, b)
            else -> Triple(r, g, b)
        }
    }
    
    /**
     * Apply professional tone mapping operator
     */
    fun applyToneMapping(r: Float, g: Float, b: Float, operator: ToneMapOperator): Triple<Float, Float, Float> {
        val tonemapper: (Float) -> Float = when (operator) {
            ToneMapOperator.ACES -> com.neoproc.neoproccamx.ai.NeoRadianceMath::acesFilmicTonemap
            ToneMapOperator.FILMIC -> { v -> com.neoproc.neoproccamx.ai.NeoRadianceMath.filmicTonemap(v) }
            ToneMapOperator.REINHARD -> com.neoproc.neoproccamx.ai.NeoRadianceMath::reinhardTonemap
            ToneMapOperator.UNCHARTED2 -> { v -> com.neoproc.neoproccamx.ai.NeoRadianceMath.uncharted2Tonemap(v) }
            ToneMapOperator.HABLE -> com.neoproc.neoproccamx.ai.NeoRadianceMath::hableTonemap
            ToneMapOperator.LOTTES -> { v -> com.neoproc.neoproccamx.ai.NeoRadianceMath.lottesTonemap(v) }
            ToneMapOperator.GRAN_TURISMO -> { v -> com.neoproc.neoproccamx.ai.NeoRadianceMath.granTurismoTonemap(v) }
            ToneMapOperator.AGX -> { v -> com.neoproc.neoproccamx.ai.NeoRadianceMath.agxTonemap(v) }
            ToneMapOperator.PBR_NEUTRAL -> { v -> com.neoproc.neoproccamx.ai.NeoRadianceMath.neutralGamutFold(v) }
            ToneMapOperator.HILL_ACES -> com.neoproc.neoproccamx.ai.NeoRadianceMath::hillAcesTonemap
            ToneMapOperator.NONE -> { v -> v }
        }
        
        return Triple(
            tonemapper(r).coerceIn(0f, 1f),
            tonemapper(g).coerceIn(0f, 1f),
            tonemapper(b).coerceIn(0f, 1f)
        )
    }
    
    /**
     * Complete HDR to SDR processing pipeline
     */
    fun processHdrToSdr(
        r: Float, g: Float, b: Float,
        operator: ToneMapOperator = ToneMapOperator.ACES,
        exposure: Float = 1.0f,
        contrast: Float = 1.0f,
        saturation: Float = 1.0f
    ): Triple<Float, Float, Float> {
        val tonemapper: (Float) -> Float = when (operator) {
            ToneMapOperator.ACES -> com.neoproc.neoproccamx.ai.NeoRadianceMath::acesFilmicTonemap
            ToneMapOperator.FILMIC -> { v -> com.neoproc.neoproccamx.ai.NeoRadianceMath.filmicTonemap(v) }
            ToneMapOperator.REINHARD -> com.neoproc.neoproccamx.ai.NeoRadianceMath::reinhardTonemap
            ToneMapOperator.UNCHARTED2 -> { v -> com.neoproc.neoproccamx.ai.NeoRadianceMath.uncharted2Tonemap(v) }
            ToneMapOperator.HABLE -> com.neoproc.neoproccamx.ai.NeoRadianceMath::hableTonemap
            ToneMapOperator.LOTTES -> { v -> com.neoproc.neoproccamx.ai.NeoRadianceMath.lottesTonemap(v) }
            ToneMapOperator.GRAN_TURISMO -> { v -> com.neoproc.neoproccamx.ai.NeoRadianceMath.granTurismoTonemap(v) }
            ToneMapOperator.AGX -> { v -> com.neoproc.neoproccamx.ai.NeoRadianceMath.agxTonemap(v) }
            ToneMapOperator.PBR_NEUTRAL -> { v -> com.neoproc.neoproccamx.ai.NeoRadianceMath.neutralGamutFold(v) }
            ToneMapOperator.HILL_ACES -> com.neoproc.neoproccamx.ai.NeoRadianceMath::hillAcesTonemap
            ToneMapOperator.NONE -> { v -> v }
        }
        
        return com.neoproc.neoproccamx.ai.NeoRadianceMath.processHdrToSdr(
            r, g, b, tonemapper, exposure, contrast, saturation
        )
    }
    
    /**
     * Apply LOG curve encoding for video recording
     */
    fun encodeLogCurve(r: Float, g: Float, b: Float, logFormat: LogFormat): Triple<Float, Float, Float> {
        val encoder: (Float) -> Float = when (logFormat) {
            LogFormat.N_LOG_BASE -> { v -> com.neoproc.neoproccamx.ai.NLogMath.encode(v, com.neoproc.neoproccamx.ai.NLogVariant.BASE) }
            LogFormat.N_LOG_SHADOW -> { v -> com.neoproc.neoproccamx.ai.NLogMath.encode(v, com.neoproc.neoproccamx.ai.NLogVariant.SHADOW) }
            LogFormat.N_LOG_HIGHLIGHT -> { v -> com.neoproc.neoproccamx.ai.NLogMath.encode(v, com.neoproc.neoproccamx.ai.NLogVariant.HIGHLIGHT) }
            LogFormat.N_LOG_CINEMA -> { v -> com.neoproc.neoproccamx.ai.NLogMath.encode(v, com.neoproc.neoproccamx.ai.NLogVariant.CINEMA) }
            LogFormat.LINEAR -> { v -> v }
        }
        
        return Triple(encoder(r), encoder(g), encoder(b))
    }

    /**
     * Decode LOG curve to linear for processing
     */
    fun decodeLogCurve(r: Float, g: Float, b: Float, logFormat: LogFormat): Triple<Float, Float, Float> {
        val decoder: (Float) -> Float = when (logFormat) {
            LogFormat.N_LOG_BASE -> { v -> com.neoproc.neoproccamx.ai.NLogMath.decode(v, com.neoproc.neoproccamx.ai.NLogVariant.BASE) }
            LogFormat.N_LOG_SHADOW -> { v -> com.neoproc.neoproccamx.ai.NLogMath.decode(v, com.neoproc.neoproccamx.ai.NLogVariant.SHADOW) }
            LogFormat.N_LOG_HIGHLIGHT -> { v -> com.neoproc.neoproccamx.ai.NLogMath.decode(v, com.neoproc.neoproccamx.ai.NLogVariant.HIGHLIGHT) }
            LogFormat.N_LOG_CINEMA -> { v -> com.neoproc.neoproccamx.ai.NLogMath.decode(v, com.neoproc.neoproccamx.ai.NLogVariant.CINEMA) }
            LogFormat.LINEAR -> { v -> v }
        }

        return Triple(decoder(r), decoder(g), decoder(b))
    }
    
    /**
     * Apply HDR transfer function (PQ/HLG)
     */
    fun applyHdrTransfer(r: Float, g: Float, b: Float, transfer: HdrTransfer, encode: Boolean = true): Triple<Float, Float, Float> {
        val func: (Float) -> Float = when (transfer) {
            HdrTransfer.PQ -> if (encode) com.neoproc.neoproccamx.ai.NeoRadianceMath::pqInvEotf else com.neoproc.neoproccamx.ai.NeoRadianceMath::pqEotf
            HdrTransfer.HLG -> if (encode) com.neoproc.neoproccamx.ai.NeoRadianceMath::hlgOetf else com.neoproc.neoproccamx.ai.NeoRadianceMath::hlgInvOetf
            HdrTransfer.SDR -> { v -> v }
        }
        
        return Triple(func(r), func(g), func(b))
    }
}

// ── Data types ────────────────────────────────────────────────────────────────

/** Tone mapping operators available */
enum class ToneMapOperator {
    NONE, ACES, FILMIC, REINHARD, UNCHARTED2, HABLE, LOTTES, 
    GRAN_TURISMO, AGX, PBR_NEUTRAL, HILL_ACES
}

/** Cinema log curve formats for professional video recording. */
enum class LogFormat {
    LINEAR,
    N_LOG_BASE,
    N_LOG_SHADOW,
    N_LOG_HIGHLIGHT,
    N_LOG_CINEMA
}

/** HDR transfer functions */
enum class HdrTransfer {
    SDR, PQ, HLG
}

data class CubeLUT(
    val title: String,
    val size: Int,
    val data: Array<FloatArray>,
    val domainMin: FloatArray,
    val domainMax: FloatArray
) {
    fun get(r: Int, g: Int, b: Int): FloatArray {
        val idx = r + g*size + b*size*size
        return if (idx < data.size) data[idx] else floatArrayOf(0f,0f,0f)
    }
    override fun equals(other: Any?) = other is CubeLUT && title == other.title
    override fun hashCode() = title.hashCode()
}

enum class ColorChannel { R, G, B, ALL }

