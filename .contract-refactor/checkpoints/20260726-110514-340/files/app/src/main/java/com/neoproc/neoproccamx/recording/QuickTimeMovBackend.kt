package com.neoproc.neoproccamx.recording

import java.nio.ByteBuffer

internal class QuickTimeMovBackend(
    private val bridge: QuickTimeMovBridge,
    private val preallocationPlan: RecordingPreallocationPolicy.Plan =
        RecordingPreallocationPolicy.processed(0L)
) : RecordingContainerBackend {
    private var handle: Long = 0L

    override fun open(outputPath: String, containerLabel: String) {
        handle = bridge.open(outputPath, containerLabel, preallocationPlan)
    }

    override fun addVideoTrack(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        codecConfig: ByteArray,
        colorConfig: QuickTimeColorAtomConfig
    ): Int {
        return bridge.addVideoTrack(
            handle,
            QuickTimeVideoTrackConfig(
                mime = mime,
                width = width,
                height = height,
                fps = fps,
                codecConfig = codecConfig,
                colorConfig = colorConfig
            )
        )
    }

    override fun addAudioTrack(
        mime: String,
        sampleRate: Int,
        channelCount: Int,
        codecConfig: ByteArray
    ): Int {
        return bridge.addAudioTrack(
            handle,
            QuickTimeAudioTrackConfig(
                mime = mime,
                sampleRate = sampleRate,
                channelCount = channelCount,
                codecConfig = codecConfig
            )
        )
    }

    override fun writeSample(
        trackIndex: Int,
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        flags: Int
    ) {
        bridge.writeSampleBuffer(
            handle = handle,
            trackIndex = trackIndex,
            buffer = buffer.slice(),
            presentationTimeUs = presentationTimeUs,
            flags = flags
        )
    }

    override fun close() {
        bridge.close(handle)
    }

    override fun abort() {
        bridge.abort(handle)
    }

    override fun setOrientationHint(degrees: Int) {
        bridge.setOrientationHint(handle, degrees)
    }
}
