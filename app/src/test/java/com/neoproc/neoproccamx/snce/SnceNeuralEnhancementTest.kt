package com.neoproc.neoproccamx.snce

import com.neoproc.neoproccamx.snce.SnceCam16.SnceCam16Appearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnceNeuralEnhancementTest {

    private fun app(j: Float, c: Float, h: Float) =
        SnceCam16Appearance(j = j, c = c, h = h, q = 100f, m = c, s = 30f, a = 0f, b = 0f)

    @Test
    fun `sky profile lifts chroma and slightly drops lightness`() {
        val out = SnceNeuralEnhancement.applyProfile(app(60f, 30f, 220f), SnceNeuralEnhancement.SemanticClass.SKY)
        assertTrue(out.c > 30f)
        assertTrue(out.j < 60f)
    }

    @Test
    fun `foliage profile shifts hue toward yellow-green`() {
        val out = SnceNeuralEnhancement.applyProfile(app(48f, 40f, 143f), SnceNeuralEnhancement.SemanticClass.FOLIAGE)
        assertEquals(140f, out.h, 1e-3f)
        assertTrue(out.c > 40f)
    }

    @Test
    fun `snow stays near-neutral and bright`() {
        val out = SnceNeuralEnhancement.applyProfile(app(70f, 30f, 220f), SnceNeuralEnhancement.SemanticClass.SNOW)
        assertTrue("snow chroma capped", out.c <= 8f)
        assertTrue("snow lightness in band", out.j in 88f..96f)
    }

    @Test
    fun `identity LUT round-trips a colour`() {
        val lut = SnceNeuralEnhancement.identityLut(33)
        val sampled = SnceNeuralEnhancement.trilinearSampleLut(lut, floatArrayOf(0.5f, 0.3f, 0.7f))
        assertEquals(0.5f, sampled[0], 1e-4f)
        assertEquals(0.3f, sampled[1], 1e-4f)
        assertEquals(0.7f, sampled[2], 1e-4f)
    }

    @Test
    fun `LUT trilinear interpolation tracks a scaled transform`() {
        val size = 9
        val maxIdx = size - 1
        val rgb = FloatArray(size * size * size * 3)
        var idx = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            rgb[idx++] = (r.toFloat() / maxIdx * 0.5f)        // halve R
            rgb[idx++] = (g.toFloat() / maxIdx)
            rgb[idx++] = (b.toFloat() / maxIdx).coerceAtMost(1f)
        }
        val lut = SnceAdaptiveLut(size, rgb)
        val sampled = SnceNeuralEnhancement.trilinearSampleLut(lut, floatArrayOf(0.8f, 0.4f, 0.6f))
        assertEquals("R halved by LUT", 0.4f, sampled[0], 1e-3f)
        assertEquals(0.4f, sampled[1], 1e-3f)
    }

    @Test
    fun `scene descriptor normalises luminance to log domain`() {
        val d = SnceNeuralEnhancement.computeSceneDescriptor(
            meanCct = 5200f, meanLuminance = 100f, indoorOutdoorFlag = 0.8f, semanticHistogram = FloatArray(8)
        )
        assertEquals(2f, d.logLuminance, 1e-4f) // log10(100) = 2
        assertEquals(5200f, d.meanCct, 1e-4f)
    }
}
