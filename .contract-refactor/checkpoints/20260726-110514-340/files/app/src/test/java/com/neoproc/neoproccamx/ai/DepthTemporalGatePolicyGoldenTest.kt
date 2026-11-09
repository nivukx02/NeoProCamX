package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s7-f03 / CP94 — GOLDEN LOCK for the depth temporal gate + confidence-honesty policy.
 *
 * Pins that the gate is a strict no-op by DEFAULT (so the live MiDaS depth field and all
 * focus-scoring consumers are reproduced bit-for-bit) and the deterministic boundary logic when
 * the device-gated flag is enabled. The SCENE_CUT_MOTION_THRESHOLD / SYNTHETIC_CONFIDENCE_SCALE
 * constants are device-calibrated placeholders and are only asserted relative to themselves.
 */
class DepthTemporalGatePolicyGoldenTest {

    @Test
    fun gateDefaultsOffReproduceTodaysBehaviorBitForBit() {
        assertFalse(DepthTemporalGatePolicy.ENABLED_DEFAULT)
        assertFalse(DepthTemporalGatePolicy.enabled)
        // Gate off: never drops history, even at max motion; full confidence trust always.
        assertEquals(
            DepthTemporalGatePolicy.FusionDecision.PASSTHROUGH,
            DepthTemporalGatePolicy.resolveFusion(enabledFlag = false, hasPrevious = true, meanDepthMotion = 1f)
        )
        assertEquals(
            DepthTemporalGatePolicy.FusionDecision.PASSTHROUGH,
            DepthTemporalGatePolicy.resolveFusion(enabledFlag = false, hasPrevious = true, meanDepthMotion = 0f)
        )
        assertEquals(1f, DepthTemporalGatePolicy.confidenceScaleFor(enabledFlag = false, syntheticFallback = true), 0f)
        assertEquals(1f, DepthTemporalGatePolicy.confidenceScaleFor(enabledFlag = false, syntheticFallback = false), 0f)
    }

    @Test
    fun enabledSceneCutGateDropsHistoryAboveThresholdOnly() {
        assertEquals(
            DepthTemporalGatePolicy.FusionDecision.DROP_HISTORY,
            DepthTemporalGatePolicy.resolveFusion(true, true, DepthTemporalGatePolicy.SCENE_CUT_MOTION_THRESHOLD)
        )
        assertEquals(
            DepthTemporalGatePolicy.FusionDecision.PASSTHROUGH,
            DepthTemporalGatePolicy.resolveFusion(true, true, DepthTemporalGatePolicy.SCENE_CUT_MOTION_THRESHOLD - 0.01f)
        )
        // No history is never a cut.
        assertEquals(
            DepthTemporalGatePolicy.FusionDecision.PASSTHROUGH,
            DepthTemporalGatePolicy.resolveFusion(true, false, 1f)
        )
        assertTrue(DepthTemporalGatePolicy.isSceneCut(true, true, 1f))
        assertFalse(DepthTemporalGatePolicy.isSceneCut(true, true, 0f))
    }

    @Test
    fun enabledSyntheticConfidenceScaleDownweightsFallbackOnly() {
        assertEquals(
            DepthTemporalGatePolicy.SYNTHETIC_CONFIDENCE_SCALE,
            DepthTemporalGatePolicy.confidenceScaleFor(true, true), 0f
        )
        assertEquals(1f, DepthTemporalGatePolicy.confidenceScaleFor(true, false), 0f)
        assertTrue(DepthTemporalGatePolicy.SYNTHETIC_CONFIDENCE_SCALE < 1f)
    }

    @Test
    fun depthDataSyntheticFallbackDefaultsFalse() {
        val d = DepthData(mapWidth = 2, mapHeight = 2, normalizedDepth = floatArrayOf(0f, 0f, 0f, 0f))
        // New trailing field is purely additive; the live MiDaS construction (which omits it) is unchanged.
        assertFalse(d.syntheticFallback)
    }
}
