package com.neoproc.neoproccamx.color

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * s2-f11 / CP40 (GPU half) — host reference for the export shader's xy gamut-compression boundary
 * (`neo_p010_export_enhance.comp` `neoWideGamutCompressXyPreserveY`).
 *
 * The live shader compresses out-of-gamut chromaticities toward the working white point using a
 * *radius* threshold per hue **angle** in the CIE xy plane. Today that threshold is a smooth 3-fold
 * cosine GUESS — [analyticSoftBoundary] — of the NeoWideGamut primary triangle. [exactGamutBoundaryRadius]
 * is the EXACT distance from the white point to the primary-triangle edge at that angle: the true
 * gamut boundary the cosine only approximates. (This is the in-space sibling of the exact Oklab
 * cusp in [OklabGamutCusp]/[OklabGamutMapping]; the shader works in xy, so the in-space triangle is
 * the faithful drop-in for the `0.365 + 0.080·cos(3h) + …` boundary.)
 *
 * Dormant by contract: the shader selects the exact path only when its `USE_EXACT_GAMUT_BOUNDARY`
 * specialization constant is enabled on-device (default false ⇒ the analytic cosine = today,
 * bit-for-bit). Pure, deterministic, JVM-unit-tested.
 */
object NeoGamutXyBoundary {

    /** Working white point — must match the shader's `center` (`:1164`). */
    const val CENTER_X = 0.3324f
    const val CENTER_Y = 0.3474f

    /**
     * NeoWideGamut primary chromaticities — the xy of the columns of `NEO_WIDE_GAMUT_TO_XYZ`
     * (`:1149-1153`), normalised. Hardcoded identically in the shader's exact branch.
     */
    val PRIMARY_R = floatArrayOf(0.7347024f, 0.2652976f)
    val PRIMARY_G = floatArrayOf(0.1151875f, 0.8259494f)
    val PRIMARY_B = floatArrayOf(0.0859901f, 0.0110001f)
    private val PRIMARIES = arrayOf(PRIMARY_R, PRIMARY_G, PRIMARY_B)

    /** Today's live boundary: the smooth 3-fold cosine guess, clamped to [0.18, 0.48]. */
    fun analyticSoftBoundary(angle: Float): Float =
        (0.365f + 0.080f * cos(3.0f * angle) + 0.035f * cos(6.0f * angle + 0.4f))
            .coerceIn(0.18f, 0.48f)

    /**
     * Exact radius from the white point to the NeoWideGamut primary triangle edge in direction
     * [angle] (CIE xy). The true gamut boundary; replaces [analyticSoftBoundary] when the shader's
     * exact-cusp path is enabled.
     */
    fun exactGamutBoundaryRadius(angle: Float): Float {
        val dx = cos(angle)
        val dy = sin(angle)
        var best = Float.MAX_VALUE
        for (k in 0 until 3) {
            val p = PRIMARIES[k]
            val q = PRIMARIES[(k + 1) % 3]
            val ex = q[0] - p[0]
            val ey = q[1] - p[1]
            // Solve C + t·d = p + s·e for t (ray param) and s (edge param ∈ [0,1]).
            val det = -dx * ey + ex * dy
            if (abs(det) < 1e-9f) continue
            val rx = p[0] - CENTER_X
            val ry = p[1] - CENTER_Y
            val t = (-rx * ey + ex * ry) / det
            val s = (dx * ry - dy * rx) / det
            if (t > 0f && s >= 0f && s <= 1f && t < best) best = t
        }
        return if (best == Float.MAX_VALUE) 0f else best
    }
}
