package com.neoproc.neoproccamx.ai

import com.neoproc.neoproccamx.ai.AIFocusDistanceController.FocusPullStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s5-f08 / CP-F9 — locks the READ-ONLY [AIFocusDistanceController] focus-pull API that the cine
 * stepper wires: each named style reproduces its mapped (duration, easing) profile sample-for-sample,
 * adaptive duration scales monotonically with pull distance, and the predictive estimate leads a
 * moving subject. No production code here — these pin the existing math so the f08 wiring is faithful.
 */
class AIFocusPullStyleProfileTest {

    private val base = 1_000_000_000L
    private val a = 2f
    private val b = 8f
    private val max = 20f // == AICinematicPullStepper.DEFAULT_MAX_DIOPTERS

    // The style→duration(ms) AIFocusDistanceController.applyFocusPull uses internally.
    private val durationMs = mapOf(
        FocusPullStyle.SMOOTH to 320L,
        FocusPullStyle.RACK to 180L,
        FocusPullStyle.CREEP to 550L,
        FocusPullStyle.SNAP to 90L
    )

    /** Sample applyFocusPull at exactly 25% of the style's duration (A→B). */
    private fun sampleAtQuarter(style: FocusPullStyle): Float {
        var state = FocusPidState(lastAppliedDiopters = a)
        val r1 = AIFocusDistanceController.applyFocusPull(b, max, state, base, style) // arms transition
        state = r1.nextState
        val quarterNs = durationMs.getValue(style) * 1_000_000L / 4
        return AIFocusDistanceController.applyFocusPull(b, max, state, base + quarterNs, style).appliedDiopters
    }

    @Test
    fun `each style reproduces its mapped easing curve at 25 percent progress`() {
        // delta = 6; expected = a + 6 * easing(0.25) for each style's curve.
        assertEquals("SMOOTH=EASE_IN_OUT_CUBIC", 2.375f, sampleAtQuarter(FocusPullStyle.SMOOTH), 1e-3f)
        assertEquals("RACK=EASE_IN_OUT_QUAD", 2.75f, sampleAtQuarter(FocusPullStyle.RACK), 1e-3f)
        assertEquals("CREEP=SMOOTHERSTEP", 2.6210938f, sampleAtQuarter(FocusPullStyle.CREEP), 1e-3f)
        assertEquals("SNAP=EASE_OUT_QUAD", 4.625f, sampleAtQuarter(FocusPullStyle.SNAP), 1e-3f)
    }

    @Test
    fun `each style is endpoint-exact when driven frame-by-frame to and past its duration`() {
        // applyFocusPull lands on B only when stepped per-frame (current creeps within the apply
        // threshold, then the completion path returns the target exactly) — exactly how the live
        // per-frame stepper drives it. A single jump to start+duration hits the transition boundary.
        for (style in FocusPullStyle.values()) {
            var state = FocusPidState(lastAppliedDiopters = a)
            val durNs = durationMs.getValue(style) * 1_000_000L
            val dtNs = 5_000_000L // 5 ms/frame
            var applied = a
            var t = 0L
            while (t <= durNs + dtNs * 4) {
                val r = AIFocusDistanceController.applyFocusPull(b, max, state, base + t, style)
                state = r.nextState
                applied = r.appliedDiopters
                t += dtNs
            }
            assertEquals("$style lands on B", b, applied, 1e-3f)
        }
    }

    @Test
    fun `adaptive transition duration scales monotonically with pull distance`() {
        val small = AIFocusDistanceController.calculateAdaptiveTransitionDuration(0f, 0.5f, MotionPattern.LINEAR, 10f)
        val mid = AIFocusDistanceController.calculateAdaptiveTransitionDuration(0f, 3f, MotionPattern.LINEAR, 10f)
        val large = AIFocusDistanceController.calculateAdaptiveTransitionDuration(0f, 8f, MotionPattern.LINEAR, 10f)
        assertEquals(180L, small)
        assertEquals(320L, mid)
        assertEquals(550L, large)
        assertTrue("monotonic with distance", small < mid && mid < large)
    }

    @Test
    fun `predictive target leads a moving subject and is neutral at zero velocity`() {
        val noLead = AIFocusDistanceController.estimateTargetDiopters(0.4f, 10f)
        val approaching = AIFocusDistanceController.estimateTargetDioptersPredictive(0.4f, 0.5f, 10f)
        val still = AIFocusDistanceController.estimateTargetDioptersPredictive(0.4f, 0f, 10f)
        assertNotNull(noLead); assertNotNull(approaching); assertNotNull(still)
        assertTrue("a forward-moving subject leads the focus", approaching!! > noLead!!)
        assertEquals("zero velocity == no lead", noLead, still!!, 1e-4f)
    }
}
