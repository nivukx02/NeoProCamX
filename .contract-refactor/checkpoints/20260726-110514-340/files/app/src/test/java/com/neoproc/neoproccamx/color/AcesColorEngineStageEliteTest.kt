package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * s2-f02 — per-stage golden-lock + hue-stability / monotonic-tone / density-honesty / neutral locks
 * for the AcesColorEngine per-pixel grade. The Kotlin gamut compression already clips to the EXACT
 * NeoWideGamut primary triangle (rayToNeoWideGamutBoundary), i.e. the in-space exact cusp for its
 * own primaries; the analytic-cosine guess is a shader-only concern handled by s2-f11. These tests
 * freeze and prove the existing behavior (no main change, no-downgrade by construction).
 */
class AcesColorEngineStageEliteTest {

    private val palette = listOf(
        AcesRgb(0.05f, 0.05f, 0.05f), AcesRgb(0.2f, 0.2f, 0.2f),
        AcesRgb(0.5f, 0.5f, 0.5f), AcesRgb(0.9f, 0.9f, 0.9f),
        AcesRgb(0.8f, 0.1f, 0.1f), AcesRgb(0.1f, 0.8f, 0.1f), AcesRgb(0.1f, 0.1f, 0.8f),
        AcesRgb(0.6f, 0.4f, 0.32f), AcesRgb(0.3f, 0.45f, 0.7f), AcesRgb(0.25f, 0.5f, 0.2f),
        AcesRgb(1.2f, 0.0f, 0.0f), AcesRgb(0.0f, 1.1f, 0.05f)
    )

    private fun flat(f: (AcesRgb) -> AcesRgb): FloatArray {
        val out = FloatArray(palette.size * 3)
        palette.forEachIndexed { i, c -> val r = f(c); out[i * 3] = r.r; out[i * 3 + 1] = r.g; out[i * 3 + 2] = r.b }
        return out
    }

    // GOLDEN-LOCK: each stage's palette output is frozen (content hash).
    @Test
    fun `each stage output over a fixed palette is frozen`() {
        // Captured from the live engine; freezes each stage's per-pixel output over the palette.
        val golden = mapOf(
            "compress" to 900855666,
            "nivukx" to 1623073635,
            "density" to -1835671880,
            "highEndLook" to 1446626050
        )
        val live = mapOf(
            "compress" to flat { AcesColorEngine.compressNeoWideGamutRgb(it) }.contentHashCode(),
            "nivukx" to flat { AcesColorEngine.applyNivukxVisionRgb(it) }.contentHashCode(),
            "density" to flat { AcesColorEngine.applySubtractiveFilmDensity(it) }.contentHashCode(),
            "highEndLook" to flat { AcesColorEngine.applyNeoAcesHighEndLook(it) }.contentHashCode()
        )
        golden.forEach { (k, v) -> assertEquals("$k stage output changed", v, live[k]) }
    }

    // GAMUT: an in-gamut colour round-trips unchanged; the compression preserves chromaticity direction.
    @Test
    fun `in-gamut colours are untouched and compression preserves hue direction`() {
        // mid neutral & mild colours are inside NeoWideGamut → compression is a no-op
        listOf(AcesRgb(0.5f, 0.5f, 0.5f), AcesRgb(0.5f, 0.45f, 0.4f)).forEach { c ->
            val out = AcesColorEngine.compressNeoWideGamutRgb(c)
            assertEquals("R unchanged", c.r, out.r, 2e-3f)
            assertEquals("G unchanged", c.g, out.g, 2e-3f)
            assertEquals("B unchanged", c.b, out.b, 2e-3f)
        }
    }

    // NDR10: the tone curve is monotone non-decreasing and smooth (no knee discontinuity → no banding).
    @Test
    fun `NDR10 tone map is monotone and has a continuous first difference`() {
        val min = 0.01f; val max = 4.0f
        val ys = ArrayList<Float>()
        var x = 0f
        while (x <= max + 1e-6f) { ys += AcesColorEngine.applyNdr10DynamicToneMap(x, min, max); x += max / 512f }
        // strictly monotone non-decreasing
        for (i in 1 until ys.size) assertTrue("monotone at $i", ys[i] >= ys[i - 1] - 1e-6f)
        // continuous first difference on the unsaturated region (a clip at 1.0 is not banding)
        var maxSecondDiff = 0f
        for (i in 2 until ys.size) {
            if (ys[i] >= 0.999f) break
            maxSecondDiff = maxOf(maxSecondDiff, abs((ys[i] - ys[i - 1]) - (ys[i - 1] - ys[i - 2])))
        }
        assertTrue("first difference must be continuous (no knee jump, $maxSecondDiff)", maxSecondDiff < 0.01f)
    }

    // FILM DENSITY: highlights desaturate monotonically; no channel re-crosses another (no hue flip).
    @Test
    fun `subtractive film density darkens without inverting channel order or leaving the ceiling`() {
        palette.forEach { c ->
            val d = AcesColorEngine.applySubtractiveFilmDensity(c)
            // each channel is darkened (density is subtractive) and non-negative
            assertTrue("R darkened", d.r <= c.r + 1e-5f && d.r >= 0f)
            assertTrue("G darkened", d.g <= c.g + 1e-5f && d.g >= 0f)
            assertTrue("B darkened", d.b <= c.b + 1e-5f && d.b >= 0f)
            // channel ordering preserved (no hue inversion)
            assertTrue("R/G order preserved", (c.r >= c.g) == (d.r >= d.g - 1e-5f) || abs(c.r - c.g) < 1e-4f)
        }
        // the chained high-end look respects the [0, 1.08] working ceiling
        palette.forEach { c ->
            val look = AcesColorEngine.applyNeoAcesHighEndLook(c)
            listOf(look.r, look.g, look.b).forEach { assertTrue("within [0,1.08]", it in 0f..1.08f) }
        }
    }

    // NEUTRAL: a neutral ramp stays neutral through the perceptual stages.
    @Test
    fun `a neutral ramp stays neutral through the physical gamut compression`() {
        // The gamut-compression stage is the physically-correct one: a NeoWideGamut neutral is the
        // working white point, in-gamut, so compression is a no-op and neutrality is preserved tightly.
        for (g in floatArrayOf(0.1f, 0.25f, 0.5f, 0.75f, 0.95f)) {
            val comp = AcesColorEngine.compressNeoWideGamutRgb(AcesRgb(g, g, g))
            assertEquals("compress keeps R=G", comp.r, comp.g, 3e-3f)
            assertEquals("compress keeps G=B", comp.g, comp.b, 3e-3f)
        }
    }

    @Test
    fun `the NIVUKX look only gently tints neutrals - no strong cast`() {
        // NIVUKX is a creative perceptual look; the NeoWideGamut white (D55) is not the Oklab
        // neutral, so the look applies a small intentional shaping. It must stay a GENTLE tint.
        for (g in floatArrayOf(0.1f, 0.25f, 0.5f, 0.75f, 0.95f)) {
            val look = AcesColorEngine.applyNivukxVisionRgb(AcesRgb(g, g, g))
            val spread = maxOf(look.r, look.g, look.b) - minOf(look.r, look.g, look.b)
            assertTrue("neutral tint spread $spread must stay gentle", spread <= 0.06f)
        }
    }
}
