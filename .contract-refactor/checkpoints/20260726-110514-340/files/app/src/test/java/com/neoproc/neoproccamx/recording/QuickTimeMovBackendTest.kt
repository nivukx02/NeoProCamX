package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class QuickTimeMovBackendTest {

    @Test
    fun encodedSamples_areForwardedToBridge() {
        val bridge = FakeQuickTimeMovBridge()
        val backend = QuickTimeMovBackend(bridge)

        backend.open(outputPath = "/tmp/output.mov", containerLabel = "QuickTime MOV")
        val videoTrack = backend.addVideoTrack(
            mime = "video/hevc",
            width = 3840,
            height = 2160,
            fps = 30,
            codecConfig = byteArrayOf(1, 2, 3),
            colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited()
        )
        val audioTrack = backend.addAudioTrack(
            mime = "audio/mp4a-latm",
            sampleRate = 48_000,
            channelCount = 2,
            codecConfig = byteArrayOf(0x12, 0x10)
        )
        backend.writeSample(
            trackIndex = videoTrack,
            buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)),
            presentationTimeUs = 0L,
            flags = 1
        )
        backend.writeSample(
            trackIndex = audioTrack,
            buffer = ByteBuffer.wrap(byteArrayOf(5, 6, 7, 8)),
            presentationTimeUs = 1_024L,
            flags = 0
        )

        assertEquals(listOf("open", "videoTrack", "audioTrack", "sampleBuffer", "sampleBuffer"), bridge.calls)
    }

    private class FakeQuickTimeMovBridge : QuickTimeMovBridge {
        val calls = mutableListOf<String>()

        override fun open(outputPath: String, containerLabel: String): Long {
            calls += "open"
            return 41L
        }

        override fun addVideoTrack(handle: Long, config: QuickTimeVideoTrackConfig): Int {
            calls += "videoTrack"
            return 0
        }

        override fun addAudioTrack(handle: Long, config: QuickTimeAudioTrackConfig): Int {
            calls += "audioTrack"
            return 1
        }

        override fun writeSample(handle: Long, trackIndex: Int, sample: QuickTimeSampleBuffer) {
            calls += "sample"
        }

        override fun writeSampleBuffer(
            handle: Long,
            trackIndex: Int,
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            flags: Int
        ) {
            calls += "sampleBuffer"
        }

        override fun setOrientationHint(handle: Long, degrees: Int) = Unit

        override fun close(handle: Long) = Unit

        override fun abort(handle: Long) = Unit
    }
}
