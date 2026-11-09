package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.CRC32
import kotlin.random.Random

class Npv10PacketCodecTest {

    @Test
    fun chunkedLosslessRoundTripIsBitExactAcrossChunkAndPartialWordBoundaries() {
        listOf(1, 3, 4, 5, 17, 262_143, 262_144, 262_145).forEach { sampleCount ->
            val decoded = p010(IntArray(sampleCount) { index ->
                ((index * 137L + index.toLong() * index.toLong() * 17L + 19L) and 0x3FFL).toInt()
            })

            val encoded = Npv10PacketCodecEngine.encode(
                decoded,
                Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
            )
            val restored = Npv10PacketCodecEngine.decode(
                encoded = encoded,
                decodedBytes = decoded.size,
                codec = Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
            )

            assertArrayEquals("sampleCount=$sampleCount", decoded, restored)
            assertTrue(
                Npv10PacketCodecEngine.isValidStoredBytes(
                    encoded.size.toLong(),
                    decoded.size.toLong(),
                    Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
                )
            )
        }
    }

    @Test
    fun chunkedLosslessCompressesStructuredP010WithoutChangingCodes() {
        val decoded = p010(IntArray(65_536) { index ->
            when ((index / 512) and 3) {
                0 -> 64
                1 -> 256
                2 -> 512
                else -> 940
            }
        })
        val packedBytes = Npv10PacketCodecEngine.packedP010Bytes(decoded.size.toLong())

        val encoded = Npv10PacketCodecEngine.encode(
            decoded,
            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        )

        assertTrue(encoded.size.toLong() < packedBytes)
        assertEquals(
            1,
            ByteBuffer.wrap(encoded)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt(Npv10ChunkedLosslessCodec.FRAME_HEADER_BYTES)
        )
        assertArrayEquals(
            decoded,
            Npv10PacketCodecEngine.decode(
                encoded,
                decoded.size,
                Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
            )
        )
    }

    @Test
    fun chunkedLosslessUsesBitExactRawFallbackForIncompressibleP010() {
        val random = Random(0x4E4C4332)
        val decoded = p010(IntArray(65_536) { random.nextInt(1024) })
        val maximumBytes = Npv10PacketCodecEngine.storedBytes(
            decoded.size.toLong(),
            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        )

        val encoded = Npv10PacketCodecEngine.encode(
            decoded,
            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        )

        assertEquals(maximumBytes, encoded.size.toLong())
        assertEquals(
            0,
            ByteBuffer.wrap(encoded)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt(Npv10ChunkedLosslessCodec.FRAME_HEADER_BYTES)
        )
        assertArrayEquals(
            decoded,
            Npv10PacketCodecEngine.decode(
                encoded,
                decoded.size,
                Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
            )
        )
    }

    @Test
    fun chunkedLosslessRejectsPacketCorruption() {
        val decoded = p010(IntArray(32_768) { index -> 64 + index % 877 })
        val encoded = Npv10PacketCodecEngine.encode(
            decoded,
            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        )
        val corruptOffset = Npv10ChunkedLosslessCodec.FRAME_HEADER_BYTES +
            Npv10ChunkedLosslessCodec.CHUNK_HEADER_BYTES + 3
        encoded[corruptOffset] = (encoded[corruptOffset].toInt() xor 0x20).toByte()

        val failure = runCatching {
            Npv10PacketCodecEngine.decode(
                encoded,
                decoded.size,
                Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun chunkedLosslessWriterReaderAuditAndRecoveryHandleVariablePackets() {
        val output = Files.createTempFile("npv10-chunked-variable", ".npv10").toFile()
        val config = nativeP010Config().copy(
            packetCodec = Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        )
        val frames = listOf(
            p010(intArrayOf(64, 64, 64, 512, 512, 512)),
            p010(intArrayOf(64, 173, 309, 511, 777, 940))
        )
        Npv10FileWriter(output, config).use { writer ->
            frames.forEachIndexed { index, decoded ->
                writer.writeCopiedP010Frame(
                    Npv10CopiedP010Frame(
                        timestampNs = 1_000L + index,
                        width = 2,
                        height = 2,
                        payload = ByteBuffer.allocateDirect(decoded.size)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .apply { put(decoded).flip() },
                        payloadBytes = decoded.size,
                        copyPath = Npv10FramePacker.P010CopyPath.DIRECT_BUFFER
                    )
                )
            }
        }

        assertEquals(2, Npv10MovConverter.estimateFrameCount(output))
        Npv10FileReader(output).use { reader ->
            frames.forEach { expected ->
                assertArrayEquals(expected, requireNotNull(reader.readNextFrame()).payload)
            }
            assertEquals(null, reader.readNextFrame())
        }
        val audit = Npv10QualityGate.auditMasterFile(
            masterFile = output,
            expectedFrameCount = frames.size.toLong(),
            expectedPayloadBytes = frames.sumOf { it.size }.toLong()
        )
        assertEquals(Npv10AuditStatus.PASS, audit.auditStatus)
        assertTrue(audit.crcValidated)
        assertTrue(audit.frameRecordByteCountValid)

        val firstFrameBoundary = Npv10FileReader(output).use { reader ->
            requireNotNull(reader.readNextFrame())
            reader.bytePosition
        }
        RandomAccessFile(output, "rw").use { file -> file.setLength(file.length() - 1L) }
        val recovery = Npv10FileRecovery.recover(output)
        assertEquals(Npv10RecoveryStatus.TRUNCATED_TO_VALID_PREFIX, recovery.status)
        assertEquals(1L, recovery.recoveredFrames)
        assertEquals(firstFrameBoundary, recovery.recoveredBytes)
        assertFalse(output.length() == 0L)
    }

    @Test
    fun packed10RoundTripIsBitExactForFullAndOddSampleGroups() {
        listOf(1, 3, 4, 5, 17).forEach { sampleCount ->
            val codes = IntArray(sampleCount) { index -> (index * 137 + 19) and 0x3FF }
            val decoded = p010(codes)

            val encoded = Npv10PacketCodecEngine.encode(
                decoded,
                Npv10PacketCodec.PACKED_P010_10_LE_V1
            )
            val restored = Npv10PacketCodecEngine.decode(
                encoded = encoded,
                decodedBytes = decoded.size,
                codec = Npv10PacketCodec.PACKED_P010_10_LE_V1
            )

            assertArrayEquals("sampleCount=$sampleCount", decoded, restored)
            assertEquals((sampleCount * 10 + 7) / 8, encoded.size)
        }
    }

    @Test
    fun directDecodeIsBitExactAndReusesCallerOwnedTarget() {
        listOf(1, 3, 4, 5, 17).forEach { sampleCount ->
            val decoded = p010(IntArray(sampleCount) { index -> (index * 211 + 7) and 0x3FF })
            val encoded = Npv10PacketCodecEngine.encode(
                decoded,
                Npv10PacketCodec.PACKED_P010_10_LE_V1
            )
            val encodedDirect = ByteBuffer.allocateDirect(encoded.size).apply {
                put(encoded)
                flip()
            }
            val decodedDirect = ByteBuffer.allocateDirect(decoded.size)

            val byteCount = Npv10PacketCodecEngine.decodeInto(
                encoded = encodedDirect,
                encodedBytes = encoded.size,
                decodedBytes = decoded.size,
                codec = Npv10PacketCodec.PACKED_P010_10_LE_V1,
                target = decodedDirect
            )
            val restored = ByteArray(byteCount).also(decodedDirect.duplicate()::get)

            assertArrayEquals("sampleCount=$sampleCount", decoded, restored)
            assertEquals(decoded.size, decodedDirect.limit())
        }
    }

    @Test
    fun heapBackedCompatibilityEncodeIsBitExactAndTruthfullyRequiresCallerCrc() {
        val decoded = p010(IntArray(19) { index -> (index * 173 + 11) and 0x3FF })
        val source = ByteBuffer.wrap(decoded)
        val encodedBytes = Npv10PacketCodecEngine.storedBytes(
            decoded.size.toLong(),
            Npv10PacketCodec.PACKED_P010_10_LE_V1
        ).toInt()
        val target = ByteBuffer.allocateDirect(encodedBytes)

        val result = Npv10PacketCodecEngine.encodeWithCrc32(
            decoded = source,
            decodedBytes = decoded.size,
            codec = Npv10PacketCodec.PACKED_P010_10_LE_V1,
            target = target
        )
        val encoded = ByteArray(result.encodedBytes).also(target.duplicate()::get)
        val expected = Npv10PacketCodecEngine.encode(
            decoded,
            Npv10PacketCodec.PACKED_P010_10_LE_V1
        )

        assertEquals(encodedBytes, result.encodedBytes)
        assertEquals(null, result.crc32)
        assertArrayEquals(expected, encoded)
        assertArrayEquals(
            decoded,
            Npv10PacketCodecEngine.decode(
                encoded,
                decoded.size,
                Npv10PacketCodec.PACKED_P010_10_LE_V1
            )
        )
        assertEquals(CRC32().apply { update(encoded) }.value, CRC32().apply { update(expected) }.value)
    }

    @Test
    fun readerDirectPathDecodesPackedFrameWithoutReplacingTargets() {
        val output = Files.createTempFile("npv10-packed-direct", ".npv10").toFile()
        val decoded = p010(intArrayOf(64, 128, 256, 512, 640, 768))
        Npv10FileWriter(output, nativeP010Config()).use { writer ->
            val direct = ByteBuffer.allocateDirect(decoded.size).order(ByteOrder.LITTLE_ENDIAN)
            direct.put(decoded).flip()
            writer.writeCopiedP010Frame(
                Npv10CopiedP010Frame(
                    timestampNs = 123L,
                    width = 2,
                    height = 2,
                    payload = direct,
                    payloadBytes = decoded.size,
                    copyPath = Npv10FramePacker.P010CopyPath.DIRECT_BUFFER
                )
            )
        }
        Npv10FileReader(output).use { reader ->
            val storedSize = Npv10PacketCodecEngine.storedBytes(
                decoded.size.toLong(),
                Npv10PacketCodec.PACKED_P010_10_LE_V1
            ).toInt()
            val stored = ByteBuffer.allocateDirect(storedSize)
            val target = ByteBuffer.allocateDirect(decoded.size)

            val frame = requireNotNull(reader.readNextFrameDirect(stored, target))
            val restored = ByteArray(frame.payload.remaining()).also(frame.payload.duplicate()::get)

            assertEquals(123L, frame.timestampNs)
            assertArrayEquals(decoded, restored)
            assertEquals(null, reader.readNextFrameDirect(stored, target))
        }
    }

    @Test
    fun fourP010WordsStoreInFiveBytesWithoutQuantisation() {
        val decoded = p010(intArrayOf(0, 1, 512, 1023))
        val stored = Npv10PacketCodecEngine.storedBytes(
            decoded.size.toLong(),
            Npv10PacketCodec.PACKED_P010_10_LE_V1
        )

        assertEquals(8, decoded.size)
        assertEquals(5L, stored)
    }

    @Test
    fun encoderRejectsNonCanonicalP010PaddingBits() {
        val invalid = p010(intArrayOf(42)).also { it[0] = (it[0].toInt() or 0x01).toByte() }

        val failure = runCatching {
            Npv10PacketCodecEngine.encode(invalid, Npv10PacketCodec.PACKED_P010_10_LE_V1)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("padding bits"))
    }

    @Test
    fun decoderRejectsNonZeroTrailingPackedBits() {
        val decoded = p010(intArrayOf(777))
        val encoded = Npv10PacketCodecEngine.encode(
            decoded,
            Npv10PacketCodec.PACKED_P010_10_LE_V1
        )
        encoded[encoded.lastIndex] = (encoded.last().toInt() or 0x80).toByte()

        val failure = runCatching {
            Npv10PacketCodecEngine.decode(
                encoded,
                decoded.size,
                Npv10PacketCodec.PACKED_P010_10_LE_V1
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("trailing padding bits"))
    }

    @Test
    fun readerDetectsTamperedPackedPacketBeforeDecode() {
        val output = Files.createTempFile("npv10-packed-crc", ".npv10").toFile()
        val decoded = p010(intArrayOf(64, 128, 256, 512, 640, 768))
        val config = nativeP010Config()
        Npv10FileWriter(output, config).use { writer ->
            val direct = ByteBuffer.allocateDirect(decoded.size).order(ByteOrder.LITTLE_ENDIAN)
            direct.put(decoded).flip()
            writer.writeCopiedP010Frame(
                Npv10CopiedP010Frame(
                    timestampNs = 99L,
                    width = 2,
                    height = 2,
                    payload = direct,
                    payloadBytes = decoded.size,
                    copyPath = Npv10FramePacker.P010CopyPath.DIRECT_BUFFER
                )
            )
        }
        val frameStart = Npv10FileReader(output).use { it.bytePosition }
        val packetStart = frameStart + Npv10ContainerPolicy.FRAME_MAGIC_BYTES.size +
            Long.SIZE_BYTES + Int.SIZE_BYTES * 4L
        RandomAccessFile(output, "rw").use { file ->
            file.seek(packetStart)
            val original = file.readUnsignedByte()
            file.seek(packetStart)
            file.writeByte(original xor 0x01)
        }

        val failure = runCatching {
            Npv10FileReader(output).use { it.readNextFrame() }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("CRC mismatch"))
    }

    @Test
    fun everyReaderEntryPointRejectsNonPositiveFrameTimestamps() {
        listOf(0L, -1L).forEach { invalidTimestampNs ->
            val output = Files.createTempFile("npv10-invalid-read-time", ".npv10").toFile()
            val decoded = p010(intArrayOf(64, 128, 256, 512, 640, 768))
            Npv10FileWriter(output, nativeP010Config()).use { writer ->
                val direct = ByteBuffer.allocateDirect(decoded.size).order(ByteOrder.LITTLE_ENDIAN)
                direct.put(decoded).flip()
                writer.writeCopiedP010Frame(
                    Npv10CopiedP010Frame(
                        timestampNs = 123L,
                        width = 2,
                        height = 2,
                        payload = direct,
                        payloadBytes = decoded.size,
                        copyPath = Npv10FramePacker.P010CopyPath.DIRECT_BUFFER
                    )
                )
            }
            val frameStart = Npv10FileReader(output).use { it.bytePosition }
            RandomAccessFile(output, "rw").use { file ->
                file.seek(frameStart + Npv10ContainerPolicy.FRAME_MAGIC_BYTES.size)
                file.writeLong(invalidTimestampNs)
            }
            val storedBytes = Npv10PacketCodecEngine.storedBytes(
                decoded.size.toLong(),
                Npv10PacketCodec.PACKED_P010_10_LE_V1
            ).toInt()
            val readAttempts: List<() -> Any?> = listOf(
                { Npv10FileReader(output).use { it.skipNextFrameForPlayback() } },
                {
                    Npv10FileReader(output).use {
                        it.readNextStoredFrameInto(ByteBuffer.allocateDirect(storedBytes))
                    }
                },
                {
                    Npv10FileReader(output).use {
                        it.readNextFrameDirect(
                            ByteBuffer.allocateDirect(storedBytes),
                            ByteBuffer.allocateDirect(decoded.size)
                        )
                    }
                },
                { Npv10FileReader(output).use { it.readNextFrame() } }
            )

            readAttempts.forEach { read ->
                val failure = runCatching { read() }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
                assertTrue(failure?.message.orEmpty().contains("timestamp must be positive"))
            }
        }
    }

    @Test
    fun readerDecodesLegacyVersionOneUncompressedMaster() {
        val output = Files.createTempFile("npv10-v1", ".npv10").toFile()
        val payload = p010(intArrayOf(4, 128, 512, 900, 256, 768))
        val metadata = listOf(
            "payloadformat=p010_le",
            "sourceformat=ycbcr_p010",
            "sourcebitdepth=10",
            "storagebitdepth=10",
            "exactselectedresolution=true"
        ).joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8)
        val crc = CRC32().apply { update(payload) }.value.toInt()
        DataOutputStream(FileOutputStream(output)).use { stream ->
            stream.write(Npv10ContainerPolicy.MAGIC_BYTES)
            stream.writeInt(Npv10ContainerPolicy.LEGACY_VERSION)
            stream.writeInt(2)
            stream.writeInt(2)
            stream.writeInt(24)
            stream.writeInt(10)
            stream.writeInt(metadata.size)
            stream.write(metadata)
            stream.write(Npv10ContainerPolicy.FRAME_MAGIC_BYTES)
            stream.writeLong(1234L)
            stream.writeInt(2)
            stream.writeInt(2)
            stream.writeInt(payload.size)
            stream.writeInt(crc)
            stream.write(payload)
        }

        Npv10FileReader(output).use { reader ->
            assertEquals(Npv10ContainerPolicy.LEGACY_VERSION, reader.header.version)
            assertEquals(Npv10PacketCodec.LEGACY_UNCOMPRESSED, reader.header.packetCodec)
            assertArrayEquals(payload, reader.readNextFrame()?.payload)
        }
    }

    private fun nativeP010Config(): Npv10SessionConfig =
        Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 24,
            mode = "PRO",
            colorProfile = "Rec.709",
            startedAtEpochMs = 1L,
            sourceBitDepth = 10,
            sourceFormat = "YCBCR_P010",
            payloadFormat = Npv10PayloadFormat.P010_LE,
            packetCodec = Npv10PacketCodec.PACKED_P010_10_LE_V1
        )

    private fun p010(codes: IntArray): ByteArray =
        ByteBuffer.allocate(codes.size * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { codes.forEach { code -> putShort(((code and 0x3FF) shl 6).toShort()) } }
            .array()
}
