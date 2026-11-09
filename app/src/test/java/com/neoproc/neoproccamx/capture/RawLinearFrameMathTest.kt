package com.neoproc.neoproccamx.capture

import com.neoproc.neoproccamx.capture.RawLinearFrameMath.BayerChannel
import com.neoproc.neoproccamx.capture.RawLinearFrameMath.CfaPattern
import com.neoproc.neoproccamx.rendering.MfsrFusionParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ELITE Centerpiece — goldens for the RAW 16-bit-linear input contract (the first brick of the multi-frame
 * stack). Also locks the DOMAIN CONSISTENCY that the MFSR 3σ veto depends on: the veto/variance must be fed
 * this SAME normalized [0,1] signal, and the zero-signal/zero-noise reject-all black-out is documented.
 */
class RawLinearFrameMathTest {

    // --- black/white normalize -------------------------------------------------------------------
    @Test
    fun `normalize maps black to 0, white to 1, midpoint to half`() {
        assertEquals(0f, RawLinearFrameMath.normalizeRawCode(64, 64, 1023), 0f)
        assertEquals(1f, RawLinearFrameMath.normalizeRawCode(1023, 64, 1023), 0f)
        assertEquals(0.5f, RawLinearFrameMath.normalizeRawCode((64 + 1023) / 2, 64, 1023), 1e-3f)
    }

    @Test
    fun `normalize clamps below-black and above-white and guards degenerate levels`() {
        assertEquals("below black clamps to 0", 0f, RawLinearFrameMath.normalizeRawCode(0, 64, 1023), 0f)
        assertEquals("above white clamps to 1", 1f, RawLinearFrameMath.normalizeRawCode(5000, 64, 1023), 0f)
        assertEquals("white<=black guards to 0", 0f, RawLinearFrameMath.normalizeRawCode(500, 900, 900), 0f)
    }

    // --- CFA parity, all four patterns at the 2x2 corners ----------------------------------------
    @Test
    fun `channelAt enumerates every CFA phase`() {
        // (0,0),(1,0),(0,1),(1,1)
        fun corners(p: CfaPattern) = listOf(
            RawLinearFrameMath.channelAt(0, 0, p),
            RawLinearFrameMath.channelAt(1, 0, p),
            RawLinearFrameMath.channelAt(0, 1, p),
            RawLinearFrameMath.channelAt(1, 1, p)
        )
        assertEquals(listOf(BayerChannel.R, BayerChannel.GR, BayerChannel.GB, BayerChannel.B), corners(CfaPattern.RGGB))
        assertEquals(listOf(BayerChannel.B, BayerChannel.GB, BayerChannel.GR, BayerChannel.R), corners(CfaPattern.BGGR))
        assertEquals(listOf(BayerChannel.GR, BayerChannel.R, BayerChannel.B, BayerChannel.GB), corners(CfaPattern.GRBG))
        assertEquals(listOf(BayerChannel.GB, BayerChannel.B, BayerChannel.R, BayerChannel.GR), corners(CfaPattern.GBRG))
    }

    @Test
    fun `channelAt is 2x2 periodic`() {
        for (p in CfaPattern.values()) {
            assertEquals(RawLinearFrameMath.channelAt(0, 0, p), RawLinearFrameMath.channelAt(2, 2, p))
            assertEquals(RawLinearFrameMath.channelAt(1, 0, p), RawLinearFrameMath.channelAt(3, 4, p))
        }
    }

    // --- WB gain ---------------------------------------------------------------------------------
    @Test
    fun `white balance gain scales per colour and clamps`() {
        // R gain 2.0 on a 0.4 red pixel -> 0.8; green unchanged; B gain 1.5 on 0.8 -> clamps to 1.0.
        assertEquals(0.8f, RawLinearFrameMath.applyWhiteBalanceGain(0.4f, BayerChannel.R, 2.0f, 1.0f, 1.5f), 1e-4f)
        assertEquals(0.5f, RawLinearFrameMath.applyWhiteBalanceGain(0.5f, BayerChannel.GR, 2.0f, 1.0f, 1.5f), 1e-4f)
        assertEquals(1.0f, RawLinearFrameMath.applyWhiteBalanceGain(0.8f, BayerChannel.B, 2.0f, 1.0f, 1.5f), 1e-4f)
    }

    @Test
    fun `full toSceneLinear composes normalize-classify-gain`() {
        // RGGB (0,0) is R; code midway -> 0.5; rGain 1.6 -> 0.8.
        val v = RawLinearFrameMath.toSceneLinear(
            code = (64 + 1023) / 2, x = 0, y = 0, pattern = CfaPattern.RGGB,
            blackLevel = 64, whiteLevel = 1023, rGain = 1.6f, gGain = 1.0f, bGain = 1.0f
        )
        assertEquals(0.8f, v, 2e-3f)
    }

    // --- DOMAIN CONSISTENCY with the MFSR veto (the whole point of this brick) --------------------
    @Test
    fun `normalized signal is the exact domain the MFSR veto consumes`() {
        // A normalized reference signal feeds ghostSigmaThreshold directly; a real (non-zero) noise model
        // yields a positive admit-window, so a slightly-different frame is NOT vetoed.
        val ref = RawLinearFrameMath.normalizeRawCode(600, 64, 1023) // ~0.558
        val thresh = MfsrFusionParams.ghostSigmaThreshold(ref, noiseS = 0.0048f, noiseO = 0.00075f)
        assertTrue("a real noise model must open a positive admit window", thresh > 0f)
        val nearby = ref + thresh * 0.5f
        assertTrue(
            "a within-threshold frame is admitted, not vetoed",
            !MfsrFusionParams.isGhost(frameIndex = 1, sampleSignal = nearby, referenceSignal = ref, noiseS = 0.0048f, noiseO = 0.00075f)
        )
    }

    @Test
    fun `zero signal with zero noise documents the reject-all black-out`() {
        // The dummy/zero path: signal 0, noise 0 -> threshold 0 -> every differing frame is a ghost.
        val thresh = MfsrFusionParams.ghostSigmaThreshold(0f, 0f, 0f)
        assertEquals("zero-noise threshold collapses to 0", 0f, thresh, 0f)
        assertTrue(
            "any differing non-reference sample is vetoed against the zero dummy (why the ring must be real first)",
            MfsrFusionParams.isGhost(frameIndex = 1, sampleSignal = 0.2f, referenceSignal = 0f, noiseS = 0f, noiseO = 0f)
        )
    }
}
