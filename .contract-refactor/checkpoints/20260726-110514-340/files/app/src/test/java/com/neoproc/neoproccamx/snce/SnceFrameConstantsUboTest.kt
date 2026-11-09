package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * s2-f06 / CP41 — the 23-float SNCE scene-brain block's byte-exact packer + std140 UBO image. The
 * GPU upload + OCCS routing is device-gated; this locks the JVM packer the upload mirrors:
 * round-trip fidelity, the documented field order, the std140 vec4[6] layout, and the engine's
 * per-frame block (no-downgrade — the block is computed identically; uploading it changes no pixel
 * until the device gate flips).
 */
class SnceFrameConstantsUboTest {

    private fun sample() = SnceFrameConstants(
        illuminantXyz = floatArrayOf(0.95f, 1.0f, 1.08f),
        illuminantCct = 5600f, illuminantDuv = 0.0031f, mixedLightFlag = 1f,
        illuminantSecondaryXyz = floatArrayOf(0.8f, 1.0f, 1.3f),
        sceneLuminanceLA = 200f, sceneLuminanceYb = 20f, fAdaptation = 1f, dAdaptation = 0.9f, fL = 0.7f,
        imuPitch = 10f, imuRoll = -5f, imuYaw = 2f, skyFractionPredicted = 0.3f,
        alphaAwb = 0.5f, temporalDePrev = 0.12f, sceneChangeScore = 0.2f, pqvgScorePrev = 0.85f,
        reprocessIteration = 3
    )

    @Test
    fun `pack then unpack round-trips all 23 fields`() {
        val c = sample()
        val r = SnceFrameConstants.fromFloatArray(c.toFloatArray())
        assertArrayEquals(c.illuminantXyz, r.illuminantXyz, 1e-6f)
        assertEquals(c.illuminantCct, r.illuminantCct, 1e-6f)
        assertEquals(c.illuminantDuv, r.illuminantDuv, 1e-6f)
        assertEquals(c.mixedLightFlag, r.mixedLightFlag, 1e-6f)
        assertArrayEquals(c.illuminantSecondaryXyz, r.illuminantSecondaryXyz, 1e-6f)
        assertEquals(c.sceneLuminanceLA, r.sceneLuminanceLA, 1e-6f)
        assertEquals(c.sceneLuminanceYb, r.sceneLuminanceYb, 1e-6f)
        assertEquals(c.fAdaptation, r.fAdaptation, 1e-6f)
        assertEquals(c.dAdaptation, r.dAdaptation, 1e-6f)
        assertEquals(c.fL, r.fL, 1e-6f)
        assertEquals(c.imuPitch, r.imuPitch, 1e-6f)
        assertEquals(c.imuRoll, r.imuRoll, 1e-6f)
        assertEquals(c.imuYaw, r.imuYaw, 1e-6f)
        assertEquals(c.skyFractionPredicted, r.skyFractionPredicted, 1e-6f)
        assertEquals(c.alphaAwb, r.alphaAwb, 1e-6f)
        assertEquals(c.temporalDePrev, r.temporalDePrev, 1e-6f)
        assertEquals(c.sceneChangeScore, r.sceneChangeScore, 1e-6f)
        assertEquals(c.pqvgScorePrev, r.pqvgScorePrev, 1e-6f)
        assertEquals(c.reprocessIteration, r.reprocessIteration)
    }

    @Test
    fun `the documented field order is locked`() {
        val a = sample().toFloatArray()
        assertEquals(SnceFrameConstants.FLOAT_COUNT, a.size)
        assertEquals("illuminantXyz.x@0", 0.95f, a[0], 0f)
        assertEquals("illuminantCct@3", 5600f, a[3], 0f)
        assertEquals("illuminantDuv@4", 0.0031f, a[4], 0f)
        assertEquals("mixedLightFlag@5", 1f, a[5], 0f)
        assertEquals("illuminantSecondaryXyz.x@6", 0.8f, a[6], 0f)
        assertEquals("sceneLuminanceLA@9", 200f, a[9], 0f)
        assertEquals("dAdaptation@12", 0.9f, a[12], 0f)
        assertEquals("imuPitch@14", 10f, a[14], 0f)
        assertEquals("alphaAwb@18", 0.5f, a[18], 0f)
        assertEquals("sceneChangeScore@20", 0.2f, a[20], 0f)
        assertEquals("reprocessIteration@22", 3f, a[22], 0f)
    }

    @Test
    fun `std140 block is vec4 aligned and mirrors the field order`() {
        val c = sample()
        val block = c.std140Block()
        assertEquals(SnceFrameConstants.STD140_FLOAT_COUNT, block.size)
        assertEquals("vec4[6] (24) is vec4-aligned", 0, block.size % 4)
        // first 23 floats == the documented order, the 24th is pad
        assertArrayEquals(c.toFloatArray(), block.copyOfRange(0, 23), 0f)
        assertEquals("trailing pad is zero", 0f, block[23], 0f)
    }

    @Test
    fun `the engine per-frame scene block is frozen`() {
        val w = 16; val h = 16
        val rgb = FloatArray(w * h * 3)
        for (i in 0 until w * h) {
            val base = 0.4f * (0.6f + 0.5f * ((i % 7) / 6f))
            rgb[i * 3] = base * 1.1f; rgb[i * 3 + 1] = base; rgb[i * 3 + 2] = base * 0.9f
        }
        val block = SnceV2Engine().processFrame(
            SnceThumbnail(w, h, rgb), sceneLuminanceLA = 200f, imuPitchDeg = 0f
        ).frameConstants.toFloatArray()
        assertEquals(23, block.size)
        // Frozen from the live engine (first-frame, deterministic). Uploading this block changes no
        // pixel until the device-gated OCCS scene-driving is enabled.
        val golden = floatArrayOf(
            0.9606539f, 1.0f, 0.9850409f, 5786.5234f, 0.0020286692f, 0.0f, 0.0f, 0.0f, 0.0f,
            200.0f, 20.0f, 1.0f, 0.9799869f, 1.0000001f, 0.0f, 0.0f, 0.0f, 0.06f, 0.02f,
            0.0f, 0.0f, 100.0f, 0.0f
        )
        assertArrayEquals("engine scene block changed", golden, block, 1e-3f)
    }
}
