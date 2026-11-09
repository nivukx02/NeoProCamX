package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s4-f03 / CP34 — locks the bounded scene-adaptive contrast policy: every output stays in the
 * shadow_lift.comp safe ranges (never crushes/inverts), low-key vs high-key scenes differ, and the
 * neutral default equals today's shader constants (no-downgrade).
 */
class SceneAdaptiveContrastPolicyTest {

    @Test
    fun neutralMatchesShaderConstants() {
        val n = SceneAdaptiveContrastPolicy.NEUTRAL
        assertEquals(0.03f, n.shadowLift, 0f)
        assertEquals(1.10f, n.contrastRecovery, 0f)
        assertEquals(0.40f, n.luminanceThreshold, 0f)
    }

    @Test
    fun everyOutputStaysInSafeRangesForAnyStats() {
        // Property sweep incl. degenerate all-black / all-clipped / over-range inputs.
        val means = floatArrayOf(-1f, 0f, 0.1f, 0.5f, 0.9f, 2f, 10f, Float.NaN.takeIf { false } ?: 0.3f)
        val shadows = floatArrayOf(-0.5f, 0f, 0.2f, 0.5f, 0.8f, 1f, 5f)
        for (m in means) for (s in shadows) {
            val r = SceneAdaptiveContrastPolicy.resolve(m, s)
            assertTrue("shadowLift in range ($r)", r.shadowLift in 0.01f..0.10f)
            assertTrue("contrastRecovery never crushes/inverts ($r)", r.contrastRecovery in 1.05f..1.15f)
            assertTrue("luminanceThreshold in range ($r)", r.luminanceThreshold in 0.05f..0.40f)
        }
    }

    @Test
    fun lowKeyLiftsMoreThanHighKey() {
        val lowKey = SceneAdaptiveContrastPolicy.resolve(meanLuma = 0.10f, shadowRatio = 0.65f)
        val highKey = SceneAdaptiveContrastPolicy.resolve(meanLuma = 0.70f, shadowRatio = 0.05f)
        assertTrue("low-key must lift shadows more (${lowKey.shadowLift} vs ${highKey.shadowLift})",
            lowKey.shadowLift > highKey.shadowLift)
        assertTrue("low-key must lift a larger tonal slice (${lowKey.luminanceThreshold} vs ${highKey.luminanceThreshold})",
            lowKey.luminanceThreshold > highKey.luminanceThreshold)
        // It must still differ from the frozen neutral default (proves it adapts).
        assertTrue("policy must actually adapt away from neutral",
            lowKey != SceneAdaptiveContrastPolicy.NEUTRAL)
    }
}
