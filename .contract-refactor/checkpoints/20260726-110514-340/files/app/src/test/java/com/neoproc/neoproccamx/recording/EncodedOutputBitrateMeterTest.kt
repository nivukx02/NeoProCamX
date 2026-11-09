package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class EncodedOutputBitrateMeterTest {

    @Test
    fun measuredBitrate_returnsZero_whenWindowHasFewerThanTwoSamples() {
        val meter = EncodedOutputBitrateMeter()
        assertEquals(0, meter.measuredBitrateBitsPerSecond())

        meter.record(timestampUs = 0L, sizeBytes = 250_000)
        assertEquals(
            "Single buffer alone has no defined rate",
            0, meter.measuredBitrateBitsPerSecond()
        )
    }

    @Test
    fun measuredBitrate_ignoresZeroAndNegativeSizedSamples() {
        val meter = EncodedOutputBitrateMeter()
        meter.record(timestampUs = 0L, sizeBytes = 0)        // codec-config style
        meter.record(timestampUs = 16_667L, sizeBytes = -1)  // EOS-flagged sentinel
        assertEquals(0, meter.sampleCount())
        assertEquals(0, meter.measuredBitrateBitsPerSecond())
    }

    /**
     * 30 fps stream, 200 000 bytes per frame, 60 frames over 2 s window.
     * Expected: 200_000 B × 60 × 8 bits / 1.967 s ≈ ~48.8 Mbps. The meter
     * normalizes by the actual span of buffered samples (last_ts − first_ts),
     * which for n equally-spaced samples is (n − 1)/n of the wall window.
     */
    @Test
    fun measuredBitrate_matchesIntegratedRateForUniformStream() {
        val meter = EncodedOutputBitrateMeter()
        val frameStepUs = 33_333L                  // ~30 fps
        val sizeBytes = 200_000                    // 200 KB / frame
        val frameCount = 60                        // 60 × 33.333 ms ≈ 2 s
        for (i in 0 until frameCount) {
            meter.record(timestampUs = i * frameStepUs, sizeBytes = sizeBytes)
        }
        // span = (frameCount - 1) * frameStepUs
        val spanUs = (frameCount - 1) * frameStepUs
        val expectedBps = (sizeBytes.toLong() * frameCount * 8_000_000L) / spanUs
        val actualBps = meter.measuredBitrateBitsPerSecond()
        // Allow ±0.5% to absorb integer-division rounding.
        val tolerance = expectedBps / 200
        assertTrue(
            "Expected ~$expectedBps bps but got $actualBps bps (tolerance $tolerance)",
            abs(actualBps - expectedBps) <= tolerance
        )
    }

    /**
     * After buffers older than the window are evicted, the rate must reflect
     * only the recent buffers. Drives a step from 50 Mbps to 200 Mbps and
     * waits past the eviction horizon: the reported rate must converge on
     * the new regime, not be polluted by the prior one.
     */
    @Test
    fun measuredBitrate_evictsSamplesOlderThanWindow() {
        val windowUs = 1_000_000L                                  // 1 s window for a tight test
        val meter = EncodedOutputBitrateMeter(windowDurationUs = windowUs)

        // Phase A: low rate (50 Mbps target) for 0..1 s.
        val frameStepUs = 33_333L
        val lowSize = 200_000                                       // 200 KB / frame at 30 fps ≈ 48 Mbps
        var t = 0L
        repeat(30) {                                               // 30 frames over ~1 s
            meter.record(timestampUs = t, sizeBytes = lowSize)
            t += frameStepUs
        }
        val rateA = meter.measuredBitrateBitsPerSecond()
        assertTrue("Phase A rate $rateA should be >= 40 Mbps", rateA >= 40_000_000)
        assertTrue("Phase A rate $rateA should be <= 60 Mbps", rateA <= 60_000_000)

        // Phase B: high rate (~200 Mbps). Push enough frames at the new rate
        // to fully evict Phase A samples beyond the 1 s window.
        val highSize = 800_000                                      // 800 KB / frame ≈ 192 Mbps
        repeat(60) {                                               // 60 frames over ~2 s — evicts Phase A
            meter.record(timestampUs = t, sizeBytes = highSize)
            t += frameStepUs
        }
        val rateB = meter.measuredBitrateBitsPerSecond()
        assertTrue("Phase B rate $rateB should be >= 160 Mbps", rateB >= 160_000_000)
        assertTrue("Phase B rate $rateB should be <= 220 Mbps", rateB <= 220_000_000)
    }

    @Test
    fun windowRateTracksRecentPhaseWhileSessionRateRepresentsWholeRecording() {
        val meter = EncodedOutputBitrateMeter(windowDurationUs = 1_000_000L)
        val frameStepUs = 33_333L
        var timestampUs = 0L

        repeat(120) {
            meter.record(timestampUs = timestampUs, sizeBytes = 200_000)
            timestampUs += frameStepUs
        }
        repeat(60) {
            meter.record(timestampUs = timestampUs, sizeBytes = 800_000)
            timestampUs += frameStepUs
        }

        val recentWindowBps = meter.measuredBitrateBitsPerSecond()
        val wholeSessionBps = meter.measuredSessionBitrateBitsPerSecond()

        assertTrue("Trailing window must respond to the high-rate phase", recentWindowBps >= 160_000_000)
        assertTrue("Whole session must include the long low-rate phase", wholeSessionBps in 80_000_000..120_000_000)
        assertTrue("Whole-session evidence must stay below the high trailing window", wholeSessionBps < recentWindowBps)
    }

    @Test
    fun record_handlesOutOfOrderTimestampsByMonotonicForward() {
        val meter = EncodedOutputBitrateMeter()
        meter.record(timestampUs = 100_000L, sizeBytes = 50_000)
        // Provoke a backward-PTS sample (would otherwise yield negative span).
        meter.record(timestampUs = 50_000L, sizeBytes = 50_000)
        // Both samples are buffered, but the second is treated as 100_001 us
        // so the span stays non-negative and the rate is finite.
        assertEquals(2, meter.sampleCount())
        val rate = meter.measuredBitrateBitsPerSecond()
        assertTrue("Rate must be a finite positive integer, got $rate", rate > 0)
        assertTrue(meter.measuredSessionBitrateBitsPerSecond() > 0)
    }

    @Test
    fun rateSnapshotsClampOverflowToIntMaximum() {
        val meter = EncodedOutputBitrateMeter()
        meter.record(timestampUs = Long.MIN_VALUE, sizeBytes = Int.MAX_VALUE)
        meter.record(timestampUs = Long.MAX_VALUE, sizeBytes = Int.MAX_VALUE)

        assertTrue(meter.measuredBitrateBitsPerSecond() >= 0)
        assertTrue(meter.measuredSessionBitrateBitsPerSecond() >= 0)

        meter.reset()
        meter.record(timestampUs = 0L, sizeBytes = Int.MAX_VALUE)
        meter.record(timestampUs = 1L, sizeBytes = Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, meter.measuredBitrateBitsPerSecond())
        assertEquals(Int.MAX_VALUE, meter.measuredSessionBitrateBitsPerSecond())
    }

    @Test
    fun reset_clearsAllState() {
        val meter = EncodedOutputBitrateMeter()
        repeat(10) { meter.record(timestampUs = it * 33_333L, sizeBytes = 200_000) }
        assertTrue(meter.measuredBitrateBitsPerSecond() > 0)
        assertTrue(meter.measuredSessionBitrateBitsPerSecond() > 0)

        meter.reset()
        assertEquals(0, meter.sampleCount())
        assertEquals(0, meter.measuredBitrateBitsPerSecond())
        assertEquals(0, meter.measuredSessionBitrateBitsPerSecond())

        // Reuse after reset must still work.
        meter.record(timestampUs = 0L, sizeBytes = 100_000)
        meter.record(timestampUs = 33_333L, sizeBytes = 100_000)
        assertTrue(meter.measuredBitrateBitsPerSecond() > 0)
        assertTrue(meter.measuredSessionBitrateBitsPerSecond() > 0)
    }

    @Test
    fun concurrentRecordReadAndResetPreserveNonnegativeSnapshots() {
        val meter = EncodedOutputBitrateMeter(windowDurationUs = 250_000L)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)
        val failure = AtomicReference<Throwable?>(null)

        fun launch(operation: () -> Unit) {
            Thread {
                try {
                    start.await()
                    operation()
                } catch (throwable: Throwable) {
                    failure.compareAndSet(null, throwable)
                } finally {
                    done.countDown()
                }
            }.start()
        }

        launch {
            repeat(5_000) { index ->
                meter.record(timestampUs = index * 1_000L, sizeBytes = 1 + index % 32_000)
            }
        }
        launch {
            repeat(5_000) { index ->
                meter.record(timestampUs = 5_000_000L - index, sizeBytes = Int.MAX_VALUE)
            }
        }
        launch {
            repeat(5_000) {
                assertTrue(meter.sampleCount() >= 0)
                assertTrue(meter.measuredBitrateBitsPerSecond() >= 0)
                assertTrue(meter.measuredSessionBitrateBitsPerSecond() >= 0)
            }
        }
        launch {
            repeat(500) {
                meter.reset()
                assertTrue(meter.sampleCount() >= 0)
            }
        }

        start.countDown()
        assertTrue("Concurrent meter operations timed out", done.await(20, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("Concurrent meter operation failed", it) }

        meter.reset()
        assertEquals(0, meter.sampleCount())
        assertEquals(0, meter.measuredBitrateBitsPerSecond())
        assertEquals(0, meter.measuredSessionBitrateBitsPerSecond())
    }
}
