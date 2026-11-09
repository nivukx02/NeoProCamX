package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuVideoCodec

internal data class RecordingPrearmSignature(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val codec: GpuVideoCodec,
    val audioEnabled: Boolean,
    val tenBitEnabled: Boolean,
    val rawVideoEnabled: Boolean,
    val logEnabled: Boolean,
    val hdrMode: String,
    val colorSpace: String,
    val cinemaMode: CinemaVideoMode
)

internal object RecordingPrearmReusePolicy {
    fun shouldReusePreparedSurface(
        recordingStateReady: Boolean,
        surfaceAvailable: Boolean,
        preparedSignature: RecordingPrearmSignature?,
        expectedSignature: RecordingPrearmSignature
    ): Boolean =
        recordingStateReady &&
            surfaceAvailable &&
            preparedSignature == expectedSignature
}
