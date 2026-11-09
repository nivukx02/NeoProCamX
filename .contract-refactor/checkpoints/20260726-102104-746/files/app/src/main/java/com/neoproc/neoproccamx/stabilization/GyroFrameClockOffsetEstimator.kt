package com.neoproc.neoproccamx.stabilization

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ELITE Rank-5 — pure-Kotlin gyro↔camera clock-offset calibrator (Karpenko-style gyro/video sync).
 *
 * Gyro samples are stamped in one clock (usually `elapsedRealtimeNanos`) and camera frames in another
 * (`SENSOR_TIMESTAMP`). When `SENSOR_INFO_TIMESTAMP_SOURCE != REALTIME` the offset between them is unknown,
 * and EIS driven with a WRONG offset counter-rotates each row against the wrong instant's motion — it ADDS
 * jello instead of removing it. So the offset MUST be measured before EIS is ever engaged.
 *
 * This estimator correlates the gyro angular-velocity magnitude signal against the per-frame image-motion
 * magnitude signal and returns the lag that maximizes their normalized cross-correlation, plus a confidence
 * score. It is fully deterministic and host-unit-testable (no Android, no device): inject a known lag and it
 * is recovered; feed flat/uncorrelated input and confidence stays low so a bad estimate is never trusted.
 *
 * Convention: [ClockOffsetEstimate.offsetNs] is the value to ADD to a gyro timestamp to reach the matching
 * camera/image timestamp — i.e. `cameraTs ≈ gyroTs + offsetNs`. (Equivalently, image motion lags gyro motion
 * by `offsetNs`.) The caller uses it as `gyroTs = cameraTs - offsetNs` when sampling orientation per row.
 */
object GyroFrameClockOffsetEstimator {

    /** Minimum peak normalized cross-correlation for the estimate to be considered usable. */
    const val MIN_CONFIDENCE: Float = 0.6f

    /** Minimum overlapping grid samples required for a meaningful correlation. */
    const val MIN_OVERLAP_SAMPLES: Int = 16

    /** (tsNs, magnitude) time series sample. */
    data class Sample(val tsNs: Long, val magnitude: Float)

    data class ClockOffsetEstimate(
        /** Offset to add to a gyro timestamp to reach the camera timestamp (ns). 0 when not usable. */
        val offsetNs: Long,
        /** Peak normalized cross-correlation in [-1,1] (clamped ≥0 for reporting), the confidence. */
        val confidence: Float,
        /** The ± lag window that was searched (ns). */
        val searchedRangeNs: Long,
        /** True only when confidence ≥ [MIN_CONFIDENCE] with enough correlated overlap — safe to trust. */
        val usable: Boolean
    ) {
        companion object {
            fun unusable(searchedRangeNs: Long) =
                ClockOffsetEstimate(0L, 0f, searchedRangeNs, usable = false)
        }
    }

    /**
     * Estimate the clock offset.
     *
     * @param gyro         angular-velocity-magnitude series (tsNs, |ω|), any monotonic clock.
     * @param imageMotion  per-frame image-motion-magnitude series (tsNs, motion), the camera clock.
     * @param maxLagNs     the ± search window (default ±50 ms covers any realistic inter-clock skew).
     * @param gridDtNs     resampling grid step (default 2 ms; sub-sample refined by parabolic interpolation).
     */
    fun estimate(
        gyro: List<Sample>,
        imageMotion: List<Sample>,
        maxLagNs: Long = 50_000_000L,
        gridDtNs: Long = 2_000_000L
    ): ClockOffsetEstimate {
        val searched = 2 * maxLagNs
        if (gyro.size < 2 || imageMotion.size < 2 || gridDtNs <= 0L || maxLagNs <= 0L) {
            return ClockOffsetEstimate.unusable(searched)
        }
        val g = gyro.sortedBy { it.tsNs }
        val m = imageMotion.sortedBy { it.tsNs }

        // Common overlap window (both signals must cover it), padded so lag shifts stay in range.
        val start = max(g.first().tsNs, m.first().tsNs)
        val end = min(g.last().tsNs, m.last().tsNs)
        if (end - start < gridDtNs * MIN_OVERLAP_SAMPLES) return ClockOffsetEstimate.unusable(searched)

        val n = ((end - start) / gridDtNs).toInt() + 1
        val gGrid = resampleNormalized(g, start, gridDtNs, n) ?: return ClockOffsetEstimate.unusable(searched)
        val mGrid = resampleNormalized(m, start, gridDtNs, n) ?: return ClockOffsetEstimate.unusable(searched)

        val kMax = (maxLagNs / gridDtNs).toInt()
        var bestK = 0
        var bestNcc = -2f
        val ncc = FloatArray(2 * kMax + 1)
        for (k in -kMax..kMax) {
            var sum = 0f
            var count = 0
            // correlate m[i+k] against g[i]; peak when m lags g by k*gridDt.
            var i = max(0, -k)
            val iEnd = min(n, n - k)
            while (i < iEnd) {
                sum += gGrid[i] * mGrid[i + k]
                count++
                i++
            }
            val c = if (count >= MIN_OVERLAP_SAMPLES) sum / count else -2f
            ncc[k + kMax] = c
            if (c > bestNcc) { bestNcc = c; bestK = k }
        }
        if (bestNcc < -1f) return ClockOffsetEstimate.unusable(searched)

        // Parabolic sub-sample refinement around the discrete peak.
        val ki = bestK + kMax
        var refined = bestK.toFloat()
        if (ki in 1 until ncc.size - 1) {
            val ym = ncc[ki - 1]; val y0 = ncc[ki]; val yp = ncc[ki + 1]
            val denom = (ym - 2f * y0 + yp)
            if (abs(denom) > 1e-6f) {
                val delta = 0.5f * (ym - yp) / denom
                if (delta in -1f..1f) refined = bestK + delta
            }
        }
        val offsetNs = (refined * gridDtNs).toLong()
        val confidence = bestNcc.coerceIn(0f, 1f)
        return ClockOffsetEstimate(
            offsetNs = offsetNs,
            confidence = confidence,
            searchedRangeNs = searched,
            usable = confidence >= MIN_CONFIDENCE
        )
    }

    /** Linear-resample a (ts,mag) series onto a uniform grid, then zero-mean/unit-variance normalize. */
    private fun resampleNormalized(s: List<Sample>, start: Long, dt: Long, n: Int): FloatArray? {
        val out = FloatArray(n)
        var j = 0
        for (i in 0 until n) {
            val t = start + i.toLong() * dt
            while (j < s.size - 2 && s[j + 1].tsNs < t) j++
            val a = s[j]; val b = s[min(j + 1, s.size - 1)]
            out[i] = if (b.tsNs == a.tsNs) {
                a.magnitude
            } else {
                val f = ((t - a.tsNs).toDouble() / (b.tsNs - a.tsNs).toDouble()).coerceIn(0.0, 1.0).toFloat()
                a.magnitude + (b.magnitude - a.magnitude) * f
            }
        }
        var mean = 0f
        for (v in out) mean += v
        mean /= n
        var varSum = 0f
        for (i in 0 until n) { out[i] -= mean; varSum += out[i] * out[i] }
        val std = sqrt(varSum / n)
        if (std < 1e-6f) return null // flat signal — no correlatable structure
        val inv = 1f / std
        for (i in 0 until n) out[i] *= inv
        return out
    }
}
