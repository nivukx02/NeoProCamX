package com.neoproc.neoproccamx.recording

/** Source-clock timing for NPRAW; never manufactures CFR timestamps or hides missing frames. */
internal object RawAudioTimelinePolicy {
    fun requireAssociation(
        firstSensorTimestampNs: Long,
        lastSensorTimestampNs: Long,
        fps: Int,
        audioOriginSensorTimestampNs: Long,
        audioEndUs: Long
    ) {
        val videoDurationUs = durationUs(firstSensorTimestampNs, lastSensorTimestampNs, fps)
        check(audioOriginSensorTimestampNs == firstSensorTimestampNs) {
            "RAW audio belongs to a different sensor timeline"
        }
        check(audioEndUs >= 0L && kotlin.math.abs(audioEndUs - videoDurationUs) <=
            Npv10AudioVideoDurationPolicy.MAXIMUM_ALLOWED_DELTA_US) {
            "RAW audio duration does not match its sensor stream"
        }
    }

    fun durationUs(firstSensorTimestampNs: Long, lastSensorTimestampNs: Long, fps: Int): Long {
        require(firstSensorTimestampNs > 0L && lastSensorTimestampNs >= firstSensorTimestampNs)
        require(fps > 0)
        return Math.addExact(
            Math.subtractExact(lastSensorTimestampNs, firstSensorTimestampNs) / 1_000L,
            (1_000_000L / fps).coerceAtLeast(1L)
        )
    }

    fun discardedPcmDurationUs(bytes: Long, sampleRate: Int, channelCount: Int): Long {
        require(bytes >= 0L && sampleRate > 0 && channelCount > 0)
        val bytesPerFrame = Math.multiplyExact(channelCount, 2)
        require(bytes % bytesPerFrame == 0L) { "Discarded PCM must contain whole stereo frames" }
        return RecordingAudioSyncPolicy.durationUsForFrames(bytes / bytesPerFrame, sampleRate)
    }
}
