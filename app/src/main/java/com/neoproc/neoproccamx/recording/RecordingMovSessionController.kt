package com.neoproc.neoproccamx.recording

import java.nio.ByteBuffer

internal class RecordingMovSessionController(
    private val backend: RecordingContainerBackend
) {
    private var opened = false

    fun open(outputPath: String, containerLabel: String, orientationHintDegrees: Int) {
        backend.open(outputPath, containerLabel)
        opened = true
        if (orientationHintDegrees != 0) {
            backend.setOrientationHint(orientationHintDegrees)
        }
    }

    fun addVideoTrack(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        codecConfig: ByteArray,
        colorConfig: QuickTimeColorAtomConfig = QuickTimeColorAtomConfig.rec709SdrLimited()
    ): Int =
        backend.addVideoTrack(
            mime = mime,
            width = width,
            height = height,
            fps = fps,
            codecConfig = codecConfig,
            colorConfig = colorConfig
        )

    fun addAudioTrack(
        mime: String,
        sampleRate: Int,
        channelCount: Int,
        codecConfig: ByteArray
    ): Int =
        backend.addAudioTrack(
            mime = mime,
            sampleRate = sampleRate,
            channelCount = channelCount,
            codecConfig = codecConfig
        )

    fun writeSample(
        trackIndex: Int,
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        flags: Int
    ): Boolean {
        if (!opened) return false
        backend.writeSample(
            trackIndex = trackIndex,
            buffer = buffer,
            presentationTimeUs = presentationTimeUs,
            flags = flags
        )
        return true
    }

    fun close() {
        if (!opened) return
        backend.close()
        opened = false
    }

    fun abort() {
        if (!opened) return
        backend.abort()
        opened = false
    }

    /** Available after close/abort; survives controller retirement long enough for certification. */
    fun preallocationResult(): RecordingPreallocationResult? = backend.preallocationResult()
}
