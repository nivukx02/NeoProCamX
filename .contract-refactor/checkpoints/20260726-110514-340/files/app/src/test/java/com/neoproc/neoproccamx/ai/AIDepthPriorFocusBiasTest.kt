package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP-F6 (s5-f05) — pure decision golden lock for the depth-prior hybrid AF bias.
 *
 * Locks the no-downgrade contract: default-OFF reproduces the hardware-AF baseline (no-op);
 * low confidence degrades to baseline; the one-shot lens hint is sub-gated and can fire AT MOST
 * ONCE per acquisition (never a continuous servo seizure).
 */
class AIDepthPriorFocusBiasTest {

    @Test
    fun defaultsAreOn() {
        assertTrue(AIDepthPriorFocusBias.ENABLED_DEFAULT)   // activated 2026-07-04 (user-directed)
        assertTrue(AIDepthPriorFocusBias.ONE_SHOT_LENS_HINT_DEFAULT)
        // Static defaults must mirror the constants (no accidental drift from the constant).
        assertEquals(AIDepthPriorFocusBias.ENABLED_DEFAULT, AIDepthPriorFocusBias.enabled)
        assertEquals(
            AIDepthPriorFocusBias.ONE_SHOT_LENS_HINT_DEFAULT,
            AIDepthPriorFocusBias.oneShotLensHintEnabled
        )
    }

    @Test
    fun masterGateOff_isExactBaselineNoOp_evenWithStrongDepth() {
        val d = AIDepthPriorFocusBias.resolve(
            enabledFlag = false,
            oneShotEnabledFlag = true,
            hasDepthTarget = true,
            depthConfidence = 0.99f,
            targetDiopters = 5f,
            maxDiopters = 10f,
            lensHintLatched = false
        )
        assertEquals(AIDepthPriorFocusBias.BiasDecision.NO_OP, d)
        assertFalse(d.applyRegionBias)
        assertFalse(d.fireOneShotLensHint)
    }

    @Test
    fun noDepthTarget_isBaselineNoOp() {
        val d = AIDepthPriorFocusBias.resolve(
            enabledFlag = true,
            oneShotEnabledFlag = true,
            hasDepthTarget = false,
            depthConfidence = 0.99f,
            targetDiopters = -1f,
            maxDiopters = 10f,
            lensHintLatched = false
        )
        assertEquals(AIDepthPriorFocusBias.BiasDecision.NO_OP, d)
    }

    @Test
    fun lowConfidence_degradesToBaselineNoOp() {
        val d = AIDepthPriorFocusBias.resolve(
            enabledFlag = true,
            oneShotEnabledFlag = true,
            hasDepthTarget = true,
            depthConfidence = AIDepthPriorFocusBias.MIN_BIAS_CONFIDENCE - 0.01f,
            targetDiopters = 5f,
            maxDiopters = 10f,
            lensHintLatched = false
        )
        assertEquals(AIDepthPriorFocusBias.BiasDecision.NO_OP, d)
    }

    @Test
    fun highConfidence_appliesRegionBias_withoutHintWhenSubGateOff() {
        val d = AIDepthPriorFocusBias.resolve(
            enabledFlag = true,
            oneShotEnabledFlag = false,
            hasDepthTarget = true,
            depthConfidence = 0.80f,
            targetDiopters = 6f,
            maxDiopters = 10f,
            lensHintLatched = false
        )
        assertTrue(d.applyRegionBias)
        assertFalse(d.fireOneShotLensHint)
        assertFalse(d.latchHint)
        assertEquals(6f, d.directionDiopters, 0.0001f)
    }

    @Test
    fun highConfidence_firesOneShotHintOnceThenLatches() {
        val first = AIDepthPriorFocusBias.resolve(
            enabledFlag = true,
            oneShotEnabledFlag = true,
            hasDepthTarget = true,
            depthConfidence = 0.80f,
            targetDiopters = 6f,
            maxDiopters = 10f,
            lensHintLatched = false
        )
        assertTrue(first.applyRegionBias)
        assertTrue(first.fireOneShotLensHint)
        assertTrue(first.latchHint)
        assertEquals(6f, first.directionDiopters, 0.0001f)

        // Same acquisition, hint already latched => never a continuous drive.
        val second = AIDepthPriorFocusBias.resolve(
            enabledFlag = true,
            oneShotEnabledFlag = true,
            hasDepthTarget = true,
            depthConfidence = 0.80f,
            targetDiopters = 6f,
            maxDiopters = 10f,
            lensHintLatched = true
        )
        assertTrue(second.applyRegionBias)   // region keeps following the subject
        assertFalse(second.fireOneShotLensHint)  // but the lens hint does NOT re-fire
        assertFalse(second.latchHint)
    }

    @Test
    fun directionDioptersBoundedToLensCeiling() {
        val d = AIDepthPriorFocusBias.resolve(
            enabledFlag = true,
            oneShotEnabledFlag = true,
            hasDepthTarget = true,
            depthConfidence = 0.90f,
            targetDiopters = 50f,  // absurd, must clamp
            maxDiopters = 8f,
            lensHintLatched = false
        )
        assertEquals(8f, d.directionDiopters, 0.0001f)
        assertTrue(d.fireOneShotLensHint)
    }

    @Test
    fun zeroMaxDiopters_noHintButRegionBiasStillAllowed() {
        val d = AIDepthPriorFocusBias.resolve(
            enabledFlag = true,
            oneShotEnabledFlag = true,
            hasDepthTarget = true,
            depthConfidence = 0.90f,
            targetDiopters = 6f,
            maxDiopters = 0f,  // lens cannot be driven
            lensHintLatched = false
        )
        assertTrue(d.applyRegionBias)
        assertFalse(d.fireOneShotLensHint)  // no lens drive possible => never hint
        assertEquals(-1f, d.directionDiopters, 0.0001f)
    }
}
