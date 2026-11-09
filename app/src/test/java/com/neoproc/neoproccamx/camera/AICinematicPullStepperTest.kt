package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.ai.AIFocusDistanceController.FocusPullStyle
import com.neoproc.neoproccamx.ai.FocusPidState
import com.neoproc.neoproccamx.ai.AIFocusDistanceController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * s5-f08 / CP-F9 — the AIFocusDistanceController-backed pull stepper that fills f07's
 * [CinematicFocusPullControl.CinematicFocusStepper] seam. Locks endpoint-exact / monotonic /
 * bounded-jerk trajectories per style, style differentiation (SNAP fast, CREEP slow), the
 * style→duration map, and bit-faithful delegation to [AIFocusDistanceController.applyFocusPull].
 */
class AICinematicPullStepperTest {

    private val dt = 0.005f // 5 ms/frame
    private val steps = 160 // 800 ms — exceeds every style duration (max CREEP=550 ms)

    private fun drive(style: FocusPullStyle, a: Float, b: Float): List<Float> {
        val stepper = AICinematicPullStepper(style)
        val out = ArrayList<Float>(steps)
        for (i in 0 until steps) {
            val v = stepper.step(
                startDiopters = a,
                endDiopters = b,
                currentDiopters = if (out.isEmpty()) a else out.last(),
                progress = i.toFloat() / steps,
                deltaTimeSeconds = if (i == 0) 0f else dt,
                curve = CinematicFocusPullControl.EasingCurve.EASE_IN_OUT,
                speedProfile = FocusSpeedProfile.CINEMATIC
            )
            out.add(v)
        }
        return out
    }

    @Test
    fun `every style is endpoint-exact and monotonic from A to B`() {
        for (style in FocusPullStyle.values()) {
            val s = drive(style, 2f, 8f)
            assertEquals("$style starts at A", 2f, s.first(), 1e-3f)
            assertEquals("$style lands exactly on B", 8f, s.last(), 1e-3f)
            for (i in 1 until s.size) {
                assertTrue("$style monotonic at $i: ${s[i - 1]} -> ${s[i]}", s[i] >= s[i - 1] - 1e-4f)
            }
        }
    }

    @Test
    fun `bounded jerk over the body of the pull (SNAP exempt on the landing segment)`() {
        for (style in FocusPullStyle.values()) {
            val s = drive(style, 2f, 8f)
            // Exclude the final landing segment (last 4 samples) where completion clamps to B.
            var maxJerk = 0f
            for (i in 3 until s.size - 4) {
                val jerk = abs(s[i] - 3f * s[i - 1] + 3f * s[i - 2] - s[i - 3])
                maxJerk = maxOf(maxJerk, jerk)
            }
            assertTrue("$style bounded jerk (maxJerk=$maxJerk)", maxJerk < 0.25f)
        }
    }

    @Test
    fun `faster styles advance further than slower styles at a fixed elapsed time`() {
        // At ~100 ms (step 20): SNAP (90 ms) has completed; CREEP (550 ms) has barely moved.
        val at = 20
        val snap = drive(FocusPullStyle.SNAP, 2f, 8f)[at]
        val rack = drive(FocusPullStyle.RACK, 2f, 8f)[at]
        val smooth = drive(FocusPullStyle.SMOOTH, 2f, 8f)[at]
        val creep = drive(FocusPullStyle.CREEP, 2f, 8f)[at]
        assertTrue("SNAP done by 100ms", snap > 7.9f)
        assertTrue("RACK ahead of SMOOTH", rack >= smooth - 1e-3f)
        assertTrue("SMOOTH ahead of CREEP", smooth >= creep - 1e-3f)
        assertTrue("CREEP still near A at 100ms", creep < 5f)
    }

    @Test
    fun `style duration map mirrors the read-only applyFocusPull profile`() {
        assertEquals(320L, AICinematicPullStepper.styleDurationMs(FocusPullStyle.SMOOTH))
        assertEquals(180L, AICinematicPullStepper.styleDurationMs(FocusPullStyle.RACK))
        assertEquals(550L, AICinematicPullStepper.styleDurationMs(FocusPullStyle.CREEP))
        assertEquals(90L, AICinematicPullStepper.styleDurationMs(FocusPullStyle.SNAP))
    }

    @Test
    fun `stepper delegates bit-faithfully to applyFocusPull`() {
        // Replicate the stepper's clock (BASE seed, +dt/frame) and assert identical diopters.
        val style = FocusPullStyle.SMOOTH
        val a = 2f; val b = 8f; val base = 1_000_000_000L
        val stepper = AICinematicPullStepper(style)
        var refState = FocusPidState(lastAppliedDiopters = a)
        var refNs = base
        for (i in 0 until 80) {
            if (i != 0) refNs += (dt * 1_000_000_000f).toLong()
            val ref = AIFocusDistanceController.applyFocusPull(
                targetDiopters = b, maxDiopters = AICinematicPullStepper.DEFAULT_MAX_DIOPTERS,
                state = refState, nowNs = refNs, pullStyle = style
            )
            refState = ref.nextState
            val got = stepper.step(
                startDiopters = a, endDiopters = b, currentDiopters = a,
                progress = 0f, deltaTimeSeconds = if (i == 0) 0f else dt,
                curve = CinematicFocusPullControl.EasingCurve.EASE_IN_OUT,
                speedProfile = FocusSpeedProfile.CINEMATIC
            )
            assertEquals("frame $i", ref.appliedDiopters, got, 1e-5f)
        }
    }
}
