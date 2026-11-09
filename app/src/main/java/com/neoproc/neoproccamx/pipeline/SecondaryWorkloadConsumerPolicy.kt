package com.neoproc.neoproccamx.pipeline

import kotlin.math.max
import kotlin.math.min

/**
 * Converts the adaptive controller's secondary-domain decision into bounded hot-path values.
 * Recording uses the hardware buffer watermark unchanged; only preview queueing and secondary
 * analytics cadence are allowed to contract under load.
 */
internal object SecondaryWorkloadConsumerPolicy {

    fun hardwareImportInFlightLimit(
        hardwareHighWatermark: Int,
        activeSubmitCount: Int,
        recordingActive: Boolean,
        decision: PipelineAdaptationDecision
    ): Int {
        val hardwareLimit = hardwareHighWatermark.coerceAtLeast(1)
        if (recordingActive) return hardwareLimit

        // previewQueueLimit describes queued frames, not the frame currently owned by the GPU submit.
        val previewInFlightLimit = decision.previewQueueLimit.coerceAtLeast(1) +
            activeSubmitCount.coerceIn(0, 1)
        return min(hardwareLimit, previewInFlightLimit).coerceAtLeast(1)
    }

    fun previewMaxFrameAgeNs(
        targetFps: Float,
        decision: PipelineAdaptationDecision
    ): Long {
        val frameIntervalNs = (1_000_000_000.0 / targetFps.coerceAtLeast(1f).toDouble())
            .toLong()
            .coerceAtLeast(1L)
        val intervalCount = if (decision.preferLatestPreviewFrame) 1L else 2L
        return if (frameIntervalNs > Long.MAX_VALUE / intervalCount) {
            Long.MAX_VALUE
        } else {
            frameIntervalNs * intervalCount
        }
    }

    fun analyticsStrideMultiplier(
        existingMultiplier: Long,
        decision: PipelineAdaptationDecision
    ): Long = max(
        existingMultiplier.coerceAtLeast(1L),
        decision.analyticsCadenceDivisor.coerceAtLeast(1).toLong()
    )
}
