package com.neoproc.neoproccamx.recording

import java.nio.ByteBuffer

internal interface RecordingContainerBackend {
    fun open(outputPath: String, containerLabel: String)
    fun addVideoTrack(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        codecConfig: ByteArray,
        colorConfig: QuickTimeColorAtomConfig
    ): Int

    fun addAudioTrack(
        mime: String,
        sampleRate: Int,
        channelCount: Int,
        codecConfig: ByteArray
    ): Int

    fun writeSample(
        trackIndex: Int,
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        flags: Int
    )

    fun close()
    fun abort()
    fun setOrientationHint(degrees: Int) {}  // Default no-op for backwards compat
    /** Finalized physical-allocation evidence when this backend owns a preallocating container. */
    fun preallocationResult(): RecordingPreallocationResult? = null
}
