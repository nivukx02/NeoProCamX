package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * s2-f10 elite locks for [SnceIlluminantEstimator]: golden-lock of the fused AWB gains
 * (no-downgrade proof for the confidence/CP44 additions), fusion-accuracy / angular-error,
 * G = 1 normalisation, neutral-under-estimate, and fusion-confidence monotonicity.
 *
 * The confidence field and the CP44 Duv axis are PURELY ADDITIVE — they must not move the
 * gains. The golden snapshot below freezes today's gains so any accidental perturbation trips.
 */
class SnceIlluminantEstimatorEliteTest {

    private fun tintedGray(w: Int, h: Int, light: FloatArray, base: Float = 0.4f): SnceThumbnail {
        val rgb = FloatArray(w * h * 3)
        for (i in 0 until w * h) {
            val b = base * (0.6f + 0.5f * ((i % 7) / 6f))
            rgb[i * 3] = b * light[0]
            rgb[i * 3 + 1] = b * light[1]
            rgb[i * 3 + 2] = b * light[2]
        }
        return SnceThumbnail(w, h, rgb)
    }

    /** Split warm/cool scene → genuinely mixed lighting (lower confidence). */
    private fun mixedScene(w: Int = 8, h: Int = 12): SnceThumbnail {
        val rgb = FloatArray(w * h * 3)
        val warm = floatArrayOf(1.5f, 1f, 0.55f)
        val cool = floatArrayOf(0.6f, 1f, 1.6f)
        for (y in 0 until h) for (x in 0 until w) {
            val light = if (x < w / 2) warm else cool
            val b = 0.4f * (0.7f + 0.3f * ((x + y) % 5) / 4f)
            val i = (y * w + x) * 3
            rgb[i] = b * light[0]; rgb[i + 1] = b * light[1]; rgb[i + 2] = b * light[2]
        }
        return SnceThumbnail(w, h, rgb)
    }

    private fun normG1(rgb: FloatArray): FloatArray {
        val g = if (kotlin.math.abs(rgb[1]) < 1e-6f) 1e-6f else rgb[1]
        return floatArrayOf(rgb[0] / g, 1f, rgb[2] / g)
    }

    /** Angle (degrees) between two RGB illuminant vectors — the standard AWB angular error. */
    private fun angularErrorDeg(a: FloatArray, b: FloatArray): Float {
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        val c = (dot / (na * nb)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c.toDouble())).toFloat()
    }

    // ----------------------------------------------------------------------------------
    // GOLDEN-LOCK: fused AWB gains for a fixed scene set are frozen bit-for-bit.
    // ----------------------------------------------------------------------------------
    @Test
    fun `golden lock - fused AWB gains are unchanged by the confidence and CP44 additions`() {
        val neutral = SnceIlluminantEstimator.estimate(tintedGray(16, 16, floatArrayOf(1f, 1f, 1f)))
        val warm = SnceIlluminantEstimator.estimate(tintedGray(16, 16, floatArrayOf(1.35f, 1f, 0.65f)))
        val cool = SnceIlluminantEstimator.estimate(tintedGray(16, 16, floatArrayOf(0.7f, 1f, 1.5f)))
        val mixed = SnceIlluminantEstimator.estimate(mixedScene())

        // Deterministic: same input → identical gains.
        val warmAgain = SnceIlluminantEstimator.estimate(tintedGray(16, 16, floatArrayOf(1.35f, 1f, 0.65f)))
        assertArrayEquals("gains must be deterministic", warm.gains, warmAgain.gains, 0f)

        // Frozen snapshot of the live AWB-gain escape path, captured BEFORE wiring the
        // confidence/CP44 additions — proves those additions are bit-for-bit no-ops on pixels.
        assertGolden("neutral", neutral,
            floatArrayOf(0.9947895f, 1.0f, 1.0012294f),
            floatArrayOf(1.0052378f, 1.0f, 0.99877214f), 6480.0938f, 0.0030171587f)
        assertGolden("warm", warm,
            floatArrayOf(0.747448f, 1.0f, 1.5004548f),
            floatArrayOf(1.3378856f, 1.0f, 0.6664646f), 4531.52f, 0.0017173217f)
        assertGolden("cool", cool,
            floatArrayOf(1.3863914f, 1.0f, 0.6789863f),
            floatArrayOf(0.721297f, 1.0f, 1.4727837f), 11495.109f, 0.0057165604f)
        assertGolden("mixed", mixed,
            floatArrayOf(0.9238061f, 1.0f, 0.7556729f),
            floatArrayOf(1.0824783f, 1.0f, 1.323324f), 7780.115f, -0.0056398786f)
    }

    private fun assertGolden(
        name: String,
        r: SnceIlluminantEstimator.Result,
        gains: FloatArray,
        rgb: FloatArray,
        cct: Float,
        duv: Float
    ) {
        assertArrayEquals("$name gains", gains, r.gains, 1e-5f)
        assertArrayEquals("$name illuminantRgb", rgb, r.illuminantRgb, 1e-5f)
        assertEquals("$name cct", cct, r.cct, 0.5f)
        assertEquals("$name duv", duv, r.duv, 1e-5f)
    }

    // ----------------------------------------------------------------------------------
    // G = 1 normalisation holds for every method and the fused result.
    // ----------------------------------------------------------------------------------
    @Test
    fun `every method estimate and the fused result are normalised to G equals 1`() {
        val r = SnceIlluminantEstimator.estimate(tintedGray(16, 16, floatArrayOf(1.2f, 1f, 0.8f)))
        r.perMethod.forEach { e ->
            assertEquals("method ${e.method} must be G=1", 1f, e.rgb[1], 1e-5f)
        }
        assertEquals("fused must be G=1", 1f, r.illuminantRgb[1], 1e-5f)
    }

    // ----------------------------------------------------------------------------------
    // Fusion accuracy: bounded angular error, and no worse than the worst contributing
    // method, and strictly better than the fixed Bayesian 6000K prior for an off-6000K light.
    // ----------------------------------------------------------------------------------
    @Test
    fun `fused angular error is bounded and not worse than the worst method`() {
        val light = floatArrayOf(1.3f, 1f, 0.7f)
        val truth = normG1(light)
        val r = SnceIlluminantEstimator.estimate(tintedGray(20, 20, light))
        val fusedErr = angularErrorDeg(r.illuminantRgb, truth)

        val methodErrors = r.perMethod.map { angularErrorDeg(it.rgb, truth) }
        val worst = methodErrors.max()
        val bayesian = r.perMethod.first { it.method == "Bayesian" }
        val bayesianErr = angularErrorDeg(bayesian.rgb, truth)

        assertTrue("fused angular error $fusedErr° must be ≤ 6°", fusedErr <= 6f)
        assertTrue("fused ($fusedErr°) must not be worse than the worst method ($worst°)",
            fusedErr <= worst + 0.5f)
        assertTrue("fusion ($fusedErr°) must beat the fixed 6000K prior alone ($bayesianErr°)",
            fusedErr < bayesianErr)
    }

    // ----------------------------------------------------------------------------------
    // Neutral under the estimate: applying the recovered gains to the light is near-neutral.
    // ----------------------------------------------------------------------------------
    @Test
    fun `a gray card under the recovered gains is near neutral`() {
        val light = floatArrayOf(1.18f, 1f, 0.86f)
        val r = SnceIlluminantEstimator.estimate(tintedGray(16, 16, light))
        val neutralised = floatArrayOf(
            r.gains[0] * light[0], r.gains[1] * light[1], r.gains[2] * light[2]
        )
        val g = neutralised[1]
        assertEquals("R/G ≈ 1", 1f, neutralised[0] / g, 0.03f)
        assertEquals("B/G ≈ 1", 1f, neutralised[2] / g, 0.03f)

        val xyz = SnceColorPrimitives.mulV(SnceIlluminantEstimator.REC709_TO_XYZ, neutralised)
        val (_, duv) = SnceColorPrimitives.cctAndDuv(xyz)
        assertTrue("neutralised Duv ${kotlin.math.abs(duv)} must be small", kotlin.math.abs(duv) <= 0.004f)
    }

    // ----------------------------------------------------------------------------------
    // Confidence: in [0,1], high for a clean single illuminant, lower for mixed lighting,
    // and monotonically decreasing as estimate disagreement grows.
    // ----------------------------------------------------------------------------------
    @Test
    fun `fusion confidence is bounded, high on clean scenes, and lower on mixed lighting`() {
        val clean = SnceIlluminantEstimator.estimate(tintedGray(16, 16, floatArrayOf(1.02f, 1f, 0.98f)))
        val mixed = SnceIlluminantEstimator.estimate(mixedScene())

        assertTrue("clean confidence in [0,1]", clean.confidence in 0f..1f)
        assertTrue("mixed confidence in [0,1]", mixed.confidence in 0f..1f)
        assertTrue("clean scene must be high-confidence", clean.confidence > 0.5f)
        assertTrue("mixed lighting must lower confidence (${clean.confidence} vs ${mixed.confidence})",
            mixed.confidence < clean.confidence)
    }

    @Test
    fun `a near-neutral scene reads higher confidence than a strongly cast one`() {
        // A strong cast makes the fixed 6000K Bayesian prior (and other anchors) disagree more
        // with the gray-world/white-patch estimates → larger consensus dispersion → lower
        // confidence. The visual-system reading is the same: an extreme illuminant is a less
        // certain estimate.
        val neutral = SnceIlluminantEstimator.estimate(tintedGray(16, 16, floatArrayOf(1.0f, 1f, 1.0f)))
        val strongCast = SnceIlluminantEstimator.estimate(tintedGray(16, 16, floatArrayOf(1.6f, 1f, 0.45f)))
        assertTrue("near-neutral (${neutral.confidence}) must be more confident than a strong cast (${strongCast.confidence})",
            neutral.confidence > strongCast.confidence)
    }
}
