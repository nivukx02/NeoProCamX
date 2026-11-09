package com.neoproc.neoproccamx.snce

import com.neoproc.neoproccamx.snce.SnceCam16.SnceCam16Appearance
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow

/**
 * SNCE v2.0 HVC — Stage 06 (CAME) appearance phenomena, directive Part III + 06.2..06.7.
 *
 * Each phenomenon is a pure function over CAM16 correlates so it can be unit-tested
 * against the directive's threshold tables, then composed by [correctAppearance].
 * Simultaneous chromatic contrast (06.7 / 3.5) needs a spatial surround and is kept
 * separate ([simultaneousContrast]); the orchestrator feeds it a low-passed surround.
 *
 * The five per-sample phenomena are derived directly from the directive's formulas
 * (which are authoritative); the directive's accompanying percentage tables are
 * illustrative, so the tests assert direction/monotonicity/identity-at-reference
 * rather than exact percentages.
 */
object SnceAppearancePhenomena {
    /** Daylight reference adapting luminance (cd/m^2) at which corrections are unity. */
    const val LA_DAYLIGHT_REFERENCE = 64f

    /** CAM16 F_L luminance-level adaptation factor (directive 3.1). */
    fun computeFL(adaptingLuminance: Float): Float {
        val la = adaptingLuminance.coerceAtLeast(0f)
        val k = 1f / (5f * la + 1f)
        val k4 = k * k * k * k
        return 0.2f * k4 * (5f * la) + 0.1f * (1f - k4) * (1f - k4) * (5f * la).pow(1f / 3f)
    }

    // ------------------------------------------------------------------
    // 3.1 / 06.2 HUNT EFFECT — colourfulness scales with luminance.
    // ------------------------------------------------------------------
    /** Multiplier applied to chroma C and colourfulness M; >1 in dim light, 1 at daylight. */
    fun huntChromaMultiplier(sceneLa: Float): Float {
        val flScene = computeFL(sceneLa).coerceAtLeast(SnceColorPrimitives.EPS)
        val flDay = computeFL(LA_DAYLIGHT_REFERENCE)
        return (flDay / flScene).pow(0.25f)
    }

    // ------------------------------------------------------------------
    // 3.2 / 06.3 STEVENS EFFECT — contrast scales with luminance.
    // ------------------------------------------------------------------
    fun stevensLightness(j: Float, sceneLa: Float): Float {
        val gammaEff = (0.42f + 0.06f * log10((sceneLa / LA_DAYLIGHT_REFERENCE).coerceAtLeast(SnceColorPrimitives.EPS)))
            .coerceIn(0.28f, 0.56f)
        return 100f * (j / 100f).coerceAtLeast(0f).pow(0.42f / gammaEff)
    }

    // ------------------------------------------------------------------
    // 3.3 / 06.4 HELMHOLTZ-KOHLRAUSCH — colourfulness boosts brightness.
    // ------------------------------------------------------------------
    fun hkBrightness(q: Float, s: Float, hueDeg: Float): Float {
        val kHk = 0.30f + 0.12f * cos(SnceColorPrimitives.toRadians(hueDeg - 200f))
        val deltaQ = kHk * (s / 100f) * q
        return q + deltaQ
    }

    // ------------------------------------------------------------------
    // 3.4 / 06.5 BEZOLD-BRUCKE — hue shifts with luminance.
    // ------------------------------------------------------------------
    fun bezoldBruckeHue(hueDeg: Float, sceneLa: Float): Float {
        val deltaLuminanceLog = log10((sceneLa / LA_DAYLIGHT_REFERENCE).coerceAtLeast(SnceColorPrimitives.EPS))
        val h = SnceColorPrimitives.wrapHueDegrees(hueDeg)
        val deltaBB = when {
            h in 50f..80f -> 6f * deltaLuminanceLog      // yellow region
            h in 215f..265f -> -8f * deltaLuminanceLog   // blue region
            else -> 0f
        }
        return SnceColorPrimitives.wrapHueDegrees(h - deltaBB)
    }

    // ------------------------------------------------------------------
    // 3.6 / 06.6 ABNEY EFFECT — hue shifts as a colour desaturates.
    // ------------------------------------------------------------------
    private val ABNEY_HUES = floatArrayOf(0f, 60f, 180f, 270f, 290f, 360f)
    private val ABNEY_SHIFTS = floatArrayOf(0f, 5f, 8f, -6f, -10f, 0f)

    /** Hue shift at zero saturation, piecewise-linear around the hue circle. */
    fun abneyShiftAtZeroSaturation(hueDeg: Float): Float {
        val h = SnceColorPrimitives.wrapHueDegrees(hueDeg)
        for (i in 0 until ABNEY_HUES.size - 1) {
            if (h >= ABNEY_HUES[i] && h <= ABNEY_HUES[i + 1]) {
                val span = (ABNEY_HUES[i + 1] - ABNEY_HUES[i]).coerceAtLeast(SnceColorPrimitives.EPS)
                val t = (h - ABNEY_HUES[i]) / span
                return ABNEY_SHIFTS[i] + t * (ABNEY_SHIFTS[i + 1] - ABNEY_SHIFTS[i])
            }
        }
        return 0f
    }

    fun abneyHue(hueDeg: Float, saturationNorm: Float): Float {
        val sNorm = saturationNorm.coerceIn(0f, 1f)
        val shift = abneyShiftAtZeroSaturation(hueDeg) * (1f - sNorm).pow(1.5f)
        return SnceColorPrimitives.wrapHueDegrees(hueDeg + shift)
    }

    // ------------------------------------------------------------------
    // 3.5 / 06.7 SIMULTANEOUS CHROMATIC CONTRAST — surround induces opposite chroma.
    // surroundA/surroundB are the low-passed (sigma ~40px) opponent signals of the
    // surround; only applied where local contrast is low (high-contrast edges self-mask).
    // ------------------------------------------------------------------
    fun simultaneousContrast(
        a: Float,
        b: Float,
        surroundA: Float,
        surroundB: Float,
        localContrast: Float,
        inductionStrength: Float = 0.07f
    ): Pair<Float, Float> {
        val threshold = 0.15f
        if (localContrast >= threshold) return a to b
        // Smooth gate: full induction in flat regions, fading to zero toward the edge mask.
        val gate = (1f - localContrast / threshold).coerceIn(0f, 1f)
        val iRg = -inductionStrength * surroundA * gate
        val iBy = -inductionStrength * surroundB * gate
        return (a + iRg) to (b + iBy)
    }

    // ------------------------------------------------------------------
    // Composition: the five per-sample phenomena, in directive order.
    // ------------------------------------------------------------------
    fun correctAppearance(app: SnceCam16Appearance, sceneLa: Float): SnceCam16Appearance {
        val huntK = huntChromaMultiplier(sceneLa)
        val c = app.c * huntK
        val m = app.m * huntK
        val j = stevensLightness(app.j, sceneLa)
        val q = hkBrightness(app.q, app.s, app.h)
        val hBB = bezoldBruckeHue(app.h, sceneLa)
        val hFinal = abneyHue(hBB, app.s / 100f)
        return app.copy(j = j, c = c, m = m, q = q, h = hFinal)
    }
}
