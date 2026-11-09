package com.neoproc.neoproccamx.rendering

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * s4-f04 / CP35 — host-side reference of the elite still-HDR merge: a Debevec/Malik radiometric
 * bracket fusion that recovers a scene-linear radiance map in full float (the "16f" working space),
 * with well-exposedness weighting and per-pixel deghosting. The live shader emits this radiance at
 * higher precision behind the `outputAcesCg16f` gate; this oracle proves the algorithm on the JVM.
 *
 * Frames are 8-bit-sRGB-quantized RGB in [0,1] (length w*h*3), modelling the captured brackets.
 */
object HdrMergeReference {

    fun srgbToLinear(c: Float): Float {
        val v = c.coerceIn(0f, 1f)
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    fun linearToSrgb(c: Float): Float {
        val v = c.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
    }

    /** Quantize a linear value to an 8-bit sRGB code and back (models the captured-frame floor). */
    fun quantize8(linear: Float): Float {
        val code = (linearToSrgb(linear) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return srgbToLinear(code / 255f)
    }

    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3f - 2f * t)
    }

    /**
     * Debevec well-exposedness weight for an 8-bit sRGB value: a Gaussian centred on mid-gray that
     * hard-down-weights clipped (→1) and black (→0) samples, so they barely contribute to the merge.
     */
    fun wellExposedness(srgbValue: Float): Float {
        val v = srgbValue.coerceIn(0f, 1f)
        val gauss = exp(-(v - 0.5f) * (v - 0.5f) / (2f * 0.2f * 0.2f))
        val valid = smooth(0.0f, 0.04f, v) * (1f - smooth(0.96f, 1.0f, v))
        return (gauss * valid).coerceAtLeast(1e-4f)
    }

    class RadianceResult(val rgb: FloatArray, val w: Int, val h: Int)

    /**
     * Fuse [frames] (each w*h*3 8-bit-sRGB) into a scene-linear radiance map. radiance estimate per
     * frame = srgbToLinear(captured) / exposureScale; weighted by [wellExposedness] (and, when
     * [deghost], a residual veto against frame [refIndex] that rejects moved content per pixel).
     */
    fun mergeRadiance(
        frames: List<FloatArray>, exposureScales: FloatArray, w: Int, h: Int,
        deghost: Boolean = false, refIndex: Int = 0
    ): RadianceResult {
        require(frames.isNotEmpty() && frames.size == exposureScales.size)
        val out = FloatArray(w * h * 3)
        val ref = frames[refIndex]
        for (p in 0 until w * h) {
            val i = p * 3
            var accR = 0f; var accG = 0f; var accB = 0f; var accW = 0f
            val refLuma = luma(ref[i], ref[i + 1], ref[i + 2])
            for (f in frames.indices) {
                val fr = frames[f]
                val r = fr[i]; val g = fr[i + 1]; val b = fr[i + 2]
                var wgt = (wellExposedness(r) + wellExposedness(g) + wellExposedness(b)) / 3f
                if (deghost && f != refIndex) {
                    // Reject pixels that differ from the reference exposure beyond the noise band.
                    val resid = abs(luma(r, g, b) - refLuma)
                    wgt *= (1f - smooth(0.06f, 0.22f, resid))
                }
                val es = exposureScales[f].coerceAtLeast(0.01f)
                accR += srgbToLinear(r) / es * wgt
                accG += srgbToLinear(g) / es * wgt
                accB += srgbToLinear(b) / es * wgt
                accW += wgt
            }
            val inv = 1f / accW.coerceAtLeast(1e-6f)
            out[i] = accR * inv; out[i + 1] = accG * inv; out[i + 2] = accB * inv
        }
        return RadianceResult(out, w, h)
    }
}
