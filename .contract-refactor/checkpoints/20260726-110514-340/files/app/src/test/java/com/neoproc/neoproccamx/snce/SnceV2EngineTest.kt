package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnceV2EngineTest {

    private fun tintedGray(w: Int, h: Int, light: FloatArray): SnceThumbnail {
        val rgb = FloatArray(w * h * 3)
        for (i in 0 until w * h) {
            val b = 0.4f * (0.6f + 0.5f * ((i % 7) / 6f))
            rgb[i * 3] = b * light[0]; rgb[i * 3 + 1] = b * light[1]; rgb[i * 3 + 2] = b * light[2]
        }
        return SnceThumbnail(w, h, rgb)
    }

    @Test
    fun `frame constants pack to the documented 23 floats`() {
        val engine = SnceV2Engine()
        val result = engine.processFrame(tintedGray(16, 16, floatArrayOf(1f, 1f, 1f)), sceneLuminanceLA = 100f)
        assertEquals(SnceFrameConstants.FLOAT_COUNT, result.frameConstants.toFloatArray().size)
        assertEquals(23, result.frameConstants.toFloatArray().size)
    }

    @Test
    fun `neutral scene yields near-unity gains and daylight CCT`() {
        val engine = SnceV2Engine()
        val r = engine.processFrame(tintedGray(16, 16, floatArrayOf(1f, 1f, 1f)), sceneLuminanceLA = 100f)
        assertEquals(1f, r.awbGains[0], 0.12f)
        assertEquals(1f, r.awbGains[2], 0.12f)
        assertEquals(6500f, r.frameConstants.illuminantCct, 900f)
    }

    @Test
    fun `warm scene produces gains that neutralise the light`() {
        val engine = SnceV2Engine()
        val warm = floatArrayOf(1.4f, 1f, 0.6f)
        val r = engine.processFrame(tintedGray(16, 16, warm), sceneLuminanceLA = 100f)
        assertTrue("warm scene boosts blue gain", r.awbGains[2] > 1.1f)
        assertTrue("warm scene attenuates red gain", r.awbGains[0] < 0.95f)
        assertTrue(r.frameConstants.illuminantCct < 5500f)
    }

    @Test
    fun `steady scene settles to the slow temporal alpha`() {
        val engine = SnceV2Engine()
        val scene = tintedGray(16, 16, floatArrayOf(1.05f, 1f, 0.95f))
        engine.processFrame(scene, sceneLuminanceLA = 100f)
        val second = engine.processFrame(scene, sceneLuminanceLA = 100f)
        assertEquals("slow stability alpha on a steady scene", 0.02f, second.alphaAwb, 1e-4f)
    }

    @Test
    fun `scene cut forces an instant AWB reset`() {
        val engine = SnceV2Engine()
        engine.processFrame(tintedGray(12, 12, floatArrayOf(1f, 1f, 1f)), sceneLuminanceLA = 100f)
        val cut = engine.processFrame(
            tintedGray(12, 12, floatArrayOf(1.4f, 1f, 0.6f)),
            sceneLuminanceLA = 100f, sceneChangeScore = 0.9f
        )
        assertEquals(1.0f, cut.alphaAwb, 1e-4f)
    }

    @Test
    fun `mixed lighting flag propagates into frame constants`() {
        val w = 8; val h = 12
        val rgb = FloatArray(w * h * 3)
        val warm = floatArrayOf(1.5f, 1f, 0.55f); val cool = floatArrayOf(0.6f, 1f, 1.6f)
        for (y in 0 until h) for (x in 0 until w) {
            val light = if (x < w / 2) warm else cool
            val b = 0.4f
            val i = (y * w + x) * 3
            rgb[i] = b * light[0]; rgb[i + 1] = b * light[1]; rgb[i + 2] = b * light[2]
        }
        val r = SnceV2Engine().processFrame(SnceThumbnail(w, h, rgb), sceneLuminanceLA = 100f)
        assertEquals(1f, r.frameConstants.mixedLightFlag, 1e-4f)
    }

    @Test
    fun `per-pixel chain keeps a neutral gray close to neutral`() {
        val engine = SnceV2Engine()
        val r = engine.processFrame(tintedGray(16, 16, floatArrayOf(1f, 1f, 1f)), sceneLuminanceLA = 100f)
        val out = engine.processSampleRgb(floatArrayOf(0.5f, 0.5f, 0.5f), r.viewingConditions, 100f)
        out.forEach { assertTrue("finite & in range", it.isFinite() && it in -0.05f..2f) }
        assertEquals(0.5f, out[0], 0.12f)
        assertEquals(0.5f, out[2], 0.12f)
    }

    @Test
    fun `sky profile changes the rendered sample versus the neutral path`() {
        val engine = SnceV2Engine()
        val r = engine.processFrame(tintedGray(16, 16, floatArrayOf(1f, 1f, 1f)), sceneLuminanceLA = 100f)
        val sample = floatArrayOf(0.30f, 0.40f, 0.75f) // bluish
        val plain = engine.processSampleRgb(sample, r.viewingConditions, 100f)
        val sky = engine.processSampleRgb(
            sample, r.viewingConditions, 100f, SnceNeuralEnhancement.SemanticClass.SKY
        )
        val diff = (0..2).sumOf { kotlin.math.abs(plain[it] - sky[it]).toDouble() }
        assertTrue("sky enhancement must alter the render", diff > 1e-3)
    }

    // CP40-4A Track A — the Stage-09 scene-change score is now derived from the thumbnail.
    private fun uniformGray(w: Int, h: Int, level: Float): SnceThumbnail {
        val rgb = FloatArray(w * h * 3) { level }
        return SnceThumbnail(w, h, rgb)
    }

    @Test
    fun `scene-change score is zero on the first frame and for identical frames`() {
        val engine = SnceV2Engine()
        val a = FloatArray(64) { 0.3f }
        assertEquals(0f, engine.sceneChangeScoreFrom(null, a), 0f)
        assertEquals(0f, engine.sceneChangeScoreFrom(a, a.copyOf()), 0f)
        assertEquals(0f, engine.sceneChangeScoreFrom(FloatArray(10), a), 0f) // size mismatch
    }

    @Test
    fun `scene-change score saturates for a large luma change`() {
        val engine = SnceV2Engine()
        val dark = FloatArray(64) { 0.1f }
        val bright = FloatArray(64) { 0.6f } // MAD 0.5 * gain 4 -> clamps to 1.0
        assertEquals(1.0f, engine.sceneChangeScoreFrom(dark, bright), 1e-4f)
    }

    @Test
    fun `engine auto-detects a hard cut without an explicit score`() {
        val engine = SnceV2Engine()
        engine.processFrame(uniformGray(16, 16, 0.1f), sceneLuminanceLA = 100f)
        // No sceneChangeScore argument: the previously-dead Stage-09 path must now react on its own.
        val cut = engine.processFrame(uniformGray(16, 16, 0.6f), sceneLuminanceLA = 100f)
        assertEquals("auto-detected hard cut -> instant reset alpha", 1.0f, cut.alphaAwb, 1e-4f)
        assertEquals("frame constants carry the detected score", 1.0f, cut.frameConstants.sceneChangeScore, 1e-4f)
    }

    @Test
    fun `engine stays slow on a steady scene with no explicit score`() {
        val engine = SnceV2Engine()
        val scene = uniformGray(16, 16, 0.3f)
        engine.processFrame(scene, sceneLuminanceLA = 100f)
        val second = engine.processFrame(uniformGray(16, 16, 0.3f), sceneLuminanceLA = 100f)
        // No false positive: an unchanged scene must keep the slow flicker-free alpha.
        assertEquals(0.02f, second.alphaAwb, 1e-4f)
        assertEquals(0f, second.frameConstants.sceneChangeScore, 1e-4f)
    }
}
