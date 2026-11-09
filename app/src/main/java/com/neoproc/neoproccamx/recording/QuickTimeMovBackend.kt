package com.neoproc.neoproccamx.recording

import java.nio.ByteBuffer

internal class QuickTimeMovBackend(
    private val bridge: QuickTimeMovBridge,
    private val preallocationPlan: RecordingPreallocationPolicy.Plan =
        RecordingPreallocationPolicy.processed(0L)
) : RecordingContainerBackend {
    private var handle: Long = 0L
    private var finalizedPreallocationResult: RecordingPreallocationResult? = null

    override fun open(outputPath: String, containerLabel: String) {
        check(handle == 0L) { "QuickTime MOV backend is already open" }
        finalizedPreallocationResult = null
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
        val activeHandle = handle
        if (activeHandle == 0L) return
        try {
            finalizedPreallocationResult = bridge.closeWithPreallocationResult(activeHandle)
        } finally {
            handle = 0L
        }
    }

    override fun abort() {
        val activeHandle = handle
        if (activeHandle == 0L) return
        try {
            finalizedPreallocationResult = bridge.abortWithPreallocationResult(activeHandle)
        } finally {
            handle = 0L
        }
    }

    override fun setOrientationHint(degrees: Int) {
        bridge.setOrientationHint(handle, degrees)
    }

    override fun preallocationResult(): RecordingPreallocationResult? =
        finalizedPreallocationResult
}
