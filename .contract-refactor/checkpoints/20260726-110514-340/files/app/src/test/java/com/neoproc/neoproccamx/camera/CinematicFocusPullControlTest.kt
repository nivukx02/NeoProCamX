package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * s5-f07 / CP-F8 — cinema focus-pull control behaviour + rack/ease-fidelity lock.
 *
 * Asserts: dormant-safe defaults (no pull => stepDiopters returns null), curve-faithful eased pull
 * sample-for-sample against [CameraFocusControl.calculateEasedFocusTransition], monotonic +
 * bounded-jerk + endpoint-exact motion, the LINEAR_RACK profile, clamping to the lens diopter
 * range, and the DoF/peaking/hunting readout delegating to the read-only math.
 */
class CinematicFocusPullControlTest {

    // Strict-mode bracket (mirrors CameraFocusControlTest): pins HighEndQualityPolicy so the
    // strict-sensitive rack-speed / peaking-threshold assertions are hermetic vs the live strict
    // default (enabled=true, strictHighEndOnly=true) and cannot leak across sibling suites.
    private inline fun <T> withStrictHighEndOnly(enabled: Boolean, block: () -> T): T {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = enabled
        try {
            return block()
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun `idle control steps nothing and reports inactive`() {
        val control = CinematicFocusPullControl()
        assertFalse(control.isPulling)
        assertNull(control.stepDiopters(nowElapsedMs = 0L, maxFocusDiopters = 10f))
        assertFalse(control.status().active)
    }

    @Test
    fun `trigger fails when a mark is unset or invalid`() {
        val control = CinematicFocusPullControl()
        assertFalse("no marks set", control.triggerPull(nowElapsedMs = 0L))
        control.setMarkA(2f)
        assertFalse("mark B unset", control.triggerPull(nowElapsedMs = 0L))
        assertFalse(
            "non-finite endpoint rejected",
            control.triggerPull(nowElapsedMs = 0L, fromDiopters = 2f, toDiopters = Float.NaN)
        )
        assertFalse(control.isPulling)
    }

    @Test
    fun `eased pull is curve-faithful sample-for-sample`() {
        val control = CinematicFocusPullControl()
        control.setMarkA(1f)
        control.setMarkB(8f)
        control.setEasingCurve(CinematicFocusPullControl.EasingCurve.EASE_IN_OUT)
        control.setDurationMs(1000L)
        assertTrue(control.triggerPull(nowElapsedMs = 0L))

        // Sample-for-sample against the read-only easing math at the same progress points.
        // Clean reference values for A=1,B=8: 1.0, 1.875, 4.5, 7.125.
        val expectedByMs = mapOf(0L to 1.0f, 250L to 1.875f, 500L to 4.5f, 750L to 7.125f)
        for ((t, expectedConst) in expectedByMs) {
            val expected = CameraFocusControl.calculateEasedFocusTransition(
                startDiopters = 1f,
                endDiopters = 8f,
                progress = t.toFloat() / 1000f
            )
            assertEquals("math const ${t}ms", expectedConst, expected, 1e-4f)
            val stepped = control.stepDiopters(nowElapsedMs = t, maxFocusDiopters = 10f)!!
            assertEquals("progress ${t}ms", expected, stepped, 1e-4f)
        }
    }

    @Test
    fun `eased pull is monotonic bounded-jerk and endpoint-exact`() {
        val control = CinematicFocusPullControl()
        control.setMarkA(1f)
        control.setMarkB(8f)
        control.setDurationMs(1000L)
        assertTrue(control.triggerPull(nowElapsedMs = 0L))

        val samples = ArrayList<Float>()
        for (i in 0..100) {
            val v = control.stepDiopters(nowElapsedMs = (i * 10).toLong(), maxFocusDiopters = 10f)
            assertTrue("pull should yield a value while in progress", v != null)
            samples.add(v!!)
        }
        // Endpoint-exact.
        assertEquals(1f, samples.first(), 1e-4f)
        assertEquals(8f, samples.last(), 1e-4f)
        // Monotonic non-decreasing (forward pull).
        for (i in 1 until samples.size) {
            assertTrue("monotonic at $i: ${samples[i - 1]} -> ${samples[i]}", samples[i] >= samples[i - 1] - 1e-4f)
        }
        // Bounded jerk: max |3rd difference| stays tiny for the smooth curve.
        var maxJerk = 0f
        for (i in 3 until samples.size) {
            val jerk = samples[i] - 3f * samples[i - 1] + 3f * samples[i - 2] - samples[i - 3]
            maxJerk = maxOf(maxJerk, abs(jerk))
        }
        assertTrue("bounded jerk (maxJerk=$maxJerk)", maxJerk < 0.05f)
    }

    @Test
    fun `pull lands exactly on B and deactivates at completion`() {
        val control = CinematicFocusPullControl()
        control.setMarkA(2f)
        control.setMarkB(6f)
        control.setDurationMs(500L)
        assertTrue(control.triggerPull(nowElapsedMs = 100L))

        control.stepDiopters(nowElapsedMs = 350L, maxFocusDiopters = 10f)
        assertTrue("still pulling mid-way", control.isPulling)
        val landed = control.stepDiopters(nowElapsedMs = 700L, maxFocusDiopters = 10f)!!
        assertEquals(6f, landed, 1e-4f)
        assertFalse("pull deactivates at completion", control.isPulling)
        assertNull("no further steps after completion", control.stepDiopters(nowElapsedMs = 800L, maxFocusDiopters = 10f))
    }

    @Test
    fun `linear rack pull steps at the cinematic speed and lands exactly`() = withStrictHighEndOnly(enabled = false) {
        val control = CinematicFocusPullControl()
        control.setEasingCurve(CinematicFocusPullControl.EasingCurve.LINEAR_RACK)
        control.setSpeedProfile(FocusSpeedProfile.STANDARD)
        control.setDurationMs(10_000L) // long duration so duration does not force the endpoint
        assertTrue(control.triggerPull(nowElapsedMs = 0L, fromDiopters = 0f, toDiopters = 8f))

        // STANDARD speed = 4.0 diopters/s, dt = 0.1s -> step 0.4.
        val s1 = control.stepDiopters(nowElapsedMs = 100L, maxFocusDiopters = 10f)!!
        assertEquals(0.4f, s1, 1e-4f)
        // It is monotonic and never overshoots the target.
        var prev = s1
        for (t in 200L..2200L step 100L) {
            val v = control.stepDiopters(nowElapsedMs = t, maxFocusDiopters = 10f)
            if (v == null) break
            assertTrue("no overshoot", v <= 8f + 1e-4f)
            assertTrue("monotonic rack", v >= prev - 1e-4f)
            prev = v
        }
        assertFalse("rack deactivates on reaching target", control.isPulling)
        assertEquals(8f, prev, 1e-3f)
    }

    @Test
    fun `stepped diopters are clamped to the lens range`() {
        val control = CinematicFocusPullControl()
        control.setMarkA(0f)
        control.setMarkB(8f)
        control.setDurationMs(1000L)
        assertTrue(control.triggerPull(nowElapsedMs = 0L))
        // maxFocusDiopters = 5 clamps the B endpoint.
        val landed = control.stepDiopters(nowElapsedMs = 2000L, maxFocusDiopters = 5f)!!
        assertEquals(5f, landed, 1e-4f)
    }

    @Test
    fun `readout surfaces dof peaking and hunting from the read-only math`() = withStrictHighEndOnly(enabled = false) {
        val control = CinematicFocusPullControl()
        // focus 2 diopters -> 0.5m; f=6mm N=2.8 c=0.005.
        val readout = control.buildReadout(
            focusDiopters = 2f,
            focalLengthMm = 6f,
            aperture = 2.8f,
            circleOfConfusionMm = 0.005f,
            laplacianVariance = 85f,
            peakingThreshold = 100f
        )
        assertEquals(0.5f, readout.focusDistanceMeters, 1e-4f)
        assertEquals(2.5714285f, readout.hyperfocalMeters, 1e-3f)
        val (near, far) = CameraFocusControl.calculateDepthOfField(0.5f, 6f, 2.8f, 0.005f)
        assertEquals(near, readout.nearLimitMeters, 1e-4f)
        assertEquals(far, readout.farLimitMeters, 1e-4f)
        // Peaking delegates to calculateFocusPeakingIntensity (85/100 = 0.85, non-strict).
        assertEquals(0.85f, readout.peakingIntensity, 1e-4f)
        // No history yet -> not hunting.
        assertFalse(readout.hunting)
    }

    @Test
    fun `peaking reads zero when no edge metric is provided`() {
        val control = CinematicFocusPullControl()
        val readout = control.buildReadout(
            focusDiopters = 1f,
            focalLengthMm = 6f,
            aperture = 2.8f
        )
        assertEquals(0f, readout.peakingIntensity, 0f)
    }

    @Test
    fun `status reports start end and progress of an active pull`() {
        val control = CinematicFocusPullControl()
        control.setMarkA(1f)
        control.setMarkB(8f)
        control.setDurationMs(1000L)
        control.triggerPull(nowElapsedMs = 0L)
        control.stepDiopters(nowElapsedMs = 500L, maxFocusDiopters = 10f)
        val status = control.status()
        assertTrue(status.active)
        assertEquals(1f, status.startDiopters, 1e-4f)
        assertEquals(8f, status.endDiopters, 1e-4f)
        assertEquals(0.5f, status.progress, 1e-3f)
    }

    @Test
    fun `custom stepper seam is invoked (the f08 wiring contract)`() {
        // Verifies a swapped-in stepper (what s5-f08 supplies for applyFocusPull) is used, while
        // f07 leaves the default = CameraFocusControl math.
        var invoked = false
        val stepper = CinematicFocusPullControl.CinematicFocusStepper {
            _, _, _, _, _, _, _ ->
            invoked = true
            3.5f
        }
        val control = CinematicFocusPullControl(stepper = stepper)
        control.setDurationMs(1000L)
        control.triggerPull(nowElapsedMs = 0L, fromDiopters = 0f, toDiopters = 8f)
        val v = control.stepDiopters(nowElapsedMs = 100L, maxFocusDiopters = 10f)
        assertTrue(invoked)
        assertEquals(3.5f, v!!, 1e-4f)
    }
}
