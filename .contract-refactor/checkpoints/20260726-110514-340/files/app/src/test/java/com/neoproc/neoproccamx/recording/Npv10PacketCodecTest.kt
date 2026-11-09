package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

class Npv10PacketCodecTest {

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
