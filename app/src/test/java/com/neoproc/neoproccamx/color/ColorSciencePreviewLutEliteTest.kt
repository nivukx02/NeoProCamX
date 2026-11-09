package com.neoproc.neoproccamx.color

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * s2-f01 — the Phase-0 golden-lock of the per-profile preview LUT lattice, plus hue-linearity and
 * skin-protection invariants. The existing ColorSciencePreviewLutTest already covers cache reuse,
 * cold-start, neutral=null and per-profile properties; this freezes the actual lattice bytes and
 * proves the creative grade is hue-stable and the skin key is continuous/bounded.
 */
class ColorSciencePreviewLutEliteTest {

    private inline fun <T> withStandardQualityPolicy(block: () -> T): T {
        val pe = HighEndQualityPolicy.enabled
        val ps = HighEndQualityPolicy.strictHighEndOnly
        return try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = false
            block()
        } finally {
            HighEndQualityPolicy.enabled = pe
            HighEndQualityPolicy.strictHighEndOnly = ps
        }
    }

    private fun lutOf(configure: ColorScience.() -> Unit): Pair<FloatArray, Int> =
        ColorScience().apply(configure).getPreviewLutFloatArray() ?: error("expected a preview LUT")

    // GOLDEN-LOCK: each shipped profile's baked lattice is frozen by content hash.
    @Test
    fun `each shipped profile bakes the frozen lattice`() = withStandardQualityPolicy {
        // Frozen lattice content hash per shipped profile (standard high-fidelity preview size).
        val golden = mapOf(
            "Rec.2020" to -425624516,
            "Neo Wide Gamut" to -379826069,
            "Neo ACES High End" to 1543409959,
            "N-Log Cinema" to -1772508696,
            // Re-locked after #16: "HDR10 Native" is now classified as a BT.2020 standards (already-
            // encoded PQ) space, so the SDR grade stages / srgbToLinear no longer run on its PQ code
            // values — this is the corrected lattice (was -712698661, the washed-out encoded-domain bug).
            "HDR10 Native" to 1626574634,
            "Warm Cinema (creative)" to 1753026282
        )
        val live = linkedMapOf(
            "Rec.2020" to lutOf { loadBuiltInProfile("Rec.2020") },
            "Neo Wide Gamut" to lutOf { loadBuiltInProfile("Neo Wide Gamut") },
            "Neo ACES High End" to lutOf { loadBuiltInProfile("Neo ACES High End") },
            "N-Log Cinema" to lutOf { loadBuiltInProfile("N-Log Cinema") },
            "HDR10 Native" to lutOf { loadBuiltInProfile("HDR10 Native") },
            "Warm Cinema (creative)" to lutOf { setBuiltInLut("Warm Cinema"); setLutToggleEnabled(true) }
        )
        live.forEach { (name, lut) ->
            assertTrue("$name lut finite & in [0,1]", lut.first.all { it.isFinite() && it in 0f..1f })
            assertEquals("$name lattice changed", golden[name], lut.first.contentHashCode())
        }
    }

    // IDENTITY / NO-DOWNGRADE: neutral/WYSIWYG bakes no LUT (exact passthrough = identity).
    @Test
    fun `neutral WYSIWYG bakes no LUT - exact passthrough`() {
        assertNull(ColorScience().getPreviewLutFloatArray())
    }

    // HUE-LINEARITY: a saturated hue sweep through the hue-locked grade keeps Oklab hue (≤ 2°).
    @Test
    fun `a 36-sample saturated hue sweep preserves Oklab hue within 2 degrees`() {
        // constant-ish lightness/chroma ring in RGB; push it hard through the creative grade.
        for (i in 0 until 36) {
            val ang = i * 10.0
            val r = (0.5 + 0.42 * Math.cos(Math.toRadians(ang))).toFloat().coerceIn(0.02f, 0.98f)
            val g = (0.5 + 0.42 * Math.cos(Math.toRadians(ang - 120.0))).toFloat().coerceIn(0.02f, 0.98f)
            val b = (0.5 + 0.42 * Math.cos(Math.toRadians(ang + 120.0))).toFloat().coerceIn(0.02f, 0.98f)
            val before = ColorScience.oklabHueDegrees(r, g, b)
            val graded = ColorScience.applyHueLockedOklabGrade(
                r, g, b, saturationScale = 1.4f, contrastScale = 1.3f, brightnessOffset = 0.02f, hueShiftDegrees = 0f
            )
            val after = ColorScience.oklabHueDegrees(graded.first, graded.second, graded.third)
            val twist = abs(before - after).let { min(it, 360f - it) }
            assertTrue("hue twist $twist° at $ang° must be ≤ 2°", twist <= 2f)
        }
    }

    // SKIN KEY: the protection strength is bounded, continuous (no discontinuity), and fades to ~0
    // as the colour moves away from the skin locus.
    @Test
    fun `skin tone protection strength is bounded, continuous and fades off-locus`() {
        val cs = ColorScience()
        // bounded
        val grid = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)
        for (r in grid) for (g in grid) for (b in grid) {
            val s = cs.skinToneProtectionStrength(r, g, b)
            assertTrue("strength in [0,0.82]", s in 0f..0.82f)
        }
        // continuity: a tiny input nudge changes the strength only a little (no jump)
        var maxJump = 0f
        var t = 0f
        var prev = cs.skinToneProtectionStrength(0.6f, 0.42f, 0.34f)
        while (t <= 1f) {
            // walk from a skin colour toward neutral grey
            val r = 0.6f + (0.5f - 0.6f) * t
            val g = 0.42f + (0.5f - 0.42f) * t
            val b = 0.34f + (0.5f - 0.34f) * t
            val s = cs.skinToneProtectionStrength(r, g, b)
            maxJump = maxOf(maxJump, abs(s - prev))
            prev = s
            t += 0.02f
        }
        assertTrue("strength must be continuous (max step $maxJump)", maxJump < 0.05f)
        // off-locus (cool blue) protects far less than an on-locus skin tone
        val skin = cs.skinToneProtectionStrength(0.62f, 0.43f, 0.34f)
        val offLocus = cs.skinToneProtectionStrength(0.2f, 0.3f, 0.8f)
        assertTrue("off-locus ($offLocus) must protect less than skin ($skin)", offLocus < skin)
    }
}
