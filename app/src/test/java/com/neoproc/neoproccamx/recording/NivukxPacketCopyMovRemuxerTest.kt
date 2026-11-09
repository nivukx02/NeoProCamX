package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Files

class NivukxPacketCopyMovRemuxerTest {
    @Test
    fun `single pass remux preserves coded hevc sei vcl audio hdr and timestamps without transcode`() {
        val input = Files.createTempFile("nivukx-packet-copy", ".nivukx").toFile()
        val outputPath = Files.createTempFile("nivukx-packet-copy", ".mov")
        Files.deleteIfExists(outputPath)
        val output = outputPath.toFile()
        val prefixSei = byteArrayOf(0x4E, 0x01, 0x11, 0x22)
        val vcl = byteArrayOf(0x26, 0x01, 0x33, 0x44, 0x55)
        val videoSample = annexB(prefixSei, vcl)
        val audioSample = byteArrayOf(0x21, 0x10, 0x56, 0x78)
        val lengthPrefixedNal = byteArrayOf(0, 0, 1, 0) +
            ByteArray(256) { index -> if (index == 0) 0x26.toByte() else (index * 13).toByte() }

        try {
            val backend = NivukxStreamingContainerBackend()
            backend.open(input.absolutePath, NivukxContainerFormat.USER_VISIBLE_LABEL)
            backend.setOrientationHint(90)
            val videoTrack = backend.addVideoTrack(
                mime = "video/hevc",
                width = 3_840,
                height = 2_160,
                fps = 60,
                codecConfig = ByteArray(0),
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
                sampleRate = 48_000,
                channelCount = 2,
                codecConfig = byteArrayOf(0x12, 0x10)
            )
            backend.writeSample(videoTrack, ByteBuffer.wrap(videoSample), 1_000L, 1)
            backend.writeSample(audioTrack, ByteBuffer.wrap(audioSample), 1_500L, 0)
            backend.writeSample(videoTrack, ByteBuffer.wrap(lengthPrefixedNal), 17_667L, 0)
            backend.close()

            val progress = mutableListOf<NivukxPacketCopyProgress>()
            val result = NivukxPacketCopyMovRemuxer.remux(
                input = input,
                output = output,
                onProgress = progress::add
            )

            assertTrue(output.isFile && output.length() > 0L)
            assertTrue(result.integrityVerified)
            assertTrue(result.codedNalUnitsPreserved)
            assertFalse(result.transcoded)
            assertEquals(3L, result.sampleCount)
            assertEquals(2L, result.videoSampleCount)
            assertEquals(1L, result.audioSampleCount)
            assertEquals(
                videoSample.size.toLong() + audioSample.size.toLong() + lengthPrefixedNal.size.toLong(),
                result.encodedPayloadBytes
            )
            assertTrue(progress.isNotEmpty())
            assertEquals(1.0, progress.last().fraction, 0.0)

            val mov = Files.readAllBytes(output.toPath())
            val mdatFourCc = indexOf(mov, "mdat".toByteArray(Charsets.US_ASCII))
            assertTrue("MOV mdat box missing", mdatFourCc >= 0)
            val mdatPayloadStart = mdatFourCc + 12 // fourCC + 64-bit large-size tail
            val expectedMediaPayload = lengthPrefixed(prefixSei, vcl) + audioSample + lengthPrefixedNal
            assertArrayEquals(
                expectedMediaPayload,
                mov.copyOfRange(mdatPayloadStart, mdatPayloadStart + expectedMediaPayload.size)
            )

            val mdcv = indexOf(mov, "mdcv".toByteArray(Charsets.US_ASCII))
            val clli = indexOf(mov, "clli".toByteArray(Charsets.US_ASCII))
            assertTrue("HDR mastering metadata missing", mdcv >= 0)
            assertTrue("HDR content-light metadata missing", clli >= 0)
            val elst = indexOf(mov, "elst".toByteArray(Charsets.US_ASCII))
            assertTrue("Audio start-offset edit list missing", elst >= 0)
            assertEquals(500L, readU32(mov, elst + 12))
            assertEquals(0xFFFF_FFFFL, readU32(mov, elst + 16))
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun `hdr static metadata parser exactly inverts encoder quantization`() {
        val source = HdrStaticMetadataPolicy.MasteringDisplay(
            redX = 0.708,
            redY = 0.292,
            greenX = 0.170,
            greenY = 0.797,
            blueX = 0.131,
            blueY = 0.046,
            whiteX = 0.3127,
            whiteY = 0.3290,
            maxLuminanceNits = 4_000.0,
            minLuminanceNits = 0.0001,
            maxContentLightLevelNits = 3_800,
            maxFrameAverageLightLevelNits = 900
        )
        val encoded = HdrStaticMetadataPolicy.buildHdr10StaticInfo(source).let { buffer ->
            ByteArray(buffer.remaining()).also(buffer::get)
        }
        val decoded = requireNotNull(HdrStaticMetadataPolicy.parseHdr10StaticInfo(encoded))

        assertEquals(source.redX, decoded.redX, 0.00002)
        assertEquals(source.whiteY, decoded.whiteY, 0.00002)
        assertEquals(source.maxLuminanceNits, decoded.maxLuminanceNits, 0.0)
        assertEquals(source.minLuminanceNits, decoded.minLuminanceNits, 0.0)
        assertEquals(source.maxContentLightLevelNits, decoded.maxContentLightLevelNits)
        assertEquals(source.maxFrameAverageLightLevelNits, decoded.maxFrameAverageLightLevelNits)
    }

    private fun annexB(vararg nals: ByteArray): ByteArray =
        nals.fold(ByteArray(0)) { out, nal -> out + byteArrayOf(0, 0, 0, 1) + nal }

    private fun lengthPrefixed(vararg nals: ByteArray): ByteArray =
        nals.fold(ByteArray(0)) { out, nal ->
            out + byteArrayOf(
                (nal.size ushr 24).toByte(),
                (nal.size ushr 16).toByte(),
                (nal.size ushr 8).toByte(),
                nal.size.toByte()
            ) + nal
        }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        for (start in 0..haystack.size - needle.size) {
            var matches = true
            for (index in needle.indices) {
                if (haystack[start + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return -1
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
}
