package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.snce.SnceNrliAnalyticLut
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Golden contract for [VideoDevelopColorPipeline] — the offline elite develop colour core.
 *
 * Locks the NO-DOWNGRADE guarantees the record-then-develop engine relies on: an identity config is a
 * bit-exact passthrough, every stage dials linearly from that passthrough, the creative LUT stays within
 * its documented bound, greys stay neutral, and the batch path matches the per-pixel path. These invariants
 * are device-independent, so they hold on the JVM host without a camera/GPU.
 */
class VideoDevelopColorPipelineTest {

    @Test
    fun `SDR warm skin correction reduces baked red without changing neutral`() {
        val pipeline = VideoDevelopColorPipeline(
            VideoDevelopColorOptions(
                snceAppearanceStrength = 0f,
                warmSkinRedTameStrength = 0.5f,
                clampOutput = false
            )
        )
        val skin = pipeline.developPixel(0.72f, 0.52f, 0.42f)
        val neutral = pipeline.developPixel(0.5f, 0.5f, 0.5f)

        assertTrue(skin[0] - skin[1] < 0.20f)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f), neutral, 0f)
    }

    @Test
    fun identityConfigIsExactPassthrough() {
        val opts = VideoDevelopColorOptions(
            snceAppearanceStrength = 0f,
            filmPrintStrength = 0f,
            acesToneMap = false
        )
        assertTrue("identity config must report isIdentity", opts.isIdentity)
        val pipeline = VideoDevelopColorPipeline(opts)

        val out = pipeline.developPixel(0.3f, 0.5f, 0.7f)
        assertEquals(0.3f, out[0], 0f)
        assertEquals(0.5f, out[1], 0f)
        assertEquals(0.7f, out[2], 0f)

        val frame = floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f, 0.4f, 0.05f)
        val developed = pipeline.develop(frame)
        for (i in frame.indices) assertEquals(frame[i], developed[i], 0f)
    }

    @Test
    fun greyStaysNeutralUnderSnceAppearance() {
        val pipeline = VideoDevelopColorPipeline(
            VideoDevelopColorOptions(snceAppearanceStrength = 1f, clampOutput = false)
        )
        for (level in floatArrayOf(0.1f, 0.25f, 0.5f, 0.75f, 0.92f)) {
            val out = pipeline.developPixel(level, level, level)
            // A neutral input has zero chroma; the CAM16 round-trip + appearance phenomena must keep it neutral.
            assertEquals("grey R==G @ $level", out[0], out[1], 2e-3f)
            assertEquals("grey G==B @ $level", out[1], out[2], 2e-3f)
        }
    }

    @Test
    fun outputIsDeterministic() {
        val pipeline = VideoDevelopColorPipeline(
            VideoDevelopColorOptions(snceAppearanceStrength = 1f, filmPrintStrength = 1f)
        )
        val a = pipeline.developPixel(0.42f, 0.18f, 0.66f)
        val b = pipeline.developPixel(0.42f, 0.18f, 0.66f)
        for (i in 0 until 3) assertEquals(a[i], b[i], 0f)
    }

    @Test
    fun filmPrintStrengthZeroIsIdentityAndFullStaysBounded() {
        val identity = VideoDevelopColorPipeline(
            VideoDevelopColorOptions(snceAppearanceStrength = 0f, filmPrintStrength = 0f)
        )
        val full = VideoDevelopColorPipeline(
            VideoDevelopColorOptions(snceAppearanceStrength = 0f, filmPrintStrength = 1f, clampOutput = false)
        )
        val bound = SnceNrliAnalyticLut.FILM_MAX_DEVIATION + 1e-4f
        var n = 0
        for (r in 0..4) for (g in 0..4) for (b in 0..4) {
            val rr = r / 4f; val gg = g / 4f; val bb = b / 4f
            val id = identity.developPixel(rr, gg, bb)
            assertEquals(rr, id[0], 0f); assertEquals(gg, id[1], 0f); assertEquals(bb, id[2], 0f)

            val graded = full.developPixel(rr, gg, bb)
            assertTrue("film R within bound @ ($rr,$gg,$bb)", abs(graded[0] - rr) <= bound)
            assertTrue("film G within bound @ ($rr,$gg,$bb)", abs(graded[1] - gg) <= bound)
            assertTrue("film B within bound @ ($rr,$gg,$bb)", abs(graded[2] - bb) <= bound)
            n++
        }
        assertTrue(n == 125)
    }

    @Test
    fun snceStrengthBlendsLinearlyFromIdentity() {
        fun pipe(strength: Float) = VideoDevelopColorPipeline(
            VideoDevelopColorOptions(snceAppearanceStrength = strength, clampOutput = false)
        )
        val input = floatArrayOf(0.40f, 0.52f, 0.61f)
        val out0 = pipe(0f).developPixel(input[0], input[1], input[2])   // identity
        val out1 = pipe(1f).developPixel(input[0], input[1], input[2])   // full appearance
        val half = pipe(0.5f).developPixel(input[0], input[1], input[2]) // must be the exact midpoint

        for (i in 0 until 3) {
            assertEquals("out0 == input ch$i", input[i], out0[i], 0f)
            assertEquals("blend is linear ch$i", 0.5f * (out0[i] + out1[i]), half[i], 1e-5f)
        }
    }

    @Test
    fun batchDevelopMatchesPerPixel() {
        val pipeline = VideoDevelopColorPipeline(
            VideoDevelopColorOptions(snceAppearanceStrength = 1f, filmPrintStrength = 0.6f)
        )
        val frame = floatArrayOf(
            0.05f, 0.10f, 0.15f,
            0.50f, 0.50f, 0.50f,
            0.80f, 0.20f, 0.65f
        )
        val batch = pipeline.develop(frame)
        var i = 0
        while (i < frame.size) {
            val single = pipeline.developPixel(frame[i], frame[i + 1], frame[i + 2])
            assertEquals(single[0], batch[i], 0f)
            assertEquals(single[1], batch[i + 1], 0f)
            assertEquals(single[2], batch[i + 2], 0f)
            i += 3
        }
    }

    @Test
    fun clampKeepsOutputInDisplayRange() {
        val pipeline = VideoDevelopColorPipeline(
            VideoDevelopColorOptions(
                snceAppearanceStrength = 1f,
                filmPrintStrength = 1f,
                clampOutput = true
            )
        )
        for (r in 0..3) for (g in 0..3) for (b in 0..3) {
            val out = pipeline.developPixel(r / 3f, g / 3f, b / 3f)
            for (c in 0 until 3) {
                assertTrue("channel in range", out[c] in 0f..1f)
            }
        }
    }

    @Test
    fun ndrTargetSuppressesCreativeFilmLut() {
        // On the PQ/HDR target the SDR creative LUT must not apply, even at full strength.
        val ndr = VideoDevelopColorPipeline(
            VideoDevelopColorOptions(
                target = DevelopTarget.NDR_PQ_BT2020,
                snceAppearanceStrength = 0f,
                filmPrintStrength = 1f,
                acesToneMap = false,
                clampOutput = false
            )
        )
        val out = ndr.developPixel(0.3f, 0.5f, 0.7f)
        assertEquals(0.3f, out[0], 0f)
        assertEquals(0.5f, out[1], 0f)
        assertEquals(0.7f, out[2], 0f)
    }
}
