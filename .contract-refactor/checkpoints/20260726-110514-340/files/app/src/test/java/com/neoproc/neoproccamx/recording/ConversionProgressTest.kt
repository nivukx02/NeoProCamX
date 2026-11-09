package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the offline-conversion progress model: the ETA estimator (deterministic, injected nanoTime),
 * the human-readable formatting, and the memory-bounded parallelism policy.
 */
class ConversionProgressTest {

    private val ms = 1_000_000L // ns per ms

    @Test
    fun startSnapshotIsZeroWithUnknownEta() {
        val est = ConversionEtaEstimator(framesTotal = 10L, pathLabel = "CPU ×4", startNanos = 0L)
        val start = est.startSnapshot()
        assertEquals(0L, start.framesDone)
        assertEquals(10L, start.framesTotal)
        assertEquals(-1L, start.etaMs)
        assertTrue(start.isDeterminate)
        assertEquals(0f, start.fraction, 0.0001f)
    }

    @Test
    fun steadyRateProducesAccurateEtaAndThroughput() {
        val est = ConversionEtaEstimator(framesTotal = 10L, pathLabel = "CPU ×4", startNanos = 0L)
        // 5 frames, each exactly 100 ms apart.
        var last: ConversionProgress? = null
        for (f in 1..5) last = est.onFrameCompleted(f * 100L * ms)
        val p = last!!
        assertEquals(5L, p.framesDone)
        assertEquals(0.5f, p.fraction, 0.0001f)
        assertEquals(500, p.perMille())
        assertEquals(500L, p.elapsedMs)
        // 5 frames remain at 100 ms each ≈ 500 ms.
        assertTrue("eta ${p.etaMs} should be ~500ms", p.etaMs in 460L..540L)
        // 5 frames in 0.5 s = 10 fps.
        assertEquals(10.0, p.framesPerSecond, 0.5)
    }

    @Test
    fun etaTrendsDownAsFramesComplete() {
        val est = ConversionEtaEstimator(framesTotal = 100L, pathLabel = "CPU", startNanos = 0L)
        val early = est.onFrameCompleted(50L * ms).etaMs
        var t = 50L
        var latest = early
        for (f in 2..90) { t += 50L; latest = est.onFrameCompleted(t * ms).etaMs }
        assertTrue("eta should shrink from $early to $latest", latest < early)
        assertTrue(latest >= 0L)
    }

    @Test
    fun unknownTotalIsIndeterminateWithNoEta() {
        val est = ConversionEtaEstimator(framesTotal = 0L, pathLabel = "CPU", startNanos = 0L)
        val p = est.onFrameCompleted(100L * ms)
        assertFalse(p.isDeterminate)
        assertEquals(-1f, p.fraction, 0.0001f)
        assertEquals(-1L, p.etaMs)
        assertEquals(0, p.perMille())
    }

    @Test
    fun durationFormattingIsHumanReadable() {
        assertEquals("45s", ConversionProgress.formatShortDuration(45_000L))
        assertEquals("1m 12s", ConversionProgress.formatShortDuration(72_000L))
        assertEquals("1h 0m", ConversionProgress.formatShortDuration(3_600_000L))
        assertEquals("2h 5m", ConversionProgress.formatShortDuration((2 * 3600 + 5 * 60) * 1000L))
        assertEquals("—", ConversionProgress.formatShortDuration(-1L))
    }

    @Test
    fun countAndEtaLinesRenderPathAndCounts() {
        val p = ConversionProgress(
            framesDone = 30L,
            framesTotal = 120L,
            elapsedMs = 3_000L,
            etaMs = 9_000L,
            framesPerSecond = 10.0,
            pathLabel = "GPU (Vulkan) + CPU"
        )
        assertTrue(p.countLine().contains("25%"))
        assertTrue(p.countLine().contains("30 / 120"))
        assertTrue(p.etaLine().contains("GPU (Vulkan) + CPU"))
        assertTrue(p.etaLine().contains("remaining"))
    }

    @Test
    fun percentNeverReadsFullBeforeTheFinalFrame() {
        val est = ConversionEtaEstimator(framesTotal = 240L, pathLabel = "CPU", startNanos = 0L)
        var p: ConversionProgress? = null
        for (f in 1..239) p = est.onFrameCompleted(f * 10L * ms)
        val penultimate = p!!
        assertEquals(239L, penultimate.framesDone)
        // 239/240 = 99.58% must render as 99% (truncated), not a premature "100%".
        assertTrue(penultimate.countLine().startsWith("99%"))
        assertTrue(penultimate.perMille() < 1000)
        val last = est.onFrameCompleted(240L * 10L * ms)
        assertEquals(240L, last.framesDone)
        assertTrue(last.countLine().startsWith("100%"))
        assertEquals(1000, last.perMille())
    }

    @Test
    fun parallelismUsesCoresButBoundsMemoryForLargeFrames() {
        val cores = 8
        // Small frame → all-but-one core.
        assertEquals(
            7,
            ConversionParallelismPolicy.workerCount(frameBytes = 1_000L, cores = cores)
        )
        // Frame nearly as large as the whole budget → forced down to a single worker (never zero).
        val huge = ConversionParallelismPolicy.workerCount(
            frameBytes = 300L * 1024 * 1024,
            cores = cores,
            memoryBudgetBytes = 384L * 1024 * 1024,
            pipelineDepth = 1,
            residentCopiesPerFrame = 1
        )
        assertEquals(1, huge)
        // Zero/unknown frame size → all-but-one core, still ≥ 1.
        assertEquals(7, ConversionParallelismPolicy.workerCount(frameBytes = 0L, cores = cores))
        assertTrue(ConversionParallelismPolicy.workerCount(frameBytes = 50L, cores = 1) >= 1)
    }

    @Test
    fun resolveMemoryBudgetCorrectlyAdaptsToHeapLimit() {
        assertEquals(1024L * 1024 * 1024, ConversionParallelismPolicy.resolveMemoryBudget(1024L * 1024 * 1024))
        assertEquals(512L * 1024 * 1024, ConversionParallelismPolicy.resolveMemoryBudget(512L * 1024 * 1024))
        assertEquals(384L * 1024 * 1024, ConversionParallelismPolicy.resolveMemoryBudget(256L * 1024 * 1024))
    }
}
