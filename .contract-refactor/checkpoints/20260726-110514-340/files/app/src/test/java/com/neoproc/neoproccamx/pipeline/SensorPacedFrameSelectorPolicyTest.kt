package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * s7-f01 / CP92 — sensor-paced selector math. Deterministic (no Random) to avoid flakiness.
 */
class SensorPacedFrameSelectorPolicyTest {

    @Test
    fun frameIntervalAndTargetAreDerivedFromFps() {
        // (1e9 / 60).toLong() == 16_666_666
        assertEquals(16_666_666L, SensorPacedFrameSelectorPolicy.frameIntervalNs(60f))
        assertEquals(33_333_332L, SensorPacedFrameSelectorPolicy.targetIntervalNs(60f, 2L))
        // fps floored at 1
        assertEquals(1_000_000_000L, SensorPacedFrameSelectorPolicy.frameIntervalNs(0f))
    }

    @Test
    fun firstFrameIsAlwaysAdmitted() {
        val d = SensorPacedFrameSelectorPolicy.decide(
            sensorTimestampNs = 12_345L,
            lastAdmittedAnchorNs = Long.MIN_VALUE,
            targetIntervalNs = 33_333_332L
        )
        assertTrue(d.admit)
        assertEquals(12_345L, d.nextAnchorNs)
    }

    @Test
    fun belowIntervalSkipsAndDoesNotAdvanceAnchor() {
        val anchor = 1_000_000L
        val d = SensorPacedFrameSelectorPolicy.decide(
            sensorTimestampNs = anchor + 10_000_000L, // < 33.3ms
            lastAdmittedAnchorNs = anchor,
            targetIntervalNs = 33_333_332L
        )
        assertFalse(d.admit)
        assertEquals(anchor, d.nextAnchorNs)
    }

    @Test
    fun atOrAboveIntervalAdmitsAndAdvancesByWholeIntervals() {
        val interval = 33_333_332L
        val anchor = 1_000_000L
        val exact = SensorPacedFrameSelectorPolicy.decide(anchor + interval, anchor, interval)
        assertTrue(exact.admit)
        assertEquals(anchor + interval, exact.nextAnchorNs)
        // A long stall (2.5 intervals) advances by 2 whole intervals (drift correction, single admit).
        val stalled = SensorPacedFrameSelectorPolicy.decide(anchor + interval * 5 / 2, anchor, interval)
        assertTrue(stalled.admit)
        assertEquals(anchor + 2 * interval, stalled.nextAnchorNs)
    }

    @Test
    fun cadenceStaysWithinOneFrameUnderBoundedJitter() {
        val previewFps = 60f
        val stride = 2L
        val frameNs = SensorPacedFrameSelectorPolicy.frameIntervalNs(previewFps)
        val target = SensorPacedFrameSelectorPolicy.targetIntervalNs(previewFps, stride)
        val selector = SensorPacedFrameSelector()
        // Deterministic bounded jitter pattern (< half a frame) on a nominal 60fps stream.
        val jitter = longArrayOf(0L, 3_000_000L, -2_000_000L, 4_000_000L, -1_500_000L, 2_500_000L)
        val admittedTs = ArrayList<Long>()
        for (i in 1..600) {
            val ts = i * frameNs + jitter[i % jitter.size]
            if (selector.shouldAnalyze(ts, previewFps, stride)) admittedTs.add(ts)
        }
        assertTrue("must admit a steady cadence", admittedTs.size > 250)
        // Every realized admitted interval is within ±1 frame of the target (no drift, no burst).
        for (k in 1 until admittedTs.size) {
            val delta = admittedTs[k] - admittedTs[k - 1]
            assertTrue(
                "interval $delta off target $target by > 1 frame",
                abs(delta - target) <= frameNs
            )
        }
    }
}
