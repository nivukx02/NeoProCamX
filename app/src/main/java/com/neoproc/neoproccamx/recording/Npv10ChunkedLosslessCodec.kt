package com.neoproc.neoproccamx.recording

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * NLC2: independently decodable, bit-exact P010 chunks for the NPV10 master.
 *
 * The input to this codec is the canonical PACKED_P010_10_LE_V1 stream. Each chunk is first
 * byte-shuffled into an eight-bit low plane plus a packed two-bit high plane. That reversible
 * transform exposes spatial redundancy to the bounded LZ4 block encoder without changing a code
 * value. A chunk that does not become smaller is stored verbatim. Consequently the codec has a
 * deterministic maximum size and never relies on a lossy or best-effort fallback.
 * Version 2 adds flag-2 64-sample two-lane range blocks. Version-1 LZ4/raw files stay readable;
 * both versions verify the same original packed-sample CRC, not merely the compressed bytes.
 */
internal object Npv10ChunkedLosslessCodec {
    const val CHUNK_SAMPLES: Int = 262_144
    const val FRAME_HEADER_BYTES: Int = 32
    /** flags/u32, sampleCount/u32, storedBytes/u32; all fields are big-endian. */
    const val CHUNK_HEADER_BYTES: Int = 12

    private const val VERSION = 1
    private const val RANGE_VERSION = 2
    private const val FLAG_SPLIT10_LZ4 = 1
    private const val FLAG_RANGE10 = 2
    private const val HASH_BITS = 16
    private const val HASH_SIZE = 1 shl HASH_BITS
    private const val MIN_MATCH = 4
    private const val MAX_DISTANCE = 65_535
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'L'.code.toByte(), 'C'.code.toByte(), '2'.code.toByte())

    fun packedBytes(decodedP010Bytes: Long): Long {
        require(decodedP010Bytes >= 0L && decodedP010Bytes % Short.SIZE_BYTES == 0L) {
            "P010 decoded payload must contain complete 16-bit words"
        }
        val samples = decodedP010Bytes / Short.SIZE_BYTES
        return (samples * 10L + 7L) / 8L
    }

    fun maximumStoredBytes(decodedP010Bytes: Long): Long {
        val packed = packedBytes(decodedP010Bytes)
        val samples = decodedP010Bytes / Short.SIZE_BYTES
        val chunks = (samples + CHUNK_SAMPLES - 1L) / CHUNK_SAMPLES
        return Math.addExact(
            Math.addExact(packed, FRAME_HEADER_BYTES.toLong()),
            Math.multiplyExact(chunks, CHUNK_HEADER_BYTES.toLong())
        )
    }

    fun minimumStoredBytes(decodedP010Bytes: Long): Long {
        val samples = decodedP010Bytes / Short.SIZE_BYTES
        val chunks = (samples + CHUNK_SAMPLES - 1L) / CHUNK_SAMPLES
        return FRAME_HEADER_BYTES.toLong() + chunks * (CHUNK_HEADER_BYTES + 1L)
    }

    fun encodeP010(decoded: ByteArray, rangePacking: Boolean = false): ByteArray {
        require(decoded.size % Short.SIZE_BYTES == 0) {
            "P010 decoded payload must contain complete 16-bit words"
        }
        val sampleCount = decoded.size / Short.SIZE_BYTES
        val packed = ByteArray(packedByteCount(sampleCount))
        var bitPosition = 0
        for (sample in 0 until sampleCount) {
            val byteOffset = sample * Short.SIZE_BYTES
            val word = (decoded[byteOffset].toInt() and 0xFF) or
                ((decoded[byteOffset + 1].toInt() and 0xFF) shl 8)
            require(word and 0x3F == 0) { "P010 packet contains non-zero padding bits" }
            writeBits(packed, bitPosition, word ushr 6, 10)
            bitPosition += 10
        }
        return encodePacked(packed, decoded.size, rangePacking)
    }

    fun decodeP010(encoded: ByteArray, decodedBytes: Int): ByteArray {
        val packed = decodePacked(encoded, decodedBytes)
        val output = ByteArray(decodedBytes)
        Npv10PackedP010WordDecoder.decodeInto(packed, output)
        return output
    }

    fun encodePacked(packed: ByteArray, decodedBytes: Int, rangePacking: Boolean = false): ByteArray {
        require(decodedBytes >= 0 && decodedBytes % Short.SIZE_BYTES == 0)
        val sampleCount = decodedBytes / Short.SIZE_BYTES
        require(packed.size == packedByteCount(sampleCount)) {
            "Packed P010 size ${packed.size} does not match $sampleCount samples"
        }
        val chunkCount = chunkCount(sampleCount)
        val output = ByteArray(maximumStoredBytes(decodedBytes.toLong()).toInt())
        val header = ByteBuffer.wrap(output).order(ByteOrder.BIG_ENDIAN)
        header.put(MAGIC)
        header.putInt(if (rangePacking) RANGE_VERSION else VERSION)
        header.putInt(packed.size)
        header.putInt(decodedBytes)
        header.putInt(sampleCount)
        header.putInt(CHUNK_SAMPLES)
        header.putInt(chunkCount)
        header.putInt(crc32(packed))
        var outputOffset = FRAME_HEADER_BYTES
        var sampleStart = 0
        while (sampleStart < sampleCount) {
            val samples = minOf(CHUNK_SAMPLES, sampleCount - sampleStart)
            val sourceOffset = packedByteCount(sampleStart)
            val sourceBytes = packedByteCount(samples)
            val range = if (rangePacking) Npv10Range10Codec.encode(packed, sourceOffset, samples) else null
            if (range != null) {
                putInt(output, outputOffset, FLAG_RANGE10)
                putInt(output, outputOffset + Int.SIZE_BYTES, samples)
                putInt(output, outputOffset + Int.SIZE_BYTES * 2, range.size)
                outputOffset += CHUNK_HEADER_BYTES
                range.copyInto(output, outputOffset)
                outputOffset += range.size
                sampleStart += samples
                continue
            }
            val split = splitTenBit(packed, sourceOffset, samples)
            val compressed = lz4Compress(split)
            val useCompressed = compressed.size < sourceBytes
            val body = if (useCompressed) compressed else packed.copyOfRange(
                sourceOffset,
                sourceOffset + sourceBytes
            )
            putInt(output, outputOffset, if (useCompressed) FLAG_SPLIT10_LZ4 else 0)
            putInt(output, outputOffset + Int.SIZE_BYTES, samples)
            putInt(output, outputOffset + Int.SIZE_BYTES * 2, body.size)
            outputOffset += CHUNK_HEADER_BYTES
            body.copyInto(output, outputOffset)
            outputOffset += body.size
            sampleStart += samples
        }
        return output.copyOf(outputOffset)
    }

    fun decodePacked(encoded: ByteArray, decodedBytes: Int): ByteArray {
        require(decodedBytes >= 0 && decodedBytes % Short.SIZE_BYTES == 0)
        require(encoded.size >= FRAME_HEADER_BYTES) { "NLC2 packet is truncated" }
        val header = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(header::get)
        require(magic.contentEquals(MAGIC)) { "Invalid NLC2 packet magic" }
        val version = header.int
        require(version == VERSION || version == RANGE_VERSION) { "Unsupported NLC2 packet version" }
        val expectedPackedBytes = header.int
        require(header.int == decodedBytes) { "NLC2 decoded byte count mismatch" }
        val sampleCount = header.int
        require(sampleCount == decodedBytes / Short.SIZE_BYTES) { "NLC2 sample count mismatch" }
        require(header.int == CHUNK_SAMPLES) { "Unsupported NLC2 chunk geometry" }
        val expectedChunks = header.int
        require(expectedChunks == chunkCount(sampleCount)) { "NLC2 chunk count mismatch" }
        val expectedPackedCrc = header.int
        require(expectedPackedBytes == packedByteCount(sampleCount)) { "NLC2 packed byte count mismatch" }

        val packed = ByteArray(expectedPackedBytes)
        var inputOffset = FRAME_HEADER_BYTES
        var outputOffset = 0
        var decodedSamples = 0
        repeat(expectedChunks) {
            require(inputOffset + CHUNK_HEADER_BYTES <= encoded.size) { "NLC2 chunk header is truncated" }
            val flags = readInt(encoded, inputOffset)
            val samples = readInt(encoded, inputOffset + Int.SIZE_BYTES)
            val storedBytes = readInt(encoded, inputOffset + Int.SIZE_BYTES * 2)
            inputOffset += CHUNK_HEADER_BYTES
            val expectedSamples = minOf(CHUNK_SAMPLES, sampleCount - decodedSamples)
            require(samples == expectedSamples && storedBytes > 0 && inputOffset + storedBytes <= encoded.size) {
                "NLC2 chunk shape is invalid"
            }
            val packedChunkBytes = packedByteCount(samples)
            when (flags) {
                0 -> {
                    require(storedBytes == packedChunkBytes) { "NLC2 raw chunk size mismatch" }
                    encoded.copyInto(packed, outputOffset, inputOffset, inputOffset + storedBytes)
                }
                FLAG_SPLIT10_LZ4 -> {
                    val split = lz4Decompress(encoded, inputOffset, storedBytes, packedChunkBytes)
                    joinTenBit(split, samples, packed, outputOffset)
                }
                FLAG_RANGE10 -> {
                    require(version == RANGE_VERSION) { "Range chunks require NLC2 version 2" }
                    Npv10Range10Codec.decode(encoded, inputOffset, storedBytes, samples, packed, outputOffset)
                }
                else -> throw IllegalArgumentException("Unsupported NLC2 chunk flags: $flags")
            }
            inputOffset += storedBytes
            outputOffset += packedChunkBytes
            decodedSamples += samples
        }
        require(inputOffset == encoded.size) { "NLC2 packet has trailing bytes" }
        require(outputOffset == packed.size && decodedSamples == sampleCount) { "NLC2 packet is incomplete" }
        require(crc32(packed) == expectedPackedCrc) { "NLC2 decoded packed-P010 CRC mismatch" }
        return packed
    }

    fun hasValidEnvelope(encoded: ByteArray, decodedBytes: Int): Boolean = runCatching {
        decodePacked(encoded, decodedBytes)
    }.isSuccess

    private fun splitTenBit(source: ByteArray, sourceOffset: Int, samples: Int): ByteArray {
        val output = ByteArray(packedByteCount(samples))
        val highOffset = samples
        var bitPosition = sourceOffset * 8
        for (index in 0 until samples) {
            val value = readBits(source, bitPosition, 10)
            bitPosition += 10
            output[index] = value.toByte()
            output[highOffset + index / 4] = (
                (output[highOffset + index / 4].toInt() and 0xFF) or
                    ((value ushr 8) shl ((index and 3) * 2))
                ).toByte()
        }
        return output
    }

    private fun joinTenBit(split: ByteArray, samples: Int, output: ByteArray, outputOffset: Int) {
        require(split.size == packedByteCount(samples)) { "NLC2 split chunk size mismatch" }
        val highOffset = samples
        var bitPosition = outputOffset * 8
        for (index in 0 until samples) {
            val high = (split[highOffset + index / 4].toInt() ushr ((index and 3) * 2)) and 0x03
            val value = (split[index].toInt() and 0xFF) or (high shl 8)
            writeBits(output, bitPosition, value, 10)
            bitPosition += 10
        }
    }

    private fun lz4Compress(source: ByteArray): ByteArray {
        if (source.isEmpty()) return ByteArray(0)
        val maximum = source.size + source.size / 255 + 16
        val output = ByteArray(maximum)
        val table = IntArray(HASH_SIZE) { -1 }
        var anchor = 0
        var input = 0
        var outputOffset = 0
        val matchLimit = source.size - 12
        while (input <= matchLimit) {
            val sequence = readIntLittleEndian(source, input)
            val hash = hash(sequence)
            val reference = table[hash]
            table[hash] = input
            if (reference < 0 || input - reference > MAX_DISTANCE ||
                readIntLittleEndian(source, reference) != sequence
            ) {
                input += 1
                continue
            }
            val literalLength = input - anchor
            var matchLength = MIN_MATCH
            while (input + matchLength < source.size &&
                source[reference + matchLength] == source[input + matchLength]
            ) {
                matchLength += 1
            }
            val tokenOffset = outputOffset++
            val encodedLiteral = minOf(literalLength, 15)
            val encodedMatch = minOf(matchLength - MIN_MATCH, 15)
            output[tokenOffset] = ((encodedLiteral shl 4) or encodedMatch).toByte()
            outputOffset = writeLength(output, outputOffset, literalLength - 15)
            source.copyInto(output, outputOffset, anchor, input)
            outputOffset += literalLength
            val distance = input - reference
            output[outputOffset++] = distance.toByte()
            output[outputOffset++] = (distance ushr 8).toByte()
            outputOffset = writeLength(output, outputOffset, matchLength - MIN_MATCH - 15)
            input += matchLength
            anchor = input
            if (input <= matchLimit) {
                table[hash(readIntLittleEndian(source, input - 2))] = input - 2
            }
        }
        val literals = source.size - anchor
        val tokenOffset = outputOffset++
        output[tokenOffset] = (minOf(literals, 15) shl 4).toByte()
        outputOffset = writeLength(output, outputOffset, literals - 15)
        source.copyInto(output, outputOffset, anchor, source.size)
        outputOffset += literals
        return output.copyOf(outputOffset)
    }

    private fun lz4Decompress(
        encoded: ByteArray,
        offset: Int,
        length: Int,
        expectedBytes: Int
    ): ByteArray {
        val output = ByteArray(expectedBytes)
        var input = offset
        val inputEnd = offset + length
        var target = 0
        while (input < inputEnd) {
            val token = encoded[input++].toInt() and 0xFF
            var literalLength = token ushr 4
            if (literalLength == 15) {
                var next: Int
                do {
                    require(input < inputEnd) { "Truncated NLC2 literal length" }
                    next = encoded[input++].toInt() and 0xFF
                    literalLength += next
                } while (next == 255)
            }
            require(input + literalLength <= inputEnd && target + literalLength <= output.size) {
                "Invalid NLC2 literal range"
            }
            encoded.copyInto(output, target, input, input + literalLength)
            input += literalLength
            target += literalLength
            if (input == inputEnd) break
            require(input + 2 <= inputEnd) { "Truncated NLC2 match offset" }
            val distance = (encoded[input].toInt() and 0xFF) or
                ((encoded[input + 1].toInt() and 0xFF) shl 8)
            input += 2
            require(distance in 1..target) { "Invalid NLC2 match distance" }
            var matchLength = (token and 0x0F) + MIN_MATCH
            if ((token and 0x0F) == 15) {
                var next: Int
                do {
                    require(input < inputEnd) { "Truncated NLC2 match length" }
                    next = encoded[input++].toInt() and 0xFF
                    matchLength += next
                } while (next == 255)
            }
            require(target + matchLength <= output.size) { "NLC2 match exceeds chunk" }
            val reference = target - distance
            for (index in 0 until matchLength) output[target + index] = output[reference + index]
            target += matchLength
        }
        require(input == inputEnd && target == expectedBytes) { "NLC2 chunk decoded length mismatch" }
        return output
    }

    private fun writeLength(output: ByteArray, start: Int, extraLength: Int): Int {
        if (extraLength < 0) return start
        var remaining = extraLength
        var offset = start
        while (remaining >= 255) {
            output[offset++] = 0xFF.toByte()
            remaining -= 255
        }
        output[offset++] = remaining.toByte()
        return offset
    }

    private fun packedByteCount(samples: Int): Int = ((samples.toLong() * 10L + 7L) / 8L).toInt()

    private fun chunkCount(samples: Int): Int =
        ((samples.toLong() + CHUNK_SAMPLES - 1L) / CHUNK_SAMPLES).toInt()

    private fun hash(value: Int): Int =
        (value * -1640531535).ushr(32 - HASH_BITS)

    private fun readIntLittleEndian(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readBits(bytes: ByteArray, bitOffset: Int, bitCount: Int): Int {
        var value = 0
        for (bit in 0 until bitCount) {
            val position = bitOffset + bit
            value = value or (((bytes[position ushr 3].toInt() ushr (position and 7)) and 1) shl bit)
        }
        return value
    }

    private fun writeBits(bytes: ByteArray, bitOffset: Int, value: Int, bitCount: Int) {
        for (bit in 0 until bitCount) {
            if (value and (1 shl bit) != 0) {
                val position = bitOffset + bit
                bytes[position ushr 3] = (
                    (bytes[position ushr 3].toInt() and 0xFF) or (1 shl (position and 7))
                    ).toByte()
            }
        }
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun crc32(bytes: ByteArray): Int = CRC32().apply { update(bytes) }.value.toInt()
}
