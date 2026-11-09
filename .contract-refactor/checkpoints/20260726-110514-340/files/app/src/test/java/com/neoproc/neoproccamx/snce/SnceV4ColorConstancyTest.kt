package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnceV4ColorConstancyTest {

    @Test
    fun `gaussian blur of a constant field is constant`() {
        val w = 12; val h = 10
        val channel = FloatArray(w * h) { 0.42f }
        val blurred = SnceV4ColorConstancy.gaussianBlur(channel, w, h, 5f)
        blurred.forEach { assertEquals(0.42f, it, 1e-4f) }
    }

    @Test
    fun `multi-scale retinex of a flat channel is near zero`() {
        val w = 16; val h = 16
        val channel = FloatArray(w * h) { 0.5f }
        val msr = SnceV4ColorConstancy.multiScaleRetinex(channel, w, h)
        msr.forEach { assertEquals(0f, it, 1e-3f) }
    }

    @Test
    fun `retinex responds at a contrast step`() {
        val w = 16; val h = 4
        val channel = FloatArray(w * h) { if ((it % w) < w / 2) 0.2f else 0.8f }
        val msr = SnceV4ColorConstancy.singleScaleRetinex(channel, w, h, 6f)
        // somewhere near the edge the retinex response is clearly non-zero
        val maxAbs = msr.maxOf { kotlin.math.abs(it) }
        assertTrue("edge should produce a response", maxAbs > 0.1f)
    }

    @Test
    fun `semantic constancy pulls a tinted region toward neutral`() {
        val w = 8; val h = 8
        val n = w * h
        val rgb = FloatArray(n * 3)
        // whole frame is a warm-tinted gray
        for (i in 0 until n) {
            rgb[i * 3] = 0.5f; rgb[i * 3 + 1] = 0.4f; rgb[i * 3 + 2] = 0.25f
        }
        val thumb = SnceThumbnail(w, h, rgb)
        val mask = FloatArray(n) { 1f }
        val out = SnceV4ColorConstancy.semanticRegionConstancy(
            thumb, listOf(SnceV4ColorConstancy.RegionMask(mask, confidence = 1f))
        )
        // region mean should move toward equal channels (neutral)
        var mr = 0f; var mg = 0f; var mb = 0f
        for (i in 0 until n) { mr += out[i * 3]; mg += out[i * 3 + 1]; mb += out[i * 3 + 2] }
        mr /= n; mg /= n; mb /= n
        val spreadBefore = 0.5f - 0.25f
        val spreadAfter = kotlin.math.abs(mr - mb)
        assertTrue("constancy reduces channel spread", spreadAfter < spreadBefore)
    }
}
