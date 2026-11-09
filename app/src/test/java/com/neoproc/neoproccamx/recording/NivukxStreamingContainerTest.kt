package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files

class NivukxStreamingContainerTest {
    @Test
    fun `api 29 software crc32c fallback matches castagnoli reference vector`() {
        assertEquals(0xE3069283.toInt(), NivukxCrc32c.computeSoftware("123456789".toByteArray()))
    }

    @Test
    fun `encoded video audio timestamps and hdr metadata round trip exactly`() {
        val output = Files.createTempFile("nivukx-roundtrip", ".nivukx").toFile()
        val videoConfig = byteArrayOf(0, 0, 0, 1, 0x40, 1, 12, 44)
        val audioConfig = byteArrayOf(0x12, 0x10)
        val videoOne = ByteArray(8_193) { index -> (index * 31).toByte() }
        val audioOne = ByteArray(777) { index -> (index * 7).toByte() }
        val videoTwo = ByteArray(12_345) { index -> (255 - index).toByte() }

        try {
            val backend = NivukxStreamingContainerBackend()
            backend.open(output.absolutePath, NivukxContainerFormat.USER_VISIBLE_LABEL)
            backend.setOrientationHint(90)
            val videoTrack = backend.addVideoTrack(
                mime = "video/hevc",
                width = 7_680,
                height = 4_320,
                fps = 120,
                codecConfig = videoConfig,
                colorConfig = QuickTimeColorAtomConfig(
                    colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                    transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                    matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                    fullRange = false,
                    masteringDisplay = HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_4000_NITS
                )
            )
            val audioTrack = backend.addAudioTrack(
                mime = "audio/mp4a-latm",
                sampleRate = 96_000,
                channelCount = 2,
                codecConfig = audioConfig
            )
            backend.writeSample(videoTrack, ByteBuffer.wrap(videoOne), 0L, 1)
            backend.writeSample(audioTrack, ByteBuffer.wrap(audioOne), 11_000L, 0)
            backend.writeSample(videoTrack, ByteBuffer.wrap(videoTwo), 16_667L, 0)
            backend.close()

            val inspection = NivukxStreamingReader(output).inspect(collectSamplePayloads = true)
            assertTrue(inspection.integrityVerified)
            assertFalse(inspection.recoveryRequired)
            assertEquals(90, inspection.header.orientationDegrees)
            assertEquals(3L, inspection.recoveredSampleCount)
            assertEquals(2, inspection.tracks.size)
            assertEquals(7_680, inspection.tracks[0].width)
            assertEquals(4_320, inspection.tracks[0].height)
            assertEquals(120, inspection.tracks[0].fps)
            assertArrayEquals(videoConfig, inspection.tracks[0].codecConfig)
            assertArrayEquals(audioConfig, inspection.tracks[1].codecConfig)
            assertEquals(25, inspection.tracks[0].color?.hdrStaticInfo?.size)
            assertEquals(listOf(0L, 11_000L, 16_667L), inspection.samples.map { it.info.presentationTimeUs })
            assertEquals(listOf(0, 1, 0), inspection.samples.map { it.info.trackIndex })
            assertArrayEquals(videoOne, inspection.samples[0].encodedPayload)
            assertArrayEquals(audioOne, inspection.samples[1].encodedPayload)
            assertArrayEquals(videoTwo, inspection.samples[2].encodedPayload)
            assertEquals(1, inspection.indexEntries.size)
            assertEquals(0L, inspection.indexEntries.single().presentationTimeUs)
        } finally {
            output.delete()
        }
    }

    @Test
    fun `corrupted second sample is rejected strictly and first sample remains recoverable`() {
        val output = Files.createTempFile("nivukx-corrupt", ".nivukx").toFile()
        try {
            writeSimpleVideo(output, listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8)))
            val clean = NivukxStreamingReader(output).inspect(collectSamplePayloads = true)
            val corruptOffset = clean.samples[1].info.dataOffset + 1L
            RandomAccessFile(output, "rw").use { file ->
                file.seek(corruptOffset)
                val original = file.readByte()
                file.seek(corruptOffset)
                file.writeByte(original.toInt() xor 0x40)
            }

            assertTrue(runCatching { NivukxStreamingReader(output).inspect() }.isFailure)
            val recovered = NivukxStreamingReader(output).inspect(
                recoverTruncatedTail = true,
                collectSamplePayloads = true
            )
            assertEquals(1L, recovered.recoveredSampleCount)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), recovered.samples.single().encodedPayload)
            assertTrue(recovered.recoveryRequired)
            assertTrue(recovered.diagnostic?.contains("CRC32C") == true)
        } finally {
            output.delete()
        }
    }

    @Test
    fun `truncated footer recovers every complete 4k sample without whole file allocation`() {
        val output = Files.createTempFile("nivukx-truncated", ".nivukx").toFile()
        try {
            val payloads = List(12) { frame -> ByteArray(32 * 1024) { index -> (frame + index).toByte() } }
            writeSimpleVideo(output, payloads, width = 4_096, height = 2_160, fps = 60)
            val clean = NivukxStreamingReader(output).inspect()
            RandomAccessFile(output, "rw").use { file ->
                file.setLength(clean.header.footerOffset + NivukxContainerFormat.CHUNK_HEADER_SIZE + 10L)
            }

            assertTrue(runCatching { NivukxStreamingReader(output).inspect() }.isFailure)
            val recovered = NivukxStreamingReader(output).inspect(recoverTruncatedTail = true)
            assertEquals(payloads.size.toLong(), recovered.recoveredSampleCount)
            assertTrue(recovered.recoveryRequired)
            assertFalse(recovered.integrityVerified)
            assertTrue(recovered.samples.isEmpty())
        } finally {
            output.delete()
        }
    }

    private fun writeSimpleVideo(
        output: java.io.File,
        samples: List<ByteArray>,
        width: Int = 3_840,
        height: Int = 2_160,
        fps: Int = 60
    ) {
        val backend = NivukxStreamingContainerBackend()
        backend.open(output.absolutePath, NivukxContainerFormat.USER_VISIBLE_LABEL)
        val videoTrack = backend.addVideoTrack(
            mime = "video/hevc",
            width = width,
            height = height,
            fps = fps,
            codecConfig = byteArrayOf(0, 0, 0, 1, 0x40),
            colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited()
        )
        samples.forEachIndexed { index, sample ->
            backend.writeSample(
                trackIndex = videoTrack,
                buffer = ByteBuffer.wrap(sample),
                presentationTimeUs = index * 1_000_000L / fps,
                flags = if (index == 0) 1 else 0
            )
        }
        backend.close()
    }
}
