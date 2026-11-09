package com.neoproc.neoproccamx.snce

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * s2-f05 elite locks for [SnceLiveColorController]: golden-lock of the gate-off nudge triple
 * (no-downgrade), the ±0.05/±0.06 clamp invariant, Hunt monotonicity, and the confidence gate that
 * can only ever REDUCE the nudge (never widen the ceiling), exposing confidence for the s2-f04 ceiling.
 */
class SnceLiveColorControllerEliteTest {

    private fun yuv(y: Int, u: Int, v: Int, w: Int = 8, h: Int = 8): SnceYuvThumbnail.Frame {
        val cw = (w + 1) / 2; val ch = (h + 1) / 2
        return SnceYuvThumbnail.Frame(
            w, h, ByteArray(w * h) { y.toByte() }, ByteArray(cw * ch) { u.toByte() },
            ByteArray(cw * ch) { v.toByte() }, w, 1, cw, cw, 1, 1, 8
        )
    }

    private fun nudgeFor(frame: SnceYuvThumbnail.Frame, lux: Float, gate: Boolean = false): FloatArray {
        val c = SnceLiveColorController(confidenceGatingEnabled = gate)
        c.analyze(frame, estimatedLux = lux)
        return c.currentNudge()
    }

    // GOLDEN-LOCK: gate-off nudge triple is frozen for a fixed scene set.
    @Test
    fun `gate-off nudge triple is frozen`() {
        // Frozen from the pre-s2-f05 (gate-off) controller; the confidence gate must not move these.
        assertNudge(nudgeFor(yuv(128, 128, 128), 300f), floatArrayOf(0.9947895f, 1.0012294f, 0.9672024f))
        assertNudge(nudgeFor(yuv(130, 110, 165), 300f), floatArrayOf(0.95f, 1.05f, 0.9672024f))
        assertNudge(nudgeFor(yuv(128, 150, 100), 300f), floatArrayOf(1.05f, 0.95f, 0.9672024f))
        assertNudge(nudgeFor(yuv(60, 128, 128), 6f), floatArrayOf(0.9947895f, 1.0012294f, 1.06f))
    }

    private fun assertNudge(actual: FloatArray, expected: FloatArray) {
        for (i in 0..2) assertEquals("nudge[$i]", expected[i], actual[i], 1e-4f)
    }

    // CLAMP: even an extreme cast stays inside the ±0.05 WB / ±0.06 sat ceiling.
    @Test
    fun `nudges never exceed the hard ceiling`() {
        listOf(yuv(130, 90, 200), yuv(128, 200, 60), yuv(200, 128, 128), yuv(20, 128, 128)).forEach { f ->
            val n = nudgeFor(f, 50f)
            assertTrue("red in bound", n[0] in (1f - SnceLiveColorController.MAX_WB_NUDGE - 1e-4f)..(1f + SnceLiveColorController.MAX_WB_NUDGE + 1e-4f))
            assertTrue("blue in bound", n[1] in (1f - SnceLiveColorController.MAX_WB_NUDGE - 1e-4f)..(1f + SnceLiveColorController.MAX_WB_NUDGE + 1e-4f))
            assertTrue("sat in bound", n[2] in (1f - SnceLiveColorController.MAX_SAT_NUDGE - 1e-4f)..(1f + SnceLiveColorController.MAX_SAT_NUDGE + 1e-4f))
        }
    }

    // HUNT: the saturation nudge rises monotonically as the scene gets darker.
    @Test
    fun `saturation nudge is monotone non-increasing with scene luminance`() {
        val luxes = floatArrayOf(3f, 8f, 25f, 100f, 400f)
        var prev = -1f
        // iterate bright -> dim; sat must be non-decreasing as we go dimmer
        for (lux in luxes.reversed()) {
            val sat = nudgeFor(yuv(80, 128, 128), lux)[2]
            assertTrue("sat must rise (or hold) as it darkens (lux=$lux sat=$sat prev=$prev)", sat >= prev - 1e-4f)
            prev = sat
        }
    }

    // CONFIDENCE GATE: exposed confidence in [0,1]; gating can only REDUCE the deviation;
    // the reduction equals exactly confidence × (gate-off deviation).
    @Test
    fun `confidence gating shrinks the nudge by exactly the confidence factor and never widens it`() {
        val warm = yuv(130, 110, 165)
        val off = SnceLiveColorController(confidenceGatingEnabled = false)
        off.analyze(warm, estimatedLux = 300f)
        val on = SnceLiveColorController(confidenceGatingEnabled = true)
        on.analyze(warm, estimatedLux = 300f)

        val conf = off.currentConfidence()
        assertEquals("same scene ⇒ same confidence", conf, on.currentConfidence(), 1e-5f)
        assertTrue("confidence in [0,1]", conf in 0f..1f)

        val nOff = off.currentNudge()
        val nOn = on.currentNudge()
        for (i in 0..2) {
            // (gate-on deviation) == confidence × (gate-off deviation)
            assertEquals("nudge[$i] scaled by confidence", conf * (nOff[i] - 1f), nOn[i] - 1f, 1e-4f)
            // and never larger in magnitude (no ceiling widening)
            assertTrue("nudge[$i] not widened", abs(nOn[i] - 1f) <= abs(nOff[i] - 1f) + 1e-5f)
        }
    }

    @Test
    fun `confidence defaults to full before any frame is analysed`() {
        assertEquals(1f, SnceLiveColorController().currentConfidence(), 0f)
    }

    // ── s7-f04 / CP4A: semantic-region constancy is no-op by default, budget-bounded when on ──

    @After
    fun resetRegionFlag() = SnceSemanticConstancyFlag.set(false)

    private fun redCastThumb(w: Int = 8, h: Int = 8): SnceThumbnail {
        val rgb = FloatArray(w * h * 3)
        for (i in 0 until w * h) {
            rgb[i * 3] = 0.4f * 2.0f; rgb[i * 3 + 1] = 0.4f; rgb[i * 3 + 2] = 0.4f * 0.5f
        }
        return SnceThumbnail(w, h, rgb)
    }

    private fun skinMask(w: Int = 8, h: Int = 8) =
        SnceSegmentationResult(w, h, IntArray(w * h) { SnceSemanticColorConstancy.CLASS_BODY_SKIN }, FloatArray(w * h) { 1f })

    @Test
    fun `region constancy is a no-op when its flag is off`() {
        SnceSemanticConstancyFlag.set(false)
        val c = SnceLiveColorController() // no analyze => multipliers all 1; engine flag ON by default
        c.applyRegionConstancy(skinMask(), redCastThumb())
        val grade = FloatArray(16) { it.toFloat() }
        c.injectIntoPreviewGrade(grade)
        // slots 1/4/6 multiplied by 1 => unchanged from their input index value.
        assertEquals(1f, grade[1], 0f)
        assertEquals(4f, grade[4], 0f)
        assertEquals(6f, grade[6], 0f)
    }

    @Test
    fun `region constancy folded multiplier stays within the WB budget when on`() {
        SnceSemanticConstancyFlag.set(true)
        val c = SnceLiveColorController()
        c.applyRegionConstancy(skinMask(), redCastThumb())
        val grade = FloatArray(16) { 1f }
        c.injectIntoPreviewGrade(grade)
        val lo = 1f - SnceLiveColorController.MAX_WB_NUDGE - 1e-4f
        val hi = 1f + SnceLiveColorController.MAX_WB_NUDGE + 1e-4f
        assertTrue("red folded within budget", grade[4] in lo..hi)
        assertTrue("blue folded within budget", grade[6] in lo..hi)
    }

    @Test
    fun `region constancy null mask under flag-on is a no-op`() {
        SnceSemanticConstancyFlag.set(true)
        val c = SnceLiveColorController()
        c.applyRegionConstancy(null, redCastThumb())
        val grade = FloatArray(16) { 1f }
        c.injectIntoPreviewGrade(grade)
        assertEquals(1f, grade[4], 0f)
        assertEquals(1f, grade[6], 0f)
    }
}
