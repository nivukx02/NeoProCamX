package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP-F5 — pure scene-cut / risk-gate decision policy (s5-f04). These exercise the
 * decision math in isolation; the coordinator only acts on it when the
 * SCENE_CUT_RISK_GATING_ENABLED gate is enabled device-side (default OFF, so CP-F1
 * output is unchanged).
 */
class AIFocusSceneCutRiskPolicyTest {

    @Test
    fun lowConfidenceHoldsLastGoodInsteadOfRetrigger() {
        val d = AIFocusSceneCutRiskPolicy.resolveSceneCutRisk(
            moved = 0.30f,
            hardReacquire = true,
            subjectConfidence = 0.18f, // below confidence floor => risk
            sceneContrast = 1.0f,
            sceneCutAlreadyArmed = false
        )
        assertTrue("low confidence must HOLD the last good target", d.holdLastGood)
        assertFalse("a held frame must not also fire a deliberate re-acquire", d.reacquireOnce)
    }

    @Test
    fun lowContrastHoldsLastGoodInsteadOfRetrigger() {
        val d = AIFocusSceneCutRiskPolicy.resolveSceneCutRisk(
            moved = 0.02f,
            hardReacquire = false,
            subjectConfidence = 0.9f,
            sceneContrast = 0.05f, // below contrast floor => risk
            sceneCutAlreadyArmed = false
        )
        assertTrue("low contrast must HOLD the last good target", d.holdLastGood)
        assertFalse(d.reacquireOnce)
    }

    @Test
    fun hardCutTriggersExactlyOneDeliberateReacquire() {
        // First frame of a whip-pan / hard cut: huge subject discontinuity + hardReacquire.
        val first = AIFocusSceneCutRiskPolicy.resolveSceneCutRisk(
            moved = 0.55f, // >> SCENE_CUT_MOVE_THRESHOLD
            hardReacquire = true,
            subjectConfidence = 0.9f,
            sceneContrast = 1.0f,
            sceneCutAlreadyArmed = false
        )
        assertTrue("a detected hard cut must arm a single deliberate re-acquire", first.reacquireOnce)
        assertFalse(first.holdLastGood)

        // Subsequent frames of the SAME discontinuity (already armed) must NOT keep re-acquiring
        // => no pump train.
        val second = AIFocusSceneCutRiskPolicy.resolveSceneCutRisk(
            moved = 0.50f,
            hardReacquire = true,
            subjectConfidence = 0.9f,
            sceneContrast = 1.0f,
            sceneCutAlreadyArmed = true
        )
        assertFalse("the re-acquire is single-shot: an already-armed cut must not re-fire", second.reacquireOnce)
    }

    @Test
    fun normalFollowNeitherHoldsNorReacquires() {
        // Calm, confident, in-contrast, sub-cut motion: the gate must be transparent (defers to
        // the existing CP-F1 path).
        val d = AIFocusSceneCutRiskPolicy.resolveSceneCutRisk(
            moved = 0.03f,
            hardReacquire = false,
            subjectConfidence = 0.85f,
            sceneContrast = 0.8f,
            sceneCutAlreadyArmed = false
        )
        assertFalse(d.holdLastGood)
        assertFalse(d.reacquireOnce)
    }

    @Test
    fun riskGateTakesPrecedenceOverSceneCut() {
        // A hard cut that is ALSO low-confidence (transient garbage) must HOLD, not chase.
        val d = AIFocusSceneCutRiskPolicy.resolveSceneCutRisk(
            moved = 0.60f,
            hardReacquire = true,
            subjectConfidence = 0.10f,
            sceneContrast = 1.0f,
            sceneCutAlreadyArmed = false
        )
        assertTrue("low-confidence cut is transient garbage => HOLD", d.holdLastGood)
        assertFalse("never chase a low-confidence cut", d.reacquireOnce)
    }

    @Test
    fun isSceneCutFlagsOnlyLargeDiscontinuityWithHardReacquire() {
        assertTrue(AIFocusSceneCutRiskPolicy.isSceneCut(moved = 0.55f, hardReacquire = true))
        assertFalse("sub-threshold motion is not a cut", AIFocusSceneCutRiskPolicy.isSceneCut(moved = 0.05f, hardReacquire = true))
        assertFalse("large move without a hard-reacquire decision is not a cut", AIFocusSceneCutRiskPolicy.isSceneCut(moved = 0.55f, hardReacquire = false))
    }

    @Test
    fun defaultContrastNeutralOneNeverGatesAsLowContrast() {
        // The coordinator passes sceneContrast=1.0f today (no real AF contrast producer); that
        // must never trip the low-contrast hold (no-downgrade default).
        val d = AIFocusSceneCutRiskPolicy.resolveSceneCutRisk(
            moved = 0.02f,
            hardReacquire = false,
            subjectConfidence = 0.9f,
            sceneContrast = 1.0f,
            sceneCutAlreadyArmed = false
        )
        assertFalse(d.holdLastGood)
    }
}
