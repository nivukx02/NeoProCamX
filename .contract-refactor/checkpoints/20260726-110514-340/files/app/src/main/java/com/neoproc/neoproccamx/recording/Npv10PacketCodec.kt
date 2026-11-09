package com.neoproc.neoproccamx.recording

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * On-disk packet codec for processed NPV10 masters.
 *
 * [PACKED_P010_10_LE_V1] is a lossless camera-data transport: four P010 code values are stored in
 * five bytes. It removes only the six mandatory zero padding bits from every P010 word. No RGB
 * rendering, quantisation, chroma resampling, or MediaCodec stage is involved. The decoder restores
 * the canonical P010 word layout before any thumbnail, develop, or MOV export consumes the packet.
 */
enum class Npv10PacketCodec {
    LEGACY_UNCOMPRESSED,
    PACKED_P010_10_LE_V1;

    companion object {
        fun fromMetadata(value: String?): Npv10PacketCodec {
            if (value.isNullOrBlank()) return LEGACY_UNCOMPRESSED
            val normalized = value.trim().replace('-', '_').uppercase(Locale.US)
            return values().firstOrNull { it.name == normalized }
                ?: throw IllegalArgumentException("Unsupported NPV10 packet codec: $value")
        }
    }
}

object Npv10PacketCodecEngine {
    private const val P010_VALID_BITS_SHIFT = 6
    private const val P010_PADDING_MASK = (1 shl P010_VALID_BITS_SHIFT) - 1

    private val nativeLibraryAvailable: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            System.loadLibrary("neoproc_vulkan")
            true
        }.getOrDefault(false)
    }

    fun storedBytes(decodedBytes: Long, codec: Npv10PacketCodec): Long =
        when (codec) {
            Npv10PacketCodec.LEGACY_UNCOMPRESSED -> decodedBytes
            Npv10PacketCodec.PACKED_P010_10_LE_V1 -> {
                require(decodedBytes >= 0L && decodedBytes % Short.SIZE_BYTES == 0L) {
                    "P010 decoded payload must contain complete 16-bit words"
                }
                val samples = decodedBytes / Short.SIZE_BYTES
                (samples * 10L + 7L) / 8L
            }
        }

    fun encode(
        decoded: ByteBuffer,
        decodedBytes: Int,
        codec: Npv10PacketCodec,
        target: ByteBuffer
    ): Int {
        require(decodedBytes >= 0 && decodedBytes <= decoded.capacity()) {
            "NPV10 decoded packet size is outside the source buffer"
        }
        val expectedStoredBytes = storedBytes(decodedBytes.toLong(), codec).toInt()
        require(target.capacity() >= expectedStoredBytes) {
            "NPV10 encoded packet target is too small"
        }
        target.clear()
        return when (codec) {
            Npv10PacketCodec.LEGACY_UNCOMPRESSED -> {
                val source = decoded.duplicate().apply {
                    position(0)
                    limit(decodedBytes)
                }
                target.put(source)
                target.flip()
                decodedBytes
            }
            Npv10PacketCodec.PACKED_P010_10_LE_V1 -> {
                require(decodedBytes % Short.SIZE_BYTES == 0) {
                    "P010 decoded packet must contain complete 16-bit words"
                }
                val source = decoded.duplicate().apply {
                    position(0)
                    limit(decodedBytes)
                }
                val nativeEncoded = source.isDirect && target.isDirect && nativeLibraryAvailable &&
                    runCatching {
                        nativePackP010TenBitPacket(
                            source,
                            decodedBytes / Short.SIZE_BYTES,
                            target
                        )
                    }.getOrDefault(false)
                if (!nativeEncoded) {
                    target.clear()
                    packP010Scalar(source, decodedBytes / Short.SIZE_BYTES, target)
                }
                target.position(expectedStoredBytes)
                target.flip()
                expectedStoredBytes
            }
        }
    }

    fun encode(decoded: ByteArray, codec: Npv10PacketCodec): ByteArray {
        val output = ByteArray(storedBytes(decoded.size.toLong(), codec).toInt())
        if (codec == Npv10PacketCodec.LEGACY_UNCOMPRESSED) return decoded.copyOf()
        val target = ByteBuffer.wrap(output)
        packP010Scalar(ByteBuffer.wrap(decoded), decoded.size / Short.SIZE_BYTES, target)
        return output
    }

    fun decode(
        encoded: ByteArray,
        decodedBytes: Int,
        codec: Npv10PacketCodec
    ): ByteArray =
        when (codec) {
            Npv10PacketCodec.LEGACY_UNCOMPRESSED -> {
                require(encoded.size == decodedBytes) {
                    "Legacy NPV10 packet has ${encoded.size} bytes, expected $decodedBytes"
                }
                encoded
            }
            Npv10PacketCodec.PACKED_P010_10_LE_V1 -> {
                require(decodedBytes % Short.SIZE_BYTES == 0) {
                    "P010 decoded packet must contain complete 16-bit words"
                }
                val expectedStoredBytes = storedBytes(decodedBytes.toLong(), codec).toInt()
                require(encoded.size == expectedStoredBytes) {
                    "Packed NPV10 packet has ${encoded.size} bytes, expected $expectedStoredBytes"
                }
                unpackP010Scalar(encoded, decodedBytes / Short.SIZE_BYTES)
            }
        }

    private fun packP010Scalar(source: ByteBuffer, sampleCount: Int, target: ByteBuffer) {
        val input = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        input.position(0)
        var sampleIndex = 0
        while (sampleIndex + 4 <= sampleCount) {
            val s0 = readP010Code(input)
            val s1 = readP010Code(input)
            val s2 = readP010Code(input)
            val s3 = readP010Code(input)
            target.put(s0.toByte())
            target.put(((s0 ushr 8) or (s1 shl 2)).toByte())
            target.put(((s1 ushr 6) or (s2 shl 4)).toByte())
            target.put(((s2 ushr 4) or (s3 shl 6)).toByte())
            target.put((s3 ushr 2).toByte())
            sampleIndex += 4
        }
        var accumulator = 0L
        var bitCount = 0
        while (sampleIndex < sampleCount) {
            accumulator = accumulator or (readP010Code(input).toLong() shl bitCount)
            bitCount += 10
            while (bitCount >= 8) {
                target.put((accumulator and 0xFFL).toByte())
                accumulator = accumulator ushr 8
                bitCount -= 8
            }
            sampleIndex += 1
        }
        if (bitCount > 0) target.put((accumulator and 0xFFL).toByte())
    }

    private fun unpackP010Scalar(encoded: ByteArray, sampleCount: Int): ByteArray {
        val output = ByteArray(sampleCount * Short.SIZE_BYTES)
        var sourceOffset = 0
        var sampleIndex = 0
        while (sampleIndex + 4 <= sampleCount) {
            val b0 = encoded[sourceOffset].toInt() and 0xFF
            val b1 = encoded[sourceOffset + 1].toInt() and 0xFF
            val b2 = encoded[sourceOffset + 2].toInt() and 0xFF
            val b3 = encoded[sourceOffset + 3].toInt() and 0xFF
            val b4 = encoded[sourceOffset + 4].toInt() and 0xFF
            writeP010Word(output, sampleIndex, b0 or ((b1 and 0x03) shl 8))
            writeP010Word(output, sampleIndex + 1, (b1 ushr 2) or ((b2 and 0x0F) shl 6))
            writeP010Word(output, sampleIndex + 2, (b2 ushr 4) or ((b3 and 0x3F) shl 4))
            writeP010Word(output, sampleIndex + 3, (b3 ushr 6) or (b4 shl 2))
            sourceOffset += 5
            sampleIndex += 4
        }
        var accumulator = 0L
        var bitCount = 0
        while (sampleIndex < sampleCount) {
            while (bitCount < 10) {
                require(sourceOffset < encoded.size) { "Packed NPV10 packet is truncated" }
                accumulator = accumulator or
                    ((encoded[sourceOffset].toLong() and 0xFFL) shl bitCount)
                sourceOffset += 1
                bitCount += 8
            }
            writeP010Word(output, sampleIndex, (accumulator and 0x3FFL).toInt())
            accumulator = accumulator ushr 10
            bitCount -= 10
            sampleIndex += 1
        }
        require(sourceOffset == encoded.size) { "Packed NPV10 packet has trailing bytes" }
        require(accumulator == 0L) { "Packed NPV10 packet has non-zero trailing padding bits" }
        return output
    }

    private fun readP010Code(input: ByteBuffer): Int {
        val word = input.short.toInt() and 0xFFFF
        require(word and P010_PADDING_MASK == 0) {
            "P010 packet contains non-zero padding bits"
        }
        return word ushr P010_VALID_BITS_SHIFT
    }

    private fun writeP010Word(output: ByteArray, sampleIndex: Int, code: Int) {
        val word = (code and 0x3FF) shl P010_VALID_BITS_SHIFT
        val offset = sampleIndex * Short.SIZE_BYTES
        output[offset] = (word and 0xFF).toByte()
        output[offset + 1] = ((word ushr 8) and 0xFF).toByte()
    }

    private external fun nativePackP010TenBitPacket(
        sourceP010: ByteBuffer,
        sampleCount: Int,
        targetPacked10: ByteBuffer
    ): Boolean
}
