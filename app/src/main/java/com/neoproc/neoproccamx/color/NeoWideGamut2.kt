package com.neoproc.neoproccamx.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Neo WG2 is the single source of truth for the project-owned D55 wide-gamut
 * working space. The matrices are derived from the published primaries and D55
 * white using primary-normalized RGB->XYZ construction, then analytically
 * inverted. Working values are scene-linear; transfer encoding happens only at
 * output profiles.
 */
object NeoWideGamut2 {
    const val RED_X = 0.7347f
    const val RED_Y = 0.2653f
    const val GREEN_X = 0.1152f
    const val GREEN_Y = 0.8260f
    const val BLUE_X = 0.0860f
    const val BLUE_Y = 0.0110f
    const val D55_X = 0.3324f
    const val D55_Y = 0.3474f
    const val D60_X = 0.32168f
    const val D60_Y = 0.33767f
    const val D65_X = 0.3127f
    const val D65_Y = 0.3290f

    val D55_XYZ = floatArrayOf(0.956822107081f, 1.000000000000f, 0.921704087507f)
    val D60_XYZ = xyToXyz(D60_X, D60_Y)
    val D65_XYZ = xyToXyz(D65_X, D65_Y)

    val NEO_TO_XYZ = floatArrayOf(
        0.774888888722f, 0.098962556523f, 0.082970661836f,
        0.279812198418f, 0.709575275068f, 0.010612526514f,
        0.000000000000f, 0.050512138225f, 0.871191949282f
    )

    val XYZ_TO_NEO = floatArrayOf(
        1.355511229305f, -0.180015704859f, -0.126903425212f,
        -0.534992948588f, 1.481565470514f, 0.032903846515f,
        0.031019154609f, -0.085901895556f, 1.145944882961f
    )

    val LUMA = floatArrayOf(NEO_TO_XYZ[3], NEO_TO_XYZ[4], NEO_TO_XYZ[5])

    enum class TargetGamut {
        REC709,
        P3_D65,
        REC2020
    }

    data class Oklab(val l: Float, val a: Float, val b: Float)

    private data class RgbSpace(
        val red: Pair<Float, Float>,
        val green: Pair<Float, Float>,
        val blue: Pair<Float, Float>,
        val white: Pair<Float, Float>
    )

    private val targetSpaces = mapOf(
        TargetGamut.REC709 to RgbSpace(
            red = 0.640f to 0.330f,
            green = 0.300f to 0.600f,
            blue = 0.150f to 0.060f,
            white = D65_X to D65_Y
        ),
        TargetGamut.P3_D65 to RgbSpace(
            red = 0.680f to 0.320f,
            green = 0.265f to 0.690f,
            blue = 0.150f to 0.060f,
            white = D65_X to D65_Y
        ),
        TargetGamut.REC2020 to RgbSpace(
            red = 0.708f to 0.292f,
            green = 0.170f to 0.797f,
            blue = 0.131f to 0.046f,
            white = D65_X to D65_Y
        )
    )

    fun xyToXyz(x: Float, y: Float): FloatArray {
        val safeY = y.takeIf { abs(it) > 0.0000001f } ?: 0.0000001f
        return floatArrayOf(x / safeY, 1f, (1f - x - safeY) / safeY)
    }

    fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size == 9 && b.size == 9) { "3x3 matrices must have 9 coefficients" }
        return FloatArray(9) { index ->
            val row = index / 3
            val col = index % 3
            a[row * 3] * b[col] +
                a[row * 3 + 1] * b[col + 3] +
                a[row * 3 + 2] * b[col + 6]
        }
    }

    fun multiply(matrix: FloatArray, vector: FloatArray): FloatArray {
        require(matrix.size == 9 && vector.size >= 3) { "Expected 3x3 matrix and 3-vector" }
        return floatArrayOf(
            matrix[0] * vector[0] + matrix[1] * vector[1] + matrix[2] * vector[2],
            matrix[3] * vector[0] + matrix[4] * vector[1] + matrix[5] * vector[2],
            matrix[6] * vector[0] + matrix[7] * vector[1] + matrix[8] * vector[2]
        )
    }

    fun invert3x3(matrix: FloatArray): FloatArray {
        require(matrix.size == 9) { "3x3 matrix must have 9 coefficients" }
        val m = matrix.map { it.toDouble() }
        val c00 = m[4] * m[8] - m[5] * m[7]
        val c01 = m[2] * m[7] - m[1] * m[8]
        val c02 = m[1] * m[5] - m[2] * m[4]
        val c10 = m[5] * m[6] - m[3] * m[8]
        val c11 = m[0] * m[8] - m[2] * m[6]
        val c12 = m[2] * m[3] - m[0] * m[5]
        val c20 = m[3] * m[7] - m[4] * m[6]
        val c21 = m[1] * m[6] - m[0] * m[7]
        val c22 = m[0] * m[4] - m[1] * m[3]
        val det = (m[0] * c00 + m[1] * c10 + m[2] * c20).takeUnless { abs(it) < 1e-14 }
            ?: 1e-14
        val inv = 1.0 / det
        return floatArrayOf(
            (c00 * inv).toFloat(), (c01 * inv).toFloat(), (c02 * inv).toFloat(),
            (c10 * inv).toFloat(), (c11 * inv).toFloat(), (c12 * inv).toFloat(),
            (c20 * inv).toFloat(), (c21 * inv).toFloat(), (c22 * inv).toFloat()
        )
    }

    fun rgbToXyzMatrix(
        red: Pair<Float, Float>,
        green: Pair<Float, Float>,
        blue: Pair<Float, Float>,
        white: Pair<Float, Float>
    ): FloatArray {
        val r = xyPrimary(red)
        val g = xyPrimary(green)
        val b = xyPrimary(blue)
        val base = floatArrayOf(
            r[0], g[0], b[0],
            r[1], g[1], b[1],
            r[2], g[2], b[2]
        )
        val scale = multiply(invert3x3(base), xyToXyz(white.first, white.second))
        return floatArrayOf(
            r[0] * scale[0], g[0] * scale[1], b[0] * scale[2],
            r[1] * scale[0], g[1] * scale[1], b[1] * scale[2],
            r[2] * scale[0], g[2] * scale[1], b[2] * scale[2]
        )
    }

    fun neoToTargetRgb(
        rgb: AcesRgb,
        target: TargetGamut,
        compress: Boolean = true
    ): AcesRgb {
        val targetSpace = targetSpaces.getValue(target)
        val neoXyzD55 = multiply(NEO_TO_XYZ, floatArrayOf(rgb.r, rgb.g, rgb.b))
        val adaptedXyz = multiply(
            NeoGamutCat16Math.calculateCat16Matrix(D55_X, D55_Y, targetSpace.white.first, targetSpace.white.second),
            neoXyzD55
        )
        val targetToXyz = rgbToXyzMatrix(targetSpace.red, targetSpace.green, targetSpace.blue, targetSpace.white)
        val targetRgb = multiply(invert3x3(targetToXyz), adaptedXyz).toAcesRgb()
        if (!compress || targetRgb.isInsideUnitCube()) return targetRgb
        return compressLinearRgbOklab(targetRgb)
    }

    fun targetRgbToNeo(rgb: AcesRgb, target: TargetGamut): AcesRgb {
        val targetSpace = targetSpaces.getValue(target)
        val targetToXyz = rgbToXyzMatrix(targetSpace.red, targetSpace.green, targetSpace.blue, targetSpace.white)
        val xyzTarget = multiply(targetToXyz, floatArrayOf(rgb.r, rgb.g, rgb.b))
        val xyzD55 = multiply(
            NeoGamutCat16Math.calculateCat16Matrix(targetSpace.white.first, targetSpace.white.second, D55_X, D55_Y),
            xyzTarget
        )
        return multiply(XYZ_TO_NEO, xyzD55).toAcesRgb()
    }

    fun compressLinearRgbOklab(rgb: AcesRgb): AcesRgb {
        if (rgb.isInsideUnitCube()) return rgb
        val lab = oklabFromLinearRgb(rgb)
        val hue = atan2(lab.b, lab.a)
        val sourceChroma = sqrt(lab.a * lab.a + lab.b * lab.b)
        if (sourceChroma < 0.000001f) {
            return AcesRgb(lab.l, lab.l, lab.l).clamped()
        }
        val safeLightness = lab.l.coerceIn(0f, 1f)
        var low = 0f
        var high = sourceChroma
        repeat(28) {
            val mid = (low + high) * 0.5f
            val candidate = linearRgbFromOklab(
                Oklab(
                    l = safeLightness,
                    a = cos(hue) * mid,
                    b = sin(hue) * mid
                )
            )
            if (candidate.isInsideUnitCube()) {
                low = mid
            } else {
                high = mid
            }
        }
        return linearRgbFromOklab(
            Oklab(
                l = safeLightness,
                a = cos(hue) * low,
                b = sin(hue) * low
            )
        ).clamped()
    }

    fun oklabFromLinearRgb(rgb: AcesRgb): Oklab {
        val l = 0.4122214708f * rgb.r + 0.5363325363f * rgb.g + 0.0514459929f * rgb.b
        val m = 0.2119034982f * rgb.r + 0.6806995451f * rgb.g + 0.1073969566f * rgb.b
        val s = 0.0883024619f * rgb.r + 0.2817188376f * rgb.g + 0.6299787005f * rgb.b
        val lp = signedCubeRoot(l)
        val mp = signedCubeRoot(m)
        val sp = signedCubeRoot(s)
        return Oklab(
            l = 0.2104542553f * lp + 0.7936177850f * mp - 0.0040720468f * sp,
            a = 1.9779984951f * lp - 2.4285922050f * mp + 0.4505937099f * sp,
            b = 0.0259040371f * lp + 0.7827717662f * mp - 0.8086757660f * sp
        )
    }

    fun linearRgbFromOklab(lab: Oklab): AcesRgb {
        val lp = lab.l + 0.3963377774f * lab.a + 0.2158037573f * lab.b
        val mp = lab.l - 0.1055613458f * lab.a - 0.0638541728f * lab.b
        val sp = lab.l - 0.0894841775f * lab.a - 1.2914855480f * lab.b
        val l = lp * lp * lp
        val m = mp * mp * mp
        val s = sp * sp * sp
        return AcesRgb(
            r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
            g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
            b = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
        )
    }

    private fun xyPrimary(primary: Pair<Float, Float>): FloatArray =
        xyToXyz(primary.first, primary.second)

    private fun signedCubeRoot(value: Float): Float =
        Math.cbrt(value.toDouble()).toFloat()

    private fun FloatArray.toAcesRgb(): AcesRgb = AcesRgb(this[0], this[1], this[2])

    private fun AcesRgb.isInsideUnitCube(): Boolean =
        r.isFinite() && g.isFinite() && b.isFinite() &&
            r >= 0f && r <= 1f &&
            g >= 0f && g <= 1f &&
            b >= 0f && b <= 1f
}
