package com.neoproc.neoproccamx.quality.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f05 / CP-Q7 — locks the windowed temporal-flicker-sigma core that SceneAnalyzer promotes to
 * live telemetry. The math lives entirely in the pure [ImageQualityMetrics] object so it is provable
 * under the pure-JVM suite (SceneAnalyzer itself field-inits a HandlerThread and consumes a non-null
 * Android CaptureResult, so its analyzeFrame is not unit-testable here; the OFF path's no-downgrade
 * guarantee is by construction — every mutation sits inside the flag guard).
 *
 * These tests prove: (1) the two public entrypoints share one inline core so a live ring and a bench
 * window agree exactly; (2) the analytic ground truth — a constant window reads 0 and a +/-j window
 * reads sigma == j.
 */
class LiveMetricSharedCoreAgreementTest {

    @Test
    fun liveAndBenchFlickerSigmaAgree() {
        // A deterministic window of per-frame mean-luma values.
        val window = doubleArrayOf(0.50, 0.52, 0.49, 0.51, 0.50, 0.48, 0.53, 0.50)

        // Bench entrypoint: the whole array.
        val bench = ImageQualityMetrics.temporalFlickerSigmaFromMeans(window)

        // Live entrypoint: the SceneAnalyzer ring is a fixed-size DoubleArray read up to `count`.
        val ring = DoubleArray(8)
        for (i in window.indices) ring[i] = window[i]
        val live = ImageQualityMetrics.temporalFlickerSigmaFromMeans(ring, window.size)

        assertEquals("live ring and bench window must share the same core", bench, live, 1e-12)
    }

    @Test
    fun partialRingMatchesArraySliceOfSameLength() {
        // Before the ring fills, the live caller passes count < capacity; the result must equal the
        // bench value over only those first `count` entries (and ignore stale trailing slots).
        val ring = DoubleArray(8)
        val filled = doubleArrayOf(0.40, 0.60, 0.40, 0.60, 0.40)
        for (i in filled.indices) ring[i] = filled[i]
        ring[5] = 99.0 // stale slot that must NOT be read
        ring[6] = -99.0
        ring[7] = 7.0

        val live = ImageQualityMetrics.temporalFlickerSigmaFromMeans(ring, filled.size)
        val bench = ImageQualityMetrics.temporalFlickerSigmaFromMeans(filled)
        assertEquals(bench, live, 1e-12)
    }

    @Test
    fun staticWindowReadsZero() {
        val flat = DoubleArray(8) { 0.5 }
        assertEquals(0.0, ImageQualityMetrics.temporalFlickerSigmaFromMeans(flat), 1e-12)
    }

    @Test
    fun injectedMeanJitterMatchesSigma() {
        // A window alternating mean +/- j has population std exactly j.
        val mean = 0.5
        val j = 0.03
        val jitter = DoubleArray(8) { if (it % 2 == 0) mean + j else mean - j }
        assertEquals(j, ImageQualityMetrics.temporalFlickerSigmaFromMeans(jitter), 1e-12)

        // And a larger jitter reads larger than a smaller one (monotonic in flicker amplitude).
        val small = DoubleArray(8) { if (it % 2 == 0) mean + 0.005 else mean - 0.005 }
        assertTrue(
            ImageQualityMetrics.temporalFlickerSigmaFromMeans(jitter) >
                ImageQualityMetrics.temporalFlickerSigmaFromMeans(small)
        )
    }

    @Test
    fun fewerThanTwoSamplesIsRejected() {
        // The live caller only invokes the core when count >= 2; guard the contract directly.
        var threw = false
        try {
            ImageQualityMetrics.temporalFlickerSigmaFromMeans(doubleArrayOf(0.5))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a single-sample window must be rejected, not silently 0", threw)
    }
}
