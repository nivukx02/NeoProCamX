package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry

/**
 * s1-f04 — ring-health invariant producer for the gyro telemetry stream.
 *
 * A gyro-EIS / rolling-shutter warp is only correct if the orientation it integrates is built from a
 * GAP-FREE, time-ordered stream: a dropped or out-of-order sample at `SENSOR_DELAY_FASTEST` silently
 * biases the integral. This computes, over an oldest-first snapshot, whether the timestamps are strictly
 * monotonic and whether every inter-sample gap stays within an expected bound for the sampling rate.
 *
 * It is a PURE invariant PRODUCER — it returns a report, it never throws and never mutates capture, so
 * it changes no live behavior. Tests assert the invariants; a future on-device diagnostic can surface
 * the same report. (The expected gap bound is a function of the real device's gyro rate — DEVICE-GATED
 * to confirm.)
 */
object GyroRingHealth {

    data class RingHealthReport(
        val sampleCount: Int,
        /** Strictly increasing timestamps (no duplicates, no rewind). Vacuously true for < 2 samples. */
        val monotonic: Boolean,
        val minGapNs: Long,
        val maxGapNs: Long,
        val meanGapNs: Double,
        /** True iff [monotonic] AND the largest gap is within the expected bound (no dropped interval). */
        val contiguous: Boolean,
    )

    /**
     * Analyse an OLDEST-FIRST [samples] window. [maxExpectedGapNs] is the largest inter-sample interval
     * that still counts as "no drop" (≈ a few sample periods at the gyro rate). With fewer than two
     * samples the stream is trivially monotonic and contiguous with zero gaps.
     */
    fun analyse(samples: List<CameraMotionTelemetry>, maxExpectedGapNs: Long): RingHealthReport {
        if (samples.size < 2) {
            return RingHealthReport(
                sampleCount = samples.size,
                monotonic = true,
                minGapNs = 0L,
                maxGapNs = 0L,
                meanGapNs = 0.0,
                contiguous = true,
            )
        }
        var monotonic = true
        var minGap = Long.MAX_VALUE
        var maxGap = Long.MIN_VALUE
        var sumGap = 0L
        for (i in 1 until samples.size) {
            val gap = samples[i].timestampNs - samples[i - 1].timestampNs
            if (gap <= 0L) monotonic = false
            if (gap < minGap) minGap = gap
            if (gap > maxGap) maxGap = gap
            sumGap += gap
        }
        return RingHealthReport(
            sampleCount = samples.size,
            monotonic = monotonic,
            minGapNs = minGap,
            maxGapNs = maxGap,
            meanGapNs = sumGap.toDouble() / (samples.size - 1),
            contiguous = monotonic && maxGap <= maxExpectedGapNs,
        )
    }
}
