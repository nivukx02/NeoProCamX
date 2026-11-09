package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s3-f04 / CP91 — locks the consolidated OmniClarity motion-stack tuning resolver: the no-downgrade
 * contract (neutral == the CP-locked native defaults), monotonic thermal backoff, scene-cut alpha gating,
 * and noise-aware deblur. This is the single test that proves the governor cannot silently degrade output.
 */
class OmniClarityMotionTuningResolverTest {

    @Test
    fun neutralInputsEqualTheNativeDefault() {
        // The whole no-downgrade contract in one assertion: a cool/idle device reproduces the exact
        // CP-locked native defaults (refine 8, base 0.0, max 0.9, rlIterations 1, snrInverse 0.0).
        val neutral = OmniClarityMotionTuningResolver.resolve()
        assertEquals(OmniClarityMotionTuning.NATIVE_DEFAULT, neutral)
        assertEquals(8, neutral.refineIterations)
        assertFalse(neutral.sceneCutActive)
        assertEquals(0.0f, neutral.baseTemporalAlpha, 0.0f)
        assertEquals(0.9f, neutral.maxTemporalAlpha, 0.0f)
        assertEquals(1, neutral.rlIterations)
        assertEquals(0.0f, neutral.snrInverse, 0.0f)
    }

    @Test
    fun thermalAndBudgetPressureBackOffMonotonically() {
        val neutral = OmniClarityMotionTuningResolver.resolve()
        val overBudget = OmniClarityMotionTuningResolver.resolve(frameBudgetMs = 16.6, recentFrameMs = 24.0)
        val thermal = OmniClarityMotionTuningResolver.resolve(thermalThrottling = true)
        // Refine iterations never rise under pressure, and the temporal max blend backs off under thermal.
        assertTrue("over-budget refine <= neutral", overBudget.refineIterations <= neutral.refineIterations)
        assertTrue("thermal refine <= over-budget", thermal.refineIterations <= overBudget.refineIterations)
        assertTrue("thermal backs off the temporal max blend", thermal.maxTemporalAlpha < neutral.maxTemporalAlpha)
        assertTrue("thermal still does fewer-but-some refine iters", thermal.refineIterations >= 2)
    }

    @Test
    fun sceneCutGatesTemporalBlendAndFlagsTheFlowGate() {
        val cut = OmniClarityMotionTuningResolver.resolve(sceneCut = true)
        // No cross-cut ghosting: temporal blend is forced to zero...
        assertEquals(0.0f, cut.baseTemporalAlpha, 0.0f)
        assertEquals(0.0f, cut.maxTemporalAlpha, 0.0f)
        // ...and the Argus flow gate is flagged (the dispatch forces previousValid=0 for the cut frame).
        assertTrue(cut.sceneCutActive)
    }

    @Test
    fun thermalSeverityThresholdOnlyTripsAtSevere() {
        // CP-QV15: the pure severity→throttle seam the adaptive guard feeds resolve(). Mild/moderate warm-up
        // (0..2) must NOT throttle; SEVERE and above (3..5) must. Locks the backoff threshold at SEVERE.
        listOf(0, 1, 2).forEach { s ->
            assertFalse("severity $s must not throttle", OmniClarityMotionTuningResolver.shouldThrottleForThermal(s))
        }
        listOf(3, 4, 5).forEach { s ->
            assertTrue("severity $s must throttle", OmniClarityMotionTuningResolver.shouldThrottleForThermal(s))
        }
        assertEquals(3, OmniClarityMotionTuningResolver.THERMAL_BACKOFF_SEVERITY)
    }

    @Test
    fun severityMappedTuningIsNativeDefaultWhenCoolAndBacksOffWhenSevere() {
        // CP-QV15: the exact end-to-end mapping the CameraViewModel wire performs — severity -> throttle ->
        // resolve(). A cool/moderate device reproduces the CP-locked native defaults (no downgrade); a severe
        // device sheds refine iters (8->4) and the temporal max blend (0.9->0.6) for graceful degradation.
        fun tuningFor(severity: Int) = OmniClarityMotionTuningResolver.resolve(
            thermalThrottling = OmniClarityMotionTuningResolver.shouldThrottleForThermal(severity)
        )
        // Cool through moderate: identical to today.
        listOf(0, 1, 2).forEach { s ->
            assertEquals("severity $s must equal native default", OmniClarityMotionTuning.NATIVE_DEFAULT, tuningFor(s))
        }
        // Severe: the documented backoff.
        val severe = tuningFor(3)
        assertEquals(4, severe.refineIterations)
        assertEquals(0.6f, severe.maxTemporalAlpha, 0.0f)
        assertEquals(0.0f, severe.baseTemporalAlpha, 0.0f)
        assertFalse(severe.sceneCutActive)
    }

    @Test
    fun noiseRaisesTheWienerFloorAndStaysConservative() {
        val clean = OmniClarityMotionTuningResolver.resolve(noiseSigma = 0f, maxRlIterations = 4)
        val noisy = OmniClarityMotionTuningResolver.resolve(noiseSigma = 0.9f, maxRlIterations = 4)
        // Noisier source -> higher Wiener noise floor + fewer deconvolution iterations (grain never amplified).
        assertTrue("noise raises the Wiener floor", noisy.snrInverse > clean.snrInverse)
        assertTrue("noise backs off iterations", noisy.rlIterations <= clean.rlIterations)
        // At the default ceiling (1) the deblur lever is always today's single step regardless of noise.
        listOf(0f, 0.5f, 1f).forEach { n ->
            assertEquals(1, OmniClarityMotionTuningResolver.resolve(noiseSigma = n).rlIterations)
        }
    }
}
