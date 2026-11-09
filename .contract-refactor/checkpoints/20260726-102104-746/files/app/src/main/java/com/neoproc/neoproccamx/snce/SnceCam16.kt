package com.neoproc.neoproccamx.snce

import com.neoproc.neoproccamx.color.ApexColorConfig
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SNCE v2.0 HVC — Stage 06 (CAME) core: the CAM16 colour appearance model.
 *
 * This is the directive's "mathematical heart". It implements the *canonical*
 * CAM16 (Li, Luo, Cui, et al. 2017 — the CIE-endorsed successor to CIECAM02),
 * NOT the HPE-mixed pseudocode in directive Stage 06 STEP 2. The directive text
 * conflates CAM16 naming with CIECAM02 structure (it adapts with CAT16 then runs
 * the non-linearity in HPE space); that hybrid does not round-trip and is not
 * "CAM16". Canonical CAM16 unifies adaptation and the post-adaptation
 * non-linearity in a single sharpened matrix M16 (== [ApexColorConfig.CAT16_M16],
 * already used by the existing CAT16 stage-03 code), giving a self-consistent,
 * invertible model. The seven appearance phenomena the directive itemises are
 * layered on top in [SnceAppearancePhenomena].
 *
 * Input XYZ is scene-referred with diffuse white Y ~= 1; internally CAM16 works at
 * Y_white = 100, so [SnceViewingConditions] scales by 100. Forward returns
 * [SnceCam16Appearance]; inverse rebuilds XYZ at Y ~= 1.
 */
object SnceCam16 {
    private val M16 = ApexColorConfig.CAT16_M16
    private val M16_INV = ApexColorConfig.CAT16_M16_INVERSE

    /** Standard CAM16 surround parameters (F, c, Nc). */
    enum class Surround(val f: Float, val c: Float, val nc: Float) {
        AVERAGE(1.0f, 0.69f, 1.0f),
        DIM(0.9f, 0.59f, 0.95f),
        DARK(0.8f, 0.525f, 0.8f)
    }

    /**
     * Per-frame viewing conditions. Precomputes everything that does not depend on
     * the sample so the per-pixel forward/inverse is cheap.
     *
     * @param whiteXyz scene white at Y ~= 1 (will be scaled to 100 internally).
     * @param adaptingLuminance L_A in cd/m^2 (directive's scene_luminance_LA).
     * @param backgroundLuminance Y_b relative to Y_white=100 (directive default ~20).
     * @param degreeOfAdaptationOverride if finite, forces D instead of the L_A formula
     *        (used for hard white-balance locks; directive Stage 03 alpha_awb=0 case).
     */
    class SnceViewingConditions(
        whiteXyz: FloatArray,
        val adaptingLuminance: Float,
        backgroundLuminance: Float = 20f,
        val surround: Surround = Surround.AVERAGE,
        degreeOfAdaptationOverride: Float = Float.NaN
    ) {
        val c = surround.c
        val nc = surround.nc
        val fl: Float
        val n: Float
        val z: Float
        val nbb: Float
        val ncb: Float
        val aw: Float
        val d: Float
        val dRgb: FloatArray

        // Per-clip constants that the canonical CAM16 forward/inverse would otherwise recompute for EVERY
        // pixel (each is a pure function of the viewing conditions only). Precomputing them here is BIT-FOR-BIT
        // identical to the per-pixel expression (same IEEE result, evaluated once) and removes ~5 pow() per
        // pixel from the offline develop hot loop — a no-downgrade speed win.
        val cz: Float           // c * z  (lightness exponent numerator)
        val flRoot: Float       // fl^0.25 (brightness/colourfulness scale)
        val tExponentBase: Float // (1.64 - 0.29^n)^0.73 (chroma temporal exponent)

        init {
            val whiteScaled = floatArrayOf(whiteXyz[0] * 100f, whiteXyz[1] * 100f, whiteXyz[2] * 100f)
            val la = adaptingLuminance.coerceAtLeast(0f)
            val rgbW = SnceColorPrimitives.mulV(M16, whiteScaled)
            d = if (degreeOfAdaptationOverride.isFinite()) {
                degreeOfAdaptationOverride.coerceIn(0f, 1f)
            } else {
                (surround.f * (1f - (1f / 3.6f) * exp((-la - 42f) / 92f))).coerceIn(0f, 1f)
            }
            // D_RGB = D * Yw / RGBw + 1 - D, with Yw = 100.
            dRgb = FloatArray(3) { i ->
                d * 100f / rgbW[i].let { if (it == 0f) SnceColorPrimitives.EPS else it } + (1f - d)
            }
            val k = 1f / (5f * la + 1f)
            val k4 = k * k * k * k
            fl = 0.2f * k4 * (5f * la) + 0.1f * (1f - k4) * (1f - k4) * (5f * la).pow(1f / 3f)
            // Background: Y_b given relative to Y_white = 100.
            n = (backgroundLuminance / 100f).coerceIn(SnceColorPrimitives.EPS, 1f)
            z = 1.48f + sqrt(n)
            nbb = 0.725f * (1f / n).pow(0.2f)
            ncb = nbb
            // Adapted white achromatic response Aw.
            val rgbWc = FloatArray(3) { dRgb[it] * rgbW[it] }
            val rgbWa = FloatArray(3) { postAdaptationNonlinearity(rgbWc[it], fl) }
            aw = (2f * rgbWa[0] + rgbWa[1] + 0.05f * rgbWa[2] - 0.305f) * nbb
            // Hoisted per-clip constants (bit-identical to the per-pixel expressions they replace).
            cz = c * z
            flRoot = fl.pow(0.25f)
            tExponentBase = (1.64f - 0.29f.pow(n)).pow(0.73f)
        }
    }

    /** CAM16 perceptual correlates. h in degrees [0,360); a,b are the raw opponent signals. */
    data class SnceCam16Appearance(
        val j: Float, // lightness
        val c: Float, // chroma
        val h: Float, // hue angle (deg)
        val q: Float, // brightness
        val m: Float, // colourfulness
        val s: Float, // saturation
        val a: Float, // red-green opponent
        val b: Float  // yellow-blue opponent
    )

    // ------------------------------------------------------------------
    // Forward: XYZ (Y~=1) -> CAM16 appearance.
    // ------------------------------------------------------------------
    fun forward(xyz: FloatArray, vc: SnceViewingConditions): SnceCam16Appearance {
        val xyz100 = floatArrayOf(xyz[0] * 100f, xyz[1] * 100f, xyz[2] * 100f)
        val rgb = SnceColorPrimitives.mulV(M16, xyz100)
        val rgbC = FloatArray(3) { vc.dRgb[it] * rgb[it] }
        val rgbA = FloatArray(3) { postAdaptationNonlinearity(rgbC[it], vc.fl) }

        val a = rgbA[0] - 12f * rgbA[1] / 11f + rgbA[2] / 11f
        val b = (rgbA[0] + rgbA[1] - 2f * rgbA[2]) / 9f
        val hRad = atan2(b, a)
        val h = SnceColorPrimitives.wrapHueDegrees(SnceColorPrimitives.toDegrees(hRad))
        val et = 0.25f * (cos(hRad + 2f) + 3.8f)

        val achromatic = (2f * rgbA[0] + rgbA[1] + 0.05f * rgbA[2] - 0.305f) * vc.nbb
        val jRatio = (achromatic / vc.aw).coerceAtLeast(0f)
        val j = 100f * jRatio.pow(vc.cz)

        val tDenom = (rgbA[0] + rgbA[1] + 1.05f * rgbA[2] + 0.305f)
        val t = if (tDenom <= 0f) 0f else
            (50000f / 13f * vc.nc * vc.ncb) * et * sqrt(a * a + b * b) / tDenom
        val sqrtJ = sqrt((j / 100f).coerceAtLeast(0f))
        val c = t.pow(0.9f) * sqrtJ * vc.tExponentBase
        val q = (4f / vc.c) * sqrtJ * (vc.aw + 4f) * vc.flRoot
        val m = c * vc.flRoot
        val s = if (q <= 0f) 0f else 100f * sqrt((m / q).coerceAtLeast(0f))

        return SnceCam16Appearance(j = j, c = c, h = h, q = q, m = m, s = s, a = a, b = b)
    }

    // ------------------------------------------------------------------
    // Inverse: (J, C, h) -> XYZ (Y~=1). Stage 12.1.
    // ------------------------------------------------------------------
    fun inverseFromJch(j: Float, chroma: Float, hDeg: Float, vc: SnceViewingConditions): FloatArray {
        val hRad = SnceColorPrimitives.toRadians(SnceColorPrimitives.wrapHueDegrees(hDeg))
        val sqrtJ = sqrt((j / 100f).coerceAtLeast(0f))
        val tInner = vc.tExponentBase
        val t = if (sqrtJ < SnceColorPrimitives.EPS || tInner < SnceColorPrimitives.EPS) {
            0f
        } else {
            (chroma / (sqrtJ * tInner)).coerceAtLeast(0f).pow(1f / 0.9f)
        }
        val et = 0.25f * (cos(hRad + 2f) + 3.8f)
        val achromatic = vc.aw * (j / 100f).coerceAtLeast(0f).pow(1f / vc.cz)
        val p2 = achromatic / vc.nbb + 0.305f

        val a: Float
        val b: Float
        if (t < SnceColorPrimitives.EPS) {
            a = 0f
            b = 0f
        } else {
            val p1 = (50000f / 13f * vc.nc * vc.ncb) * et / t
            val sinH = sin(hRad)
            val cosH = cos(hRad)
            val p3 = 21f / 20f
            if (kotlin.math.abs(sinH) >= kotlin.math.abs(cosH)) {
                val p4 = p1 / sinH
                b = (p2 * (2f + p3) * (460f / 1403f)) /
                    (p4 + (2f + p3) * (220f / 1403f) * (cosH / sinH) -
                        (27f / 1403f) + p3 * (6300f / 1403f))
                a = b * (cosH / sinH)
            } else {
                val p5 = p1 / cosH
                a = (p2 * (2f + p3) * (460f / 1403f)) /
                    (p5 + (2f + p3) * (220f / 1403f) -
                        ((27f / 1403f) - p3 * (6300f / 1403f)) * (sinH / cosH))
                b = a * (sinH / cosH)
            }
        }

        val rgbA = floatArrayOf(
            (460f * p2 + 451f * a + 288f * b) / 1403f,
            (460f * p2 - 891f * a - 261f * b) / 1403f,
            (460f * p2 - 220f * a - 6300f * b) / 1403f
        )
        val rgbC = FloatArray(3) { inversePostAdaptationNonlinearity(rgbA[it], vc.fl) }
        val rgb = FloatArray(3) { rgbC[it] / vc.dRgb[it] }
        val xyz100 = SnceColorPrimitives.mulV(M16_INV, rgb)
        return floatArrayOf(xyz100[0] / 100f, xyz100[1] / 100f, xyz100[2] / 100f)
    }

    /** Inverse from (J, M, h): converts colourfulness M back to chroma C first. */
    fun inverseFromJmh(j: Float, m: Float, hDeg: Float, vc: SnceViewingConditions): FloatArray {
        val flRoot = vc.flRoot.coerceAtLeast(SnceColorPrimitives.EPS)
        return inverseFromJch(j, m / flRoot, hDeg, vc)
    }

    // ------------------------------------------------------------------
    // CAM16-UCS (J', a', b') — perceptually uniform; directive Stage 12.2 / scoring.
    // ------------------------------------------------------------------
    data class SnceCam16Ucs(val jp: Float, val ap: Float, val bp: Float)

    fun toUcs(app: SnceCam16Appearance): SnceCam16Ucs {
        val jp = (1.7f * app.j) / (1f + 0.007f * app.j)
        val mp = (1f / 0.0228f) * SnceColorPrimitives.safeLog(1f + 0.0228f * app.m)
        val hRad = SnceColorPrimitives.toRadians(app.h)
        return SnceCam16Ucs(jp, mp * cos(hRad), mp * sin(hRad))
    }

    /** Perceptual ΔE in CAM16-UCS (K_L = 1). A complementary metric to CIEDE2000. */
    fun deltaEUcs(a: SnceCam16Ucs, b: SnceCam16Ucs): Float {
        val dj = a.jp - b.jp
        val da = a.ap - b.ap
        val db = a.bp - b.bp
        return sqrt(dj * dj + da * da + db * db)
    }

    // ------------------------------------------------------------------
    // Post-adaptation non-linearity (sign-preserving Naka-Rushton) and its inverse.
    // ------------------------------------------------------------------
    private fun postAdaptationNonlinearity(value: Float, fl: Float): Float {
        val mag = (fl * kotlin.math.abs(value) / 100f).pow(0.42f)
        val signed = if (value >= 0f) 1f else -1f
        return signed * (400f * mag) / (mag + 27.13f) + 0.1f
    }

    private fun inversePostAdaptationNonlinearity(value: Float, fl: Float): Float {
        val x = value - 0.1f
        val mag = kotlin.math.abs(x)
        val denom = (400f - mag)
        if (denom <= 0f) return if (x >= 0f) 1e4f else -1e4f
        val base = (27.13f * mag / denom).coerceAtLeast(0f).pow(1f / 0.42f)
        val signed = if (x >= 0f) 1f else -1f
        return signed * (100f / fl) * base
    }
}
