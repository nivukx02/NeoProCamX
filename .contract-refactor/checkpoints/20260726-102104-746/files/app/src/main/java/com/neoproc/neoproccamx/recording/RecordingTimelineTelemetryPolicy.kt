package com.neoproc.neoproccamx.recording

import kotlin.math.abs

internal object RecordingTimelineTelemetryPolicy {
    fun framePacingJitterMs(
        previousVideoPtsUs: Long,
        currentVideoPtsUs: Long,
        frameRate: Float
    ): Long? {
        if (previousVideoPtsUs <= 0L || currentVideoPtsUs <= previousVideoPtsUs || frameRate <= 0f) {
            return null
        }
        val expectedDeltaMs = (1_000f / frameRate).toLong().coerceAtLeast(1L)
        val actualDeltaMs = (currentVideoPtsUs - previousVideoPtsUs) / 1_000L
        return abs(actualDeltaMs - expectedDeltaMs)
    }

    fun avSyncDriftMs(
        latestVideoPtsUs: Long,
        latestAudioPtsUs: Long
    ): Long? {
        if (latestVideoPtsUs < 0L || latestAudioPtsUs < 0L) return null
        return abs(latestVideoPtsUs - latestAudioPtsUs) / 1_000L
    }
}
