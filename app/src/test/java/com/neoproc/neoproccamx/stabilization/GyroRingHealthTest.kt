package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroRingHealthTest {

    private val oneMs = 1_000_000L
    private val gapBound = 3 * oneMs // ≈ a few sample periods at the gyro rate

    private fun streamOf(vararg tsNs: Long): List<CameraMotionTelemetry> =
        tsNs.map { t ->
            CameraMotionTelemetry(
                timestampNs = t,
                oisShiftXPixels = 0f,
                oisShiftYPixels = 0f,
                gyroXRadPerSec = 0f,
                gyroYRadPerSec = 0f,
                gyroZRadPerSec = 0f,
                source = CameraMotionTelemetry.Source.GYRO,
            )
        }

    @Test
    fun gapFreeMonotonicStreamIsContiguous() {
        val samples = streamOf(0, oneMs, 2 * oneMs, 3 * oneMs, 4 * oneMs)
        val report = GyroRingHealth.analyse(samples, maxExpectedGapNs = gapBound)
        assertEquals(5, report.sampleCount)
        assertTrue(report.monotonic)
        assertTrue(report.contiguous)
        assertEquals(oneMs, report.minGapNs)
        assertEquals(oneMs, report.maxGapNs)
        assertEquals(oneMs.toDouble(), report.meanGapNs, 1e-6)
    }

    @Test
    fun droppedIntervalBreaksContiguityButNotMonotonicity() {
        // ...0,1,2 then an 8 ms hole then 10,11 — still time-ordered, but a sample interval was dropped.
        val samples = streamOf(0, oneMs, 2 * oneMs, 10 * oneMs, 11 * oneMs)
        val report = GyroRingHealth.analyse(samples, maxExpectedGapNs = gapBound)
        assertTrue(report.monotonic)
        assertFalse(report.contiguous)
        assertEquals(8 * oneMs, report.maxGapNs)
    }

    @Test
    fun outOfOrderTimestampsAreNonMonotonic() {
        val samples = streamOf(0, oneMs, 3 * oneMs, 2 * oneMs, 4 * oneMs)
        val report = GyroRingHealth.analyse(samples, maxExpectedGapNs = gapBound)
        assertFalse(report.monotonic)
        assertFalse(report.contiguous)
    }

    @Test
    fun duplicateTimestampIsNonMonotonic() {
        val samples = streamOf(0, oneMs, oneMs, 2 * oneMs)
        val report = GyroRingHealth.analyse(samples, maxExpectedGapNs = gapBound)
        assertFalse(report.monotonic)
        assertEquals(0L, report.minGapNs)
    }

    @Test
    fun fewerThanTwoSamplesIsTriviallyHealthy() {
        val empty = GyroRingHealth.analyse(emptyList(), maxExpectedGapNs = gapBound)
        assertEquals(0, empty.sampleCount)
        assertTrue(empty.monotonic)
        assertTrue(empty.contiguous)
        assertEquals(0L, empty.maxGapNs)

        val single = GyroRingHealth.analyse(streamOf(42L), maxExpectedGapNs = gapBound)
        assertEquals(1, single.sampleCount)
        assertTrue(single.monotonic)
        assertTrue(single.contiguous)
    }
}
