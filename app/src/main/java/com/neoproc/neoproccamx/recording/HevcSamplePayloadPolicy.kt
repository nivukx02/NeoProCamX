package com.neoproc.neoproccamx.recording

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal object HevcSamplePayloadPolicy {
    private const val NAL_TYPE_SPS = 33
    private const val NAL_TYPE_PREFIX_SEI = HevcColorMetadataRewriter.NAL_TYPE_PREFIX_SEI
    private const val BYTE_LANES_ONE = 0x0101010101010101L
    private const val BYTE_LANES_HIGH_BIT = -9187201950435737472L // 0x8080808080808080

    fun writeForMovSample(
        sampleBuffer: ByteBuffer,
        colorConfig: QuickTimeColorAtomConfig,
        writeBuffer: (ByteBuffer) -> Unit
    ): Int {
        val source = sampleBuffer.slice()
        singleLengthPrefixedNonSeiPayloadSize(source)?.let { payloadSize ->
            writeBuffer(source.duplicate())
            return Int.SIZE_BYTES + payloadSize
        }
        val units = splitLengthPrefixed(source)
        if (units.isNotEmpty()) {
            val nonSeiUnits = units.filterNot { hevcNalType(source, it.payloadOffset, it.payloadSize) == NAL_TYPE_PREFIX_SEI }
            if (nonSeiUnits.size == units.size) {
                writeBuffer(source.duplicate())
                return source.remaining()
            }
            var bytesWritten = 0
            nonSeiUnits.forEach { unit ->
                val segment = source.duplicate()
                segment.position(unit.lengthOffset)
                segment.limit(unit.payloadOffset + unit.payloadSize)
                writeBuffer(segment.slice())
                bytesWritten += Int.SIZE_BYTES + unit.payloadSize
            }
            return bytesWritten
        }

        writeAnnexBForMovSample(source, colorConfig, writeBuffer)?.let { return it }

        // Unknown payload framing: preserve the encoder output exactly. The byte-array conversion
        // used here previously also returned the original payload in this case, but allocated a full
        // frame first (multiple megabytes at the elite bitrate).
        val size = source.remaining()
        writeBuffer(source.duplicate())
        return size
    }

    fun convertForMovSample(
        sampleBytes: ByteArray,
        colorConfig: QuickTimeColorAtomConfig
    ): ByteArray {
        // Prove complete MOV length framing before scanning for Annex-B. A 256-byte NAL starts with
        // 00 00 01 00 and otherwise looks like a three-byte start code.
        val lengthPrefixedUnits = splitLengthPrefixed(sampleBytes)
        if (lengthPrefixedUnits.isNotEmpty()) {
            return joinLengthPrefixed(
                lengthPrefixedUnits.filterNot { hevcNalType(it) == NAL_TYPE_PREFIX_SEI }
            )
        }

        val annexBUnits = splitAnnexB(sampleBytes)
        if (annexBUnits.isNotEmpty()) {
            val rewritten = HevcColorMetadataRewriter.rewriteAnnexBColorMetadata(
                bytes = sampleBytes,
                colorConfig = colorConfig
            )
            return joinLengthPrefixed(
                splitAnnexB(rewritten)
                    .map { it.payload }
                    .filterNot { hevcNalType(it) == NAL_TYPE_PREFIX_SEI }
            )
        }

        return sampleBytes
    }

    private data class LengthPrefixedUnit(
        val lengthOffset: Int,
        val payloadOffset: Int,
        val payloadSize: Int
    )

    private data class AnnexBStartCode(
        val offset: Int,
        val prefixSize: Int
    )

    /**
     * Converts Annex-B access units directly into MOV length-prefixed NALs.
     *
     * VCL payloads are written as slices of the codec buffer, so a 2-4 MB frame never becomes several
     * equally large heap arrays. Only SPS NALs are copied because their VUI colour bits may need the
     * existing metadata correction. Prefix SEI filtering remains identical to [convertForMovSample].
     */
    private fun writeAnnexBForMovSample(
        source: ByteBuffer,
        colorConfig: QuickTimeColorAtomConfig,
        writeBuffer: (ByteBuffer) -> Unit
    ): Int? {
        val startCodes = findAnnexBStartCodes(source)
        if (startCodes.isEmpty()) return null

        var bytesWritten = 0
        startCodes.forEachIndexed { index, startCode ->
            val payloadStart = startCode.offset + startCode.prefixSize
            val payloadEnd = if (index + 1 < startCodes.size) {
                startCodes[index + 1].offset
            } else {
                source.remaining()
            }
            val payloadSize = payloadEnd - payloadStart
            if (payloadSize <= 0) return@forEachIndexed

            val nalType = hevcNalType(source, payloadStart, payloadSize)
            if (nalType == NAL_TYPE_PREFIX_SEI) return@forEachIndexed

            if (nalType == NAL_TYPE_SPS) {
                val sps = ByteArray(payloadSize)
                source.duplicate().apply {
                    position(payloadStart)
                    limit(payloadEnd)
                    get(sps)
                }
                val rewritten = HevcColorMetadataRewriter.rewriteSpsNalForMov(sps, colorConfig)
                writeNalLength(rewritten.size, writeBuffer)
                writeBuffer(ByteBuffer.wrap(rewritten))
                bytesWritten += Int.SIZE_BYTES + rewritten.size
            } else {
                writeNalLength(payloadSize, writeBuffer)
                val payload = source.duplicate().apply {
                    position(payloadStart)
                    limit(payloadEnd)
                }.slice()
                writeBuffer(payload)
                bytesWritten += Int.SIZE_BYTES + payloadSize
            }
        }
        return bytesWritten
    }

    private fun findAnnexBStartCodes(source: ByteBuffer): List<AnnexBStartCode> {
        val startCodes = ArrayList<AnnexBStartCode>(4)
        val size = source.remaining()
        var index = 0
        var consecutiveZeros = 0

        /*
         * MediaCodec exposes encoded access units through a direct ByteBuffer. Calling absolute
         * ByteBuffer.get(index) for every byte crosses ART's DirectByteBuffer/JNI boundary and was
         * measured on the target device as the sustained 4K25 writer's dominant CPU cost. Read eight
         * bytes per boundary crossing and decode them in registers. Chunks with no zero byte cannot
         * contain an Annex-B prefix and are skipped as a unit. The stateful zero run preserves exact
         * detection when 00 00 01 or 00 00 00 01 straddles an eight-byte boundary.
         */
        while (index <= size - Long.SIZE_BYTES) {
            val word = source.getLong(index)
            if (consecutiveZeros == 0 && !containsZeroByte(word)) {
                index += Long.SIZE_BYTES
                continue
            }
            var shift = 56
            while (shift >= 0) {
                val value = ((word ushr shift) and 0xFFL).toInt()
                if (value == 0) {
                    consecutiveZeros = (consecutiveZeros + 1).coerceAtMost(3)
                } else {
                    if (value == 1 && consecutiveZeros >= 2) {
                        val prefixSize = if (consecutiveZeros >= 3) 4 else 3
                        startCodes += AnnexBStartCode(
                            offset = index - prefixSize + 1,
                            prefixSize = prefixSize
                        )
                    }
                    consecutiveZeros = 0
                }
                index += 1
                shift -= Byte.SIZE_BITS
            }
        }

        while (index < size) {
            val value = source.get(index).toInt() and 0xFF
            if (value == 0) {
                consecutiveZeros = (consecutiveZeros + 1).coerceAtMost(3)
            } else {
                if (value == 1 && consecutiveZeros >= 2) {
                    val prefixSize = if (consecutiveZeros >= 3) 4 else 3
                    startCodes += AnnexBStartCode(
                        offset = index - prefixSize + 1,
                        prefixSize = prefixSize
                    )
                }
                consecutiveZeros = 0
            }
            index += 1
        }
        return startCodes
    }

    /** SWAR zero-byte test; byte order is irrelevant because only lane equality is inspected. */
    private fun containsZeroByte(word: Long): Boolean =
        ((word - BYTE_LANES_ONE) and word.inv() and BYTE_LANES_HIGH_BIT) != 0L

    private fun writeNalLength(length: Int, writeBuffer: (ByteBuffer) -> Unit) {
        writeBuffer(
            ByteBuffer.wrap(
                byteArrayOf(
                    (length ushr 24).toByte(),
                    (length ushr 16).toByte(),
                    (length ushr 8).toByte(),
                    length.toByte()
                )
            )
        )
    }

    private fun singleLengthPrefixedNonSeiPayloadSize(buffer: ByteBuffer): Int? {
        val size = buffer.remaining()
        if (size <= Int.SIZE_BYTES) return null
        val length =
            ((buffer.get(0).toInt() and 0xFF) shl 24) or
                ((buffer.get(1).toInt() and 0xFF) shl 16) or
                ((buffer.get(2).toInt() and 0xFF) shl 8) or
                (buffer.get(3).toInt() and 0xFF)
        if (length <= 0 || length + Int.SIZE_BYTES != size) return null
        return length.takeIf {
            hevcNalType(buffer, payloadOffset = Int.SIZE_BYTES, payloadSize = it) != NAL_TYPE_PREFIX_SEI
        }
    }

    private fun splitLengthPrefixed(buffer: ByteBuffer): List<LengthPrefixedUnit> {
        val units = ArrayList<LengthPrefixedUnit>()
        var offset = 0
        val size = buffer.remaining()
        while (offset < size) {
            if (offset + Int.SIZE_BYTES > size) return emptyList()
            val length =
                ((buffer.get(offset).toInt() and 0xFF) shl 24) or
                    ((buffer.get(offset + 1).toInt() and 0xFF) shl 16) or
                    ((buffer.get(offset + 2).toInt() and 0xFF) shl 8) or
                    (buffer.get(offset + 3).toInt() and 0xFF)
            if (length <= 0 || offset + Int.SIZE_BYTES + length > size) return emptyList()
            units += LengthPrefixedUnit(
                lengthOffset = offset,
                payloadOffset = offset + Int.SIZE_BYTES,
                payloadSize = length
            )
            offset += Int.SIZE_BYTES + length
        }
        return if (offset == size) units else emptyList()
    }

    private fun splitLengthPrefixed(bytes: ByteArray): List<ByteArray> {
        val units = ArrayList<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            if (offset + 4 > bytes.size) return emptyList()
            val length =
                ((bytes[offset].toInt() and 0xFF) shl 24) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)
            if (length <= 0 || offset + 4 + length > bytes.size) return emptyList()
            units += bytes.copyOfRange(offset + 4, offset + 4 + length)
            offset += 4 + length
        }
        return if (offset == bytes.size) units else emptyList()
    }

    private fun splitAnnexB(bytes: ByteArray): List<AnnexBNalUnit> {
        val startCodes = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index <= bytes.size - 3) {
            val fourByte =
                index <= bytes.size - 4 &&
                    bytes[index] == 0.toByte() &&
                    bytes[index + 1] == 0.toByte() &&
                    bytes[index + 2] == 0.toByte() &&
                    bytes[index + 3] == 1.toByte()
            val threeByte =
                bytes[index] == 0.toByte() &&
                    bytes[index + 1] == 0.toByte() &&
                    bytes[index + 2] == 1.toByte()
            when {
                fourByte -> {
                    startCodes += index to 4
                    index += 4
                }

                threeByte -> {
                    startCodes += index to 3
                    index += 3
                }

                else -> index += 1
            }
        }
        if (startCodes.isEmpty()) return emptyList()
        return startCodes.mapIndexedNotNull { listIndex, (start, prefixLength) ->
            val payloadStart = start + prefixLength
            val payloadEnd = if (listIndex + 1 < startCodes.size) {
                startCodes[listIndex + 1].first
            } else {
                bytes.size
            }
            if (payloadEnd <= payloadStart) {
                null
            } else {
                AnnexBNalUnit(
                    prefix = bytes.copyOfRange(start, payloadStart),
                    payload = bytes.copyOfRange(payloadStart, payloadEnd)
                )
            }
        }
    }

    private fun joinLengthPrefixed(units: List<ByteArray>): ByteArray {
        if (units.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream(units.sumOf { 4 + it.size })
        units.forEach { nal ->
            out.write((nal.size ushr 24) and 0xFF)
            out.write((nal.size ushr 16) and 0xFF)
            out.write((nal.size ushr 8) and 0xFF)
            out.write(nal.size and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun hevcNalType(nal: ByteArray): Int {
        if (nal.size < 2) return -1
        return (nal[0].toInt() ushr 1) and 0x3F
    }

    private fun hevcNalType(buffer: ByteBuffer, payloadOffset: Int, payloadSize: Int): Int {
        if (payloadSize < 2) return -1
        return (buffer.get(payloadOffset).toInt() ushr 1) and 0x3F
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val copy = duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    private data class AnnexBNalUnit(
        val prefix: ByteArray,
        val payload: ByteArray
    )
}
