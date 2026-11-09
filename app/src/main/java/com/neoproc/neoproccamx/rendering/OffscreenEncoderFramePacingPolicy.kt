package com.neoproc.neoproccamx.rendering

internal data class OffscreenEncoderFramePacingState(
    val targetFps: Int = 25,
    val nextDuePresentationTimeNs: Long = 0L,
    val lastSubmittedPresentationTimeNs: Long = 0L,
    val sessionBasePresentationTimeNs: Long = 0L,
    /** True only between the transport-readiness access unit and the first live camera frame. */
    val awaitingFirstLiveFrame: Boolean = false
)

internal data class OffscreenEncoderFramePacingDecision(
    val submit: Boolean,
    val state: OffscreenEncoderFramePacingState,
    val submissionPresentationTimeNs: Long = 0L,
    /** Allows device evidence to prove that readiness was bridged to live cadence exactly once. */
    val readinessHandoffRebased: Boolean = false
)

internal object OffscreenEncoderFramePacingPolicy {
    private const val NANOS_PER_SECOND = 1_000_000_000L

    fun reset(targetFps: Int): OffscreenEncoderFramePacingState =
        OffscreenEncoderFramePacingState(targetFps = targetFps.coerceAtLeast(1))

    /**
     * Continues the codec-surface timeline after its non-clip readiness access unit.
     *
     * The readiness frame and live camera frames must share one monotonic timebase. Starting the
     * readiness frame at 1 ns and the first live frame at CLOCK_MONOTONIC uptime can create a gap of
     * several days; some hardware encoders then retain every subsequent frame instead of emitting
     * output. The readiness frame is transport-only, so the encoder timeline—not camera preparation
     * latency—owns this boundary. The first live frame must therefore be exactly one target interval
     * after readiness and every later frame follows that same CFR cadence. Camera sensor timestamps
     * remain preserved separately as capture evidence and are never rewritten.
     */
    fun continueAfterReadiness(
        readinessPresentationTimeNs: Long,
        targetFps: Int
    ): OffscreenEncoderFramePacingState {
        val safeTargetFps = targetFps.coerceAtLeast(1)
        val readinessPtsNs = readinessPresentationTimeNs.coerceAtLeast(1L)
        return OffscreenEncoderFramePacingState(
            targetFps = safeTargetFps,
            nextDuePresentationTimeNs =
                (NANOS_PER_SECOND / safeTargetFps).coerceAtLeast(1L),
            lastSubmittedPresentationTimeNs = readinessPtsNs,
            sessionBasePresentationTimeNs = readinessPtsNs,
            awaitingFirstLiveFrame = true
        )
    }

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

    /**
     * Recorder-owned RAW/NDR ingress is already one exact camera master per requested sensor frame.
     * It must not use [resolve]'s rate-conversion drop decision: normal Camera2 timestamp jitter can
     * make a frame arrive a few milliseconds before the ideal phase even though the sustained source
     * cadence is correct. Preserve every master and provide MediaCodec with a monotonic CFR timeline.
     */
    fun resolveOneToOne(
        presentationTimeNs: Long,
        targetFps: Int,
        state: OffscreenEncoderFramePacingState
    ): OffscreenEncoderFramePacingDecision {
        val safeTargetFps = targetFps.coerceAtLeast(1)
        val normalizedState =
            if (state.targetFps == safeTargetFps) state else reset(safeTargetFps)
        val frameIntervalNs = (NANOS_PER_SECOND / safeTargetFps).coerceAtLeast(1L)
        val previousPtsNs = normalizedState.lastSubmittedPresentationTimeNs
        val minimumNextPtsNs = when {
            previousPtsNs <= 0L -> 1L
            previousPtsNs > Long.MAX_VALUE - frameIntervalNs -> Long.MAX_VALUE
            else -> previousPtsNs + frameIntervalNs
        }
        val liveClockPtsNs = presentationTimeNs.coerceAtLeast(1L)
        val readinessHandoff = normalizedState.awaitingFirstLiveFrame
        val submissionPtsNs = when {
            readinessHandoff -> minimumNextPtsNs
            previousPtsNs > 0L -> minimumNextPtsNs
            else -> liveClockPtsNs
        }
        val baseNs = when {
            readinessHandoff && normalizedState.sessionBasePresentationTimeNs > 0L ->
                normalizedState.sessionBasePresentationTimeNs
            normalizedState.sessionBasePresentationTimeNs > 0L ->
                normalizedState.sessionBasePresentationTimeNs
            else -> liveClockPtsNs
        }
        val relativeNextDueNs =
            (submissionPtsNs - baseNs).coerceAtLeast(0L) + frameIntervalNs
        return OffscreenEncoderFramePacingDecision(
            submit = true,
            state = normalizedState.copy(
                sessionBasePresentationTimeNs = baseNs,
                nextDuePresentationTimeNs = relativeNextDueNs,
                lastSubmittedPresentationTimeNs = submissionPtsNs,
                awaitingFirstLiveFrame = false
            ),
            submissionPresentationTimeNs = submissionPtsNs,
            readinessHandoffRebased = readinessHandoff
        )
    }
}
