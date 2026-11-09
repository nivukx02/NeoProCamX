package com.neoproc.neoproccamx.recording

/**
 * Separates camera/source cadence from losses introduced after a frame reaches the app.
 *
 * Wall time remains useful diagnostic evidence, but it is not an encoder-input count: Camera2 may
 * deliver slightly fewer frames while a device is thermally constrained. Publication is therefore
 * gated by exact accepted-submission ownership, encoder output cadence, and explicit loss counters.
 */
internal data class RecordingStrictStopFrameGateRequest(
    val enabled: Boolean,
    val wallExpectedFrameCount: Long,
    val submittedFrameCount: Long,
    val encodedSampleCount: Long,
    val hotPathDroppedFrames: Long,
    val frameIdentityLosses: Long,
    val pendingPresentations: Long,
    val unmatchedEncoderOutputs: Long,
    val encoderCadenceWithinTolerance: Boolean,
    val nonMonotonicPtsCount: Long
)

internal data class RecordingStrictStopFrameGateDecision(
    val required: Boolean,
    val minimumEncodedSamples: Long,
    val wallExpectedFrameCount: Long,
    val sourceCadenceShortfallFrames: Long,
    val applicationLossFrames: Long,
    val integrityPassed: Boolean,
    val failureReasons: List<String>
)

internal object RecordingStrictStopFrameGatePolicy {
    fun evaluate(request: RecordingStrictStopFrameGateRequest): RecordingStrictStopFrameGateDecision {
        val wallExpected = request.wallExpectedFrameCount.coerceAtLeast(1L)
        val submitted = request.submittedFrameCount.coerceAtLeast(0L)
        val encoded = request.encodedSampleCount.coerceAtLeast(0L)
        val hotPathDrops = request.hotPathDroppedFrames.coerceAtLeast(0L)
        val identityLosses = request.frameIdentityLosses.coerceAtLeast(0L)
        val pending = request.pendingPresentations.coerceAtLeast(0L)
        val unmatched = request.unmatchedEncoderOutputs.coerceAtLeast(0L)
        val nonMonotonic = request.nonMonotonicPtsCount.coerceAtLeast(0L)
        val submissionGap = (submitted - encoded).coerceAtLeast(0L)
        val unexpectedEncodedSamples = (encoded - submitted).coerceAtLeast(0L)
        val applicationLosses = saturatedSum(
            submissionGap,
            unexpectedEncodedSamples,
            hotPathDrops,
            identityLosses,
            pending,
            unmatched,
            nonMonotonic
        )
        val reasons = buildList {
            if (request.enabled && submitted <= 0L) add("no accepted encoder submissions")
            if (request.enabled && encoded != submitted) {
                add("accepted/encoded mismatch submitted=$submitted encoded=$encoded")
            }
            if (request.enabled && hotPathDrops > 0L) add("hot-path drops=$hotPathDrops")
            if (request.enabled && identityLosses > 0L) add("frame-identity losses=$identityLosses")
            if (request.enabled && pending > 0L) add("pending presentations=$pending")
            if (request.enabled && unmatched > 0L) add("unmatched encoder outputs=$unmatched")
            if (request.enabled && nonMonotonic > 0L) add("non-monotonic PTS=$nonMonotonic")
            if (request.enabled && !request.encoderCadenceWithinTolerance) {
                add("encoded PTS cadence outside target tolerance")
            }
        }
        return RecordingStrictStopFrameGateDecision(
            required = request.enabled,
            minimumEncodedSamples = submitted.coerceAtLeast(1L),
            wallExpectedFrameCount = wallExpected,
            sourceCadenceShortfallFrames = (wallExpected - submitted).coerceAtLeast(0L),
            applicationLossFrames = applicationLosses,
            integrityPassed = !request.enabled || reasons.isEmpty(),
            failureReasons = reasons
        )
    }

    private fun saturatedSum(vararg values: Long): Long = values.fold(0L) { total, value ->
        if (total > Long.MAX_VALUE - value) Long.MAX_VALUE else total + value
    }
}
