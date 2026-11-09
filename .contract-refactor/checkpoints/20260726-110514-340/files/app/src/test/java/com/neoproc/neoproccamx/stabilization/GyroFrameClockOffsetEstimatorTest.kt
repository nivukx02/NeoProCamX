package com.neoproc.neoproccamx.stabilization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * ELITE Rank-5 — goldens for the gyro↔camera clock-offset calibrator.
 *
 * Proves the two properties EIS safety depends on:
 *  - a KNOWN injected lag is recovered (whole-step and sub-step) with high confidence;
 *  - a degenerate/uncorrelated input yields an UNUSABLE estimate, so a wrong offset is never trusted
 *    (the "wrong offset ADDS wobble" regression is fenced off at the source).
 */
class GyroFrameClockOffsetEstimatorTest {

    private val dt = 2_000_000L          // 2 ms grid / 500 Hz
    private val durationNs = 500_000_000L // 0.5 s of motion

    /** A rich, non-flat motion magnitude signal (three incommensurate tones). t in ns. */
    private fun sig(tNs: Long): Float {
        val t = tNs / 1e9
        return (2.0 + sin(2 * PI * 7 * t) + 0.6 * sin(2 * PI * 17 * t) + 0.4 * sin(2 * PI * 31 * t)).toFloat()
    }

    private fun series(lagNs: Long): List<GyroFrameClockOffsetEstimator.Sample> {
        val out = ArrayList<GyroFrameClockOffsetEstimator.Sample>()
        var t = 0L
        while (t <= durationNs) {
            out.add(GyroFrameClockOffsetEstimator.Sample(t, sig(t - lagNs)))
            t += dt
        }
        return out
    }

    @Test
    fun `recovers a whole-step injected lag with high confidence`() {
        val gyro = series(0L)
        val lag = 12_000_000L // 12 ms = 6 grid steps
        val image = series(lag) // image motion lags gyro by 12 ms
        val est = GyroFrameClockOffsetEstimator.estimate(gyro, image)
        assertTrue("must be usable (conf=${est.confidence})", est.usable)
        assertTrue("confidence must be high (${est.confidence})", est.confidence > 0.9f)
        assertEquals("offset recovers the injected lag", lag.toDouble(), est.offsetNs.toDouble(), dt.toDouble())
    }

    @Test
    fun `recovers a sub-step lag via parabolic refinement`() {
        val gyro = series(0L)
        val lag = 7_000_000L // 7 ms = 3.5 grid steps (not a whole step)
        val image = series(lag)
        val est = GyroFrameClockOffsetEstimator.estimate(gyro, image)
        assertTrue("must be usable", est.usable)
        assertEquals("sub-step lag recovered within one grid step", lag.toDouble(), est.offsetNs.toDouble(), dt.toDouble())
    }

    @Test
    fun `zero lag maps to zero offset`() {
        val gyro = series(0L)
        val image = series(0L)
        val est = GyroFrameClockOffsetEstimator.estimate(gyro, image)
        assertTrue(est.usable)
        assertTrue("near-zero offset (${est.offsetNs})", abs(est.offsetNs) <= dt)
    }

    @Test
    fun `flat signal is unusable (never trusts a bad estimate)`() {
        val flat = (0..250).map { GyroFrameClockOffsetEstimator.Sample(it.toLong() * dt, 1.0f) }
        val real = series(0L)
        val est = GyroFrameClockOffsetEstimator.estimate(flat, real)
        assertFalse("flat gyro must be unusable", est.usable)
        assertEquals("unusable estimate reports zero offset", 0L, est.offsetNs)
    }

    @Test
    fun `too-few or non-overlapping samples are unusable`() {
        val tiny = listOf(GyroFrameClockOffsetEstimator.Sample(0L, 1f), GyroFrameClockOffsetEstimator.Sample(dt, 2f))
        val est = GyroFrameClockOffsetEstimator.estimate(tiny, series(0L))
        assertFalse(est.usable)
    }
}
