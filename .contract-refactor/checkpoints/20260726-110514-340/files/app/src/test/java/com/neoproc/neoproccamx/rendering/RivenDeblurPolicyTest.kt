package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RivenDeblurPolicyTest {

    private fun resolve(
        flow: Boolean = true,
        exposure: Float = 0.5f,
        noise: Float = 0.1f,
        maxFlow: Float = 12f,
        strict: Boolean = false
    ) = RivenDeblurPolicy.resolve(
        motionFieldAvailable = flow,
        exposureFraction = exposure,
        noiseSigma = noise,
        maxFlow = maxFlow,
        highEndStrict = strict
    )

    @Test
    fun noFlowDisablesDeblur() {
        val plan = resolve(flow = false)
        assertFalse(plan.active)
        assertEquals(0f, plan.params.deblurStrength, 0.0f)
        assertEquals("NO_FLOW", plan.reason)
    }

    @Test
    fun zeroExposureFractionDisablesDeblur() {
        val plan = resolve(exposure = 0f)
        assertFalse(plan.active)
        assertEquals("ZERO_EXPOSURE_FRACTION", plan.reason)
    }

    @Test
    fun activeDeblurIsConfidenceGatedWithMinKernel() {
        val plan = resolve()
        assertTrue(plan.active)
        assertEquals(2f, plan.params.minKernelPx, 0.0f)
        assertEquals("CONFIDENCE_GATED_DEBLUR", plan.reason)
    }

    @Test
    fun regularizationTracksNoiseAndReducesGain() {
        val clean = resolve(noise = 0.0f)
        val noisy = resolve(noise = 0.8f)
        // 1/SNR regularization rises with noise...
        assertTrue(noisy.params.snrInverse > clean.params.snrInverse)
        // ...and the deconvolution gain falls so it does not amplify noise.
        assertTrue(noisy.params.deblurStrength < clean.params.deblurStrength)
    }

    @Test
    fun strictRaisesStrengthAndParamsAreWellFormed() {
        val strict = resolve(strict = true, noise = 0.0f)
        val normal = resolve(strict = false, noise = 0.0f)
        assertTrue(strict.params.deblurStrength > normal.params.deblurStrength)

        val packed = resolve(maxFlow = 100f, exposure = 1.0f).params.toPushConstants()
        assertEquals(5, packed.size)
        assertTrue("maxKernelPx capped", resolve(maxFlow = 100f, exposure = 1.0f).params.maxKernelPx <= 48f)
        assertTrue(resolve().params.maxKernelPx > resolve().params.minKernelPx)
    }

    // ───────────────────── s3-f03 / CP53 — live noise-aware deblur lever ─────────────────────

    @Test
    fun liveDeblurTuningNeutralEqualsCp53NativeDefault() {
        // No-downgrade contract: zero noise + the default iteration ceiling (1) reproduce today's native
        // RivenDeblurPushConstants defaults exactly (rlIterations=1, snrInverse=0.0).
        val (iters, snr) = RivenDeblurPolicy.liveDeblurTuning(noiseSigma = 0f)
        assertEquals(1, iters)
        assertEquals(0.0f, snr, 0.0f)
        assertEquals(1, RivenDeblurPolicy.LIVE_RL_ITERATIONS_DEFAULT)
    }

    @Test
    fun liveDeblurTuningIsStrongerOnCleanAndConservativeOnNoisy() {
        // With an owner-raised ceiling, a clean frame gets more deconvolution iterations and a lower noise
        // floor; a noisy frame backs the count off toward 1 and raises the Wiener floor.
        val ceiling = 4
        val (cleanIters, cleanSnr) = RivenDeblurPolicy.liveDeblurTuning(noiseSigma = 0f, maxRlIterations = ceiling)
        val (noisyIters, noisySnr) = RivenDeblurPolicy.liveDeblurTuning(noiseSigma = 1f, maxRlIterations = ceiling)
        assertEquals(4, cleanIters)
        assertEquals(1, noisyIters)
        assertTrue("clean gets more iterations than noisy", cleanIters > noisyIters)
        assertTrue("noisier frame raises the Wiener noise floor", noisySnr > cleanSnr)
        // The lever never drops below 1 or exceeds the owner ceiling.
        listOf(0f, 0.3f, 0.7f, 1f).forEach { n ->
            val (it2, _) = RivenDeblurPolicy.liveDeblurTuning(n, ceiling)
            assertTrue("iters in [1,ceiling] for noise=$n: $it2", it2 in 1..ceiling)
        }
    }
}
