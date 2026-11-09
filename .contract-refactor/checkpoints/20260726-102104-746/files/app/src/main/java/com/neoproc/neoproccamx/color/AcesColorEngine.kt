package com.neoproc.neoproccamx.color

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class AcesRgb(
    val r: Float,
    val g: Float,
    val b: Float
) {
    fun clamped(minValue: Float = 0f, maxValue: Float = 1f): AcesRgb =
        AcesRgb(
            r.coerceIn(minValue, maxValue),
            g.coerceIn(minValue, maxValue),
            b.coerceIn(minValue, maxValue)
    )
}

data class Sdr10CodeValues(
    val r: Int,
    val g: Int,
    val b: Int
)

enum class AcesInputColorSpace {
    REC709,
    REC2020,
    NEO_WIDE_GAMUT,
    NEO_ACES_AP1,
    NEO_ACES_AP0,
    N_LOG,
    NDR10_PLUS,
    NIVUKX_VISION
}

enum class AcesOutputColorSpace {
    REC709_SDR,
    REC2020_SDR,
    DISPLAY_P3_SDR,
    REC2020_PQ,
    REC2020_HLG,
    N_LOG_SDR
}

data class AcesColorPlan(
    val input: AcesInputColorSpace,
    val output: AcesOutputColorSpace,
    val selectedColorSpace: String,
    val selectedColorScience: String,
    val workingSpace: String,
    val certificationStatus: String,
    val truthLabel: String,
    val signature: String
)

internal data class NeoOklabReferenceMatrices(
    val acesCgToD65ReferenceRgb: FloatArray,
    val d65ReferenceRgbToAcesCg: FloatArray
)

object AcesColorEngine {
    const val CERTIFICATION_STATUS = "NON_CERTIFIED_INTERNAL_ACESCG"
    private const val WORKING_SPACE = "ACESCG_LINEAR"
    private const val HIGH_END_WORKING_SPACE = "ACESCG_LINEAR_HIGH_END"
    val NEO_WIDE_GAMUT_TO_XYZ = NeoGamutCat16Math.NEO_TO_XYZ.copyOf()
    val XYZ_TO_NEO_WIDE_GAMUT = NeoGamutCat16Math.XYZ_TO_NEO.copyOf()

    data class XyChromaticity(val x: Float, val y: Float)
    data class NivukxOklab(val l: Float, val a: Float, val b: Float)
    data class Ndr10ToneMapParams(val alpha: Float, val beta: Float, val gamma: Float, val contrastRatio: Float)

    private data class Chromaticity(val x: Double, val y: Double)
    private data class RgbSpace(
        val name: String,
        val red: Chromaticity,
        val green: Chromaticity,
        val blue: Chromaticity,
        val white: Chromaticity
    )

    private data class Vec3(val x: Double, val y: Double, val z: Double) {
        fun scaled(sx: Double, sy: Double, sz: Double): Vec3 = Vec3(x * sx, y * sy, z * sz)
    }

    private data class Matrix3(
        val m00: Double, val m01: Double, val m02: Double,
        val m10: Double, val m11: Double, val m12: Double,
        val m20: Double, val m21: Double, val m22: Double
    ) {
        fun multiply(v: Vec3): Vec3 =
            Vec3(
                m00 * v.x + m01 * v.y + m02 * v.z,
                m10 * v.x + m11 * v.y + m12 * v.z,
                m20 * v.x + m21 * v.y + m22 * v.z
            )

        fun multiply(other: Matrix3): Matrix3 =
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
            val det = (m00 * c00 + m01 * c10 + m02 * c20).takeUnless { abs(it) < 1e-12 }
                ?: 1e-12
            val inv = 1.0 / det
            return Matrix3(
                c00 * inv, c01 * inv, c02 * inv,
                c10 * inv, c11 * inv, c12 * inv,
                c20 * inv, c21 * inv, c22 * inv
            )
        }
    }

    private val HPE_XYZ_TO_LMS = Matrix3(
        0.8189330, 0.3670669, -0.1574301,
        -0.0351149, 1.0253456, 0.0097103,
        0.0815124, -0.0435106, 1.1251144
    )
    private val HPE_LMS_TO_XYZ = HPE_XYZ_TO_LMS.inverse()

    private val D65 = Chromaticity(0.3127, 0.3290)
    private val D55 = Chromaticity(0.3324, 0.3474)
    private val ACES_D60 = Chromaticity(0.32168, 0.33767)

    private val REC709 = RgbSpace(
        name = "Rec.709",
        red = Chromaticity(0.640, 0.330),
        green = Chromaticity(0.300, 0.600),
        blue = Chromaticity(0.150, 0.060),
        white = D65
    )
    private val REC2020 = RgbSpace(
        name = "Rec.2020",
        red = Chromaticity(0.708, 0.292),
        green = Chromaticity(0.170, 0.797),
        blue = Chromaticity(0.131, 0.046),
        white = D65
    )
    private val NEO_WIDE_GAMUT = RgbSpace(
        name = "Neo Wide Gamut",
        red = Chromaticity(0.7347, 0.2653),
        green = Chromaticity(0.1152, 0.8260),
        blue = Chromaticity(0.0860, 0.0110),
        white = D55
    )
    private val ACESCG = RgbSpace(
        name = "ACEScg",
        red = Chromaticity(0.713, 0.293),
        green = Chromaticity(0.165, 0.830),
        blue = Chromaticity(0.128, 0.044),
        white = ACES_D60
    )
    private val ACES_AP0 = RgbSpace(
        name = "ACES AP0",
        red = Chromaticity(0.7347, 0.2653),
        green = Chromaticity(0.0000, 1.0000),
        blue = Chromaticity(0.0001, -0.0770),
        white = ACES_D60
    )

    private val rgbToXyz = mapOf(
        AcesInputColorSpace.REC709 to rgbToXyzMatrix(REC709),
        AcesInputColorSpace.REC2020 to rgbToXyzMatrix(REC2020),
        AcesInputColorSpace.NEO_WIDE_GAMUT to NEO_WIDE_GAMUT_TO_XYZ.toMatrix3(),
        AcesInputColorSpace.NEO_ACES_AP1 to rgbToXyzMatrix(ACESCG),
        AcesInputColorSpace.NEO_ACES_AP0 to rgbToXyzMatrix(ACES_AP0),
        AcesInputColorSpace.N_LOG to NEO_WIDE_GAMUT_TO_XYZ.toMatrix3(),
        AcesInputColorSpace.NDR10_PLUS to NEO_WIDE_GAMUT_TO_XYZ.toMatrix3(),
        AcesInputColorSpace.NIVUKX_VISION to NEO_WIDE_GAMUT_TO_XYZ.toMatrix3()
    )
    private val inputWhite = mapOf(
        AcesInputColorSpace.REC709 to REC709.white,
        AcesInputColorSpace.REC2020 to REC2020.white,
        AcesInputColorSpace.NEO_WIDE_GAMUT to NEO_WIDE_GAMUT.white,
        AcesInputColorSpace.NEO_ACES_AP1 to ACESCG.white,
        AcesInputColorSpace.NEO_ACES_AP0 to ACES_AP0.white,
        AcesInputColorSpace.N_LOG to NEO_WIDE_GAMUT.white,
        AcesInputColorSpace.NDR10_PLUS to NEO_WIDE_GAMUT.white,
        AcesInputColorSpace.NIVUKX_VISION to NEO_WIDE_GAMUT.white
    )
    private val acesCgToXyz = rgbToXyzMatrix(ACESCG)
    private val xyzToAcesCg = acesCgToXyz.inverse()
    private val acesAp0ToXyz = rgbToXyzMatrix(ACES_AP0)
    private val xyzToAcesAp0 = acesAp0ToXyz.inverse()

    fun toAcesCg(rgb: AcesRgb, input: AcesInputColorSpace): AcesRgb {
        val sourceMatrix = rgbToXyz.getValue(input)
        val sourceWhite = inputWhite.getValue(input)
        val xyz = sourceMatrix.multiply(Vec3(rgb.r.toDouble(), rgb.g.toDouble(), rgb.b.toDouble()))
        val adapted = adaptWhite(xyz, sourceWhite, ACES_D60)
        return xyzToAcesCg.multiply(adapted).toAcesRgb()
    }

    fun fromAcesCg(acesCg: AcesRgb, output: AcesOutputColorSpace): AcesRgb {
        val targetSpace = when (output) {
            AcesOutputColorSpace.REC709_SDR -> REC709
            AcesOutputColorSpace.REC2020_SDR,
            AcesOutputColorSpace.REC2020_PQ,
            AcesOutputColorSpace.REC2020_HLG,
            AcesOutputColorSpace.N_LOG_SDR -> REC2020
            AcesOutputColorSpace.DISPLAY_P3_SDR -> REC2020
        }
        val xyzD60 = acesCgToXyz.multiply(Vec3(acesCg.r.toDouble(), acesCg.g.toDouble(), acesCg.b.toDouble()))
        val targetXyz = adaptWhite(xyzD60, ACES_D60, targetSpace.white)
        return rgbToXyzMatrix(targetSpace).inverse().multiply(targetXyz).toAcesRgb()
    }

    fun neoWideGamutToAcesAp1(rgb: AcesRgb): AcesRgb =
        toAcesCg(rgb, AcesInputColorSpace.NEO_WIDE_GAMUT)

    fun acesAp1ToNeoWideGamut(ap1: AcesRgb): AcesRgb {
        val xyzD60 = acesCgToXyz.multiply(Vec3(ap1.r.toDouble(), ap1.g.toDouble(), ap1.b.toDouble()))
        val xyzD55 = adaptWhite(xyzD60, ACES_D60, D55)
        return XYZ_TO_NEO_WIDE_GAMUT.toMatrix3().multiply(xyzD55).toAcesRgb()
    }

    fun neoWideGamutToAcesAp0(rgb: AcesRgb): AcesRgb {
        val xyzD55 = NEO_WIDE_GAMUT_TO_XYZ.toMatrix3()
            .multiply(Vec3(rgb.r.toDouble(), rgb.g.toDouble(), rgb.b.toDouble()))
        val xyzD60 = adaptWhite(xyzD55, D55, ACES_D60)
        return xyzToAcesAp0.multiply(xyzD60).toAcesRgb()
    }

    fun acesAp0ToNeoWideGamut(ap0: AcesRgb): AcesRgb {
        val xyzD60 = acesAp0ToXyz.multiply(Vec3(ap0.r.toDouble(), ap0.g.toDouble(), ap0.b.toDouble()))
        val xyzD55 = adaptWhite(xyzD60, ACES_D60, D55)
        return XYZ_TO_NEO_WIDE_GAMUT.toMatrix3().multiply(xyzD55).toAcesRgb()
    }

    fun compressNeoWideGamutToTarget(
        rgb: AcesRgb,
        target: NeoWideGamut2.TargetGamut,
        compress: Boolean = true
    ): AcesRgb =
        NeoWideGamut2.neoToTargetRgb(rgb, target, compress)

    internal fun neoOklabReferenceMatrices(): NeoOklabReferenceMatrices {
        val acesCgToNeoD55 = XYZ_TO_NEO_WIDE_GAMUT.toMatrix3()
            .multiply(adaptationMatrix(ACES_D60, D55))
            .multiply(acesCgToXyz)
        val neoD55ToD65ReferenceRgb = rgbToXyzMatrix(REC709).inverse()
            .multiply(adaptationMatrix(D55, D65))
            .multiply(NEO_WIDE_GAMUT_TO_XYZ.toMatrix3())
        val acesCgToD65ReferenceRgb = neoD55ToD65ReferenceRgb.multiply(acesCgToNeoD55)
        return NeoOklabReferenceMatrices(
            acesCgToD65ReferenceRgb = acesCgToD65ReferenceRgb.toFloatArray(),
            d65ReferenceRgbToAcesCg = acesCgToD65ReferenceRgb.inverse().toFloatArray()
        )
    }

    fun applyFittedRrtOdt(acesCg: AcesRgb, output: AcesOutputColorSpace): AcesRgb {
        val displayLinear = fromAcesCg(acesCg, output)
        return AcesRgb(
            fittedRrtOdtChannel(displayLinear.r),
            fittedRrtOdtChannel(displayLinear.g),
            fittedRrtOdtChannel(displayLinear.b)
        ).clamped()
    }

    fun applySdr10ShoulderToe(
        linearSceneLuma: Float,
        toeStart: Float = 0.12f,
        shoulderStart: Float = 0.78f,
        shadowSlope: Float = 1.25f
    ): Float {
        val x = max(linearSceneLuma, 0f)
        val toe = toeStart.coerceIn(0.001f, 0.40f)
        val shoulder = shoulderStart.coerceIn(toe + 0.05f, 2.0f)
        if (x < toe) return (shadowSlope * x).coerceIn(0f, 1f)

        val xDouble = x.toDouble()
        val toeDouble = toe.toDouble()
        val shoulderDouble = shoulder.toDouble()
        val toeDistance = ((shoulderDouble - toeDouble) * 2.05).coerceAtLeast(0.001)
        val x0 = toeDouble - toeDistance
        val a = shadowSlope.toDouble() * toeDistance * ln(10.0)
        val b = shadowSlope.toDouble() * toeDouble - a * log10(toeDouble - x0)
        val shoulderValue = (a * log10(shoulderDouble - x0) + b).coerceIn(0.0, 0.98)
        if (x < shoulder) {
            return (a * log10(xDouble - x0) + b).toFloat().coerceIn(0f, 1f)
        }

        val shoulderDerivative = a / ((shoulderDouble - x0) * ln(10.0))
        val c = (1.0 - shoulderValue).coerceAtLeast(1e-5)
        val k = (shoulderDerivative / c).coerceAtLeast(1e-5)
        return (1.0 - c * exp(-k * (xDouble - shoulderDouble))).toFloat().coerceIn(0f, 0.999999f)
    }

    fun encodeRec2020Sdr10(acesCg: AcesRgb): Sdr10CodeValues {
        val rec2020Linear = fromAcesCg(acesCg, AcesOutputColorSpace.REC2020_SDR)
        return Sdr10CodeValues(
            r = quantizeSdr10(applySdr10ShoulderToe(rec2020Linear.r)),
            g = quantizeSdr10(applySdr10ShoulderToe(rec2020Linear.g)),
            b = quantizeSdr10(applySdr10ShoulderToe(rec2020Linear.b))
        )
    }

    fun xyChromaticity(xyz: FloatArray): XyChromaticity {
        require(xyz.size >= 3) { "XYZ array must contain at least three values" }
        val sum = xyz[0] + xyz[1] + xyz[2]
        if (!sum.isFinite() || abs(sum) < 0.000001f) {
            return XyChromaticity(D55.x.toFloat(), D55.y.toFloat())
        }
        return XyChromaticity(x = xyz[0] / sum, y = xyz[1] / sum)
    }

    fun isInsideNeoWideGamut(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        val red = XyChromaticity(NEO_WIDE_GAMUT.red.x.toFloat(), NEO_WIDE_GAMUT.red.y.toFloat())
        val green = XyChromaticity(NEO_WIDE_GAMUT.green.x.toFloat(), NEO_WIDE_GAMUT.green.y.toFloat())
        val blue = XyChromaticity(NEO_WIDE_GAMUT.blue.x.toFloat(), NEO_WIDE_GAMUT.blue.y.toFloat())
        val p = XyChromaticity(x, y)
        val c1 = cross(red, green, p)
        val c2 = cross(green, blue, p)
        val c3 = cross(blue, red, p)
        val hasNegative = c1 < -0.000001f || c2 < -0.000001f || c3 < -0.000001f
        val hasPositive = c1 > 0.000001f || c2 > 0.000001f || c3 > 0.000001f
        return !(hasNegative && hasPositive)
    }

    fun compressNeoWideGamutXyzPreservingY(xyz: FloatArray, strength: Float = 5.0f): FloatArray {
        require(xyz.size >= 3) { "XYZ array must contain at least three values" }
        val yLuma = xyz[1].takeIf { it.isFinite() } ?: 0f
        val xy = xyChromaticity(xyz)
        val mappedXy = compressNeoWideGamutChromaticity(xy, strength)
        return xyzFromXyY(mappedXy, yLuma)
    }

    fun compressNeoWideGamutRgb(rgb: AcesRgb, strength: Float = 5.0f): AcesRgb {
        val xyz = NEO_WIDE_GAMUT_TO_XYZ.toMatrix3()
            .multiply(Vec3(rgb.r.toDouble(), rgb.g.toDouble(), rgb.b.toDouble()))
        val compressed = compressNeoWideGamutXyzPreservingY(
            floatArrayOf(xyz.x.toFloat(), xyz.y.toFloat(), xyz.z.toFloat()),
            strength
        )
        val remapped = XYZ_TO_NEO_WIDE_GAMUT.toMatrix3()
            .multiply(Vec3(compressed[0].toDouble(), compressed[1].toDouble(), compressed[2].toDouble()))
        return remapped.toAcesRgb()
    }

    fun nivukxHpeOklabFromXyz(xyz: FloatArray): NivukxOklab {
        require(xyz.size >= 3) { "XYZ array must contain at least three values" }
        val lms = HPE_XYZ_TO_LMS.multiply(
            Vec3(
                xyz[0].coerceAtLeast(0f).toDouble(),
                xyz[1].coerceAtLeast(0f).toDouble(),
                xyz[2].coerceAtLeast(0f).toDouble()
            )
        )
        val lRoot = signedCubeRoot(lms.x.toFloat())
        val mRoot = signedCubeRoot(lms.y.toFloat())
        val sRoot = signedCubeRoot(lms.z.toFloat())
        return NivukxOklab(
            l = 0.2104542553f * lRoot + 0.7936177850f * mRoot - 0.0040720403f * sRoot,
            a = 1.9779984951f * lRoot - 2.4285922050f * mRoot + 0.4505937099f * sRoot,
            b = 0.0259040371f * lRoot + 0.7827717658f * mRoot - 0.8086757660f * sRoot
        )
    }

    fun nivukxHpeOklabToXyz(lab: NivukxOklab): FloatArray {
        val lPrime = lab.l + 0.3963377774f * lab.a + 0.2158037573f * lab.b
        val mPrime = lab.l - 0.1055613458f * lab.a - 0.0638541728f * lab.b
        val sPrime = lab.l - 0.0894841775f * lab.a - 1.2914855480f * lab.b
        val lms = Vec3(
            (lPrime * lPrime * lPrime).toDouble(),
            (mPrime * mPrime * mPrime).toDouble(),
            (sPrime * sPrime * sPrime).toDouble()
        )
        val xyz = HPE_LMS_TO_XYZ.multiply(lms)
        return floatArrayOf(xyz.x.toFloat(), xyz.y.toFloat(), xyz.z.toFloat())
    }

    fun applyNivukxApexSignalShaping(
        lab: NivukxOklab,
        sceneLux: Float,
        kelvin: Float
    ): NivukxOklab {
        val chroma = sqrt(lab.a * lab.a + lab.b * lab.b)
        if (chroma < 0.000001f) return lab.copy(l = lab.l.coerceAtLeast(0f))
        val hue = atan2(lab.b, lab.a)
        val highlight = smoothstep(0.56f, 1.06f, lab.l)
        val deepShadow = 1f - smoothstep(0.04f, 0.24f, lab.l)
        val lowLux = 1f - smoothstep(12f, 900f, sceneLux.coerceAtLeast(0f))
        val kelvinBias = ((6_500f - kelvin.coerceIn(1_800f, 12_000f)) / 6_500f).coerceIn(-0.45f, 0.45f)
        val chromaScale = (1f - highlight * 0.42f - deepShadow * lowLux * 0.10f + kelvinBias * 0.035f)
            .coerceIn(0.16f, 1.04f)
        val shapedChroma = chroma * chromaScale
        val lightnessLift = deepShadow * lowLux * 0.018f
        return NivukxOklab(
            l = (lab.l + lightnessLift).coerceAtLeast(0f),
            a = cos(hue) * shapedChroma,
            b = sin(hue) * shapedChroma
        )
    }

    fun applyNivukxVisionRgb(rgb: AcesRgb, sceneLux: Float = 400f, kelvin: Float = 5_500f): AcesRgb {
        val xyz = NEO_WIDE_GAMUT_TO_XYZ.toMatrix3()
            .multiply(Vec3(rgb.r.toDouble(), rgb.g.toDouble(), rgb.b.toDouble()))
        val shaped = applyNivukxApexSignalShaping(
            lab = nivukxHpeOklabFromXyz(floatArrayOf(xyz.x.toFloat(), xyz.y.toFloat(), xyz.z.toFloat())),
            sceneLux = sceneLux,
            kelvin = kelvin
        )
        val shapedXyz = nivukxHpeOklabToXyz(shaped)
        val remapped = XYZ_TO_NEO_WIDE_GAMUT.toMatrix3()
            .multiply(Vec3(shapedXyz[0].toDouble(), shapedXyz[1].toDouble(), shapedXyz[2].toDouble()))
        return compressNeoWideGamutRgb(remapped.toAcesRgb()).clamped(0f, 1.08f)
    }

    fun resolveNdr10ToneMapParams(sceneMin: Float, sceneMax: Float): Ndr10ToneMapParams {
        val safeMin = sceneMin.takeIf { it.isFinite() }?.coerceAtLeast(0.0001f) ?: 0.0001f
        val safeMax = sceneMax.takeIf { it.isFinite() }?.coerceAtLeast(safeMin + 0.0001f) ?: 1f
        val contrastRatio = (safeMax / safeMin).coerceIn(1f, 100_000f)
        val highDynamicRange = smoothstep(80f, 8_000f, contrastRatio)
        val lowLight = 1f - smoothstep(0.24f, 1.20f, safeMax)
        return Ndr10ToneMapParams(
            alpha = (0.16f + highDynamicRange * 0.68f).coerceIn(0.16f, 0.84f),
            beta = (0.20f + lowLight * 0.58f).coerceIn(0.20f, 0.78f),
            gamma = (0.12f + highDynamicRange * 0.34f).coerceIn(0.12f, 0.46f),
            contrastRatio = contrastRatio
        )
    }

    fun applyNdr10DynamicToneMap(
        linearSceneLuma: Float,
        sceneMin: Float,
        sceneMax: Float
    ): Float {
        val params = resolveNdr10ToneMapParams(sceneMin, sceneMax)
        val x = (linearSceneLuma / sceneMax.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val denominator = x + params.beta * (1f - params.gamma * x).coerceAtLeast(0.05f)
        val mapped = x * (1f + params.alpha * x) / denominator.coerceAtLeast(0.000001f)
        return mapped.coerceIn(0f, 1f)
    }

    fun applySubtractiveFilmDensity(
        rgb: AcesRgb,
        density: AcesRgb = AcesRgb(0.34f, 0.30f, 0.26f)
    ): AcesRgb {
        val maxChannel = maxOf(rgb.r, rgb.g, rgb.b)
        val minChannel = minOf(rgb.r, rgb.g, rgb.b)
        val saturation = (maxChannel - minChannel).coerceIn(0f, 1f)
        fun channel(value: Float, kappa: Float): Float =
            (value * exp(-kappa * (1f - value.coerceIn(0f, 1f)) * saturation)).coerceAtLeast(0f)
        return AcesRgb(
            channel(rgb.r, density.r),
            channel(rgb.g, density.g),
            channel(rgb.b, density.b)
        )
    }

    fun highlightSaturationRolloff(rgb: AcesRgb, peakStart: Float = 0.78f): AcesRgb {
        val peak = maxOf(rgb.r, rgb.g, rgb.b)
        val rolloff = smoothstep(peakStart, 1.0f, peak)
        if (rolloff <= 0f) return rgb
        val luma = 0.2627f * rgb.r + 0.6780f * rgb.g + 0.0593f * rgb.b
        return AcesRgb(
            rgb.r + (luma - rgb.r) * rolloff,
            rgb.g + (luma - rgb.g) * rolloff,
            rgb.b + (luma - rgb.b) * rolloff
        )
    }

    fun applyNeoAcesHighEndLook(acesCg: AcesRgb): AcesRgb {
        val density = applySubtractiveFilmDensity(
            rgb = acesCg,
            density = AcesRgb(0.22f, 0.20f, 0.18f)
        )
        return highlightSaturationRolloff(density, peakStart = 0.72f).clamped(0f, 1.08f)
    }

    fun resolvePlan(
        selectedColorSpace: String?,
        selectedColorScience: String?,
        hdrTransport: Boolean,
        logTransport: Boolean
    ): AcesColorPlan {
        val input = resolveInputColorSpace(selectedColorSpace, selectedColorScience, logTransport)
        val output = when {
            logTransport -> AcesOutputColorSpace.N_LOG_SDR
            hdrTransport -> AcesOutputColorSpace.REC2020_PQ
            input == AcesInputColorSpace.REC2020 ||
                input == AcesInputColorSpace.NEO_WIDE_GAMUT ||
                input == AcesInputColorSpace.NEO_ACES_AP1 ||
                input == AcesInputColorSpace.NEO_ACES_AP0 ||
                input == AcesInputColorSpace.NDR10_PLUS ||
                input == AcesInputColorSpace.NIVUKX_VISION -> AcesOutputColorSpace.REC2020_SDR
            else -> AcesOutputColorSpace.REC709_SDR
        }
        val colorSpaceLabel = when (input) {
            AcesInputColorSpace.REC709 -> "Rec.709"
            AcesInputColorSpace.REC2020 -> "Rec.2020"
            AcesInputColorSpace.NEO_ACES_AP1 -> "Neo ACES AP1"
            AcesInputColorSpace.NEO_ACES_AP0 -> "Neo ACES AP0"
            AcesInputColorSpace.NEO_WIDE_GAMUT,
            AcesInputColorSpace.N_LOG,
            AcesInputColorSpace.NDR10_PLUS,
            AcesInputColorSpace.NIVUKX_VISION -> "Neo Wide Gamut"
        }
        val scienceLabel = normalizeColorScience(selectedColorScience, logTransport)
        val workingSpace = if (
            input == AcesInputColorSpace.NEO_ACES_AP1 ||
            input == AcesInputColorSpace.NEO_ACES_AP0 ||
            scienceLabel == "Neo ACES High End"
        ) HIGH_END_WORKING_SPACE else WORKING_SPACE
        val truthLabel = "$CERTIFICATION_STATUS $workingSpace $colorSpaceLabel $scienceLabel"
        val signature = listOf(
            "cert=$CERTIFICATION_STATUS",
            "working=$workingSpace",
            "input=${input.name}",
            "output=${output.name}",
            "space=$colorSpaceLabel",
            "science=$scienceLabel"
        ).joinToString(separator = "|")
        return AcesColorPlan(
            input = input,
            output = output,
            selectedColorSpace = colorSpaceLabel,
            selectedColorScience = scienceLabel,
            workingSpace = workingSpace,
            certificationStatus = CERTIFICATION_STATUS,
            truthLabel = truthLabel,
            signature = signature
        )
    }

    private fun resolveInputColorSpace(
        selectedColorSpace: String?,
        selectedColorScience: String?,
        logTransport: Boolean
    ): AcesInputColorSpace {
        val spaceKey = selectedColorSpace.orEmpty().uppercase(Locale.US)
        val scienceKey = selectedColorScience.orEmpty().uppercase(Locale.US)
        val key = "$spaceKey $scienceKey"
        return when {
            logTransport || key.contains("N-LOG") || key.contains("NLOG") -> AcesInputColorSpace.N_LOG
            spaceKey.contains("NEO ACES AP0") || spaceKey.contains("ACES AP0") -> AcesInputColorSpace.NEO_ACES_AP0
            spaceKey.contains("NEO ACES") || spaceKey.contains("ACESCG") || spaceKey.contains("ACES AP1") || spaceKey.contains("ACES") ||
                (spaceKey.isBlank() && (scienceKey.contains("NEO ACES") || scienceKey.contains("ACESCG") || scienceKey.contains("ACES"))) ->
                AcesInputColorSpace.NEO_ACES_AP1
            key.contains("NEO WIDE") || key.contains("NWG") || key.contains("WIDE GAMUT") -> AcesInputColorSpace.NEO_WIDE_GAMUT
            spaceKey.contains("2020") || spaceKey.contains("2100") -> AcesInputColorSpace.REC2020
            spaceKey.contains("709") -> AcesInputColorSpace.REC709
            scienceKey.contains("NIVUKX") -> AcesInputColorSpace.NIVUKX_VISION
            scienceKey.contains("NDR10") -> AcesInputColorSpace.NDR10_PLUS
            else -> AcesInputColorSpace.REC709
        }
    }

    private fun normalizeColorScience(selectedColorScience: String?, logTransport: Boolean): String {
        val key = selectedColorScience.orEmpty().trim()
        val upper = key.uppercase(Locale.US)
        return when {
            logTransport || upper.contains("N-LOG") || upper.contains("NLOG") -> "N-Log"
            upper.contains("NEO ACES") || upper.contains("ACESCG") || upper.contains("ACES") -> "Neo ACES High End"
            upper.contains("NIVUKX") -> "NIVUKX Vision"
            upper.contains("NDR10") -> "NDR10+"
            upper.contains("NEO WIDE") || upper.contains("NWG") || upper.contains("WIDE GAMUT") -> "Neo Wide Gamut"
            upper.contains("REC.2020") || upper.contains("REC2020") -> "Rec.2020"
            upper.contains("REC.709") || upper.contains("REC709") -> "Rec.709"
            key.isNotBlank() -> "Neo Wide Gamut"
            else -> "Neo Wide Gamut"
        }
    }

    private fun compressNeoWideGamutChromaticity(xy: XyChromaticity, strength: Float): XyChromaticity {
        if (isInsideNeoWideGamut(xy.x, xy.y)) return xy
        val center = XyChromaticity(D55.x.toFloat(), D55.y.toFloat())
        val dirX = xy.x - center.x
        val dirY = xy.y - center.y
        if (abs(dirX) + abs(dirY) < 0.000001f) return center
        val boundaryT = rayToNeoWideGamutBoundary(center, dirX, dirY)
        if (!boundaryT.isFinite() || boundaryT <= 0f) return center
        val excess = (1f - boundaryT).coerceAtLeast(0f)
        val compressedT = boundaryT *
            (1f - 0.025f * (1f - exp(-excess * strength.coerceIn(0.25f, 12f))))
        return XyChromaticity(
            x = center.x + dirX * compressedT,
            y = center.y + dirY * compressedT
        )
    }

    private fun rayToNeoWideGamutBoundary(center: XyChromaticity, dirX: Float, dirY: Float): Float {
        val red = XyChromaticity(NEO_WIDE_GAMUT.red.x.toFloat(), NEO_WIDE_GAMUT.red.y.toFloat())
        val green = XyChromaticity(NEO_WIDE_GAMUT.green.x.toFloat(), NEO_WIDE_GAMUT.green.y.toFloat())
        val blue = XyChromaticity(NEO_WIDE_GAMUT.blue.x.toFloat(), NEO_WIDE_GAMUT.blue.y.toFloat())
        val edges = arrayOf(red to green, green to blue, blue to red)
        var best = Float.POSITIVE_INFINITY
        edges.forEach { (a, b) ->
            val edgeX = b.x - a.x
            val edgeY = b.y - a.y
            val denom = cross2(dirX, dirY, edgeX, edgeY)
            if (abs(denom) > 0.0000001f) {
                val qx = a.x - center.x
                val qy = a.y - center.y
                val t = cross2(qx, qy, edgeX, edgeY) / denom
                val u = cross2(qx, qy, dirX, dirY) / denom
                if (t > 0f && u >= -0.0001f && u <= 1.0001f) {
                    best = minOf(best, t)
                }
            }
        }
        return best
    }

    private fun xyzFromXyY(xy: XyChromaticity, luminanceY: Float): FloatArray {
        val safeY = xy.y.takeIf { it.isFinite() && abs(it) > 0.000001f } ?: D55.y.toFloat()
        return floatArrayOf(
            xy.x * luminanceY / safeY,
            luminanceY,
            (1f - xy.x - xy.y) * luminanceY / safeY
        )
    }

    private fun cross(a: XyChromaticity, b: XyChromaticity, p: XyChromaticity): Float =
        (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

    private fun cross2(ax: Float, ay: Float, bx: Float, by: Float): Float =
        ax * by - ay * bx

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0).coerceAtLeast(0.000001f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun signedCubeRoot(value: Float): Float =
        Math.cbrt(value.toDouble()).toFloat()

    private fun FloatArray.toMatrix3(): Matrix3 =
        Matrix3(
            this[0].toDouble(), this[1].toDouble(), this[2].toDouble(),
            this[3].toDouble(), this[4].toDouble(), this[5].toDouble(),
            this[6].toDouble(), this[7].toDouble(), this[8].toDouble()
        )

    private fun Matrix3.toFloatArray(): FloatArray =
        floatArrayOf(
            m00.toFloat(), m01.toFloat(), m02.toFloat(),
            m10.toFloat(), m11.toFloat(), m12.toFloat(),
            m20.toFloat(), m21.toFloat(), m22.toFloat()
        )

    private fun fittedRrtOdtChannel(value: Float): Float {
        val x = max(value, 0f)
        val numerator = x * (x + 0.0245786f) - 0.000090537f
        val denominator = x * (0.983729f * x + 0.4329510f) + 0.238081f
        return (numerator / denominator.coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
    }

    private fun quantizeSdr10(value: Float): Int =
        (value.coerceIn(0f, 1f) * 1023f).roundToInt().coerceIn(0, 1023)

    private fun invert3x3(matrix: FloatArray): FloatArray {
        require(matrix.size == 9) { "3x3 matrix must have 9 coefficients" }
        val m00 = matrix[0].toDouble()
        val m01 = matrix[1].toDouble()
        val m02 = matrix[2].toDouble()
        val m10 = matrix[3].toDouble()
        val m11 = matrix[4].toDouble()
        val m12 = matrix[5].toDouble()
        val m20 = matrix[6].toDouble()
        val m21 = matrix[7].toDouble()
        val m22 = matrix[8].toDouble()
        val c00 = m11 * m22 - m12 * m21
        val c01 = m02 * m21 - m01 * m22
        val c02 = m01 * m12 - m02 * m11
        val c10 = m12 * m20 - m10 * m22
        val c11 = m00 * m22 - m02 * m20
        val c12 = m02 * m10 - m00 * m12
        val c20 = m10 * m21 - m11 * m20
        val c21 = m01 * m20 - m00 * m21
        val c22 = m00 * m11 - m01 * m10
        val det = (m00 * c00 + m01 * c10 + m02 * c20).takeUnless { abs(it) < 1e-12 }
            ?: 1e-12
        val invDet = 1.0 / det
        return floatArrayOf(
            (c00 * invDet).toFloat(), (c01 * invDet).toFloat(), (c02 * invDet).toFloat(),
            (c10 * invDet).toFloat(), (c11 * invDet).toFloat(), (c12 * invDet).toFloat(),
            (c20 * invDet).toFloat(), (c21 * invDet).toFloat(), (c22 * invDet).toFloat()
        )
    }

    private fun rgbToXyzMatrix(space: RgbSpace): Matrix3 {
        val red = primaryToXyz(space.red)
        val green = primaryToXyz(space.green)
        val blue = primaryToXyz(space.blue)
        val base = Matrix3(
            red.x, green.x, blue.x,
            red.y, green.y, blue.y,
            red.z, green.z, blue.z
        )
        val white = chromaticityToXyz(space.white)
        val scale = base.inverse().multiply(white)
        return Matrix3(
            red.x * scale.x, green.x * scale.y, blue.x * scale.z,
            red.y * scale.x, green.y * scale.y, blue.y * scale.z,
            red.z * scale.x, green.z * scale.y, blue.z * scale.z
        )
    }

    private fun primaryToXyz(primary: Chromaticity): Vec3 =
        Vec3(primary.x / primary.y, 1.0, (1.0 - primary.x - primary.y) / primary.y)

    private fun chromaticityToXyz(chromaticity: Chromaticity): Vec3 =
        Vec3(chromaticity.x / chromaticity.y, 1.0, (1.0 - chromaticity.x - chromaticity.y) / chromaticity.y)

    private fun adaptWhite(xyz: Vec3, sourceWhite: Chromaticity, targetWhite: Chromaticity): Vec3 {
        if (abs(sourceWhite.x - targetWhite.x) < 1e-7 && abs(sourceWhite.y - targetWhite.y) < 1e-7) {
            return xyz
        }
        return adaptationMatrix(sourceWhite, targetWhite).multiply(xyz)
    }

    private fun adaptationMatrix(sourceWhite: Chromaticity, targetWhite: Chromaticity): Matrix3 =
        NeoGamutCat16Math.calculateCat16Matrix(
            sourceX = sourceWhite.x.toFloat(),
            sourceY = sourceWhite.y.toFloat(),
            targetX = targetWhite.x.toFloat(),
            targetY = targetWhite.y.toFloat()
        ).toMatrix3()

    private fun Vec3.toAcesRgb(): AcesRgb =
        AcesRgb(x.toFloat(), y.toFloat(), z.toFloat())
}
