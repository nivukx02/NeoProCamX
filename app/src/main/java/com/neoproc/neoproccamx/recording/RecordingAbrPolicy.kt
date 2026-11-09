package com.neoproc.neoproccamx.recording

import android.media.MediaCodec
import android.os.Bundle
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.max
import kotlin.math.roundToInt

internal data class RecordingAbrDecision(
    val backpressureActive: Boolean,
    val shouldSetCodecParameters: Boolean,
    val targetBitrateBps: Int,
    val shaderEntropyScale: Float,
    val detailGainScale: Float,
    val ditherAmplitudeScale: Float,
    val pressureScore: Float
) {
    companion object {
        fun stable(targetBitrateBps: Int): RecordingAbrDecision =
            RecordingAbrDecision(
                backpressureActive = false,
                shouldSetCodecParameters = false,
                targetBitrateBps = targetBitrateBps.coerceAtLeast(1),
                shaderEntropyScale = 1f,
                detailGainScale = 1f,
                ditherAmplitudeScale = 1f,
                pressureScore = 0f
            )
    }
}

/**
 * Runtime ABR policy for the recording encoder path.
 *
 * This does not silently lower requested quality. It only reacts to measured
 * encoder/muxer backpressure and keeps the bitrate above the caller-provided
 * floor while reducing shader entropy knobs enough to protect frame pacing.
 */
internal object RecordingAbrPolicy {
    const val ENCODER_LATENCY_GUARD_MS = 16L
    const val EVALUATION_INTERVAL_FRAMES = 3L

    fun shouldEvaluate(frameIndex: Long): Boolean =
        frameIndex >= 0L && frameIndex % EVALUATION_INTERVAL_FRAMES == 0L

    fun resolve(
        frameIndex: Long,
        encoderQueueLatencyMs: Long,
        targetBitrateBps: Int,
        minimumBitrateBps: Int,
        queueOccupancy: Float,
        thermalPressure: Float,
        allowBitrateReduction: Boolean = true
    ): RecordingAbrDecision {
        val safeTarget = targetBitrateBps.coerceAtLeast(1)
        if (!shouldEvaluate(frameIndex)) {
            return RecordingAbrDecision.stable(safeTarget)
        }

        val latencyPressure =
            ((encoderQueueLatencyMs - ENCODER_LATENCY_GUARD_MS).toFloat() / ENCODER_LATENCY_GUARD_MS)
                .coerceIn(0f, 1f)
        val occupancyPressure = ((queueOccupancy.coerceIn(0f, 1f) - 0.70f) / 0.30f).coerceIn(0f, 1f)
        val thermalPenalty = thermalPressure.coerceIn(0f, 1f)
        val pressure = max(max(latencyPressure, occupancyPressure), thermalPenalty)
        if (pressure <= 0f) {
            return RecordingAbrDecision.stable(safeTarget)
        }

        if (!allowBitrateReduction) {
            return RecordingAbrDecision(
                backpressureActive = true,
                shouldSetCodecParameters = false,
                targetBitrateBps = safeTarget,
                shaderEntropyScale = 1f,
                detailGainScale = 1f,
                ditherAmplitudeScale = 1f,
                pressureScore = pressure
            )
        }

        val bitrateScale = (1f - 0.18f * pressure).coerceIn(0.72f, 1f)
        val floor = minimumBitrateBps.coerceAtLeast(1).coerceAtMost(safeTarget)
        val targetBitrate = (safeTarget * bitrateScale)
            .roundToInt()
            .coerceIn(floor, safeTarget)
        val strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        val shaderEntropyFloor = if (strictHighEnd) 0.82f else 0.60f
        val detailGainFloor = if (strictHighEnd) 0.88f else 0.68f
        val ditherAmplitudeFloor = if (strictHighEnd) 0.84f else 0.58f
        return RecordingAbrDecision(
            backpressureActive = true,
            shouldSetCodecParameters = targetBitrate < safeTarget,
            targetBitrateBps = targetBitrate,
            shaderEntropyScale = (1f - 0.30f * pressure).coerceIn(shaderEntropyFloor, 1f),
            detailGainScale = (1f - 0.22f * pressure).coerceIn(detailGainFloor, 1f),
            ditherAmplitudeScale = (1f - 0.28f * pressure).coerceIn(ditherAmplitudeFloor, 1f),
            pressureScore = pressure
        )
    }

    fun toCodecParameters(decision: RecordingAbrDecision): Bundle =
        Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, decision.targetBitrateBps)
        }

    fun toCodecParameterMap(decision: RecordingAbrDecision): Map<String, Int> =
        mapOf(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE to decision.targetBitrateBps)
}
