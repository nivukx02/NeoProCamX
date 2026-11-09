package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP-QV20 — pure unit test for the endpoint-hold decision that keeps the lens on B instead of
 * snapping back to autofocus when a cine focus-pull completes.
 */
class CinematicFocusPullEndpointHoldPolicyTest {

    @Test
    fun `persists endpoint when a pull completes this frame`() {
        val held = CinematicFocusPullEndpointHoldPolicy.resolveHold(
            wasPulling = true,
            isPullingNow = false,
            steppedOrNull = 4.2f,
            manFocusDist = 8.0f
        )
        assertEquals(4.2f, held, 0f)
    }

    @Test
    fun `no change while still pulling`() {
        val held = CinematicFocusPullEndpointHoldPolicy.resolveHold(
            wasPulling = true,
            isPullingNow = true,
            steppedOrNull = 5.5f,
            manFocusDist = 8.0f
        )
        assertEquals(8.0f, held, 0f)
    }

    @Test
    fun `no change when there was no active pull`() {
        val held = CinematicFocusPullEndpointHoldPolicy.resolveHold(
            wasPulling = false,
            isPullingNow = false,
            steppedOrNull = 5.5f,
            manFocusDist = -1.0f
        )
        assertEquals(-1.0f, held, 0f)
    }

    @Test
    fun `no change when the completing frame produced no step`() {
        val held = CinematicFocusPullEndpointHoldPolicy.resolveHold(
            wasPulling = true,
            isPullingNow = false,
            steppedOrNull = null,
            manFocusDist = 3.0f
        )
        assertEquals(3.0f, held, 0f)
    }

    @Test
    fun `rejects a non-finite endpoint and holds the incoming distance`() {
        val nan = CinematicFocusPullEndpointHoldPolicy.resolveHold(
            wasPulling = true,
            isPullingNow = false,
            steppedOrNull = Float.NaN,
            manFocusDist = 2.0f
        )
        assertEquals(2.0f, nan, 0f)
    }

    @Test
    fun `rejects a negative endpoint and holds the incoming distance`() {
        val neg = CinematicFocusPullEndpointHoldPolicy.resolveHold(
            wasPulling = true,
            isPullingNow = false,
            steppedOrNull = -0.5f,
            manFocusDist = 2.0f
        )
        assertEquals(2.0f, neg, 0f)
    }

    @Test
    fun `curve parsing maps linear_rack and defaults everything else to ease-in-out`() {
        assertEquals(
            CinematicFocusPullControl.EasingCurve.LINEAR_RACK,
            CinematicFocusPullDebugCommands.parseCurve("linear_rack")
        )
        assertEquals(
            CinematicFocusPullControl.EasingCurve.LINEAR_RACK,
            CinematicFocusPullDebugCommands.parseCurve("  LINEAR_RACK  ")
        )
        assertEquals(
            CinematicFocusPullControl.EasingCurve.EASE_IN_OUT,
            CinematicFocusPullDebugCommands.parseCurve("ease_in_out")
        )
        assertEquals(
            CinematicFocusPullControl.EasingCurve.EASE_IN_OUT,
            CinematicFocusPullDebugCommands.parseCurve(null)
        )
        assertEquals(
            CinematicFocusPullControl.EasingCurve.EASE_IN_OUT,
            CinematicFocusPullDebugCommands.parseCurve("garbage")
        )
    }

    /**
     * Integrated pull -> step -> endpoint-hold sequence, driving the real control + the hold policy
     * exactly as [CameraController.resolveEffectiveManualFocusDistance] does per frame: advance A->B
     * monotonically, land on B exactly, deactivate, and PERSIST manFocusDist at B on the completing
     * frame (no snap-back), then leave it unchanged afterwards.
     */
    @Test
    fun `pull then step then endpoint hold advances A to B and persists B on completion`() {
        val control = CinematicFocusPullControl()
        control.setMarkA(2f)
        control.setMarkB(6f)
        control.setDurationMs(500L)
        assertEquals(true, control.triggerPull(nowElapsedMs = 100L))

        // Seed manFocusDist at mark A (what triggerCinematicFocusPull does via status().startDiopters).
        var manFocusDist = control.status().startDiopters
        assertEquals(2f, manFocusDist, 1e-4f)

        // Per-frame loop mirroring resolveEffectiveManualFocusDistance.
        val steps = ArrayList<Float>()
        var completed = false
        for (t in longArrayOf(200L, 300L, 400L, 500L, 600L, 700L)) {
            val wasPulling = control.isPulling
            if (!wasPulling) {
                // Post-completion: resolve short-circuits on !isPulling; manFocusDist is untouched.
                continue
            }
            val stepped = control.stepDiopters(nowElapsedMs = t, maxFocusDiopters = 10f) ?: continue
            manFocusDist = CinematicFocusPullEndpointHoldPolicy.resolveHold(
                wasPulling = wasPulling,
                isPullingNow = control.isPulling,
                steppedOrNull = stepped,
                manFocusDist = manFocusDist
            )
            steps.add(stepped)
            if (wasPulling && !control.isPulling) completed = true
        }

        assertTrue("the pull completed within the window", completed)
        // Monotonic non-decreasing A->B.
        for (i in 1 until steps.size) {
            assertTrue("monotonic at $i: ${steps[i - 1]} -> ${steps[i]}", steps[i] >= steps[i - 1] - 1e-4f)
        }
        // Landed exactly on B.
        assertEquals(6f, steps.last(), 1e-4f)
        // Endpoint HELD: manFocusDist persisted at B, not reverted.
        assertEquals(6f, manFocusDist, 1e-4f)
        assertFalse("control deactivated after landing on B", control.isPulling)
    }

    @Test
    fun `debug command sink is a silent no-op when no camera is registered`() {
        CinematicFocusPullDebugCommands.register(null)
        // Must not throw; trigger returns false when nothing is wired.
        CinematicFocusPullDebugCommands.setMark(isA = true, diopters = 2f)
        CinematicFocusPullDebugCommands.configure(
            CinematicFocusPullControl.EasingCurve.EASE_IN_OUT,
            750L
        )
        assertEquals(false, CinematicFocusPullDebugCommands.trigger())
    }
}
