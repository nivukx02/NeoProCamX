package com.neoproc.neoproccamx.recording

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Device proof for the unrolled packed-10 + cache-hot CRC native ABI, including tail groups. */
@RunWith(AndroidJUnit4::class)
class Npv10PacketCodecNativeInstrumentedTest {

    @Test
    fun nativeChunkedLosslessCodec_isBitExactAcrossMultipleChunks() {
        val sampleCount = Npv10ChunkedLosslessCodec.CHUNK_SAMPLES + 17
        val decodedBytes = sampleCount * Short.SIZE_BYTES
        val expected = ByteArray(decodedBytes)
        val source = ByteBuffer.allocateDirect(decodedBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(sampleCount) { sample ->
                val code = when ((sample / 1024) and 3) {
                    0 -> 64
                    1 -> 256
                    2 -> 512
                    else -> (sample * 37 + 19) and 0x3FF
                }
                putShort((code shl 6).toShort())
            }
            flip()
            duplicate().get(expected)
        }
        val maximumBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes.toLong(),
            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        ).toInt()
        val encoded = ByteBuffer.allocateDirect(maximumBytes)

        val encodedResult = requireNotNull(
            Npv10PacketCodecEngine.encodeChunkedLosslessNativeForTest(
                decoded = source,
                decodedBytes = decodedBytes,
                target = encoded
            )
        )
        assertTrue(encodedResult.encodedBytes < maximumBytes)
        val encodedBytes = ByteArray(encodedResult.encodedBytes).also(encoded.duplicate()::get)
        assertEquals(CRC32().apply { update(encodedBytes) }.value.toInt(), encodedResult.crc32)

        val restored = ByteBuffer.allocateDirect(decodedBytes)
        assertTrue(
            Npv10PacketCodecEngine.decodeChunkedLosslessNativeForTest(
                encoded = encoded,
                encodedBytes = encodedResult.encodedBytes,
                decodedBytes = decodedBytes,
                target = restored
            )
        )
        assertArrayEquals(expected, ByteArray(decodedBytes).also(restored.duplicate()::get))
    }

    @Test
    fun nativeChunkedLosslessCodec_preservesIncompressibleRawFallbackAndRejectsCorruption() {
        val sampleCount = 65_536
        var state = 0x6D2B79F5
        val expected = ByteArray(sampleCount * Short.SIZE_BYTES)
        val source = ByteBuffer.allocateDirect(expected.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(sampleCount) {
                state = state * 1_664_525 + 1_013_904_223
                putShort(((state ushr 22) shl 6).toShort())
            }
            flip()
            duplicate().get(expected)
        }
        val maximumBytes = Npv10PacketCodecEngine.storedBytes(
            expected.size.toLong(),
            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        ).toInt()
        val encoded = ByteBuffer.allocateDirect(maximumBytes)
        val result = requireNotNull(
            Npv10PacketCodecEngine.encodeChunkedLosslessNativeForTest(
                decoded = source,
                decodedBytes = expected.size,
                target = encoded
            )
        )
        assertEquals(maximumBytes, result.encodedBytes)
        assertEquals(
            0,
            encoded.duplicate().order(ByteOrder.BIG_ENDIAN)
                .getInt(Npv10ChunkedLosslessCodec.FRAME_HEADER_BYTES)
        )

        val restored = ByteBuffer.allocateDirect(expected.size)
        assertTrue(
            Npv10PacketCodecEngine.decodeChunkedLosslessNativeForTest(
                encoded,
                result.encodedBytes,
                expected.size,
                restored
            )
        )
        assertArrayEquals(expected, ByteArray(expected.size).also(restored.duplicate()::get))

        val corrupt = encoded.duplicate()
        val bodyOffset = Npv10ChunkedLosslessCodec.FRAME_HEADER_BYTES +
            Npv10ChunkedLosslessCodec.CHUNK_HEADER_BYTES + 7
        corrupt.put(bodyOffset, (corrupt.get(bodyOffset).toInt() xor 0x01).toByte())
        assertTrue(
            !Npv10PacketCodecEngine.decodeChunkedLosslessNativeForTest(
                corrupt,
                result.encodedBytes,
                expected.size,
                ByteBuffer.allocateDirect(expected.size)
            )
        )
    }

    @Test
    fun nativeCompressionAdmission_selectsStructuredAndRawChunksWithDecoderParity() {
        val sampleCount = Npv10ChunkedLosslessCodec.CHUNK_SAMPLES + 65_536
        var state = 0x31A2B3C4
        val expected = ByteArray(sampleCount * Short.SIZE_BYTES)
        val source = ByteBuffer.allocateDirect(expected.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(sampleCount) { sample ->
                val code = if (sample < Npv10ChunkedLosslessCodec.CHUNK_SAMPLES) {
                    when ((sample / 2048) and 3) {
                        0 -> 64
                        1 -> 256
                        2 -> 512
                        else -> 940
                    }
                } else {
                    state = state * 1_664_525 + 1_013_904_223
                    state ushr 22
                }
                putShort((code shl 6).toShort())
            }
            flip()
            duplicate().get(expected)
        }
        val maximumBytes = Npv10PacketCodecEngine.storedBytes(
            expected.size.toLong(),
            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        ).toInt()
        val encoded = ByteBuffer.allocateDirect(maximumBytes)

        val result = requireNotNull(
            Npv10PacketCodecEngine.encodeChunkedLosslessNativeForTest(
                decoded = source,
                decodedBytes = expected.size,
                target = encoded
            )
        )
        val packet = ByteArray(result.encodedBytes).also(encoded.duplicate()::get)
        val header = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val firstHeader = Npv10ChunkedLosslessCodec.FRAME_HEADER_BYTES
        assertEquals(1, header.getInt(firstHeader))
        val firstStoredBytes = header.getInt(firstHeader + Int.SIZE_BYTES * 2)
        val secondHeader = firstHeader + Npv10ChunkedLosslessCodec.CHUNK_HEADER_BYTES +
            firstStoredBytes
        assertEquals(0, header.getInt(secondHeader))

        val nativeRestored = ByteBuffer.allocateDirect(expected.size)
        assertTrue(
            Npv10PacketCodecEngine.decodeChunkedLosslessNativeForTest(
                encoded = encoded,
                encodedBytes = result.encodedBytes,
                decodedBytes = expected.size,
                target = nativeRestored
            )
        )
        assertArrayEquals(expected, ByteArray(expected.size).also(nativeRestored.duplicate()::get))
        assertArrayEquals(
            expected,
            Npv10PacketCodecEngine.decode(
                encoded = packet,
                decodedBytes = expected.size,
                codec = Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
            )
        )
    }

    @Test
    fun nativeCompressionAdmission_preservesChunkBoundaryTailsAndCrcRejection() {
        val sampleCount = Npv10ChunkedLosslessCodec.CHUNK_SAMPLES + 3
        var state = 0x6D2B79F5
        val expected = ByteArray(sampleCount * Short.SIZE_BYTES)
        val source = ByteBuffer.allocateDirect(expected.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(sampleCount) {
                state = state * 1_664_525 + 1_013_904_223
                putShort(((state ushr 22) shl 6).toShort())
            }
            flip()
            duplicate().get(expected)
        }
        val maximumBytes = Npv10PacketCodecEngine.storedBytes(
            expected.size.toLong(),
            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        ).toInt()
        val encoded = ByteBuffer.allocateDirect(maximumBytes)
        val result = requireNotNull(
            Npv10PacketCodecEngine.encodeChunkedLosslessNativeForTest(
                decoded = source,
                decodedBytes = expected.size,
                target = encoded
            )
        )
        val packet = ByteArray(result.encodedBytes).also(encoded.duplicate()::get)
        val nativeRestored = ByteBuffer.allocateDirect(expected.size)
        assertTrue(
            Npv10PacketCodecEngine.decodeChunkedLosslessNativeForTest(
                encoded,
                result.encodedBytes,
                expected.size,
                nativeRestored
            )
        )
        assertArrayEquals(expected, ByteArray(expected.size).also(nativeRestored.duplicate()::get))
        assertArrayEquals(
            expected,
            Npv10PacketCodecEngine.decode(
                packet,
                expected.size,
                Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
            )
        )

        val corrupted = ByteBuffer.allocateDirect(result.encodedBytes).apply {
            put(packet)
            val bodyOffset = Npv10ChunkedLosslessCodec.FRAME_HEADER_BYTES +
                Npv10ChunkedLosslessCodec.CHUNK_HEADER_BYTES + 19
            put(bodyOffset, (get(bodyOffset).toInt() xor 0x40).toByte())
            position(0)
        }
        assertTrue(
            !Npv10PacketCodecEngine.decodeChunkedLosslessNativeForTest(
                corrupted,
                result.encodedBytes,
                expected.size,
                ByteBuffer.allocateDirect(expected.size)
            )
        )
    }

    @Test
    fun nativePacked10AndCrc_areBitExactForFastBlocksAndEveryTailShape() {
        listOf(1, 2, 3, 4, 5, 7, 8, 9, 31, 32, 33, 257).forEach { sampleCount ->
            val decodedBytes = sampleCount * Short.SIZE_BYTES
            val decoded = ByteBuffer.allocateDirect(decodedBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(sampleCount) { sample ->
                    val code = (sample * 613 + 29) and 0x3FF
                    putShort((code shl 6).toShort())
                }
                flip()
            }
            val expectedDecoded = ByteArray(decodedBytes).also(decoded.duplicate()::get)
            val expectedPacket = Npv10PacketCodecEngine.encode(
                expectedDecoded,
                Npv10PacketCodec.PACKED_P010_10_LE_V1
            )
            val encoded = ByteBuffer.allocateDirect(expectedPacket.size)

            val result = Npv10PacketCodecEngine.encodeWithCrc32(
                decoded = decoded,
                decodedBytes = decodedBytes,
                codec = Npv10PacketCodec.PACKED_P010_10_LE_V1,
                target = encoded
            )
            val actualPacket = ByteArray(result.encodedBytes).also(encoded.duplicate()::get)
            val referenceCrc = CRC32().apply { update(expectedPacket) }.value.toInt()

            assertEquals("sampleCount=$sampleCount native CRC unavailable", true, result.crc32 != null)
            assertEquals("sampleCount=$sampleCount CRC", referenceCrc, result.crc32)
            assertArrayEquals("sampleCount=$sampleCount packet", expectedPacket, actualPacket)
            assertArrayEquals(
                "sampleCount=$sampleCount round trip",
                expectedDecoded,
                Npv10PacketCodecEngine.decode(
                    encoded = actualPacket,
                    decodedBytes = decodedBytes,
                    codec = Npv10PacketCodec.PACKED_P010_10_LE_V1
                )
            )
        }
    }

    @Test
    fun nativePacked10_rejectsNonCanonicalP010PaddingWithoutPublishingACrc() {
        val decoded = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(4) { putShort((((it * 211) and 0x3FF) shl 6).toShort()) }
            put(0, (get(0).toInt() or 0x01).toByte())
            position(0)
        }
        val target = ByteBuffer.allocateDirect(5)

        val failure = runCatching {
            Npv10PacketCodecEngine.encodeWithCrc32(
                decoded = decoded,
                decodedBytes = 8,
                codec = Npv10PacketCodec.PACKED_P010_10_LE_V1,
                target = target
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("padding bits"))
    }
}
