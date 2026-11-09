package com.neoproc.neoproccamx.niv99

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.max

class Niv99TelemetrySnapshot {
    var processedFrames: Long = 0L
    var rejectedFrames: Long = 0L
    var resetCount: Long = 0L
    var gpuBackpressureFrames: Long = 0L
    var gpuFailureFrames: Long = 0L
    var medianFrameTimeNs: Long = 0L
    var p95FrameTimeNs: Long = 0L
    var p99FrameTimeNs: Long = 0L
    var maximumFrameTimeNs: Long = 0L
    var deadlineMisses: Long = 0L
    var maximumResidentBytes: Long = 0L
    var temporalHistoryHighWatermark: Int = 0
    var meanAwbConfidence: Float = 0f
    var meanMixedLightConfidence: Float = 0f
    var meanTemporalStrength: Float = 0f
}

/** Lock-free bounded telemetry; no timestamp or frame payload is retained. */
class Niv99Telemetry {
    private val frameTimeHistogram = AtomicLongArray(FRAME_TIME_BIN_COUNT)
    private val processedFrames = AtomicLong(0L)
    private val rejectedFrames = AtomicLong(0L)
    private val resetCount = AtomicLong(0L)
    private val gpuBackpressureFrames = AtomicLong(0L)
    private val gpuFailureFrames = AtomicLong(0L)
    private val deadlineMisses = AtomicLong(0L)
    private val maximumFrameTimeNs = AtomicLong(0L)
    private val maximumResidentBytes = AtomicLong(0L)
    private val temporalHistoryHighWatermark = AtomicLong(0L)
    private val awbConfidenceMicros = AtomicLong(0L)
    private val mixedLightConfidenceMicros = AtomicLong(0L)
    private val temporalStrengthMicros = AtomicLong(0L)

    fun record(packet: Niv99StatePacket, residentBytes: Long) {
        if (!packet.accepted) {
            rejectedFrames.incrementAndGet()
            return
        }
        val count = processedFrames.incrementAndGet()
        val frameTime = packet.processingTimeNs.coerceAtLeast(0L)
        frameTimeHistogram.incrementAndGet(frameTimeBin(frameTime))
        updateMaximum(maximumFrameTimeNs, frameTime)
        updateMaximum(maximumResidentBytes, residentBytes.coerceAtLeast(0L))
        updateMaximum(
            temporalHistoryHighWatermark,
            packet.denoise.temporalHistoryFrames.toLong()
        )
        awbConfidenceMicros.addAndGet((packet.whiteBalance.confidence.coerceIn(0f, 1f) * MICRO_SCALE).toLong())
        mixedLightConfidenceMicros.addAndGet(
            (packet.whiteBalance.mixedLightConfidence.coerceIn(0f, 1f) * MICRO_SCALE).toLong()
        )
        temporalStrengthMicros.addAndGet(
            (packet.denoise.temporalStrength.coerceIn(0f, 1f) * MICRO_SCALE).toLong()
        )
        if (packet.whiteBalance.resetReason != Niv99ResetReason.NONE ||
            packet.denoise.resetReason != Niv99ResetReason.NONE
        ) {
            resetCount.incrementAndGet()
        }
        if (frameTime > packet.denoise.realtimeDeadlineNs) deadlineMisses.incrementAndGet()
        if (count == Long.MAX_VALUE) clear()
    }

    fun recordGpuBackpressure() {
        gpuBackpressureFrames.incrementAndGet()
    }

    fun recordGpuFailure() {
        gpuFailureFrames.incrementAndGet()
    }

    fun snapshot(output: Niv99TelemetrySnapshot) {
        val count = processedFrames.get()
        output.processedFrames = count
        output.rejectedFrames = rejectedFrames.get()
        output.resetCount = resetCount.get()
        output.gpuBackpressureFrames = gpuBackpressureFrames.get()
        output.gpuFailureFrames = gpuFailureFrames.get()
        output.medianFrameTimeNs = percentile(0.50f, count)
        output.p95FrameTimeNs = percentile(0.95f, count)
        output.p99FrameTimeNs = percentile(0.99f, count)
        output.maximumFrameTimeNs = maximumFrameTimeNs.get()
        output.deadlineMisses = deadlineMisses.get()
        output.maximumResidentBytes = maximumResidentBytes.get()
        output.temporalHistoryHighWatermark = temporalHistoryHighWatermark.get().toInt()
        val denominator = max(count, 1L).toFloat() * MICRO_SCALE
        output.meanAwbConfidence = awbConfidenceMicros.get() / denominator
        output.meanMixedLightConfidence = mixedLightConfidenceMicros.get() / denominator
        output.meanTemporalStrength = temporalStrengthMicros.get() / denominator
    }

    fun clear() {
        for (index in 0 until frameTimeHistogram.length()) frameTimeHistogram.set(index, 0L)
        processedFrames.set(0L)
        rejectedFrames.set(0L)
        resetCount.set(0L)
        gpuBackpressureFrames.set(0L)
        gpuFailureFrames.set(0L)
        deadlineMisses.set(0L)
        maximumFrameTimeNs.set(0L)
        maximumResidentBytes.set(0L)
        temporalHistoryHighWatermark.set(0L)
        awbConfidenceMicros.set(0L)
        mixedLightConfidenceMicros.set(0L)
        temporalStrengthMicros.set(0L)
    }

    private fun frameTimeBin(frameTimeNs: Long): Int {
        if (frameTimeNs <= 0L) return 0
        val micros = (frameTimeNs + 999L) / 1_000L
        var value = micros
        var exponent = 0
        while (value > 1L && exponent < FRAME_TIME_BIN_COUNT - 1) {
            value = value ushr 1
            exponent += 1
        }
        return exponent
    }

    private fun percentile(fraction: Float, count: Long): Long {
        if (count <= 0L) return 0L
        val target = (count * fraction).toLong().coerceAtLeast(1L)
        var cumulative = 0L
        for (index in 0 until frameTimeHistogram.length()) {
            cumulative += frameTimeHistogram.get(index)
            if (cumulative >= target) return (1L shl index) * 1_000L
        }
        return maximumFrameTimeNs.get()
    }

    private fun updateMaximum(target: AtomicLong, value: Long) {
        var observed = target.get()
        while (value > observed && !target.compareAndSet(observed, value)) observed = target.get()
    }

    companion object {
        private const val FRAME_TIME_BIN_COUNT = 32
        private const val MICRO_SCALE = 1_000_000f
    }
}

