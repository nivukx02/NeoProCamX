package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class RecordingMovSessionControllerTest {

    @Test
    fun openAppliesOrientationBeforeTracksAndSamples() {
        val backend = FakeBackend()
        val controller = RecordingMovSessionController(backend)

        controller.open("/proc/self/fd/42", "QuickTime MOV", orientationHintDegrees = 90)
        val videoTrack = controller.addVideoTrack("video/hevc", 3840, 2160, 30, byteArrayOf(1, 2))
        val audioTrack = controller.addAudioTrack("audio/mp4a-latm", 48_000, 2, byteArrayOf(0x12, 0x10))
        controller.writeSample(videoTrack, ByteBuffer.wrap(byteArrayOf(1, 2, 3)), 0L, 1)
        controller.writeSample(audioTrack, ByteBuffer.wrap(byteArrayOf(4, 5)), 1_024L, 0)
        controller.close()

        assertEquals(
            listOf("open:/proc/self/fd/42:QuickTime MOV", "orientation:90", "video", "audio", "sample:0", "sample:1", "close"),
            backend.calls
        )
    }

    @Test
    fun zeroOrientationDoesNotSendRedundantOrientationHint() {
        val backend = FakeBackend()
        val controller = RecordingMovSessionController(backend)

        controller.open("/tmp/out.mov", "QuickTime MOV", orientationHintDegrees = 0)
        controller.close()

        assertEquals(listOf("open:/tmp/out.mov:QuickTime MOV", "close"), backend.calls)
    }

    @Test
    fun abortClosesBackendWithAbortOnly() {
        val backend = FakeBackend()
        val controller = RecordingMovSessionController(backend)

        controller.open("/tmp/out.mov", "QuickTime MOV", orientationHintDegrees = 0)
        controller.abort()
        controller.close()

        assertEquals(listOf("open:/tmp/out.mov:QuickTime MOV", "abort"), backend.calls)
    }

    @Test
    fun lateWritesAfterCloseOrAbortAreIgnored() {
        val closedBackend = FakeBackend()
        val closed = RecordingMovSessionController(closedBackend)
        closed.open("/tmp/closed.mov", "QuickTime MOV", orientationHintDegrees = 0)
        closed.close()
        closed.writeSample(0, ByteBuffer.wrap(byteArrayOf(1)), 0L, 0)

        val abortedBackend = FakeBackend()
        val aborted = RecordingMovSessionController(abortedBackend)
        aborted.open("/tmp/aborted.mov", "QuickTime MOV", orientationHintDegrees = 0)
        aborted.abort()
        aborted.writeSample(0, ByteBuffer.wrap(byteArrayOf(2)), 0L, 0)

        assertEquals(listOf("open:/tmp/closed.mov:QuickTime MOV", "close"), closedBackend.calls)
        assertEquals(listOf("open:/tmp/aborted.mov:QuickTime MOV", "abort"), abortedBackend.calls)
    }

    private class FakeBackend : RecordingContainerBackend {
        val calls = mutableListOf<String>()

        override fun open(outputPath: String, containerLabel: String) {
            calls += "open:$outputPath:$containerLabel"
        }

        override fun addVideoTrack(
            mime: String,
            width: Int,
            height: Int,
            fps: Int,
            codecConfig: ByteArray,
            colorConfig: QuickTimeColorAtomConfig
        ): Int {
            calls += "video"
            return 0
        }

        override fun addAudioTrack(
            mime: String,
            sampleRate: Int,
            channelCount: Int,
            codecConfig: ByteArray
        ): Int {
            calls += "audio"
            return 1
        }

        override fun writeSample(
            trackIndex: Int,
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            flags: Int
        ) {
            calls += "sample:$trackIndex"
        }

        override fun close() {
            calls += "close"
        }

        override fun abort() {
            calls += "abort"
        }

        override fun setOrientationHint(degrees: Int) {
            calls += "orientation:$degrees"
        }
    }
}
