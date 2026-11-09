package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP-F5 — observe-only metric counters added to AIFocusStabilityMetrics (s5-f04): pump cycles,
 * re-acquire events, and bounded follow-jerk. These are telemetry only; they never alter the
 * live decision path (default OFF gate).
 */
class AIFocusStabilityMetricsCountersTest {

    @Test
    fun constantVelocityFollowHasBoundedJerk() {
        val m = AIFocusStabilityMetrics()
        // A perfectly constant-velocity lens follow: diopters advance by a fixed step every
        // fixed dt. Velocity is constant => acceleration 0 => jerk 0. Jerk (3rd derivative)
        // must stay below the bounded-follow threshold.
        var d = 1.0f
        for (i in 0 until 16) {
            m.recordFollowSample(positionDiopters = d, timestampMs = i * 33L)
            d += 0.01f // constant velocity
        }
        val snap = m.metricsSnapshot()
        assertTrue(
            "constant-velocity follow jerk (%.4f) must be below the bounded-jerk threshold".format(snap.maxFollowJerk),
            snap.maxFollowJerk < AIFocusStabilityMetrics.BOUNDED_FOLLOW_JERK_THRESHOLD
        )
    }

    @Test
    fun jerkySnapBackExceedsBoundedThreshold() {
        val m = AIFocusStabilityMetrics()
        // A pump/snap-back series (sign-flipping large steps) has high 3rd-derivative content.
        val series = floatArrayOf(1.0f, 1.4f, 1.0f, 1.4f, 1.0f, 1.4f, 1.0f, 1.4f)
        for (i in series.indices) {
            m.recordFollowSample(positionDiopters = series[i], timestampMs = i * 33L)
        }
        val snap = m.metricsSnapshot()
        assertTrue(
            "a pumping snap-back must register jerk above the bounded threshold",
            snap.maxFollowJerk >= AIFocusStabilityMetrics.BOUNDED_FOLLOW_JERK_THRESHOLD
        )
    }

    @Test
    fun pumpAndReacquireCountersAccumulateAndReset() {
        val m = AIFocusStabilityMetrics()
        m.notePumpDecision(suppressed = false) // a real (non-suppressed) retrigger = a pump candidate
        m.notePumpDecision(suppressed = true)  // suppressed => not counted as a pump
        m.notePumpDecision(suppressed = false)
        m.recordReacquireEvent()
        m.recordReacquireEvent()
        var snap = m.metricsSnapshot()
        assertEquals("two non-suppressed retriggers counted as pump cycles", 2, snap.pumpCycleCount)
        assertEquals(2, snap.reacquireEventCount)
        m.reset()
        snap = m.metricsSnapshot()
        assertEquals("reset clears pump counter", 0, snap.pumpCycleCount)
        assertEquals("reset clears reacquire counter", 0, snap.reacquireEventCount)
        assertEquals("reset clears jerk", 0f, snap.maxFollowJerk, 0f)
    }

    @Test
    fun zeroPumpOnStaticFollowSeries() {
        // CP-F1 guarantee mirrored in the counter: a static (suppressed-or-no-retrigger) series
        // accrues zero pump cycles.
        val m = AIFocusStabilityMetrics()
        for (i in 0 until 20) {
            m.notePumpDecision(suppressed = true)
        }
        assertEquals(0, m.metricsSnapshot().pumpCycleCount)
    }
}
