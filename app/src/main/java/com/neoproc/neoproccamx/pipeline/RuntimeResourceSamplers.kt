package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/** Delta-based process CPU utilization normalized to the device's available CPU capacity. */
internal class ProcessCpuUtilizationSampler(
    private val availableProcessors: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
) {
    private var previousElapsedMs = Long.MIN_VALUE
    private var previousProcessCpuMs = Long.MIN_VALUE

    @Synchronized
    fun sample(elapsedRealtimeMs: Long, processCpuTimeMs: Long): Float? {
        if (elapsedRealtimeMs < 0L || processCpuTimeMs < 0L) return null
        val previousWall = previousElapsedMs
        val previousCpu = previousProcessCpuMs
        previousElapsedMs = elapsedRealtimeMs
        previousProcessCpuMs = processCpuTimeMs
        if (previousWall == Long.MIN_VALUE || previousCpu == Long.MIN_VALUE) return null

        val wallDeltaMs = elapsedRealtimeMs - previousWall
        val cpuDeltaMs = processCpuTimeMs - previousCpu
        if (wallDeltaMs <= 0L || cpuDeltaMs < 0L) return null
        val capacityMs = wallDeltaMs.toDouble() * availableProcessors.toDouble()
        if (capacityMs <= 0.0) return null
        return (cpuDeltaMs.toDouble() / capacityMs).toFloat().coerceIn(0f, 1f)
    }

    @Synchronized
    fun reset() {
        previousElapsedMs = Long.MIN_VALUE
        previousProcessCpuMs = Long.MIN_VALUE
    }
}

/**
 * Allocation-free live storage throughput from bytes actually committed by a storage backend.
 * Producers never lock; diagnostic readers scan a fixed two-second ring.
 */
internal class RollingStorageWriteThroughputMeter(
    private val windowDurationNs: Long = DEFAULT_WINDOW_NS,
    capacity: Int = DEFAULT_CAPACITY
) {
    private val safeCapacity = capacity.coerceAtLeast(8)
    private val timestampsNs = AtomicLongArray(safeCapacity)
    private val committedBytes = AtomicLongArray(safeCapacity)
    private val sequence = AtomicLong(0L)

    fun recordCommittedBytes(byteCount: Long, committedAtNs: Long) {
        if (byteCount <= 0L || committedAtNs <= 0L) return
        val index = (sequence.getAndIncrement() % safeCapacity).toInt()
        timestampsNs.set(index, 0L)
        committedBytes.set(index, byteCount)
        timestampsNs.set(index, committedAtNs)
    }

    fun megabytesPerSecond(nowNs: Long): Float? {
        if (nowNs <= 0L) return null
        val cutoffNs = (nowNs - windowDurationNs.coerceAtLeast(1L)).coerceAtLeast(0L)
        var oldestNs = Long.MAX_VALUE
        var newestNs = Long.MIN_VALUE
        var totalBytes = 0L
        var count = 0
        for (index in 0 until safeCapacity) {
            val timestampNs = timestampsNs.get(index)
            if (timestampNs > cutoffNs && timestampNs <= nowNs) {
                val bytes = committedBytes.get(index).coerceAtLeast(0L)
                oldestNs = minOf(oldestNs, timestampNs)
                newestNs = maxOf(newestNs, timestampNs)
                totalBytes = saturatingAdd(totalBytes, bytes)
                count += 1
            }
        }
        if (count < 2 || totalBytes <= 0L || newestNs <= oldestNs) {
            return if (sequence.get() >= 2L) 0f else null
        }
        val durationNs = (nowNs - oldestNs).coerceAtLeast(newestNs - oldestNs)
        if (durationNs <= 0L) return null
        val bytesPerSecond = totalBytes.toDouble() * 1_000_000_000.0 / durationNs.toDouble()
        return (bytesPerSecond / BYTES_PER_MEBIBYTE).toFloat()
            .takeIf { it.isFinite() && it >= 0f }
    }

    fun reset() {
        sequence.set(0L)
        for (index in 0 until safeCapacity) {
            timestampsNs.set(index, 0L)
            committedBytes.set(index, 0L)
        }
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        const val DEFAULT_WINDOW_NS = 2_000_000_000L
        const val DEFAULT_CAPACITY = 512
        const val BYTES_PER_MEBIBYTE = 1024.0 * 1024.0
    }
}
