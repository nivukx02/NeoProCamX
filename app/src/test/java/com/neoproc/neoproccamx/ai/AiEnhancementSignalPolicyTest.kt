package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** s7-f09 / CP99 — pure signal-dependent enhancement helpers. Hand-verifiable. */
class AiEnhancementSignalPolicyTest {

    @Test
    fun denoisePathClassifiesEveryCaseHonestly() {
        val p = AiEnhancementSignalPolicy
        assertEquals(DenoisePath.NONE, p.denoisePath(denoiseApplied = false, neuralResultPresent = true, usedNeuralModel = true, usedGuidedFallback = false))
        assertEquals(DenoisePath.CLASSICAL_BILATERAL, p.denoisePath(true, neuralResultPresent = false, usedNeuralModel = false, usedGuidedFallback = false))
        assertEquals(DenoisePath.NEURAL, p.denoisePath(true, neuralResultPresent = true, usedNeuralModel = true, usedGuidedFallback = false))
        assertEquals(DenoisePath.GUIDED_FALLBACK, p.denoisePath(true, neuralResultPresent = true, usedNeuralModel = false, usedGuidedFallback = true))
        // Both false (e.g. empty-input early return inside the engine) => classical, not a fake neural claim.
        assertEquals(DenoisePath.CLASSICAL_BILATERAL, p.denoisePath(true, neuralResultPresent = true, usedNeuralModel = false, usedGuidedFallback = false))
    }

    @Test
    fun sharpenScaleIsFullOnCleanAndBacksOffMonotonicallyWithNoise() {
        val p = AiEnhancementSignalPolicy
        assertEquals(1f, p.signalAdaptiveSharpenScale(0f, strictHighEnd = false), 1e-6f)
        val clean = p.signalAdaptiveSharpenScale(0.05f, false)
        val mid = p.signalAdaptiveSharpenScale(0.4f, false)
        val noisy = p.signalAdaptiveSharpenScale(0.9f, false)
        assertTrue("clean >= mid", clean >= mid)
        assertTrue("mid >= noisy", mid >= noisy)
        // Bounded within [floor, 1].
        for (n in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val s = p.signalAdaptiveSharpenScale(n, false)
            assertTrue("in bounds at $n", s in p.MIN_SHARPEN_SCALE..1f)
        }
        // Very noisy saturates the floor.
        assertEquals(p.MIN_SHARPEN_SCALE, p.signalAdaptiveSharpenScale(1f, false), 1e-6f)
        assertEquals(p.MIN_SHARPEN_SCALE_STRICT, p.signalAdaptiveSharpenScale(1f, true), 1e-6f)
    }

    @Test
    fun unsharpOvershootIsHardClamped() {
        val p = AiEnhancementSignalPolicy
        assertEquals(24f, p.clampUnsharpOvershoot(100f, 24f), 0f)
        assertEquals(-24f, p.clampUnsharpOvershoot(-100f, 24f), 0f)
        assertEquals(10f, p.clampUnsharpOvershoot(10f, 24f), 0f)
    }
}
