package com.neoproc.neoproccamx.pipeline

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Locks the grade-safe RAW-LOG elite-detail pass ([RawDevelopEliteDetailPolicy] / [RawDevelopEliteDetailFlag]).
 *
 * Contract:
 *  - flag OFF ⇒ strict no-op (the develop stays byte-identical);
 *  - a flat region is left unchanged (nothing to denoise/sharpen);
 *  - a real edge gains local contrast (the sharpen/clarity actually recovers detail);
 *  - it is COLOUR-SAFE: a channel that is exactly 0 stays 0 (ratio-preserving scale), so it can only
 *    clean/sharpen — never invent colour;
 *  - output is finite, non-negative and bounded; and the pass is deterministic.
 */
class RawDevelopEliteDetailPolicyTest {

    private val lumaR = 0.2722287168f
    private val lumaG = 0.6740817658f
    private val lumaB = 0.0536895174f
    private fun luma(r: Float, g: Float, b: Float) = lumaR * r + lumaG * g + lumaB * b

    @Before
    fun setup() {
        RawDevelopEliteDetailFlag.set(true)
    }

    @After
    fun teardown() {
        RawDevelopEliteDetailFlag.set(true) // production default; never leak a toggled state across classes
    }

    @Test
    fun `flag OFF is a strict no-op`() {
        RawDevelopEliteDetailFlag.set(false)
        val plane = gradientPlane(8, 8)
        val before = plane.copyOf()
        RawDevelopEliteDetailPolicy.enhanceInPlace(plane, 8, 8)
        assertArrayEquals("flag OFF must leave the plane byte-identical", before, plane, 0f)
    }

    @Test
    fun `flag ON changes a textured plane`() {
        val plane = gradientPlane(8, 8)
        val before = plane.copyOf()
        RawDevelopEliteDetailPolicy.enhanceInPlace(plane, 8, 8)
        var moved = false
        for (i in plane.indices) if (kotlin.math.abs(plane[i] - before[i]) > 1e-5f) { moved = true; break }
        assertTrue("flag ON must actually enhance a textured plane", moved)
    }

    @Test
    fun `a flat region is left unchanged`() {
        val w = 6; val h = 6
        val plane = FloatArray(w * h * 3) { 0.42f }
        val before = plane.copyOf()
        RawDevelopEliteDetailPolicy.enhanceInPlaceForced(plane, w, h)
        for (i in plane.indices) {
            assertEquals("flat region moved at $i", before[i], plane[i], 1e-4f)
        }
    }

    @Test
    fun `an edge gains local contrast`() {
        // Left half dark (0.05), right half bright (0.80), neutral gray. Sharpen+clarity must overshoot:
        // the bright side gets brighter and the dark side darker (local contrast expands).
        val w = 8; val h = 8
        val plane = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val v = if (x < w / 2) 0.05f else 0.80f
            val o = (y * w + x) * 3
            plane[o] = v; plane[o + 1] = v; plane[o + 2] = v
        }
        var inMax = Float.NEGATIVE_INFINITY; var inMin = Float.POSITIVE_INFINITY
        for (i in 0 until w * h) {
            val l = luma(plane[i * 3], plane[i * 3 + 1], plane[i * 3 + 2])
            if (l > inMax) inMax = l; if (l < inMin) inMin = l
        }
        RawDevelopEliteDetailPolicy.enhanceInPlaceForced(plane, w, h)
        var outMax = Float.NEGATIVE_INFINITY; var outMin = Float.POSITIVE_INFINITY
        for (i in 0 until w * h) {
            val l = luma(plane[i * 3], plane[i * 3 + 1], plane[i * 3 + 2])
            if (l > outMax) outMax = l; if (l < outMin) outMin = l
        }
        assertTrue("bright side should overshoot up ($outMax !> $inMax)", outMax > inMax)
        assertTrue("dark side should overshoot down ($outMin !< $inMin)", outMin < inMin)
    }

    @Test
    fun `zero channels stay zero (colour-safe)`() {
        // Only the R channel is lit (varying); G and B are exactly 0 everywhere. The colour-preserving
        // scale must keep G and B at 0 (never invent colour).
        val w = 8; val h = 8
        val plane = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 3
            plane[o] = 0.1f + 0.7f * (x.toFloat() / (w - 1)) // R ramps
            plane[o + 1] = 0f
            plane[o + 2] = 0f
        }
        RawDevelopEliteDetailPolicy.enhanceInPlaceForced(plane, w, h)
        for (i in 0 until w * h) {
            assertEquals("G must stay 0 at $i", 0f, plane[i * 3 + 1], 0f)
            assertEquals("B must stay 0 at $i", 0f, plane[i * 3 + 2], 0f)
            assertTrue("R must stay positive at $i", plane[i * 3] > 0f)
        }
    }

    @Test
    fun `output is finite non-negative and bounded`() {
        val w = 10; val h = 7
        val plane = gradientPlane(w, h)
        RawDevelopEliteDetailPolicy.enhanceInPlaceForced(plane, w, h)
        for (v in plane) {
            assertTrue("must be finite", v.isFinite())
            assertTrue("must be non-negative ($v)", v >= 0f)
            // Input peaks at ~1.0; the luma factor is clamped to <=2.0, so nothing can exceed ~2.1.
            assertTrue("must stay bounded ($v)", v <= 2.1f)
        }
    }

    @Test
    fun `is deterministic`() {
        val a = gradientPlane(9, 9)
        val b = a.copyOf()
        RawDevelopEliteDetailPolicy.enhanceInPlaceForced(a, 9, 9)
        RawDevelopEliteDetailPolicy.enhanceInPlaceForced(b, 9, 9)
        assertArrayEquals(a, b, 0f)
    }

    @Test
    fun `frames smaller than the kernel are left unchanged`() {
        val plane = FloatArray(2 * 2 * 3) { 0.3f + 0.1f * it }
        val before = plane.copyOf()
        RawDevelopEliteDetailPolicy.enhanceInPlaceForced(plane, 2, 2)
        assertArrayEquals(before, plane, 0f)
    }

    /** A deterministic textured plane (diagonal ramps per channel) in scene-linear-ish [0,1]. */
    private fun gradientPlane(w: Int, h: Int): FloatArray {
        val plane = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 3
            plane[o] = 0.05f + 0.9f * ((x + y) % 5) / 4f
            plane[o + 1] = 0.05f + 0.9f * ((x * 2 + y) % 4) / 3f
            plane[o + 2] = 0.05f + 0.9f * ((x + y * 3) % 3) / 2f
        }
        return plane
    }
}
