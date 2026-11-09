package com.neoproc.neoproccamx.color

import kotlin.math.abs
import kotlin.math.exp

internal object NeoGamutCat16Math {
    const val D55_X = NeoWideGamut2.D55_X
    const val D55_Y = NeoWideGamut2.D55_Y
    const val D60_X = NeoWideGamut2.D60_X
    const val D60_Y = NeoWideGamut2.D60_Y
    const val D65_X = NeoWideGamut2.D65_X
    const val D65_Y = NeoWideGamut2.D65_Y

    val NEO_TO_XYZ = NeoWideGamut2.NEO_TO_XYZ.copyOf()

    val XYZ_TO_NEO = NeoWideGamut2.XYZ_TO_NEO.copyOf()

    private val CAT16 = ApexColorConfig.CAT16_M16
    private val CAT16_INVERSE = ApexColorConfig.CAT16_M16_INVERSE
    private val BRADFORD = floatArrayOf(
        0.8951000f, 0.2664000f, -0.1614000f,
        -0.7502000f, 1.7135000f, 0.0367000f,
        0.0389000f, -0.0685000f, 1.0296000f
    )
    private val BRADFORD_INVERSE = floatArrayOf(
        0.9869929f, -0.1470543f, 0.1599627f,
        0.4323053f, 0.5183603f, 0.0492912f,
        -0.0085287f, 0.0400428f, 0.9684867f
    )

    fun calculateCat16ToD55(sourceX: Float, sourceY: Float): FloatArray =
        calculateCat16Matrix(sourceX, sourceY, D55_X, D55_Y)

    fun calculateAdaptiveCat16ToD55(kelvin: Float, lux: Float, duv: Float = 0f): FloatArray {
        if (!kelvin.isFinite() || !lux.isFinite()) return identity3()
        val sourceWhite = kelvinDuvToCieXy(kelvin, duv)
        val degree = ciecam16DegreeOfAdaptation(
            lux = lux,
            surroundF = ApexColorConfig.SURROUND_AVERAGE,
            metaConfidence = 1f
        )
        return calculateCat16MatrixCiecam16(
            sourceX = sourceWhite.first,
            sourceY = sourceWhite.second,
            targetX = D55_X,
            targetY = D55_Y,
            degreeOfAdaptation = degree
        )
    }

    fun calculateAdaptiveCat16ToD65(
        kelvin: Float,
        lux: Float,
        surroundF: Float = ApexColorConfig.SURROUND_AVERAGE,
        metaConfidence: Float = 1f,
        duv: Float = 0f
    ): FloatArray {
        if (!kelvin.isFinite() || !lux.isFinite()) return identity3()
        val sourceWhite = kelvinDuvToCieXy(kelvin, duv)
        return calculateCat16MatrixCiecam16(
            sourceX = sourceWhite.first,
            sourceY = sourceWhite.second,
            targetX = ApexColorConfig.WhitePoint.D65.x,
            targetY = ApexColorConfig.WhitePoint.D65.y,
            lux = lux,
            surroundF = surroundF,
            metaConfidence = metaConfidence
        )
    }

    fun ciecam16DegreeOfAdaptation(
        lux: Float,
        surroundF: Float = ApexColorConfig.SURROUND_AVERAGE,
        metaConfidence: Float = 1f
    ): Float {
        if (!lux.isFinite() || !surroundF.isFinite() || !metaConfidence.isFinite()) return 0f
        val la = lux.coerceAtLeast(0f) / (5f * Math.PI.toFloat())
        val f = surroundF.coerceIn(ApexColorConfig.SURROUND_DARK, ApexColorConfig.SURROUND_AVERAGE)
        val d = f * (1f - (1f / 3.6f) * exp((-la - 42f) / 92f))
        return (d * metaConfidence.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    fun calculateCat16Matrix(
        sourceX: Float,
        sourceY: Float,
        targetX: Float,
        targetY: Float
    ): FloatArray =
        calculateConeAdaptationMatrix(CAT16, CAT16_INVERSE, sourceX, sourceY, targetX, targetY)

    fun calculateCat16MatrixCiecam16(
        sourceX: Float,
        sourceY: Float,
        targetX: Float,
        targetY: Float,
        lux: Float,
        surroundF: Float = ApexColorConfig.SURROUND_AVERAGE,
        metaConfidence: Float = 1f
    ): FloatArray =
        calculateCat16MatrixCiecam16(
            sourceX = sourceX,
            sourceY = sourceY,
            targetX = targetX,
            targetY = targetY,
            degreeOfAdaptation = ciecam16DegreeOfAdaptation(lux, surroundF, metaConfidence)
        )

    fun calculateCat16MatrixCiecam16(
        sourceX: Float,
        sourceY: Float,
        targetX: Float,
        targetY: Float,
        degreeOfAdaptation: Float
    ): FloatArray =
        calculateConeAdaptationMatrix(
            coneMatrix = CAT16,
            coneInverse = CAT16_INVERSE,
            sourceX = sourceX,
            sourceY = sourceY,
            targetX = targetX,
            targetY = targetY,
            degreeOfAdaptation = degreeOfAdaptation.coerceIn(0f, 1f)
        )

    fun calculateBradfordMatrix(
        sourceX: Float,
        sourceY: Float,
        targetX: Float,
        targetY: Float
    ): FloatArray =
        calculateConeAdaptationMatrix(BRADFORD, BRADFORD_INVERSE, sourceX, sourceY, targetX, targetY)

    private fun calculateConeAdaptationMatrix(
        coneMatrix: FloatArray,
        coneInverse: FloatArray,
        sourceX: Float,
        sourceY: Float,
        targetX: Float,
        targetY: Float,
        degreeOfAdaptation: Float = 1f
    ): FloatArray {
        if (
            !sourceX.isFinite() || !sourceY.isFinite() ||
            !targetX.isFinite() || !targetY.isFinite() ||
            sourceY <= 0f || targetY <= 0f
        ) {
            return identity3()
        }
        if (degreeOfAdaptation <= 0f ||
            abs(sourceX - targetX) < 0.0000001f && abs(sourceY - targetY) < 0.0000001f
        ) {
            return identity3()
        }

        val sourceResponse = multiplyVector(coneMatrix, xyToXyz(sourceX, sourceY))
        val targetResponse = multiplyVector(coneMatrix, xyToXyz(targetX, targetY))
        if (sourceResponse.any { !it.isFinite() || abs(it) < ApexColorConfig.EPSILON }) {
            return identity3()
        }
        val scale = floatArrayOf(
            degreeOfAdaptation * (targetResponse[0] / sourceResponse[0]) + (1f - degreeOfAdaptation),
            degreeOfAdaptation * (targetResponse[1] / sourceResponse[1]) + (1f - degreeOfAdaptation),
            degreeOfAdaptation * (targetResponse[2] / sourceResponse[2]) + (1f - degreeOfAdaptation)
        )
        val scaledCat16 = floatArrayOf(
            coneMatrix[0] * scale[0], coneMatrix[1] * scale[0], coneMatrix[2] * scale[0],
            coneMatrix[3] * scale[1], coneMatrix[4] * scale[1], coneMatrix[5] * scale[1],
            coneMatrix[6] * scale[2], coneMatrix[7] * scale[2], coneMatrix[8] * scale[2]
        )
        val adaptation = multiply3x3(coneInverse, scaledCat16)
        return if (
            adaptation.all { it.isFinite() } &&
            abs(determinant3x3(adaptation)) >= ApexColorConfig.MATRIX_DETERMINANT_EPSILON
        ) {
            adaptation
        } else {
            identity3()
        }
    }

    fun kelvinToCieXy(kelvin: Float): Pair<Float, Float> {
        val temperature = kelvin.coerceIn(1_667f, 25_000f).toDouble()
        val x = if (temperature <= 4_000.0) {
            -0.2661239e9 / (temperature * temperature * temperature) -
                0.2343580e6 / (temperature * temperature) +
                0.8776956e3 / temperature +
                0.179910
        } else {
            -3.0258469e9 / (temperature * temperature * temperature) +
                2.1070379e6 / (temperature * temperature) +
                0.2226347e3 / temperature +
                0.240390
        }
        val y = when {
            temperature <= 2_222.0 ->
                -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            temperature <= 4_000.0 ->
                -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else ->
                3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        return x.toFloat() to y.toFloat()
    }

    /**
     * s2-f10 / CP44 — green-magenta (Duv) tint axis on the source white point.
     *
     * Returns the CIE xy of the white at [kelvin] offset PERPENDICULAR to the Planckian locus
     * by [duv] in CIE 1960 UCS (positive = above the locus / greenish, matching
     * `SnceColorPrimitives.cctAndDuv`'s sign). A CCT-only adaptation assumes the light sits on
     * the locus (Duv = 0) and therefore leaves a residual green/magenta cast under real
     * fluorescent/LED illuminants that sit off-locus; feeding the measured Duv here lets the
     * CAT16 adaptation neutralise that tint while keeping the CCT axis untouched.
     *
     * NO-DOWNGRADE: `duv == 0f` (or non-finite) returns EXACTLY [kelvinToCieXy], so every
     * existing adaptation path is bit-for-bit identical until a real Duv is supplied.
     */
    fun kelvinDuvToCieXy(kelvin: Float, duv: Float): Pair<Float, Float> {
        val base = kelvinToCieXy(kelvin)
        if (duv == 0f || !duv.isFinite()) return base
        val (u0, v0) = cieXyToUv1960(base.first, base.second)
        // Locus tangent in 1960 uv via a central finite difference around the CCT.
        val dT = (kelvin * 0.01f).coerceIn(10f, 200f)
        val (u1, v1) = kelvinToUv1960(kelvin + dT)
        val (u2, v2) = kelvinToUv1960(kelvin - dT)
        val tu = u1 - u2
        val tv = v1 - v2
        val tlen = kotlin.math.sqrt(tu * tu + tv * tv)
        if (tlen < 1e-9f) return base
        // Unit normal to the locus; orient so +duv increases v (greenish) to match cctAndDuv.
        var nu = -tv / tlen
        var nv = tu / tlen
        if (nv < 0f) { nu = -nu; nv = -nv }
        val u = u0 + duv * nu
        val v = v0 + duv * nv
        return uv1960ToCieXy(u, v)
    }

    private fun cieXyToUv1960(x: Float, y: Float): Pair<Float, Float> {
        val denom = (-2f * x + 12f * y + 3f).let { if (abs(it) < 1e-9f) 1e-9f else it }
        return (4f * x / denom) to (6f * y / denom)
    }

    private fun kelvinToUv1960(kelvin: Float): Pair<Float, Float> {
        val xy = kelvinToCieXy(kelvin)
        return cieXyToUv1960(xy.first, xy.second)
    }

    private fun uv1960ToCieXy(u: Float, v: Float): Pair<Float, Float> {
        val denom = (2f * u - 8f * v + 4f).let { if (abs(it) < 1e-9f) 1e-9f else it }
        return (3f * u / denom) to (2f * v / denom)
    }

    fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size == 9 && b.size == 9) { "CAT16 matrices must be 3x3" }
        return FloatArray(9) { index ->
            val row = index / 3
            val column = index % 3
            a[row * 3] * b[column] +
                a[row * 3 + 1] * b[3 + column] +
                a[row * 3 + 2] * b[6 + column]
        }
    }

    private fun xyToXyz(x: Float, y: Float): FloatArray =
        floatArrayOf(x / y, 1f, (1f - x - y) / y)

    fun multiplyVector(matrix: FloatArray, vector: FloatArray): FloatArray =
        floatArrayOf(
            matrix[0] * vector[0] + matrix[1] * vector[1] + matrix[2] * vector[2],
            matrix[3] * vector[0] + matrix[4] * vector[1] + matrix[5] * vector[2],
            matrix[6] * vector[0] + matrix[7] * vector[1] + matrix[8] * vector[2]
        )

    private fun determinant3x3(matrix: FloatArray): Float =
        matrix[0] * (matrix[4] * matrix[8] - matrix[5] * matrix[7]) -
            matrix[1] * (matrix[3] * matrix[8] - matrix[5] * matrix[6]) +
            matrix[2] * (matrix[3] * matrix[7] - matrix[4] * matrix[6])

    /**
     * Inverse of a row-major 3×3 matrix via the adjugate / determinant. Computed in Double for
     * precision, returned as a row-major Float 3×3. Throws on a (near-)singular matrix — callers
     * inverting a valid colour transform (det far from 0) never hit it.
     */
    fun invert3x3(matrix: FloatArray): FloatArray {
        require(matrix.size == 9) { "3x3 matrix required" }
        val a = DoubleArray(9) { matrix[it].toDouble() }
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[2] * a[7] - a[1] * a[8]
        val c02 = a[1] * a[5] - a[2] * a[4]
        val c10 = a[5] * a[6] - a[3] * a[8]
        val c11 = a[0] * a[8] - a[2] * a[6]
        val c12 = a[2] * a[3] - a[0] * a[5]
        val c20 = a[3] * a[7] - a[4] * a[6]
        val c21 = a[1] * a[6] - a[0] * a[7]
        val c22 = a[0] * a[4] - a[1] * a[3]
        val det = a[0] * c00 + a[1] * c10 + a[2] * c20
        require(kotlin.math.abs(det) > 1e-12) { "matrix is singular; cannot invert" }
        val inv = 1.0 / det
        return floatArrayOf(
            (c00 * inv).toFloat(), (c01 * inv).toFloat(), (c02 * inv).toFloat(),
            (c10 * inv).toFloat(), (c11 * inv).toFloat(), (c12 * inv).toFloat(),
            (c20 * inv).toFloat(), (c21 * inv).toFloat(), (c22 * inv).toFloat()
        )
    }

    private fun identity3(): FloatArray =
        floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
}
