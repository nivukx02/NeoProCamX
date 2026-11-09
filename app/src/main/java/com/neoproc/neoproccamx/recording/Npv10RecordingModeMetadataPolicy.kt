package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.SdrQualityIntent

internal object Npv10RecordingModeMetadataPolicy {
    fun resolve(
        requestedHdrMode: String,
        logEnabled: Boolean,
        sdrQualityIntent: SdrQualityIntent
    ): String {
        val normalizedHdrMode = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(requestedHdrMode)
        return when {
            normalizedHdrMode != "OFF" -> normalizedHdrMode
            logEnabled -> "LOG"
            sdrQualityIntent == SdrQualityIntent.NORMAL -> "NORMAL_SDR"
            else -> "PRO_SDR"
        }
    }
}
