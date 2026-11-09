package com.neoproc.neoproccamx.snce

import com.neoproc.neoproccamx.snce.SnceCam16.SnceCam16Appearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnceMemoryColorAnchorsTest {

    private fun app(j: Float, c: Float, h: Float, s: Float = 30f) =
        SnceCam16Appearance(j = j, c = c, h = h, q = 100f, m = c, s = s, a = 0f, b = 0f)

    @Test
    fun `database carries the four skin and both sky anchors`() {
        assertEquals(4, SnceMemoryColor.anchorsFor(SnceMemoryColor.Category.SKIN).size)
        assertEquals(2, SnceMemoryColor.anchorsFor(SnceMemoryColor.Category.SKY).size)
    }

    @Test
    fun `blend moves a measured colour toward its anchor`() {
        val caucasian = SnceMemoryColor.DATABASE.first { it.name == "Caucasian Skin" }
        val measured = app(j = 40f, c = 18f, h = 60f)
        val before = SnceMemoryColor.anchorDistance(measured, caucasian)
        val anchored = SnceMemoryColor.blendToward(measured, caucasian, detectionConfidence = 1f)
        val after = SnceMemoryColor.anchorDistance(anchored, caucasian)
        assertTrue("anchoring must reduce CAM16 distance", after < before)
        // capped blend: never overshoots past the anchor
        assertTrue(anchored.j in 40f..caucasian.j + 0.001f)
    }

    @Test
    fun `zero confidence leaves colour unchanged`() {
        val anchor = SnceMemoryColor.DATABASE.first { it.category == SnceMemoryColor.Category.SKY }
        val measured = app(j = 50f, c = 20f, h = 200f)
        val anchored = SnceMemoryColor.blendToward(measured, anchor, detectionConfidence = 0f)
        assertEquals(measured.j, anchored.j, 1e-4f)
        assertEquals(measured.c, anchored.c, 1e-4f)
        assertEquals(measured.h, anchored.h, 1e-4f)
    }

    @Test
    fun `ITA classifies light and dark skin into the expected Fitzpatrick tiers`() {
        val veryLight = SnceSkinTone.computeIta(floatArrayOf(78f, 12f, 18f))
        val light = SnceSkinTone.computeIta(floatArrayOf(70f, 14f, 20f))
        val dark = SnceSkinTone.computeIta(floatArrayOf(32f, 9f, 15f))
        val veryDark = SnceSkinTone.computeIta(floatArrayOf(22f, 8f, 11f))
        assertEquals(1, SnceSkinTone.itaToFitzpatrick(veryLight))
        assertEquals(2, SnceSkinTone.itaToFitzpatrick(light))
        assertEquals(5, SnceSkinTone.itaToFitzpatrick(dark))
        assertEquals(6, SnceSkinTone.itaToFitzpatrick(veryDark))
    }

    @Test
    fun `Monk scale spans 1 to 10 across the skin range`() {
        assertEquals(1, SnceSkinTone.itaToMonk(60f))
        assertEquals(10, SnceSkinTone.itaToMonk(-60f))
        val mid = SnceSkinTone.itaToMonk(0f)
        assertTrue(mid in 2..9)
    }

    @Test
    fun `every Fitzpatrick tier has an anchor`() {
        (1..6).forEach { tier ->
            val anchor = SnceSkinTone.anchorFor(tier)
            assertTrue(anchor.j > 0f && anchor.c > 0f)
        }
    }

    @Test
    fun `skin precision clamps chroma floor lightness range and warm hue band`() {
        val washedOut = app(j = 85f, c = 5f, h = 70f)
        val fixed = SnceSkinTone.applySkinPrecision(washedOut)
        assertTrue("chroma floor enforced", fixed.c >= 15f)
        assertTrue("lightness ceiling enforced", fixed.j <= 75f)
        assertEquals("hue pulled to warm band edge", 50f, fixed.h, 1e-3f)

        val coolHue = app(j = 50f, c = 25f, h = 20f)
        assertEquals(33f, SnceSkinTone.applySkinPrecision(coolHue).h, 1e-3f)

        val inBand = app(j = 55f, c = 26f, h = 42f)
        assertEquals(42f, SnceSkinTone.applySkinPrecision(inBand).h, 1e-3f)
    }

    @Test
    fun `refine skin produces an in-band warm result`() {
        val measured = app(j = 48f, c = 12f, h = 58f)
        val refined = SnceSkinTone.refineSkin(
            measured,
            labForIta = floatArrayOf(60f, 14f, 18f),
            detectionConfidence = 0.9f
        )
        assertTrue(refined.h in 33f..50f)
        assertTrue(refined.c >= 15f)
        assertTrue(refined.j in 30f..75f)
    }
}
