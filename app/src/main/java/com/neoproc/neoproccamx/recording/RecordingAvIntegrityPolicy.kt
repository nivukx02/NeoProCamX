package com.neoproc.neoproccamx.recording

internal data class RecordingAvIntegrityAudit(
    val audioRequested: Boolean,
    val videoSampleCount: Long,
    val audioSampleCount: Long,
    val startOffsetUs: Long,
    val videoDurationUs: Long,
    val audioDurationUs: Long,
    val durationDriftUs: Long,
    val passed: Boolean,
    val failureReason: String?
)

/**
 * Publication gate based on samples that were durably committed to the container.
 * Encoder configuration and track creation alone are not proof that a clip contains usable audio.
 */
internal object RecordingAvIntegrityPolicy {
    const val MAXIMUM_START_OFFSET_US = 40_000L
    const val MAXIMUM_DURATION_DRIFT_US = 20_000L
    private const val DEFAULT_AUDIO_SAMPLE_RATE = 48_000
    private const val AAC_LC_FRAME_SAMPLES = 1024

    fun audit(
        audioRequested: Boolean,
        videoTrackIndex: Int,
        audioTrackIndex: Int,
        videoSampleCount: Long,
        audioSampleCount: Long,
        firstVideoPtsUs: Long,
        lastVideoPtsUs: Long,
        firstAudioPtsUs: Long,
        lastAudioPtsUs: Long,
        videoFps: Int,
        audioSampleRate: Int = DEFAULT_AUDIO_SAMPLE_RATE
    ): RecordingAvIntegrityAudit {
        if (!audioRequested) {
            return RecordingAvIntegrityAudit(
                audioRequested = false,
                videoSampleCount = videoSampleCount,
                audioSampleCount = audioSampleCount,
                startOffsetUs = 0L,
                videoDurationUs = 0L,
                audioDurationUs = 0L,
                durationDriftUs = 0L,
                passed = true,
                failureReason = null
            )
        }

        val structuralFailure = when {
            videoTrackIndex < 0 -> "requested A/V recording has no video track"
            audioTrackIndex < 0 -> "requested A/V recording has no audio track"
            videoSampleCount <= 0L -> "requested A/V recording committed no video samples"
            audioSampleCount <= 0L -> "requested A/V recording committed no audio samples"
            firstVideoPtsUs < 0L || lastVideoPtsUs < firstVideoPtsUs ->
                "requested A/V recording has an invalid committed video timeline"
            firstAudioPtsUs < 0L || lastAudioPtsUs < firstAudioPtsUs ->
                "requested A/V recording has an invalid committed audio timeline"
            videoFps <= 0 -> "requested A/V recording has an invalid video frame rate"
            audioSampleRate <= 0 -> "requested A/V recording has an invalid audio sample rate"
            else -> null
        }
        if (structuralFailure != null) {
            return RecordingAvIntegrityAudit(
                audioRequested = true,
                videoSampleCount = videoSampleCount,
                audioSampleCount = audioSampleCount,
                startOffsetUs = Long.MAX_VALUE,
                videoDurationUs = Long.MAX_VALUE,
                audioDurationUs = Long.MAX_VALUE,
                durationDriftUs = Long.MAX_VALUE,
                passed = false,
                failureReason = structuralFailure
            )
        }

        val videoFrameDurationUs = 1_000_000L / videoFps.toLong()
        val audioFrameDurationUs =
            AAC_LC_FRAME_SAMPLES.toLong() * 1_000_000L / audioSampleRate.toLong()
        val videoDurationUs = lastVideoPtsUs - firstVideoPtsUs + videoFrameDurationUs
        val audioDurationUs = lastAudioPtsUs - firstAudioPtsUs + audioFrameDurationUs
        val startOffsetUs = absoluteDifference(firstVideoPtsUs, firstAudioPtsUs)
        val durationDriftUs = absoluteDifference(videoDurationUs, audioDurationUs)
        val failureReason = when {
            startOffsetUs > MAXIMUM_START_OFFSET_US ->
                "A/V start offset ${startOffsetUs}us exceeds ${MAXIMUM_START_OFFSET_US}us"
            durationDriftUs > MAXIMUM_DURATION_DRIFT_US ->
                "A/V duration drift ${durationDriftUs}us exceeds ${MAXIMUM_DURATION_DRIFT_US}us"
            else -> null
        }
        return RecordingAvIntegrityAudit(
            audioRequested = true,
            videoSampleCount = videoSampleCount,
            audioSampleCount = audioSampleCount,
            startOffsetUs = startOffsetUs,
            videoDurationUs = videoDurationUs,
            audioDurationUs = audioDurationUs,
            durationDriftUs = durationDriftUs,
            passed = failureReason == null,
            failureReason = failureReason
        )
    }

    /**
     * Certificate evidence is derived only from samples committed to the container. Producer-side
     * lead/lag remains visible as a separate diagnostic and cannot invalidate an otherwise correct
     * finalized timeline.
     */
    fun toCertificateMetadata(
        audit: RecordingAvIntegrityAudit?,
        transientProducerLeadLagMs: Long
    ): Map<String, String> = linkedMapOf(
        "recordingCommittedAvAuditPresent" to (audit != null).toString(),
        "recordingCommittedAvAuditPassed" to (audit?.passed == true).toString(),
        "recordingCommittedAvAudioRequested" to audit?.audioRequested?.toString().orEmpty(),
        "recordingCommittedAvVideoSampleCount" to audit?.videoSampleCount?.toString().orEmpty(),
        "recordingCommittedAvAudioSampleCount" to audit?.audioSampleCount?.toString().orEmpty(),
        "recordingCommittedAvStartOffsetUs" to audit?.startOffsetUs?.toString().orEmpty(),
        "recordingCommittedAvVideoDurationUs" to audit?.videoDurationUs?.toString().orEmpty(),
        "recordingCommittedAvAudioDurationUs" to audit?.audioDurationUs?.toString().orEmpty(),
        "recordingCommittedAvDurationDriftUs" to audit?.durationDriftUs?.toString().orEmpty(),
        "recordingCommittedAvMaximumStartOffsetUs" to MAXIMUM_START_OFFSET_US.toString(),
        "recordingCommittedAvMaximumDurationDriftUs" to MAXIMUM_DURATION_DRIFT_US.toString(),
        "recordingCommittedAvFailureReason" to audit?.failureReason.orEmpty(),
        "recordingTransientProducerLeadLagMaxMs" to
            transientProducerLeadLagMs.coerceAtLeast(0L).toString()
    )

    fun maximumCommittedTimingErrorMs(audit: RecordingAvIntegrityAudit?): Long {
        if (audit == null) return Long.MAX_VALUE
        val maximumUs = maxOf(audit.startOffsetUs, audit.durationDriftUs).coerceAtLeast(0L)
        return if (maximumUs > Long.MAX_VALUE - 999L) {
            Long.MAX_VALUE
        } else {
            (maximumUs + 999L) / 1_000L
        }
    }

    private fun absoluteDifference(first: Long, second: Long): Long =
        if (first >= second) first - second else second - first
}
