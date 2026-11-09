package com.neoproc.neoproccamx.snce

import com.neoproc.neoproccamx.snce.SnceCam16.SnceCam16Appearance
import kotlin.math.log10

/**
 * SNCE v2.0 HVC — Stage 11 (NCQEE): region-adaptive colour quality enhancement.
 *
 * Two parts:
 *  - Per-semantic-class enhancement profiles (directive 11.2): each class gets a hue /
 *    chroma / lightness adjustment plus hard caps (snow stays near-neutral, metal keeps
 *    neutral highlights). Applied to CAM16 attributes.
 *  - The Neural Real-time LUT (NRLI, 11.3): a [SceneDescriptor] is computed every frame and
 *    handed to the optional [SnceAdaptiveLutModel]; when no model is loaded the analytic
 *    per-class path above carries the look. [trilinearSampleLut] is the CPU reference for
 *    the GLSL 3D-LUT apply (nrli_lut_apply.comp).
 */
object SnceNeuralEnhancement {

    enum class SemanticClass { SKY, SKIN, FOLIAGE, SHADOW, SNOW, METAL, WATER, OTHER }

    class EnhancementProfile(
        val hueShiftDeg: Float = 0f,
        val chromaScale: Float = 1f,
        val lightnessScale: Float = 1f,
        val chromaCap: Float = Float.MAX_VALUE,
        val jMin: Float = 0f,
        val jMax: Float = 100f
    )

    /** Directive 11.2 per-class enhancement rules. */
    val PROFILES: Map<SemanticClass, EnhancementProfile> = mapOf(
        SemanticClass.SKY to EnhancementProfile(hueShiftDeg = 0f, chromaScale = 1.08f, lightnessScale = 0.97f),
        SemanticClass.SKIN to EnhancementProfile(hueShiftDeg = 2f, chromaScale = 1.05f),
        SemanticClass.FOLIAGE to EnhancementProfile(hueShiftDeg = -3f, chromaScale = 1.12f),
        SemanticClass.SHADOW to EnhancementProfile(hueShiftDeg = 5f, chromaScale = 0.95f),
        SemanticClass.SNOW to EnhancementProfile(chromaCap = 8f, jMin = 88f, jMax = 96f),
        SemanticClass.METAL to EnhancementProfile(chromaScale = 0.85f, chromaCap = 10f),
        SemanticClass.WATER to EnhancementProfile(chromaScale = 1.05f),
        SemanticClass.OTHER to EnhancementProfile()
    )

    fun applyProfile(app: SnceCam16Appearance, semanticClass: SemanticClass): SnceCam16Appearance {
        val p = PROFILES[semanticClass] ?: EnhancementProfile()
        val h = SnceColorPrimitives.wrapHueDegrees(app.h + p.hueShiftDeg)
        val c = (app.c * p.chromaScale).coerceAtMost(p.chromaCap)
        val j = (app.j * p.lightnessScale).coerceIn(p.jMin, p.jMax)
        return app.copy(j = j, c = c, h = h)
    }

    // ------------------------------------------------------------------
    // Scene descriptor (directive 11.3 NRLI input).
    // ------------------------------------------------------------------
    class SceneDescriptor(
        val meanCct: Float,
        val logLuminance: Float,
        val indoorOutdoorFlag: Float, // 0 indoor .. 1 outdoor
        val semanticHistogram: FloatArray
    )

    fun computeSceneDescriptor(
        meanCct: Float,
        meanLuminance: Float,
        indoorOutdoorFlag: Float,
        semanticHistogram: FloatArray
    ): SceneDescriptor = SceneDescriptor(
        meanCct = meanCct,
        logLuminance = log10(meanLuminance.coerceAtLeast(SnceColorPrimitives.EPS)),
        indoorOutdoorFlag = indoorOutdoorFlag.coerceIn(0f, 1f),
        semanticHistogram = semanticHistogram
    )

    // ------------------------------------------------------------------
    // Trilinear 3D-LUT sample — CPU reference for the GLSL apply.
    // ------------------------------------------------------------------
    fun trilinearSampleLut(lut: SnceAdaptiveLut, rgb: FloatArray): FloatArray {
        val size = lut.size
        val maxIdx = size - 1
        val cr = rgb[0].coerceIn(0f, 1f) * maxIdx
        val cg = rgb[1].coerceIn(0f, 1f) * maxIdx
        val cb = rgb[2].coerceIn(0f, 1f) * maxIdx
        val r0 = cr.toInt().coerceIn(0, maxIdx); val r1 = (r0 + 1).coerceAtMost(maxIdx)
        val g0 = cg.toInt().coerceIn(0, maxIdx); val g1 = (g0 + 1).coerceAtMost(maxIdx)
        val b0 = cb.toInt().coerceIn(0, maxIdx); val b1 = (b0 + 1).coerceAtMost(maxIdx)
        val fr = cr - r0; val fg = cg - g0; val fb = cb - b0

        fun sample(ri: Int, gi: Int, bi: Int, c: Int): Float {
            val idx = ((bi * size + gi) * size + ri) * 3 + c
            return lut.rgb[idx]
        }

        val out = FloatArray(3)
        for (c in 0 until 3) {
            val c000 = sample(r0, g0, b0, c); val c100 = sample(r1, g0, b0, c)
            val c010 = sample(r0, g1, b0, c); val c110 = sample(r1, g1, b0, c)
            val c001 = sample(r0, g0, b1, c); val c101 = sample(r1, g0, b1, c)
            val c011 = sample(r0, g1, b1, c); val c111 = sample(r1, g1, b1, c)
            val c00 = c000 + (c100 - c000) * fr
            val c10 = c010 + (c110 - c010) * fr
            val c01 = c001 + (c101 - c001) * fr
            val c11 = c011 + (c111 - c011) * fr
            val c0 = c00 + (c10 - c00) * fg
            val c1 = c01 + (c11 - c01) * fg
            out[c] = c0 + (c1 - c0) * fb
        }
        return out
    }

    /**
     * s2-f08 / CP43 — CPU reference for the NRLI shader's tetrahedral 3D-LUT interpolation (the
     * broadcast/DI standard: split each cell into 6 tetrahedra and interpolate over 4 vertices,
     * eliminating trilinear's diagonal hue twist). Mirrors the shader's tetrahedral branch exactly.
     */
    fun tetrahedralSampleLut(lut: SnceAdaptiveLut, rgb: FloatArray): FloatArray {
        val size = lut.size
        val maxIdx = size - 1
        val cr = rgb[0].coerceIn(0f, 1f) * maxIdx
        val cg = rgb[1].coerceIn(0f, 1f) * maxIdx
        val cb = rgb[2].coerceIn(0f, 1f) * maxIdx
        val r0 = cr.toInt().coerceIn(0, maxIdx); val r1 = (r0 + 1).coerceAtMost(maxIdx)
        val g0 = cg.toInt().coerceIn(0, maxIdx); val g1 = (g0 + 1).coerceAtMost(maxIdx)
        val b0 = cb.toInt().coerceIn(0, maxIdx); val b1 = (b0 + 1).coerceAtMost(maxIdx)
        val fr = cr - r0; val fg = cg - g0; val fb = cb - b0

        fun s(ri: Int, gi: Int, bi: Int, c: Int): Float = lut.rgb[((bi * size + gi) * size + ri) * 3 + c]

        val out = FloatArray(3)
        for (c in 0 until 3) {
            val c000 = s(r0, g0, b0, c); val c111 = s(r1, g1, b1, c)
            out[c] = if (fr >= fg) {
                if (fg >= fb) c000 + fr * (s(r1, g0, b0, c) - c000) + fg * (s(r1, g1, b0, c) - s(r1, g0, b0, c)) + fb * (c111 - s(r1, g1, b0, c))
                else if (fr >= fb) c000 + fr * (s(r1, g0, b0, c) - c000) + fb * (s(r1, g0, b1, c) - s(r1, g0, b0, c)) + fg * (c111 - s(r1, g0, b1, c))
                else c000 + fb * (s(r0, g0, b1, c) - c000) + fr * (s(r1, g0, b1, c) - s(r0, g0, b1, c)) + fg * (c111 - s(r1, g0, b1, c))
            } else {
                if (fb >= fg) c000 + fb * (s(r0, g0, b1, c) - c000) + fg * (s(r0, g1, b1, c) - s(r0, g0, b1, c)) + fr * (c111 - s(r0, g1, b1, c))
                else if (fb >= fr) c000 + fg * (s(r0, g1, b0, c) - c000) + fb * (s(r0, g1, b1, c) - s(r0, g1, b0, c)) + fr * (c111 - s(r0, g1, b1, c))
                else c000 + fg * (s(r0, g1, b0, c) - c000) + fr * (s(r1, g1, b0, c) - s(r0, g1, b0, c)) + fb * (c111 - s(r1, g1, b0, c))
            }
        }
        return out
    }

    /** Build an identity 33^3 LUT (the analytic fallback when no model is loaded). */
    fun identityLut(size: Int = 33): SnceAdaptiveLut {
        val maxIdx = size - 1
        val rgb = FloatArray(size * size * size * 3)
        var idx = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            rgb[idx++] = r.toFloat() / maxIdx
            rgb[idx++] = g.toFloat() / maxIdx
            rgb[idx++] = b.toFloat() / maxIdx
        }
        return SnceAdaptiveLut(size, rgb)
    }
}
