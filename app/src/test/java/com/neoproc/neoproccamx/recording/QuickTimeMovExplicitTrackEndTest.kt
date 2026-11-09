package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class QuickTimeMovExplicitTrackEndTest {

    @Test
    fun explicitTrackEndPreservesMeasuredFinalSourceIntervalInStts() {
        val output = Files.createTempFile("camx-explicit-track-end", ".mov")
        try {
            val writer = QuickTimeMovFileWriter(
                outputPath = output.toFile().absolutePath,
                containerLabel = "Source-accurate timing"
            )
            val track = writer.addVideoTrack(
                QuickTimeVideoTrackConfig(
                    mime = "video/hevc",
                    width = 1920,
                    height = 1080,
                    fps = 25,
                    codecConfig = ByteArray(0),
                    colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited()
                )
            )
            listOf(0L, 40_000L, 85_000L).forEach { ptsUs ->
                writer.writeSample(
                    track,
                    QuickTimeSampleBuffer(
                        bytes = byteArrayOf(0, 0, 0, 1, 0x26, 1, 2),
                        presentationTimeUs = ptsUs,
                        flags = 1
                    )
                )
            }
            writer.setTrackEndPresentationTimeUs(track, 135_000L)
            writer.close()

            assertArrayEquals(
                longArrayOf(40_000L, 45_000L, 50_000L),
                readSttsDurations(output.toFile().readBytes())
            )
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun explicitTrackEndRejectsEmptyAndNonPositiveFinalDurations() {
        val output = Files.createTempFile("camx-invalid-track-end", ".mov")
        try {
            val writer = QuickTimeMovFileWriter(
                outputPath = output.toFile().absolutePath,
                containerLabel = "Source-accurate timing"
            )
            val track = writer.addVideoTrack(
                QuickTimeVideoTrackConfig(
                    mime = "video/hevc",
                    width = 16,
                    height = 16,
                    fps = 25,
                    codecConfig = ByteArray(0),
                    colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited()
                )
            )
            assertThrows(IllegalStateException::class.java) {
                writer.setTrackEndPresentationTimeUs(track, 40_000L)
            }
            writer.writeSample(
                track,
                QuickTimeSampleBuffer(
                    bytes = byteArrayOf(0, 0, 0, 1, 0x26, 1, 2),
                    presentationTimeUs = 10_000L,
                    flags = 1
                )
            )
            assertThrows(IllegalArgumentException::class.java) {
                writer.setTrackEndPresentationTimeUs(track, 10_000L)
            }
            writer.abort()
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun readSttsDurations(bytes: ByteArray): LongArray {
        val typeOffset = findFourCc(bytes, "stts")
        require(typeOffset >= 0) { "stts box missing" }
        val entryCount = readUInt32(bytes, typeOffset + 8).toInt()
        val durations = ArrayList<Long>()
        var offset = typeOffset + 12
        repeat(entryCount) {
            val sampleCount = readUInt32(bytes, offset).toInt()
            val sampleDuration = readUInt32(bytes, offset + 4)
            repeat(sampleCount) { durations += sampleDuration }
            offset += 8
        }
        return durations.toLongArray()
    }

    private fun findFourCc(bytes: ByteArray, fourCc: String): Int {
        val target = fourCc.toByteArray(Charsets.US_ASCII)
        for (offset in 0..bytes.size - target.size) {
            if (target.indices.all { bytes[offset + it] == target[it] }) return offset
        }
        return -1
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
}
