package com.neoproc.neoproccamx.snce

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SNCE v2.0 HVC — Stage-shared colorimetric primitives.
 *
 * Pure, allocation-light Kotlin. Conventions deliberately mirror
 * [com.neoproc.neoproccamx.color.NeoGamutCat16Math] so the two engines agree:
 *  - 3x3 matrices are row-major [FloatArray] of length 9.
 *  - colour vectors are [FloatArray] of length 3.
 *
 * All XYZ here is CIE 1931 2-degree, scene-referred, with diffuse white Y ~= 1
 * unless a function name says otherwise (CAM16 internally works at Y_white=100).
 *
 * This object owns the transforms the directive's Part II / Stage 02 / Stage 04
 * call for: XYZ<->LMS (HPE and Stockman-Sharpe), XYZ<->IPT (Ebner-Fairchild),
 * XYZ<->Lab, CIEDE2000, and CCT/Duv. CAM16 itself lives in [SnceCam16].
 */
object SnceColorPrimitives {
    const val EPS = 1e-6f

    /** CIE D65 reference white, normalised to Y = 1 (directive Stage 03 reference). */
    val D65_XYZ = floatArrayOf(0.95047f, 1.00000f, 1.08883f)

    // ------------------------------------------------------------------
    // XYZ <-> LMS : Hunt-Pointer-Estevez (sharpened cones, CAM16 M_HPE form).
    // directive Part II 2.2.1 / Stage 06 STEP 2. PHYSICAL.
    // ------------------------------------------------------------------
    val XYZ_TO_LMS_HPE = floatArrayOf(
        0.38971f, 0.68898f, -0.07868f,
        -0.22981f, 1.18340f, 0.04641f,
        0.00000f, 0.00000f, 1.00000f
    )
    val LMS_HPE_TO_XYZ = invert3(XYZ_TO_LMS_HPE)

    // ------------------------------------------------------------------
    // XYZ <-> LMS : Stockman-Sharpe 2000 cone fundamentals (directive Stage 02.4).
    // PHYSICAL.
    // ------------------------------------------------------------------
    val XYZ_TO_LMS_SS = floatArrayOf(
        0.210576f, 0.855098f, -0.0396983f,
        -0.417076f, 1.177260f, 0.0786959f,
        0.0f, 0.0f, 0.5168350f
    )
    val LMS_SS_TO_XYZ = invert3(XYZ_TO_LMS_SS)

    // ------------------------------------------------------------------
    // IPT opponent space (Ebner & Fairchild 1998), directive Stage 04.1.
    // PHYSICAL. I = achromatic, P = protan (red-green), T = tritan (blue-yellow).
    // ------------------------------------------------------------------
    val XYZ_TO_LMS_IPT = floatArrayOf(
        0.4002f, 0.7076f, -0.0808f,
        -0.2263f, 1.1653f, 0.0457f,
        0.0000f, 0.0000f, 0.9182f
    )
    val LMS_IPT_TO_XYZ = invert3(XYZ_TO_LMS_IPT)
    val LMSP_TO_IPT = floatArrayOf(
        0.4000f, 0.4000f, 0.2000f,
        4.4550f, -4.8510f, 0.3960f,
        0.8056f, 0.3572f, -1.1628f
    )
    val IPT_TO_LMSP = invert3(LMSP_TO_IPT)
    private const val IPT_GAMMA = 0.43f

    // ==================================================================
    // Matrix / vector helpers (row-major FloatArray(9), vector FloatArray(3)).
    // ==================================================================

    fun mulV(m: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
    )

    fun mul3(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { index ->
        val row = index / 3
        val col = index % 3
        a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
    }

    fun determinant3(m: FloatArray): Float =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])

    /** Closed-form 3x3 inverse via adjugate / determinant. Returns identity if singular. */
    fun invert3(m: FloatArray): FloatArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val a00 = e * i - f * h
        val a01 = -(d * i - f * g)
        val a02 = d * h - e * g
        val det = a * a00 + b * a01 + c * a02
        if (abs(det) < 1e-12f) return identity3()
        val inv = 1f / det
        return floatArrayOf(
            a00 * inv, (c * h - b * i) * inv, (b * f - c * e) * inv,
            a01 * inv, (a * i - c * g) * inv, (c * d - a * f) * inv,
            a02 * inv, (b * g - a * h) * inv, (a * e - b * d) * inv
        )
    }

    fun identity3(): FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )

    fun signedPow(value: Float, exponent: Float): Float =
        if (value >= 0f) value.pow(exponent) else -((-value).pow(exponent))

    // ==================================================================
    // Chromaticity helpers.
    // ==================================================================

    /** CIE xy chromaticity -> XYZ with Y = 1. */
    fun xyToXyz(x: Float, y: Float): FloatArray {
        val safeY = if (abs(y) < EPS) EPS else y
        return floatArrayOf(x / safeY, 1f, (1f - x - safeY) / safeY)
    }

    /** CIE 1976 u'v'. */
    fun xyzToUvPrime(xyz: FloatArray): Pair<Float, Float> {
        val denom = (xyz[0] + 15f * xyz[1] + 3f * xyz[2]).coerceAtLeast(EPS)
        return (4f * xyz[0] / denom) to (9f * xyz[1] / denom)
    }

    /** CIE 1960 UCS uv (used for CCT / Duv). */
    fun xyzToUv1960(xyz: FloatArray): Pair<Float, Float> {
        val denom = (xyz[0] + 15f * xyz[1] + 3f * xyz[2]).coerceAtLeast(EPS)
        return (4f * xyz[0] / denom) to (6f * xyz[1] / denom)
    }

    /**
     * McCamy 1992 correlated colour temperature from XYZ (directive Stage 01 CCT).
     * Valid roughly 2000K..20000K which is the directive's accepted illuminant band.
     */
    fun correlatedColorTemperature(xyz: FloatArray): Float {
        val sum = xyz[0] + xyz[1] + xyz[2]
        if (sum < EPS) return 6500f
        val x = xyz[0] / sum
        val y = xyz[1] / sum
        val denom = (0.1858f - y)
        if (abs(denom) < EPS) return 6500f
        val n = (x - 0.3320f) / denom
        return (449f * n * n * n + 3525f * n * n + 6823.3f * n + 5520.33f)
            .coerceIn(1000f, 30000f)
    }

    /** CCT (Kelvin) -> CIE xy on the Planckian locus (Krystek piecewise; directive Stage 01). */
    fun kelvinToXy(kelvin: Float): Pair<Float, Float> {
        val t = kelvin.coerceIn(1667f, 25000f).toDouble()
        val x = if (t <= 4000.0) {
            -0.2661239e9 / (t * t * t) - 0.2343580e6 / (t * t) + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
        }
        val y = when {
            t <= 2222.0 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t <= 4000.0 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        return x.toFloat() to y.toFloat()
    }

    /**
     * Approximate (CCT, Duv). Duv is the signed UCS distance to the locus point at the
     * estimated CCT (positive = above the Planckian locus, greenish). Adequate for the
     * directive's metadata + sanity gate; not a full Ohno perpendicular solve.
     */
    fun cctAndDuv(xyz: FloatArray): Pair<Float, Float> {
        val cct = correlatedColorTemperature(xyz)
        val (u, v) = xyzToUv1960(xyz)
        val locusXy = kelvinToXy(cct)
        val (ul, vl) = xyzToUv1960(xyToXyz(locusXy.first, locusXy.second))
        val du = u - ul
        val dv = v - vl
        val mag = sqrt(du * du + dv * dv)
        return cct to (if (dv >= 0f) mag else -mag)
    }

    // ==================================================================
    // LMS / IPT transforms.
    // ==================================================================

    fun xyzToLmsHpe(xyz: FloatArray): FloatArray = mulV(XYZ_TO_LMS_HPE, xyz)
    fun xyzToLmsStockmanSharpe(xyz: FloatArray): FloatArray = mulV(XYZ_TO_LMS_SS, xyz)

    /** XYZ -> IPT opponent (sign-preserving cone compression at gamma 0.43). */
    fun xyzToIpt(xyz: FloatArray): FloatArray {
        val lms = mulV(XYZ_TO_LMS_IPT, xyz)
        val lmsP = floatArrayOf(
            signedPow(lms[0], IPT_GAMMA),
            signedPow(lms[1], IPT_GAMMA),
            signedPow(lms[2], IPT_GAMMA)
        )
        return mulV(LMSP_TO_IPT, lmsP)
    }

    /** IPT opponent -> XYZ (inverse of [xyzToIpt]). */
    fun iptToXyz(ipt: FloatArray): FloatArray {
        val lmsP = mulV(IPT_TO_LMSP, ipt)
        val lms = floatArrayOf(
            signedPow(lmsP[0], 1f / IPT_GAMMA),
            signedPow(lmsP[1], 1f / IPT_GAMMA),
            signedPow(lmsP[2], 1f / IPT_GAMMA)
        )
        return mulV(LMS_IPT_TO_XYZ, lms)
    }

    // ==================================================================
    // CIE L*a*b* (D65) and CIEDE2000.
    // ==================================================================

    fun xyzToLab(xyz: FloatArray, white: FloatArray = D65_XYZ): FloatArray {
        val x = labPivot(xyz[0] / white[0])
        val y = labPivot(xyz[1] / white[1])
        val z = labPivot(xyz[2] / white[2])
        return floatArrayOf(116f * y - 16f, 500f * (x - y), 200f * (y - z))
    }

    private fun labPivot(value: Float): Float =
        if (value > 216f / 24389f) {
            value.toDouble().pow(1.0 / 3.0).toFloat()
        } else {
            (24389f / 27f * value + 16f) / 116f
        }

    /**
     * CIEDE2000 colour difference. Same algorithm as
     * [com.neoproc.neoproccamx.color.ColorValidationHarness] (kept independent so the
     * SNCE package is self-contained and unit-testable without the color package).
     */
    fun deltaE2000(lab1: FloatArray, lab2: FloatArray): Float {
        val l1 = lab1[0].toDouble(); val a1 = lab1[1].toDouble(); val b1 = lab1[2].toDouble()
        val l2 = lab2[0].toDouble(); val a2 = lab2[1].toDouble(); val b2 = lab2[2].toDouble()
        val c1 = sqrt(a1 * a1 + b1 * b1)
        val c2 = sqrt(a2 * a2 + b2 * b2)
        val cBar = (c1 + c2) * 0.5
        val cBar7 = cBar.pow(7.0)
        val g = 0.5 * (1.0 - sqrt(cBar7 / (cBar7 + 25.0.pow(7.0))))
        val ap1 = (1.0 + g) * a1
        val ap2 = (1.0 + g) * a2
        val cp1 = sqrt(ap1 * ap1 + b1 * b1)
        val cp2 = sqrt(ap2 * ap2 + b2 * b2)
        val hp1 = hueRadians(b1, ap1)
        val hp2 = hueRadians(b2, ap2)
        val deltaLp = l2 - l1
        val deltaCp = cp2 - cp1
        val dhp = when {
            cp1 * cp2 == 0.0 -> 0.0
            abs(hp2 - hp1) <= Math.PI -> hp2 - hp1
            hp2 <= hp1 -> hp2 - hp1 + 2.0 * Math.PI
            else -> hp2 - hp1 - 2.0 * Math.PI
        }
        val deltaHp = 2.0 * sqrt(cp1 * cp2) * sin(dhp * 0.5)
        val lpBar = (l1 + l2) * 0.5
        val cpBar = (cp1 + cp2) * 0.5
        val hpBar = when {
            cp1 * cp2 == 0.0 -> hp1 + hp2
            abs(hp1 - hp2) <= Math.PI -> (hp1 + hp2) * 0.5
            hp1 + hp2 < 2.0 * Math.PI -> (hp1 + hp2 + 2.0 * Math.PI) * 0.5
            else -> (hp1 + hp2 - 2.0 * Math.PI) * 0.5
        }
        val t = 1.0 -
            0.17 * cos(hpBar - Math.toRadians(30.0)) +
            0.24 * cos(2.0 * hpBar) +
            0.32 * cos(3.0 * hpBar + Math.toRadians(6.0)) -
            0.20 * cos(4.0 * hpBar - Math.toRadians(63.0))
        val deltaTheta = Math.toRadians(30.0) *
            exp(-((Math.toDegrees(hpBar) - 275.0) / 25.0).pow(2.0))
        val rc = 2.0 * sqrt(cpBar.pow(7.0) / (cpBar.pow(7.0) + 25.0.pow(7.0)))
        val sl = 1.0 + (0.015 * (lpBar - 50.0).pow(2.0)) / sqrt(20.0 + (lpBar - 50.0).pow(2.0))
        val sc = 1.0 + 0.045 * cpBar
        val sh = 1.0 + 0.015 * cpBar * t
        val rt = -sin(2.0 * deltaTheta) * rc
        val lTerm = deltaLp / sl
        val cTerm = deltaCp / sc
        val hTerm = deltaHp / sh
        return sqrt(lTerm * lTerm + cTerm * cTerm + hTerm * hTerm + rt * cTerm * hTerm).toFloat()
    }

    private fun hueRadians(b: Double, a: Double): Double {
        val hue = atan2(b, a)
        return if (hue >= 0.0) hue else hue + 2.0 * Math.PI
    }

    /** Convenience: CIEDE2000 directly between two XYZ samples under [white]. */
    fun deltaE2000Xyz(xyz1: FloatArray, xyz2: FloatArray, white: FloatArray = D65_XYZ): Float =
        deltaE2000(xyzToLab(xyz1, white), xyzToLab(xyz2, white))

    // ==================================================================
    // Small scalar helpers shared by several stages.
    // ==================================================================

    /** Shortest signed hue difference (target - source) in degrees, wrapped to [-180, 180]. */
    fun hueDifferenceDegrees(sourceDeg: Float, targetDeg: Float): Float {
        var d = (targetDeg - sourceDeg) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /** Wrap an angle to [0, 360). */
    fun wrapHueDegrees(deg: Float): Float {
        var h = deg % 360f
        if (h < 0f) h += 360f
        return h
    }

    fun toRadians(deg: Float): Float = deg * (Math.PI.toFloat() / 180f)
    fun toDegrees(rad: Float): Float = rad * (180f / Math.PI.toFloat())

    /** Sign-aware natural log used by retinex / UCS colourfulness; guards the +eps. */
    fun safeLog(value: Float): Float = ln(value.coerceAtLeast(EPS).toDouble()).toFloat()
}
