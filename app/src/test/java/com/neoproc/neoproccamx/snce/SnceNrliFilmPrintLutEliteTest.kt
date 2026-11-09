package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ELITE Rank-1 — golden locks for the hand-graded FILM-PRINT NRLI 3D-LUT (a real colorist look).
 *
 * Proves the safety + intent properties the live wiring depends on, WITHOUT touching the near-identity
 * builder or its golden ([SnceNrliAnalyticLutEliteTest] stays authoritative for buildNearIdentityLut):
 *  (a) BOUNDED: every sampled voxel stays within FILM_MAX_DEVIATION of identity — can't clip/band/black.
 *  (b) NON-TRIVIAL: it is a real print grade (a saturated midtone deviates meaningfully, > 0.04).
 *  (c) MONOTONE: the grey ramp's output luma is non-decreasing (no S-curve inversion / posterization).
 *  (d) BOUNDED GREY TINT: the split-tone tints greys ON PURPOSE, but only within a small, documented band.
 *  (e) SKIN-HUE LOCK: a skin-band swatch keeps its input hue (no sunburn/wax shift) within +/-2 deg.
 *  (f) ABI: length == size^3*3, every channel finite in [0,1] (native rejects anything else).
 */
class SnceNrliFilmPrintLutEliteTest {

    private val size = 33

    private fun hueDeg(r: Float, g: Float, b: Float): Float {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        if (d < 1e-6f) return 0f
        val h = when {
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return (h % 360f + 360f) % 360f
    }

    private fun angularDistanceDeg(a: Float, b: Float): Float = abs(((b - a + 540f) % 360f) - 180f)

    // (a) BOUNDED.
    @Test
    fun `film-print LUT is bounded within FILM_MAX_DEVIATION of identity`() {
        val lut = SnceNrliAnalyticLut.buildFilmPrintLut(size)
        var maxErr = 0f
        (0 until 500).forEach { i ->
            val c = floatArrayOf(((i * 47) % 100) / 100f, ((i * 31 + 7) % 100) / 100f, ((i * 89 + 13) % 100) / 100f)
            val out = SnceNeuralEnhancement.trilinearSampleLut(lut, c)
            for (k in 0..2) maxErr = max(maxErr, abs(out[k] - c[k]))
        }
        assertTrue(
            "film look must stay within ${SnceNrliAnalyticLut.FILM_MAX_DEVIATION} of identity (got $maxErr)",
            maxErr <= SnceNrliAnalyticLut.FILM_MAX_DEVIATION + 1e-4f
        )
    }

    // (b) NON-TRIVIAL: a real grade, not a disguised identity.
    @Test
    fun `film-print LUT is a real (non-identity) print grade`() {
        val lut = SnceNrliAnalyticLut.buildFilmPrintLut(size)
        var maxErr = 0f
        // Saturated non-skin midtones (hue 0/blue/green) where the grade bites hardest.
        listOf(
            floatArrayOf(0.85f, 0.20f, 0.20f),
            floatArrayOf(0.20f, 0.35f, 0.85f),
            floatArrayOf(0.25f, 0.80f, 0.25f)
        ).forEach { c ->
            val out = SnceNeuralEnhancement.trilinearSampleLut(lut, c)
            for (k in 0..2) maxErr = max(maxErr, abs(out[k] - c[k]))
        }
        assertTrue("film look must be a real grade (got $maxErr)", maxErr > 0.04f)
    }

    // (c) MONOTONE grey ramp (no contrast inversion).
    @Test
    fun `film-print grey ramp is monotone in output luma`() {
        val lut = SnceNrliAnalyticLut.buildFilmPrintLut(size)
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        var prev = -1f
        for (i in 0 until size) {
            val x = i.toFloat() / (size - 1)
            val out = SnceNeuralEnhancement.trilinearSampleLut(lut, floatArrayOf(x, x, x))
            val luma = lr * out[0] + lg * out[1] + lb * out[2]
            assertTrue("grey ramp luma must be non-decreasing at x=$x (prev=$prev cur=$luma)", luma >= prev - 1e-4f)
            prev = luma
        }
    }

    // (d) GREY-AXIS TINT: the split-tone tints shadow/highlight neutrals ON PURPOSE (the film signature),
    // but the tint is bounded everywhere and MID greys (the ColorChecker-relevant patches) stay ~neutral.
    @Test
    fun `film-print tints shadow-highlight greys but keeps mids neutral`() {
        val lut = SnceNrliAnalyticLut.buildFilmPrintLut(size)
        var maxTintAll = 0f
        var maxTintMid = 0f
        var maxTintAny = 0f
        for (i in 0 until size) {
            val x = i.toFloat() / (size - 1)
            val o = SnceNeuralEnhancement.trilinearSampleLut(lut, floatArrayOf(x, x, x))
            val tint = max(abs(o[0] - o[1]), max(abs(o[1] - o[2]), abs(o[0] - o[2])))
            maxTintAll = max(maxTintAll, tint)
            maxTintAny = max(maxTintAny, tint)
            if (x in 0.35f..0.65f) maxTintMid = max(maxTintMid, tint)
        }
        assertTrue("grey tint must stay bounded everywhere (got $maxTintAll)", maxTintAll <= 0.038f)
        assertTrue("mid greys must stay ~neutral (got $maxTintMid)", maxTintMid <= 0.012f)
        assertTrue("must carry a real split-tone somewhere (got $maxTintAny)", maxTintAny > 1e-3f)
    }

    // (e) SKIN-HUE LOCK: a warm skin swatch at ~51 deg keeps its hue.
    @Test
    fun `film-print holds skin hue within 2 degrees`() {
        val lut = SnceNrliAnalyticLut.buildFilmPrintLut(size)
        // A saturated warm skin/tan swatch constructed to sit at hue ~51 deg (R>G>B, B is min).
        val skin = floatArrayOf(0.80f, 0.7325f, 0.35f)
        val inHue = hueDeg(skin[0], skin[1], skin[2])
        assertEquals("test swatch must be at the skin anchor", SnceNrliAnalyticLut.SKIN_HUE_DEG, inHue, 1.5f)
        val out = SnceNeuralEnhancement.trilinearSampleLut(lut, skin)
        val outHue = hueDeg(out[0], out[1], out[2])
        assertTrue(
            "skin hue must be held within 2 deg (in=$inHue out=$outHue)",
            angularDistanceDeg(inHue, outHue) <= 2f
        )
    }

    // (f) ABI + finiteness.
    @Test
    fun `film-print LUT has the exact native ABI and finite voxels`() {
        val lut = SnceNrliAnalyticLut.buildFilmPrintLut(size)
        assertEquals("length must be size^3*3", size * size * size * 3, lut.rgb.size)
        for (v in lut.rgb) {
            assertTrue("voxel must be finite", v.isFinite())
            assertTrue("voxel must be in [0,1] (got $v)", v in 0f..1f)
        }
    }
}
