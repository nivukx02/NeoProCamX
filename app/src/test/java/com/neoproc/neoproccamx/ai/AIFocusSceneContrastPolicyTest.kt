package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AF-SD (F12) — pure scene-contrast mapper + no-downgrade defaults + ON-path risk-gate meaningfulness.
 */
class AIFocusSceneContrastPolicyTest {

    private val eps = 1e-4f

    @Test
    fun neutralSharpnessMapsToNeutralContrastAndComplexity() {
        assertEquals(1.0f, AIFocusSceneContrastPolicy.sceneContrastFromEnhancement(0.5f, 0f), eps)
        assertEquals(1.0f, AIFocusSceneContrastPolicy.sceneContrastFromEnhancement(0.9f, 0f), eps)
        // Neutral contrast preserves the legacy hardcoded complexity (0.5), so enabling the consumer
        // gate on a normal frame does not perturb CP-F1.
        assertEquals(0.5f, AIFocusSceneContrastPolicy.sceneComplexityFromContrast(1.0f), eps)
        assertEquals(1.0f, AIFocusSceneContrastPolicy.sceneComplexityFromContrast(0.0f), eps)
    }

    @Test
    fun flatFrameContrastFallsBelowRetriggerFloor() {
        val flat = AIFocusSceneContrastPolicy.sceneContrastFromEnhancement(0.0f, 0f)
        assertTrue(flat <= AIFocusSceneCutRiskPolicy.MIN_CONTRAST_FOR_RETRIGGER)
    }

    @Test
    fun noiseLowersContrastMonotonically_bounded() {
        val clean = AIFocusSceneContrastPolicy.sceneContrastFromEnhancement(0.4f, 0f)
        val noisy = AIFocusSceneContrastPolicy.sceneContrastFromEnhancement(0.4f, 0.8f)
        assertTrue(noisy < clean)
        assertTrue(clean in 0f..1f && noisy in 0f..1f)
    }

    @Test
    fun gatesDefaultOn_targetContrastDefaultsNeutral() {
        assertTrue(AIFocusSceneContrastFlag.enabled)
        assertTrue(SceneContrastProducerFlag.enabled)
        val target = SubjectMeteringTarget(
            focusX = 0.5f, focusY = 0.5f, subjectBounds = RectF(),
            subjectType = SubjectType.FACE, category = SubjectCategory.HUMAN,
            trackingId = 1, confidence = 0.9f, focusDepthScore = 0f,
            hdrSuggested = false, lowLightSuggested = false
        )
        assertEquals(AIFocusSceneContrastPolicy.NEUTRAL_CONTRAST, target.sceneContrast, eps)
    }

    @Test
    fun onPath_lowContrastHoldsSceneCut_neutralReacquires() {
        val low = AIFocusSceneContrastPolicy.sceneContrastFromEnhancement(0.05f, 0.6f)
        assertTrue(AIFocusSceneCutRiskPolicy.isRisk(0.9f, low))
        assertFalse(AIFocusSceneCutRiskPolicy.isRisk(0.9f, 1.0f))

        val hold = AIFocusSceneCutRiskPolicy.resolveSceneCutRisk(
            moved = 0.5f, hardReacquire = true, subjectConfidence = 0.9f,
            sceneContrast = low, sceneCutAlreadyArmed = false
        )
        assertTrue(hold.holdLastGood)
        assertFalse(hold.reacquireOnce)

        val reacquire = AIFocusSceneCutRiskPolicy.resolveSceneCutRisk(
            moved = 0.5f, hardReacquire = true, subjectConfidence = 0.9f,
            sceneContrast = 1.0f, sceneCutAlreadyArmed = false
        )
        assertTrue(reacquire.reacquireOnce)
        assertFalse(reacquire.holdLastGood)
    }
}
