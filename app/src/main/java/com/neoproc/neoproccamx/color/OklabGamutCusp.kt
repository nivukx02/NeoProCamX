package com.neoproc.neoproccamx.color

import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.sin

/**
 * CP40 foundation — the EXACT Oklab gamut cusp (maximum-chroma point of the target gamut at a given
 * hue), to replace the live shader's analytic `0.365 + 0.080·cos(3h) + 0.035·cos(6h)` boundary guess
 * (neo_p010_export_enhance.comp:1168). Implements Björn Ottosson's closed-form max-saturation + one
 * Halley step + cusp construction for linear sRGB. Pure, deterministic, JVM-unit-tested; dormant —
 * wiring the resulting per-hue LUT into the gamut-compression shader is the device-gated step.
 *
 * The cusp is self-validating: at the returned (L_cusp, C_cusp) the corresponding linear sRGB colour
 * has exactly one channel at 1.0 (it sits on the [0,1]³ gamut boundary), which the tests assert
 * directly — so a bug in either the algorithm or the matrices shows up as a boundary-property failure,
 * not a mismatch against hand-picked reference numbers.
 */
object OklabGamutCusp {

    /** Oklab → linear sRGB (Ottosson constants). */
    fun oklabToLinearSrgb(l: Float, a: Float, b: Float): FloatArray {
        val lp = l + 0.3963377774f * a + 0.2158037573f * b
        val mp = l - 0.1055613458f * a - 0.0638541728f * b
        val sp = l - 0.0894841775f * a - 1.2914855480f * b
        val ll = lp * lp * lp
        val mm = mp * mp * mp
        val ss = sp * sp * sp
        return floatArrayOf(
            4.0767416621f * ll - 3.3077115913f * mm + 0.2309699292f * ss,
            -1.2684380046f * ll + 2.6097574011f * mm - 0.3413193965f * ss,
            -0.0041960863f * ll - 0.7034186147f * mm + 1.7076147010f * ss,
        )
    }

    /** Linear sRGB → Oklab (Ottosson constants). */
    fun linearSrgbToOklab(r: Float, g: Float, b: Float): FloatArray {
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
        val lp = cbrt(l)
        val mp = cbrt(m)
        val sp = cbrt(s)
        return floatArrayOf(
            0.2104542553f * lp + 0.7936177850f * mp - 0.0040720468f * sp,
            1.9779984951f * lp - 2.4285922050f * mp + 0.4505937099f * sp,
            0.0259040371f * lp + 0.7827717662f * mp - 0.8086757660f * sp,
        )
    }

    /**
     * Maximum saturation S = C/L for the (already-normalised, a²+b²=1) hue direction — the value at
     * which the first of R/G/B leaves [0,1]. Polynomial approximation + one Halley iteration.
     */
    fun computeMaxSaturation(a: Float, b: Float): Float {
        val k0: Float; val k1: Float; val k2: Float; val k3: Float; val k4: Float
        val wl: Float; val wm: Float; val ws: Float
        if (-1.88170328f * a - 0.80936493f * b > 1f) { // Red
            k0 = 1.19086277f; k1 = 1.76576728f; k2 = 0.59662641f; k3 = 0.75515197f; k4 = 0.56771245f
            wl = 4.0767416621f; wm = -3.3077115913f; ws = 0.2309699292f
        } else if (1.81444104f * a - 1.19445276f * b > 1f) { // Green
            k0 = 0.73956515f; k1 = -0.45954404f; k2 = 0.08285427f; k3 = 0.12541070f; k4 = 0.14503204f
            wl = -1.2684380046f; wm = 2.6097574011f; ws = -0.3413193965f
        } else { // Blue
            k0 = 1.35733652f; k1 = -0.00915799f; k2 = -1.15130210f; k3 = -0.50559606f; k4 = 0.00692167f
            wl = -0.0041960863f; wm = -0.7034186147f; ws = 1.7076147010f
        }
        var s = k0 + k1 * a + k2 * b + k3 * a * a + k4 * a * b
        val kl = 0.3963377774f * a + 0.2158037573f * b
        val km = -0.1055613458f * a - 0.0638541728f * b
        val ks = -0.0894841775f * a - 1.2914855480f * b
        val lp = 1f + s * kl
        val mp = 1f + s * km
        val sp = 1f + s * ks
        val l = lp * lp * lp
        val m = mp * mp * mp
        val sc = sp * sp * sp
        val lDs = 3f * kl * lp * lp
        val mDs = 3f * km * mp * mp
        val sDs = 3f * ks * sp * sp
        val lDs2 = 6f * kl * kl * lp
        val mDs2 = 6f * km * km * mp
        val sDs2 = 6f * ks * ks * sp
        val f = wl * l + wm * m + ws * sc
        val f1 = wl * lDs + wm * mDs + ws * sDs
        val f2 = wl * lDs2 + wm * mDs2 + ws * sDs2
        s -= f * f1 / (f1 * f1 - 0.5f * f * f2)
        return s
    }

    /** Cusp (L, C) for a normalised hue direction (a, b). */
    fun findCusp(a: Float, b: Float): FloatArray {
        val sCusp = computeMaxSaturation(a, b)
        val rgb = oklabToLinearSrgb(1f, sCusp * a, sCusp * b)
        val maxCh = maxOf(rgb[0], rgb[1], rgb[2])
        val lCusp = cbrt(1f / maxCh)
        val cCusp = lCusp * sCusp
        return floatArrayOf(lCusp, cCusp)
    }

    /** Cusp (L, C) for a hue angle in radians. */
    fun findCuspForHue(hueRadians: Float): FloatArray =
        findCusp(cos(hueRadians), sin(hueRadians))

    /**
     * Per-hue cusp LUT: [bins] entries of (L_cusp, C_cusp) sampled uniformly over [0, 2π). This is the
     * exact replacement for the analytic shader boundary — upload it as a 1D texture / SSBO and sample
     * by Oklab hue (device-gated wiring step).
     */
    fun cuspLut(bins: Int): FloatArray {
        require(bins > 0)
        val out = FloatArray(bins * 2)
        for (i in 0 until bins) {
            val hue = (i.toFloat() / bins) * (2f * Math.PI.toFloat())
            val lc = findCuspForHue(hue)
            out[i * 2] = lc[0]
            out[i * 2 + 1] = lc[1]
        }
        return out
    }
}
