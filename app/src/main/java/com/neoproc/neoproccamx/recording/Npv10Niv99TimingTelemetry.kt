package com.neoproc.neoproccamx.recording

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

internal data class Npv10Niv99TimingSnapshot(
    val processedFrames: Long,
    val persistedFrames: Long,
    val failedFrames: Long,
    val averageMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maximumMs: Double
) {
    fun provesEveryPersistedFrame(expectedFrames: Long): Boolean =
        expectedFrames > 0L &&
            processedFrames == expectedFrames &&
            persistedFrames == expectedFrames &&
            failedFrames == 0L
}

/** Fixed histogram: recording hot paths update atomics only and never retain per-frame samples. */
internal class Npv10Niv99TimingTelemetry {
    private val histogram = AtomicLongArray(BUCKET_COUNT)
    private val processedFrames = AtomicLong(0L)
    private val persistedFrames = AtomicLong(0L)
    private val failedFrames = AtomicLong(0L)
    private val totalNs = AtomicLong(0L)
    private val maximumNs = AtomicLong(0L)

    fun reset() {
        for (index in 0 until histogram.length()) histogram.set(index, 0L)
        processedFrames.set(0L)
        persistedFrames.set(0L)
        failedFrames.set(0L)
        totalNs.set(0L)
        maximumNs.set(0L)
    }

    fun recordProcessed(durationNs: Long) {
        val bounded = durationNs.coerceAtLeast(0L)
        processedFrames.incrementAndGet()
        totalNs.addAndGet(bounded)
        maximumNs.accumulateAndGet(bounded, ::maxOf)
        val bucket = (bounded / BUCKET_WIDTH_NS)
            .coerceAtMost((BUCKET_COUNT - 1).toLong())
            .toInt()
        histogram.incrementAndGet(bucket)
    }

    fun recordFailure() {
        failedFrames.incrementAndGet()
    }

    fun recordPersisted() {
        persistedFrames.incrementAndGet()
    }

    fun snapshot(): Npv10Niv99TimingSnapshot {
        val processed = processedFrames.get()
        val total = totalNs.get()
        return Npv10Niv99TimingSnapshot(
            processedFrames = processed,
            persistedFrames = persistedFrames.get(),
            failedFrames = failedFrames.get(),
            averageMs = if (processed == 0L) 0.0 else nanosToMs(total / processed),
            p50Ms = percentileMs(processed, 0.50),
            p95Ms = percentileMs(processed, 0.95),
            p99Ms = percentileMs(processed, 0.99),
            maximumMs = nanosToMs(maximumNs.get())
        )
    }

    private fun percentileMs(sampleCount: Long, quantile: Double): Double {
        if (sampleCount <= 0L) return 0.0
        val rank = kotlin.math.ceil(sampleCount.toDouble() * quantile)
            .toLong()
            .coerceAtLeast(1L)
        var cumulative = 0L
        for (index in 0 until histogram.length()) {
            cumulative += histogram.get(index)
            if (cumulative >= rank) {
                return nanosToMs((index + 1L) * BUCKET_WIDTH_NS)
            }
        }
        return nanosToMs(maximumNs.get())
    }

    private fun nanosToMs(nanos: Long): Double = nanos.toDouble() / 1_000_000.0

    private companion object {
        const val BUCKET_COUNT = 401
        const val BUCKET_WIDTH_NS = 250_000L
    }
}
