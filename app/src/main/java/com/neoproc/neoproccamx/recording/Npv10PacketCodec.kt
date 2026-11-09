package com.neoproc.neoproccamx.recording

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.CRC32

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
    PACKED_P010_10_LE_V1,
    /** Bit-exact split-10 + independently compressed LZ4 chunks; raw chunks remain available. */
    CHUNKED_SPLIT_P010_10_LZ4_V2;

    companion object {
        fun fromMetadata(value: String?): Npv10PacketCodec {
            if (value.isNullOrBlank()) return LEGACY_UNCOMPRESSED
            val normalized = value.trim().replace('-', '_').uppercase(Locale.US)
            return values().firstOrNull { it.name == normalized }
                ?: throw IllegalArgumentException("Unsupported NPV10 packet codec: $value")
        }
    }
}

/** Result of a caller-owned packet encode. [crc32] is present only when native fused CRC ran. */
data class Npv10PacketEncodeResult(
    val encodedBytes: Int,
    val crc32: Int?
)

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
            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2 ->
                Npv10ChunkedLosslessCodec.maximumStoredBytes(decodedBytes)
        }

    fun packedP010Bytes(decodedBytes: Long): Long =
        Npv10ChunkedLosslessCodec.packedBytes(decodedBytes)

    fun isValidStoredBytes(
        actualBytes: Long,
        decodedBytes: Long,
        codec: Npv10PacketCodec
    ): Boolean = when (codec) {
        Npv10PacketCodec.LEGACY_UNCOMPRESSED,
        Npv10PacketCodec.PACKED_P010_10_LE_V1 -> actualBytes == storedBytes(decodedBytes, codec)
        Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2 ->
            actualBytes in Npv10ChunkedLosslessCodec.minimumStoredBytes(decodedBytes)..
                Npv10ChunkedLosslessCodec.maximumStoredBytes(decodedBytes)
    }

    fun encode(
        decoded: ByteBuffer,
        decodedBytes: Int,
        codec: Npv10PacketCodec,
        target: ByteBuffer
    ): Int = encodeInternal(
        decoded = decoded,
        decodedBytes = decodedBytes,
        codec = codec,
        target = target,
        requestCrc32 = false
    ).encodedBytes

    /**
     * Encodes a writer packet and obtains CRC32 from the same cache-hot native packing pass.
     *
     * Unsupported/native-unavailable layouts return a null CRC so the writer can retain its exact
     * Java CRC fallback. No packet is accepted without one of those two independent CRC paths.
     */
    fun encodeWithCrc32(
        decoded: ByteBuffer,
        decodedBytes: Int,
        codec: Npv10PacketCodec,
        target: ByteBuffer
    ): Npv10PacketEncodeResult = encodeInternal(
        decoded = decoded,
        decodedBytes = decodedBytes,
        codec = codec,
        target = target,
        requestCrc32 = true
    )

    private fun encodeInternal(
        decoded: ByteBuffer,
        decodedBytes: Int,
        codec: Npv10PacketCodec,
        target: ByteBuffer,
        requestCrc32: Boolean
    ): Npv10PacketEncodeResult {
        require(decodedBytes >= 0 && decodedBytes <= decoded.capacity()) {
            "NPV10 decoded packet size is outside the source buffer"
        }
        val expectedStoredBytesLong = storedBytes(decodedBytes.toLong(), codec)
        require(expectedStoredBytesLong in 0L..Int.MAX_VALUE.toLong()) {
            "NPV10 encoded packet capacity exceeds the supported buffer range"
        }
        val expectedStoredBytes = expectedStoredBytesLong.toInt()
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
                Npv10PacketEncodeResult(decodedBytes, crc32 = null)
            }
            Npv10PacketCodec.PACKED_P010_10_LE_V1 -> {
                require(decodedBytes % Short.SIZE_BYTES == 0) {
                    "P010 decoded packet must contain complete 16-bit words"
                }
                val source = decoded.duplicate().apply {
                    position(0)
                    limit(decodedBytes)
                }
                val nativeCrc = if (requestCrc32 && source.isDirect && target.isDirect &&
                    nativeLibraryAvailable
                ) {
                    runCatching {
                        nativePackP010TenBitPacketWithCrc(
                            source,
                            decodedBytes / Short.SIZE_BYTES,
                            target
                        )
                    }.getOrDefault(NATIVE_PACK_FAILURE)
                } else {
                    NATIVE_PACK_FAILURE
                }
                val nativeEncodedWithCrc = nativeCrc in 0L..UNSIGNED_INT_MAX
                val nativeEncoded = nativeEncodedWithCrc ||
                    (!requestCrc32 && source.isDirect && target.isDirect && nativeLibraryAvailable &&
                        runCatching {
                        nativePackP010TenBitPacket(
                            source,
                            decodedBytes / Short.SIZE_BYTES,
                            target
                        )
                    }.getOrDefault(false))
                if (!nativeEncoded) {
                    target.clear()
                    packP010Scalar(source, decodedBytes / Short.SIZE_BYTES, target)
                }
                target.position(expectedStoredBytes)
                target.flip()
                Npv10PacketEncodeResult(
                    encodedBytes = expectedStoredBytes,
                    crc32 = nativeCrc.takeIf { nativeEncodedWithCrc }?.toInt()
                )
            }
            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2 -> {
                require(decodedBytes % Short.SIZE_BYTES == 0) {
                    "P010 decoded packet must contain complete 16-bit words"
                }
                val source = decoded.duplicate().apply {
                    position(0)
                    limit(decodedBytes)
                }
                val nativeResult = encodeChunkedLosslessNative(
                    decoded = source,
                    decodedBytes = decodedBytes,
                    target = target,
                    maximumStoredBytes = expectedStoredBytes
                )
                if (nativeResult != null) {
                    nativeResult.copy(crc32 = nativeResult.crc32.takeIf { requestCrc32 })
                } else {
                    target.clear()
                    val bytes = ByteArray(decodedBytes)
                    sourceCopy(source, decodedBytes, bytes)
                    val encoded = Npv10ChunkedLosslessCodec.encodeP010(bytes)
                    target.put(encoded)
                    target.flip()
                    Npv10PacketEncodeResult(
                        encodedBytes = encoded.size,
                        crc32 = CRC32().apply { update(encoded) }.value.toInt().takeIf { requestCrc32 }
                    )
                }
            }
        }
    }

    fun encode(decoded: ByteArray, codec: Npv10PacketCodec): ByteArray {
        if (codec == Npv10PacketCodec.LEGACY_UNCOMPRESSED) return decoded.copyOf()
        if (codec == Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2) {
            return Npv10ChunkedLosslessCodec.encodeP010(decoded)
        }
        val output = ByteArray(storedBytes(decoded.size.toLong(), codec).toInt())
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
            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2 ->
                Npv10ChunkedLosslessCodec.decodeP010(encoded, decodedBytes)
        }

    /**
     * Decodes into a caller-owned buffer without creating frame-sized heap arrays. Both buffers may be
     * direct and reused for the entire export. The returned byte count is also the target's limit.
     */
    fun decodeInto(
        encoded: ByteBuffer,
        encodedBytes: Int,
        decodedBytes: Int,
        codec: Npv10PacketCodec,
        target: ByteBuffer
    ): Int {
        require(encodedBytes >= 0 && encodedBytes <= encoded.capacity()) {
            "NPV10 encoded packet size is outside the source buffer"
        }
        require(decodedBytes >= 0 && decodedBytes <= target.capacity()) {
            "NPV10 decoded packet target is too small"
        }
        val maximumStoredBytes = storedBytes(decodedBytes.toLong(), codec).toInt()
        require(isValidStoredBytes(encodedBytes.toLong(), decodedBytes.toLong(), codec)) {
            "NPV10 packet has $encodedBytes bytes, expected at most $maximumStoredBytes"
        }
        val source = encoded.duplicate().apply {
            position(0)
            limit(encodedBytes)
        }.slice()
        target.clear()
        target.limit(decodedBytes)
        when (codec) {
            Npv10PacketCodec.LEGACY_UNCOMPRESSED -> target.put(source)
            Npv10PacketCodec.PACKED_P010_10_LE_V1 -> {
                require(decodedBytes % Short.SIZE_BYTES == 0) {
                    "P010 decoded packet must contain complete 16-bit words"
                }
                val nativeDecoded = source.isDirect && target.isDirect && nativeLibraryAvailable &&
                    runCatching {
                        nativeUnpackP010TenBitPacket(
                            source,
                            decodedBytes / Short.SIZE_BYTES,
                            target
                        )
                    }.getOrDefault(false)
                if (!nativeDecoded) {
                    target.clear()
                    target.limit(decodedBytes)
                    unpackP010Scalar(source, decodedBytes / Short.SIZE_BYTES, target)
                }
            }
            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2 -> {
                val nativeDecoded = decodeChunkedLosslessNative(
                    encoded = source,
                    encodedBytes = encodedBytes,
                    decodedBytes = decodedBytes,
                    target = target
                )
                if (!nativeDecoded) {
                    val encodedArray = ByteArray(encodedBytes)
                    source.get(encodedArray)
                    target.put(Npv10ChunkedLosslessCodec.decodeP010(encodedArray, decodedBytes))
                }
            }
        }
        target.position(decodedBytes)
        target.flip()
        return decodedBytes
    }

    /** Device-test hook that fails rather than silently taking the scalar compatibility path. */
    internal fun encodeChunkedLosslessNativeForTest(
        decoded: ByteBuffer,
        decodedBytes: Int,
        target: ByteBuffer
    ): Npv10PacketEncodeResult? {
        val maximumStoredBytes = storedBytes(
            decodedBytes.toLong(),
            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        )
        if (maximumStoredBytes !in 1L..Int.MAX_VALUE.toLong()) return null
        target.clear()
        return encodeChunkedLosslessNative(
            decoded = decoded,
            decodedBytes = decodedBytes,
            target = target,
            maximumStoredBytes = maximumStoredBytes.toInt()
        )
    }

    /** Device-test hook that proves the native NLC2 decoder itself, without scalar fallback. */
    internal fun decodeChunkedLosslessNativeForTest(
        encoded: ByteBuffer,
        encodedBytes: Int,
        decodedBytes: Int,
        target: ByteBuffer
    ): Boolean {
        target.clear()
        val decoded = decodeChunkedLosslessNative(encoded, encodedBytes, decodedBytes, target)
        if (decoded) {
            target.position(decodedBytes)
            target.flip()
        }
        return decoded
    }

    private fun encodeChunkedLosslessNative(
        decoded: ByteBuffer,
        decodedBytes: Int,
        target: ByteBuffer,
        maximumStoredBytes: Int
    ): Npv10PacketEncodeResult? {
        if (!decoded.isDirect || !target.isDirect || !nativeLibraryAvailable || decodedBytes <= 0 ||
            decodedBytes % Short.SIZE_BYTES != 0 || decoded.capacity() < decodedBytes ||
            target.capacity() < maximumStoredBytes
        ) {
            return null
        }
        val source = decoded.duplicate().apply {
            position(0)
            limit(decodedBytes)
        }
        target.clear()
        val token = runCatching {
            nativeEncodeChunkedLosslessP010Packet(
                source,
                decodedBytes / Short.SIZE_BYTES,
                target
            )
        }.getOrDefault(NATIVE_PACK_FAILURE)
        if (token < 0L) {
            target.clear()
            return null
        }
        val encodedBytes = (token ushr Int.SIZE_BITS).toInt()
        if (encodedBytes !in 1..maximumStoredBytes) {
            target.clear()
            return null
        }
        target.position(encodedBytes)
        target.flip()
        return Npv10PacketEncodeResult(encodedBytes = encodedBytes, crc32 = token.toInt())
    }

    private fun decodeChunkedLosslessNative(
        encoded: ByteBuffer,
        encodedBytes: Int,
        decodedBytes: Int,
        target: ByteBuffer
    ): Boolean = encoded.isDirect && target.isDirect && nativeLibraryAvailable &&
        runCatching {
            nativeDecodeChunkedLosslessP010Packet(
                encoded,
                encodedBytes,
                decodedBytes,
                target
            )
        }.getOrDefault(false)

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

    private fun unpackP010Scalar(encoded: ByteBuffer, sampleCount: Int, output: ByteBuffer) {
        val source = encoded.duplicate()
        var sampleIndex = 0
        while (sampleIndex + 4 <= sampleCount) {
            val b0 = source.get().toInt() and 0xFF
            val b1 = source.get().toInt() and 0xFF
            val b2 = source.get().toInt() and 0xFF
            val b3 = source.get().toInt() and 0xFF
            val b4 = source.get().toInt() and 0xFF
            writeP010Word(output, b0 or ((b1 and 0x03) shl 8))
            writeP010Word(output, (b1 ushr 2) or ((b2 and 0x0F) shl 6))
            writeP010Word(output, (b2 ushr 4) or ((b3 and 0x3F) shl 4))
            writeP010Word(output, (b3 ushr 6) or (b4 shl 2))
            sampleIndex += 4
        }
        var accumulator = 0L
        var bitCount = 0
        while (sampleIndex < sampleCount) {
            while (bitCount < 10) {
                require(source.hasRemaining()) { "Packed NPV10 packet is truncated" }
                accumulator = accumulator or ((source.get().toLong() and 0xFFL) shl bitCount)
                bitCount += 8
            }
            writeP010Word(output, (accumulator and 0x3FFL).toInt())
            accumulator = accumulator ushr 10
            bitCount -= 10
            sampleIndex += 1
        }
        require(!source.hasRemaining()) { "Packed NPV10 packet has trailing bytes" }
        require(accumulator == 0L) { "Packed NPV10 packet has non-zero trailing padding bits" }
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

    private fun writeP010Word(output: ByteBuffer, code: Int) {
        val word = (code and 0x3FF) shl P010_VALID_BITS_SHIFT
        output.put((word and 0xFF).toByte())
        output.put(((word ushr 8) and 0xFF).toByte())
    }

    private fun sourceCopy(source: ByteBuffer, length: Int, target: ByteArray) {
        val copy = source.duplicate().apply {
            position(0)
            limit(length)
        }
        copy.get(target)
    }

    private external fun nativePackP010TenBitPacket(
        sourceP010: ByteBuffer,
        sampleCount: Int,
        targetPacked10: ByteBuffer
    ): Boolean

    private external fun nativePackP010TenBitPacketWithCrc(
        sourceP010: ByteBuffer,
        sampleCount: Int,
        targetPacked10: ByteBuffer
    ): Long

    private external fun nativeUnpackP010TenBitPacket(
        sourcePacked10: ByteBuffer,
        sampleCount: Int,
        targetP010: ByteBuffer
    ): Boolean

    private external fun nativeEncodeChunkedLosslessP010Packet(
        sourceP010: ByteBuffer,
        sampleCount: Int,
        targetPacket: ByteBuffer
    ): Long

    private external fun nativeDecodeChunkedLosslessP010Packet(
        sourcePacket: ByteBuffer,
        encodedBytes: Int,
        decodedBytes: Int,
        targetP010: ByteBuffer
    ): Boolean

    private const val NATIVE_PACK_FAILURE = -1L
    private const val UNSIGNED_INT_MAX = 0xFFFF_FFFFL
}
