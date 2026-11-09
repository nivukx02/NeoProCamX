package com.neoproc.neoproccamx.recording

import java.util.Locale
import kotlin.math.abs

/** Runtime wire evidence from actual dequeued encoder access units, not requested MediaFormat. */
internal data class EncoderOutputCadenceEvidence(
    val status: String,
    val submittedFrameCount: Long,
    val encodedSampleCount: Long,
    val submissionToEncodeGap: Long,
    val keyFrameCount: Long,
    val keyFrameRatio: Double,
    val firstPtsUs: Long,
    val lastPtsUs: Long,
    val minimumPtsDeltaUs: Long,
    val maximumPtsDeltaUs: Long,
    val averagePtsDeltaUs: Double,
    val observedFps: Double,
    val targetFps: Int,
    val nonMonotonicPtsCount: Long,
    val outOfCadenceDeltaCount: Long,
    val allIntraExpected: Boolean,
    val unexpectedRepeatedKeyFrames: Boolean,
    val cadenceWithinTolerance: Boolean
)

/** Thread-safe because callback/block-model and polling drains use different execution contexts. */
internal class EncoderOutputCadenceTracker {
    private var sampleCount = 0L
    private var keyFrameCount = 0L
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L
    private var minimumDeltaUs = Long.MAX_VALUE
    private var maximumDeltaUs = 0L
    private var deltaTotalUs = 0L
    private var deltaCount = 0L
    private var nonMonotonicCount = 0L

    @Synchronized
    fun reset() {
        sampleCount = 0L
        keyFrameCount = 0L
        firstPtsUs = -1L
        lastPtsUs = -1L
        minimumDeltaUs = Long.MAX_VALUE
        maximumDeltaUs = 0L
        deltaTotalUs = 0L
        deltaCount = 0L
        nonMonotonicCount = 0L
    }

    @Synchronized
    fun record(presentationTimeUs: Long, keyFrame: Boolean) {
        if (sampleCount == 0L) {
            firstPtsUs = presentationTimeUs
        } else {
            val delta = presentationTimeUs - lastPtsUs
            if (delta <= 0L) {
                nonMonotonicCount += 1L
            } else {
                minimumDeltaUs = minOf(minimumDeltaUs, delta)
                maximumDeltaUs = maxOf(maximumDeltaUs, delta)
                deltaTotalUs = saturatedAdd(deltaTotalUs, delta)
                deltaCount += 1L
            }
        }
        lastPtsUs = presentationTimeUs
        sampleCount += 1L
        if (keyFrame) keyFrameCount += 1L
    }

    @Synchronized
    fun snapshot(
        submittedFrameCount: Long,
        targetFps: Int,
        allIntraExpected: Boolean
    ): EncoderOutputCadenceEvidence {
        val safeTargetFps = targetFps.coerceAtLeast(1)
        val expectedDeltaUs = 1_000_000.0 / safeTargetFps.toDouble()
        val averageDeltaUs = if (deltaCount > 0L) {
            deltaTotalUs.toDouble() / deltaCount.toDouble()
        } else {
            0.0
        }
        val observedFps = if (sampleCount > 1L && lastPtsUs > firstPtsUs) {
            (sampleCount - 1L).toDouble() * 1_000_000.0 /
                (lastPtsUs - firstPtsUs).toDouble()
        } else {
            0.0
        }
        val keyRatio = if (sampleCount > 0L) {
            keyFrameCount.toDouble() / sampleCount.toDouble()
        } else {
            0.0
        }
        val outOfCadence = if (deltaCount > 0L) {
            // This tracker stores aggregate extrema; exact per-delta accounting is intentionally
            // derived conservatively so telemetry never invents a successful cadence.
            listOf(minimumDeltaUs, maximumDeltaUs).count { delta ->
                abs(delta.toDouble() - expectedDeltaUs) > expectedDeltaUs * 0.25
            }.toLong()
        } else {
            0L
        }
        val repeatedKeyFrames =
            !allIntraExpected && sampleCount >= MIN_SUSTAINED_SAMPLE_COUNT && keyRatio >= 0.90
        val cadenceProven =
            sampleCount >= MIN_SUSTAINED_SAMPLE_COUNT &&
                nonMonotonicCount == 0L &&
                observedFps >= safeTargetFps.toDouble() * 0.95 &&
                maximumDeltaUs.toDouble() <= expectedDeltaUs * 1.50
        val status = when {
            sampleCount < MIN_SUSTAINED_SAMPLE_COUNT -> STATUS_NOT_PROVEN
            repeatedKeyFrames -> STATUS_UNEXPECTED_ALL_INTRA
            !cadenceProven -> STATUS_CADENCE_MISMATCH
            else -> STATUS_PROVEN
        }
        return EncoderOutputCadenceEvidence(
            status = status,
            submittedFrameCount = submittedFrameCount.coerceAtLeast(0L),
            encodedSampleCount = sampleCount,
            submissionToEncodeGap =
                (submittedFrameCount - sampleCount).coerceAtLeast(0L),
            keyFrameCount = keyFrameCount,
            keyFrameRatio = keyRatio,
            firstPtsUs = firstPtsUs,
            lastPtsUs = lastPtsUs,
            minimumPtsDeltaUs = if (minimumDeltaUs == Long.MAX_VALUE) -1L else minimumDeltaUs,
            maximumPtsDeltaUs = if (deltaCount == 0L) -1L else maximumDeltaUs,
            averagePtsDeltaUs = averageDeltaUs,
            observedFps = observedFps,
            targetFps = safeTargetFps,
            nonMonotonicPtsCount = nonMonotonicCount,
            outOfCadenceDeltaCount = outOfCadence,
            allIntraExpected = allIntraExpected,
            unexpectedRepeatedKeyFrames = repeatedKeyFrames,
            cadenceWithinTolerance = cadenceProven
        )
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    companion object {
        const val STATUS_PROVEN = "PROVEN"
        const val STATUS_NOT_PROVEN = "NOT_PROVEN"
        const val STATUS_UNEXPECTED_ALL_INTRA = "UNEXPECTED_ALL_INTRA"
        const val STATUS_CADENCE_MISMATCH = "CADENCE_MISMATCH"
        private const val MIN_SUSTAINED_SAMPLE_COUNT = 8L

        fun toMetadata(evidence: EncoderOutputCadenceEvidence): Map<String, String> = linkedMapOf(
            "encoderCadenceStatus" to evidence.status,
            "encoderCadenceSubmittedFrames" to evidence.submittedFrameCount.toString(),
            "encoderCadenceEncodedSamples" to evidence.encodedSampleCount.toString(),
            "encoderCadenceSubmissionToEncodeGap" to evidence.submissionToEncodeGap.toString(),
            "encoderCadenceKeyFrames" to evidence.keyFrameCount.toString(),
            "encoderCadenceKeyFrameRatio" to
                String.format(Locale.US, "%.6f", evidence.keyFrameRatio),
            "encoderCadenceFirstPtsUs" to evidence.firstPtsUs.toString(),
            "encoderCadenceLastPtsUs" to evidence.lastPtsUs.toString(),
            "encoderCadenceMinimumPtsDeltaUs" to evidence.minimumPtsDeltaUs.toString(),
            "encoderCadenceMaximumPtsDeltaUs" to evidence.maximumPtsDeltaUs.toString(),
            "encoderCadenceAveragePtsDeltaUs" to
                String.format(Locale.US, "%.3f", evidence.averagePtsDeltaUs),
            "encoderCadenceObservedFps" to
                String.format(Locale.US, "%.6f", evidence.observedFps),
            "encoderCadenceTargetFps" to evidence.targetFps.toString(),
            "encoderCadenceNonMonotonicPts" to evidence.nonMonotonicPtsCount.toString(),
            "encoderCadenceOutOfCadenceExtrema" to evidence.outOfCadenceDeltaCount.toString(),
            "encoderCadenceAllIntraExpected" to evidence.allIntraExpected.toString(),
            "encoderCadenceUnexpectedRepeatedKeyFrames" to
                evidence.unexpectedRepeatedKeyFrames.toString(),
            "encoderCadenceWithinTolerance" to evidence.cadenceWithinTolerance.toString()
        )
    }
}
