package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP-F1 — the anti-hunting stability loop. Validates (a) that AIFocusStabilityMetrics actually detects
 * focus hunting from an oscillating reported-lens series (it was previously fed a hardcoded 0f, so it
 * could never fire), and (b) the coordinator's pure re-trigger-suppression decision.
 */
class AIFocusAntiHuntingTest {

    private fun report(
        isHunting: Boolean = false,
        risk: Float = 0f,
    ) = AIFocusStabilityMetrics.FocusStabilityReport(
        isHunting = isHunting,
        huntingConfidence = if (isHunting) 0.9f else 0f,
        stabilityScore = 1f,
        focusLockConfidence = 0f,
        shouldLockFocus = false,
        huntingRiskPrediction = risk,
        recommendedLockDuration = 0L,
        focusVariance = 0f,
        subjectTrackingStability = 1f,
    )

    @Test
    fun stableLensSeriesIsNotFlaggedAsHunting() {
        val metrics = AIFocusStabilityMetrics()
        var last = report()
        for (i in 0 until 12) {
            last = metrics.analyzeFocusStability(
                currentFocusDiopters = 2.0f, // perfectly steady focus
                subjectConfidence = 0.9f,
                sceneComplexity = 0.4f,
                timestampMs = i * 33L,
            )
        }
        assertFalse("a steady lens position must not read as hunting", last.isHunting)
    }

    @Test
    fun oscillatingLensSeriesIsDetectedAsHunting() {
        val metrics = AIFocusStabilityMetrics()
        var last = report()
        for (i in 0 until 12) {
            // Alternate by 0.15 diopters (> the 0.08 oscillation threshold) => clear hunting signature.
            val d = if (i % 2 == 0) 2.0f else 2.15f
            last = metrics.analyzeFocusStability(
                currentFocusDiopters = d,
                subjectConfidence = 0.9f,
                sceneComplexity = 0.4f,
                timestampMs = i * 33L,
            )
        }
        assertTrue("an oscillating lens position must be detected as hunting", last.isHunting)
    }

    @Test
    fun resetClearsHistorySoHuntingDoesNotPersist() {
        val metrics = AIFocusStabilityMetrics()
        for (i in 0 until 12) {
            metrics.analyzeFocusStability(if (i % 2 == 0) 2.0f else 2.2f, 0.9f, 0.4f, i * 33L)
        }
        metrics.reset()
        var last = report()
        for (i in 0 until 12) {
            last = metrics.analyzeFocusStability(2.0f, 0.9f, 0.4f, 1000L + i * 33L)
        }
        assertFalse("after reset a steady series must not inherit prior hunting", last.isHunting)
    }

    @Test
    fun suppressRetriggerWhileHardwareAfIsScanning() {
        // Never pile a single-shot trigger on top of an in-progress scan.
        assertTrue(AIAutoFocusCoordinator.shouldSuppressRetrigger(report(), afScanning = true))
    }

    @Test
    fun suppressRetriggerWhenHunting() {
        assertTrue(AIAutoFocusCoordinator.shouldSuppressRetrigger(report(isHunting = true), afScanning = false))
    }

    @Test
    fun suppressRetriggerWhenPredictedHuntingRiskIsHigh() {
        assertTrue(AIAutoFocusCoordinator.shouldSuppressRetrigger(report(risk = 0.9f), afScanning = false))
    }

    @Test
    fun doNotSuppressOnASettledNonHuntingFrame() {
        // The safety property: a calm, settled, low-risk frame must NOT be suppressed (no focus miss).
        assertFalse(AIAutoFocusCoordinator.shouldSuppressRetrigger(report(isHunting = false, risk = 0.1f), afScanning = false))
    }
}
