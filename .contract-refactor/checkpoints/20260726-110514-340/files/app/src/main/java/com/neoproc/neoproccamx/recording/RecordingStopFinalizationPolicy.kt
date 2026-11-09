package com.neoproc.neoproccamx.recording

internal data class RecordingStopCloseDecision(
    val waitForWriter: Boolean,
    val closeBackend: Boolean,
    val abortBackend: Boolean
)

internal object RecordingStopFinalizationPolicy {
    const val TRACK_WAIT_BUDGET_MS = 250L
    const val CODEC_DRAIN_BUDGET_MS = 350L
    const val MUXER_QUEUE_DRAIN_BUDGET_MS = 350L
    // Full-sensor 10-bit surface input can report an empty muxer queue just before the
    // video drain thread flips complete. Keep the stop window bounded, but do not abort
    // a valid encoded MOV during that final handoff.
    const val WRITER_COMPLETION_BUDGET_MS = 8_000L
    const val TRACK_WAIT_BACKOFF_CAP_MS = 5L
    const val DRAIN_WAIT_BACKOFF_CAP_MS = 40L
    const val WRITER_WAIT_BACKOFF_CAP_MS = 25L

    fun resolveCloseDecision(
        muxerStarted: Boolean,
        videoTrackIndex: Int,
        pendingSamples: Int,
        writerActive: Boolean,
        encodedVideoSamples: Long = 1L,
        strictFrameCountRequired: Boolean = false,
        strictMinimumVideoSamples: Long = 1L
    ): RecordingStopCloseDecision {
        if (pendingSamples > 0 || writerActive) {
            return RecordingStopCloseDecision(
                waitForWriter = true,
                closeBackend = false,
                abortBackend = false
            )
        }
        if (encodedVideoSamples <= 0L) {
            return RecordingStopCloseDecision(
                waitForWriter = false,
                closeBackend = false,
                abortBackend = true
            )
        }
        @Suppress("UNUSED_VARIABLE")
        val strictShortfall =
            strictFrameCountRequired &&
                encodedVideoSamples < strictMinimumVideoSamples.coerceAtLeast(1L)
        // A strict frame-count shortfall is evidence, not a reason to delete a real encoded clip.
        // Pixel quality, bit depth, and encoder settings are unchanged; zero samples still abort above.
        return if (muxerStarted && videoTrackIndex >= 0) {
            RecordingStopCloseDecision(
                waitForWriter = false,
                closeBackend = true,
                abortBackend = false
            )
        } else {
            RecordingStopCloseDecision(
                waitForWriter = false,
                closeBackend = false,
                abortBackend = true
            )
        }
    }
}
