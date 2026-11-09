package com.neoproc.neoproccamx.apex

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * TASK 2 — ICtCp COLOR SPACE CONVERTER (CPU PATH)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Complete ICtCp ↔ BT.2020 conversion per ITU-R BT.2100 specification.
 * Reference: BT.2100 ICtCp transform. All matrix constants exact.
 *
 * Conversion chain:
 *   Forward: Linear BT.2020 RGB → LMS → PQ(LMS) → ICtCp
 *   Inverse: ICtCp → PQ(LMS) → Linear LMS → Linear BT.2020 RGB
 *
 * For GPU path, see apex_ictcp.frag (GLSL ES 3.1).
 * CPU path used for sidecar metadata computation and unit testing.
 */
object ICtCpConverter {

    private const val EPS = 1e-8f
    private const val FP16_MAX = 65504f

    // ══════════════════════════════════════════════════════════════════════
    // PQ (ST2084) TRANSFER FUNCTION CONSTANTS
    // ══════════════════════════════════════════════════════════════════════
    private const val PQ_M1 = 2610.0 / (4096.0 * 4.0)    // 0.1593017578125
    private const val PQ_M2 = 2523.0 * 128.0 / 4096.0     // 78.84375
    private const val PQ_C1 = 3424.0 / 4096.0              // 0.8359375
    private const val PQ_C2 = 2413.0 / 128.0               // 18.8515625
    private const val PQ_C3 = 2392.0 / 128.0               // 18.6875

    // ══════════════════════════════════════════════════════════════════════
    // STEP 1: Linear BT.2020 RGB → LMS
    // | L |   | 1688  2146   467 |   | R |
    // | M | = | 1880  2048   444 | × | G | / 4096
    // | S |   |  756   502  3994 |   | B |
    // ══════════════════════════════════════════════════════════════════════
    private val RGB_TO_LMS = floatArrayOf(
        1688f / 4096f, 2146f / 4096f,  467f / 4096f,
        1880f / 4096f, 2048f / 4096f,  444f / 4096f,
         756f / 4096f,  502f / 4096f, 3994f / 4096f
    )

    // ══════════════════════════════════════════════════════════════════════
    // STEP 3: PQ LMS → ICtCp
    // I  =  0.5000 * L' + 0.5000 * M'
    // Ct = (6610 * L' - 13613 * M' + 7003 * S') / 4096
    // Cp = (17933 * L' - 17390 * M' - 543 * S') / 4096
    // ══════════════════════════════════════════════════════════════════════
    private val PQ_LMS_TO_ICTCP = floatArrayOf(
        0.5f,                0.5f,                0f,
        6610f / 4096f,      -13613f / 4096f,      7003f / 4096f,
        17933f / 4096f,     -17390f / 4096f,      -543f / 4096f
    )

    // ══════════════════════════════════════════════════════════════════════
    // INVERSE: ICtCp → PQ LMS
    // L' = I + 0.008609 * Ct + 0.111030 * Cp
    // M' = I - 0.008609 * Ct - 0.111030 * Cp
    // S' = I + 0.560031 * Ct - 0.320627 * Cp
    // ══════════════════════════════════════════════════════════════════════
    private const val INV_CT_L = 0.008609f
    private const val INV_CP_L = 0.111030f
    private const val INV_CT_S = 0.560031f
    private const val INV_CP_S = 0.320627f

    // ══════════════════════════════════════════════════════════════════════
    // INVERSE: LMS → Linear BT.2020 RGB
    // | R |   |  3.4366 -2.5064  0.0698 |   | L |
    // | G | = | -0.7913  1.9836 -0.1923 | × | M |
    // | B |   | -0.0259 -0.0989  1.1248 |   | S |
    // ══════════════════════════════════════════════════════════════════════
    private val LMS_TO_RGB = floatArrayOf(
         3.4366f, -2.5064f,  0.0698f,
        -0.7913f,  1.9836f, -0.1923f,
        -0.0259f, -0.0989f,  1.1248f
    )

    // ══════════════════════════════════════════════════════════════════════
    // PQ OETF (Linear nits → PQ encoded [0,1])
    //
    // Ym = (x / 10000)^m1
    // PQ = ((c1 + c2*Ym) / (1 + c3*Ym))^m2
    // ══════════════════════════════════════════════════════════════════════
    fun pqOetf(linearNits: Float): Float {
        val x = max(linearNits, 0f) / 10000f
        val ym = x.toDouble().pow(PQ_M1)
        val num = PQ_C1 + PQ_C2 * ym
        val den = 1.0 + PQ_C3 * ym
        return (num / den).pow(PQ_M2).toFloat().coerceIn(0f, 1f)
    }

    // ══════════════════════════════════════════════════════════════════════
    // PQ EOTF (PQ encoded [0,1] → Linear nits)
    //
    // Xp = x^(1/m2)
    // Lin = 10000 * max(Xp - c1, 0) / (c2 - c3*Xp)
    // ══════════════════════════════════════════════════════════════════════
    fun pqEotf(pqValue: Float): Float {
        val xp = pqValue.toDouble().coerceIn(0.0, 1.0).pow(1.0 / PQ_M2)
        val num = max(xp - PQ_C1, 0.0)
        val den = PQ_C2 - PQ_C3 * xp
        return (10000.0 * num / max(den, 1e-12)).toFloat().coerceIn(0f, FP16_MAX)
    }

    /**
     * Forward conversion: Linear BT.2020 RGB → ICtCp.
     *
     * @param r Linear R [0, peak_nits]
     * @param g Linear G [0, peak_nits]
     * @param b Linear B [0, peak_nits]
     * @return FloatArray[3] = {I, Ct, Cp}
     */
    fun linearBT2020ToICtCp(r: Float, g: Float, b: Float): FloatArray {
        // Step 1: RGB → LMS (absolute nits)
        val l = RGB_TO_LMS[0] * r + RGB_TO_LMS[1] * g + RGB_TO_LMS[2] * b
        val m = RGB_TO_LMS[3] * r + RGB_TO_LMS[4] * g + RGB_TO_LMS[5] * b
        val s = RGB_TO_LMS[6] * r + RGB_TO_LMS[7] * g + RGB_TO_LMS[8] * b

        // Step 2: LMS → PQ-encoded LMS
        val lp = pqOetf(l)
        val mp = pqOetf(m)
        val sp = pqOetf(s)

        // Step 3: PQ LMS → ICtCp
        val i  = PQ_LMS_TO_ICTCP[0] * lp + PQ_LMS_TO_ICTCP[1] * mp + PQ_LMS_TO_ICTCP[2] * sp
        val ct = PQ_LMS_TO_ICTCP[3] * lp + PQ_LMS_TO_ICTCP[4] * mp + PQ_LMS_TO_ICTCP[5] * sp
        val cp = PQ_LMS_TO_ICTCP[6] * lp + PQ_LMS_TO_ICTCP[7] * mp + PQ_LMS_TO_ICTCP[8] * sp

        return floatArrayOf(i, ct, cp)
    }

    /**
     * Inverse conversion: ICtCp → Linear BT.2020 RGB.
     *
     * @param i  Intensity channel
     * @param ct Tritan (blue-yellow) channel
     * @param cp Protan (red-green) channel
     * @return FloatArray[3] = {R_linear, G_linear, B_linear} in nits
     */
    fun ictcpToLinearBT2020(i: Float, ct: Float, cp: Float): FloatArray {
        // Step 1: ICtCp → PQ LMS
        val lp = i + INV_CT_L * ct + INV_CP_L * cp
        val mp = i - INV_CT_L * ct - INV_CP_L * cp
        val sp = i + INV_CT_S * ct - INV_CP_S * cp

        // Step 2: PQ → Linear LMS (nits)
        val l = pqEotf(lp)
        val m = pqEotf(mp)
        val s = pqEotf(sp)

        // Step 3: LMS → Linear BT.2020 RGB
        val r = (LMS_TO_RGB[0] * l + LMS_TO_RGB[1] * m + LMS_TO_RGB[2] * s).coerceIn(0f, FP16_MAX)
        val g = (LMS_TO_RGB[3] * l + LMS_TO_RGB[4] * m + LMS_TO_RGB[5] * s).coerceIn(0f, FP16_MAX)
        val b = (LMS_TO_RGB[6] * l + LMS_TO_RGB[7] * m + LMS_TO_RGB[8] * s).coerceIn(0f, FP16_MAX)

        return floatArrayOf(r, g, b)
    }

    // ══════════════════════════════════════════════════════════════════════
    // APEX SIGNAL SHAPING FUNCTIONS (CPU reference implementations)
    // ══════════════════════════════════════════════════════════════════════

    /** Hermite smoothstep interpolation. Zero derivative at both edges. */
    fun smoothstepNeo(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Exponential shoulder: asymptotic approach to 1.0. Never hard clips. */
    fun shoulder(u: Float, a: Float): Float {
        return 1f - exp(-a * u.coerceIn(0f, 1f))
    }

    /**
     * S_apex_sharp(x) — Mode 1 signal shaping.
     * x^0.85 + smoothstep(0,0.28,x)*0.032*(1-x) - smoothstep(0.72,1,x)*0.014*x
     */
    fun sApexSharp(x: Float): Float {
        val xc = x.coerceIn(0f, 1f)
        val base = xc.toDouble().pow(0.85).toFloat()
        val toe = smoothstepNeo(0f, 0.28f, xc) * 0.032f * (1f - xc)
        val hs = smoothstepNeo(0.72f, 1f, xc) * 0.014f * xc
        return (base + toe - hs).coerceIn(0f, 1f)
    }

    /**
     * S_apex_zero(x) — Mode 2 signal shaping.
     * x^0.96 + smoothstep(0,0.12,x)*0.008*(1-x)
     */
    fun sApexZero(x: Float): Float {
        val xc = x.coerceIn(0f, 1f)
        val base = xc.toDouble().pow(0.96).toFloat()
        val toe = smoothstepNeo(0f, 0.12f, xc) * 0.008f * (1f - xc)
        return (base + toe).coerceIn(0f, 1f)
    }

    /**
     * S_apex_cinema(x) — Mode 3 signal shaping.
     * x^0.87 + smoothstep(0,0.30,x)*0.028*(1-x) - smoothstep(0.68,1,x)*0.022*x
     */
    fun sApexCinema(x: Float): Float {
        val xc = x.coerceIn(0f, 1f)
        val base = xc.toDouble().pow(0.87).toFloat()
        val toe = smoothstepNeo(0f, 0.30f, xc) * 0.028f * (1f - xc)
        val hs = smoothstepNeo(0.68f, 1f, xc) * 0.022f * xc
        return (base + toe - hs).coerceIn(0f, 1f)
    }

    /** Select signal shaping function by mode. */
    fun sApex(x: Float, mode: ApexMode): Float = when (mode) {
        ApexMode.SHARP -> sApexSharp(x)
        ApexMode.ZERO -> sApexZero(x)
        ApexMode.CINEMA -> sApexCinema(x)
    }
}
