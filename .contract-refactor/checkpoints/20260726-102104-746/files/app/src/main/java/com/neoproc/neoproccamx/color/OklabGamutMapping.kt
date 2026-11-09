package com.neoproc.neoproccamx.color

import kotlin.math.sqrt

/**
 * CP40 foundation (part 2) — exact Oklab gamut clipping built on [OklabGamutCusp]. This is the
 * operation the live shader's analytic boundary feeds: project an out-of-gamut colour back onto the
 * target-gamut boundary along a hue-preserving path. Björn Ottosson's `find_gamut_intersection`
 * (analytic seed + one Halley step) for linear sRGB. Pure, JVM-tested, dormant.
 *
 * Self-validating: in-gamut colours are returned unchanged; out-of-gamut colours come back inside
 * [0,1]³ with the same Oklab hue — the tests assert both directly.
 */
object OklabGamutMapping {

    private const val IN_GAMUT_EPS = 1e-4f

    private fun inGamut(rgb: FloatArray): Boolean =
        rgb[0] >= -IN_GAMUT_EPS && rgb[0] <= 1f + IN_GAMUT_EPS &&
            rgb[1] >= -IN_GAMUT_EPS && rgb[1] <= 1f + IN_GAMUT_EPS &&
            rgb[2] >= -IN_GAMUT_EPS && rgb[2] <= 1f + IN_GAMUT_EPS

    /**
     * Fraction t in [0,1] of the line from (L0, 0) to (L1, C1) — at hue direction (a,b) — that stays
     * inside the linear-sRGB gamut. [cusp] defaults to the exact cusp for the hue.
     */
    fun findGamutIntersection(
        a: Float,
        b: Float,
        l1: Float,
        c1: Float,
        l0: Float,
        cusp: FloatArray = OklabGamutCusp.findCusp(a, b),
    ): Float {
        val cuspL = cusp[0]
        val cuspC = cusp[1]
        var t: Float
        if ((l1 - l0) * cuspC - (cuspL - l0) * c1 <= 0f) {
            // Lower half — closed form.
            t = cuspC * l0 / (c1 * cuspL + cuspC * (l0 - l1))
        } else {
            // Upper half — closed-form seed + one Halley step against the actual R/G/B = 1 surfaces.
            t = cuspC * (l0 - 1f) / (c1 * (cuspL - 1f) + cuspC * (l0 - l1))
            val dL = l1 - l0
            val dC = c1
            val kl = 0.3963377774f * a + 0.2158037573f * b
            val km = -0.1055613458f * a - 0.0638541728f * b
            val ks = -0.0894841775f * a - 1.2914855480f * b
            val lDt = dL + dC * kl
            val mDt = dL + dC * km
            val sDt = dL + dC * ks

            val l = l0 * (1f - t) + t * l1
            val c = t * c1
            val lp = l + c * kl
            val mp = l + c * km
            val sp = l + c * ks
            val l3 = lp * lp * lp
            val m3 = mp * mp * mp
            val s3 = sp * sp * sp
            val ldt = 3f * lDt * lp * lp
            val mdt = 3f * mDt * mp * mp
            val sdt = 3f * sDt * sp * sp
            val ldt2 = 6f * lDt * lDt * lp
            val mdt2 = 6f * mDt * mDt * mp
            val sdt2 = 6f * sDt * sDt * sp

            val rr = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3 - 1f
            val rr1 = 4.0767416621f * ldt - 3.3077115913f * mdt + 0.2309699292f * sdt
            val rr2 = 4.0767416621f * ldt2 - 3.3077115913f * mdt2 + 0.2309699292f * sdt2
            val uR = rr1 / (rr1 * rr1 - 0.5f * rr * rr2)
            var tR = -rr * uR

            val gg = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3 - 1f
            val gg1 = -1.2684380046f * ldt + 2.6097574011f * mdt - 0.3413193965f * sdt
            val gg2 = -1.2684380046f * ldt2 + 2.6097574011f * mdt2 - 0.3413193965f * sdt2
            val uG = gg1 / (gg1 * gg1 - 0.5f * gg * gg2)
            var tG = -gg * uG

            val bb = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3 - 1f
            val bb1 = -0.0041960863f * ldt - 0.7034186147f * mdt + 1.7076147010f * sdt
            val bb2 = -0.0041960863f * ldt2 - 0.7034186147f * mdt2 + 1.7076147010f * sdt2
            val uB = bb1 / (bb1 * bb1 - 0.5f * bb * bb2)
            var tB = -bb * uB

            tR = if (uR >= 0f) tR else Float.MAX_VALUE
            tG = if (uG >= 0f) tG else Float.MAX_VALUE
            tB = if (uB >= 0f) tB else Float.MAX_VALUE
            t += minOf(tR, tG, tB)
        }
        return t
    }

    /**
     * Clip [r],[g],[b] (linear sRGB, possibly out of gamut) to the gamut boundary, preserving Oklab
     * hue and reducing chroma toward the achromatic axis at the clamped lightness. In-gamut colours
     * are returned unchanged.
     */
    fun gamutClipPreserveChroma(r: Float, g: Float, b: Float): FloatArray {
        val rgb = floatArrayOf(r, g, b)
        if (inGamut(rgb)) return rgb
        val lab = OklabGamutCusp.linearSrgbToOklab(r, g, b)
        val l = lab[0]
        val eps = 1e-5f
        val c = maxOf(eps, sqrt(lab[1] * lab[1] + lab[2] * lab[2]))
        val aN = lab[1] / c
        val bN = lab[2] / c
        val l0 = l.coerceIn(0f, 1f)
        val t = findGamutIntersection(aN, bN, l, c, l0)
        val lClip = l0 * (1f - t) + t * l
        val cClip = t * c
        return OklabGamutCusp.oklabToLinearSrgb(lClip, cClip * aN, cClip * bN)
    }
}
