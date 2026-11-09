package com.neoproc.neoproccamx.recording

internal data class RecordingFramePresentation(
    val sensorTimestampNs: Long,
    val encoderPresentationTimeUs: Long,
    val submittedAtNs: Long
)
