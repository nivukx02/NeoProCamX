package com.neoproc.neoproccamx.recording

import androidx.annotation.OptIn
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files

@OptIn(UnstableApi::class)
class NivukxMedia3ExtractorTest {
    @Test
    fun `extractor feeds original access units and publishes a seekable keyframe map`() {
        val outputFile = Files.createTempFile("nivukx-media3", ".nivukx").toFile()
        val videoOne = ByteArray(4_099) { it.toByte() }
        val videoTwo = ByteArray(8_203) { (it * 3).toByte() }
        val audio = ByteArray(301) { (it * 11).toByte() }
        try {
            val backend = NivukxStreamingContainerBackend()
            backend.open(outputFile.absolutePath, NivukxContainerFormat.USER_VISIBLE_LABEL)
            val videoTrack = backend.addVideoTrack(
                mime = "video/hevc",
                width = 3_840,
                height = 2_160,
                fps = 60,
                codecConfig = byteArrayOf(0, 0, 0, 1, 0x40),
                colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited()
            )
            val audioTrack = backend.addAudioTrack(
                mime = "audio/mp4a-latm",
                sampleRate = 48_000,
                channelCount = 2,
                codecConfig = byteArrayOf(0x12, 0x10)
            )
            backend.writeSample(videoTrack, ByteBuffer.wrap(videoOne), 0L, 1)
            backend.writeSample(audioTrack, ByteBuffer.wrap(audio), 5_000L, 0)
            backend.writeSample(videoTrack, ByteBuffer.wrap(videoTwo), 16_667L, 1)
            backend.close()

            val fileBytes = outputFile.readBytes()
            val extractor = NivukxMedia3Extractor()
            val extractorOutput = CapturingExtractorOutput()
            extractor.init(extractorOutput)
            val sniffInput = extractorInput(fileBytes, 0)
            assertTrue(extractor.sniff(sniffInput))

            var input = extractorInput(fileBytes, 0)
            val positionHolder = PositionHolder()
            var iterations = 0
            while (iterations++ < 100) {
                when (extractor.read(input, positionHolder)) {
                    Extractor.RESULT_CONTINUE -> Unit
                    Extractor.RESULT_SEEK -> input = extractorInput(fileBytes, positionHolder.position.toInt())
                    Extractor.RESULT_END_OF_INPUT -> break
                }
            }

            assertTrue(iterations < 100)
            assertEquals(2, extractorOutput.tracks.size)
            assertEquals(2, extractorOutput.tracks.getValue(videoTrack).samples.size)
            assertEquals(1, extractorOutput.tracks.getValue(audioTrack).samples.size)
            assertArrayEquals(videoOne, extractorOutput.tracks.getValue(videoTrack).samples[0].bytes)
            assertArrayEquals(videoTwo, extractorOutput.tracks.getValue(videoTrack).samples[1].bytes)
            assertArrayEquals(audio, extractorOutput.tracks.getValue(audioTrack).samples.single().bytes)
            assertEquals(listOf(0L, 16_667L), extractorOutput.tracks.getValue(videoTrack).samples.map { it.timeUs })
            assertNotNull(extractorOutput.seekMap)
            assertTrue(extractorOutput.seekMap?.isSeekable == true)
            assertEquals(2, extractorOutput.seekMap?.getSeekPoints(10_000L)?.let { points ->
                if (points.first == points.second) 1 else 2
            })
        } finally {
            outputFile.delete()
        }
    }

    private fun extractorInput(bytes: ByteArray, offset: Int): DefaultExtractorInput =
        DefaultExtractorInput(
            ByteArrayDataReader(bytes, offset),
            offset.toLong(),
            bytes.size.toLong()
        )

    private class ByteArrayDataReader(
        private val bytes: ByteArray,
        startOffset: Int
    ) : DataReader {
        private var offset = startOffset

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (this.offset >= bytes.size) return -1
            val count = minOf(length, bytes.size - this.offset)
            bytes.copyInto(buffer, destinationOffset = offset, startIndex = this.offset, endIndex = this.offset + count)
            this.offset += count
            return count
        }
    }

    private class CapturingExtractorOutput : ExtractorOutput {
        val tracks = LinkedHashMap<Int, CapturingTrackOutput>()
        var seekMap: SeekMap? = null

        override fun track(id: Int, type: Int): TrackOutput =
            tracks.getOrPut(id) { CapturingTrackOutput() }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private class CapturingTrackOutput : TrackOutput {
        data class Sample(val timeUs: Long, val flags: Int, val bytes: ByteArray)

        var format: Format? = null
        val samples = ArrayList<Sample>()
        private var pending = ByteArrayOutputStream()

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
            sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int
        ): Int {
            val bytes = ByteArray(length)
            val count = input.read(bytes, 0, length)
            if (count > 0) pending.write(bytes, 0, count)
            return count
        }

        override fun sampleData(data: ParsableByteArray, length: Int) {
            sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            pending.write(bytes)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            val bytes = pending.toByteArray()
            assertEquals(size, bytes.size)
            samples += Sample(timeUs, flags, bytes)
            pending = ByteArrayOutputStream()
        }
    }
}
