package com.neoproc.neoproccamx.rendering

internal data class OffscreenEncoderFramePacingState(
    val targetFps: Int = 25,
    val nextDuePresentationTimeNs: Long = 0L,
    val lastSubmittedPresentationTimeNs: Long = 0L,
    val sessionBasePresentationTimeNs: Long = 0L
)

internal data class OffscreenEncoderFramePacingDecision(
    val submit: Boolean,
    val state: OffscreenEncoderFramePacingState,
    val submissionPresentationTimeNs: Long = 0L
)

internal object OffscreenEncoderFramePacingPolicy {
    private const val NANOS_PER_SECOND = 1_000_000_000L

    fun reset(targetFps: Int): OffscreenEncoderFramePacingState =
        OffscreenEncoderFramePacingState(targetFps = targetFps.coerceAtLeast(1))

    fun resolve(
        presentationTimeNs: Long,
        targetFps: Int,
        state: OffscreenEncoderFramePacingState
    ): OffscreenEncoderFramePacingDecision {
        val safeTargetFps = targetFps.coerceAtLeast(1)
        val normalizedState =
            if (state.targetFps == safeTargetFps) {
                state
            } else {
                reset(safeTargetFps)
            }
        if (presentationTimeNs <= 0L) {
            return OffscreenEncoderFramePacingDecision(
                submit = true,
                state = normalizedState
            )
        }

        val frameIntervalNs = (NANOS_PER_SECOND / safeTargetFps).coerceAtLeast(1L)
        
        // s6-f10: normalize timestamps relative to the first frame of the session.
        // This ensures the hardware HEVC encoder receives clean 0-based timing,
        // preventing silent frame drops / stalls due to absolute timebase skew.
        val baseNs = if (normalizedState.sessionBasePresentationTimeNs > 0L) {
            normalizedState.sessionBasePresentationTimeNs
        } else {
            presentationTimeNs
        }
        val relativePtsNs = (presentationTimeNs - baseNs).coerceAtLeast(0L)

        fun nextSubmissionPtsNs(): Long =
            if (normalizedState.lastSubmittedPresentationTimeNs > 0L) {
                normalizedState.lastSubmittedPresentationTimeNs + frameIntervalNs
            } else {
                baseNs // start at the actual base CLOCK_MONOTONIC timestamp!
            }

        val toleranceNs = (frameIntervalNs / 10L).coerceAtLeast(1_000_000L)
        val nextDueNs = normalizedState.nextDuePresentationTimeNs
        if (nextDueNs <= 0L) {
            val submissionPtsNs = nextSubmissionPtsNs()
            return OffscreenEncoderFramePacingDecision(
                submit = true,
                state = normalizedState.copy(
                    sessionBasePresentationTimeNs = baseNs,
                    nextDuePresentationTimeNs = relativePtsNs + frameIntervalNs,
                    lastSubmittedPresentationTimeNs = submissionPtsNs
                ),
                submissionPresentationTimeNs = submissionPtsNs
            )
        }

        val eligibleAtNs = relativePtsNs + toleranceNs
        if (eligibleAtNs < nextDueNs) {
            return OffscreenEncoderFramePacingDecision(
                submit = false,
                state = normalizedState
            )
        }

        val intervalsElapsed = ((eligibleAtNs - nextDueNs) / frameIntervalNs)
            .coerceAtLeast(0L)
        val submissionPtsNs = nextSubmissionPtsNs()
        return OffscreenEncoderFramePacingDecision(
            submit = true,
            state = normalizedState.copy(
                sessionBasePresentationTimeNs = baseNs,
                nextDuePresentationTimeNs = nextDueNs + (intervalsElapsed + 1L) * frameIntervalNs,
                lastSubmittedPresentationTimeNs = submissionPtsNs
            ),
            submissionPresentationTimeNs = submissionPtsNs
        )
    }
}

