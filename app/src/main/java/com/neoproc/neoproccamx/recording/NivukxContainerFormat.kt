package com.neoproc.neoproccamx.recording

import java.io.IOException
import java.lang.reflect.Constructor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.Checksum

/**
 * NIVUKX v5 is a streaming wrapper around the camera's original encoded access units.
 *
 * Unlike the browser prototype's 8-bit RGB/RLE representation, v5 never decodes and re-encodes a
 * recorded sample. HEVC Main10/AV1 and AAC payloads, timestamps, flags, codec setup bytes, colour
 * metadata, and HDR static metadata are retained exactly.
 */
internal object NivukxContainerFormat {
    /** Logical in-app media type used by the custom Media3 extractor. */
    const val MIME_TYPE = "video/vnd.neoproc.nivukx"
    /**
     * Android MediaProvider rejects unregistered custom video subtypes. NIVUKX therefore lives in the
     * generic Files collection under this platform-safe storage MIME; its extension and in-app
     * logical MIME still identify it as playable NIVUKX media.
     */
    const val MEDIASTORE_MIME_TYPE = "application/octet-stream"
    const val FILE_EXTENSION = "nivukx"
    const val USER_VISIBLE_LABEL = "NIVUKX Lossless Encoded Master"

    const val VERSION_MAJOR = 5
    const val VERSION_MINOR = 0
    const val HEADER_SIZE = 128
    const val CHUNK_HEADER_SIZE = 32
    const val SAMPLE_HEADER_SIZE = 32
    const val FIRST_CHUNK_OFFSET = HEADER_SIZE.toLong()

    const val HEADER_FLAG_FINALIZED = 1
    const val CHUNK_MARKER = 0x4E58434B // NXCK
    const val CHUNK_METADATA = 0x4D455441 // META
    const val CHUNK_TRACK = 0x5452414B // TRAK
    const val CHUNK_SAMPLE = 0x53414D50 // SAMP
    const val CHUNK_INDEX = 0x494E4458 // INDX
    const val CHUNK_FOOTER = 0x444F4E45 // DONE

    const val TRACK_SCHEMA = 1
    const val SAMPLE_SCHEMA = 1
    const val INDEX_SCHEMA = 1
    const val FOOTER_SCHEMA = 1
    const val DIGEST_SHA256 = 1

    const val MAX_TRACKS = 16
    const val MAX_MIME_BYTES = 1_024
    const val MAX_CODEC_CONFIG_BYTES = 16 * 1024 * 1024
    const val MAX_HDR_STATIC_INFO_BYTES = 64 * 1024
    const val MAX_TRACK_CHUNK_BYTES = 32L * 1024L * 1024L
    const val MAX_SAMPLE_DATA_BYTES = 1024L * 1024L * 1024L
    const val MAX_SAMPLE_CHUNK_BYTES = MAX_SAMPLE_DATA_BYTES + SAMPLE_HEADER_SIZE
    const val MAX_INDEX_CHUNK_BYTES = 64L * 1024L * 1024L
    const val MAX_SMALL_CHUNK_BYTES = 1024L * 1024L
    const val MAX_INDEX_ENTRIES = 262_144
    const val INDEX_ENTRY_SIZE = 24

    private const val HEADER_CRC_OFFSET = 112
    private const val CHUNK_HEADER_CRC_OFFSET = 28
    private val MAGIC = byteArrayOf(
        'N'.code.toByte(),
        'I'.code.toByte(),
        'V'.code.toByte(),
        'U'.code.toByte(),
        'K'.code.toByte(),
        'X'.code.toByte(),
        '5'.code.toByte(),
        0
    )

    data class Header(
        val sessionId: UUID,
        val createdAtEpochMs: Long,
        val orientationDegrees: Int,
        val trackCount: Int,
        val sampleCount: Long,
        val durationUs: Long,
        val firstSampleOffset: Long,
        val indexOffset: Long,
        val footerOffset: Long,
        val fileLength: Long,
        val finalized: Boolean
    )

    enum class TrackKind(val id: Int) {
        VIDEO(1),
        AUDIO(2),
        DEPTH(3),
        TELEMETRY(4);

        companion object {
            fun fromId(id: Int): TrackKind = entries.firstOrNull { it.id == id }
                ?: throw IOException("Unsupported NIVUKX track kind $id")
        }
    }

    data class ColorDescriptor(
        val colorPrimaries: Int,
        val transferCharacteristics: Int,
        val matrixCoefficients: Int,
        val fullRange: Boolean,
        val hdrStaticInfo: ByteArray
    )

    data class TrackDescriptor(
        val index: Int,
        val kind: TrackKind,
        val mime: String,
        val codecConfig: ByteArray,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Int = 0,
        val sampleRate: Int = 0,
        val channelCount: Int = 0,
        val color: ColorDescriptor? = null
    )

    data class SampleInfo(
        val trackIndex: Int,
        val flags: Int,
        val presentationTimeUs: Long,
        val dataSize: Long,
        val chunkOffset: Long,
        val dataOffset: Long
    )

    data class IndexEntry(
        val trackIndex: Int,
        val flags: Int,
        val presentationTimeUs: Long,
        val chunkOffset: Long
    )

    data class ChunkHeader(
        val type: Int,
        val sequence: Long,
        val payloadLength: Long,
        val payloadCrc32c: Int,
        val encodedBytes: ByteArray
    )

    data class Footer(
        val sessionId: UUID,
        val sampleCount: Long,
        val durationUs: Long,
        val trackCount: Int,
        val sessionDigest: ByteArray
    )

    fun encodeHeader(header: Header): ByteArray {
        require(header.createdAtEpochMs >= 0L)
        require(header.orientationDegrees in 0..359)
        require(header.trackCount in 0..MAX_TRACKS)
        require(header.sampleCount >= 0L)
        require(header.durationUs >= 0L)
        val bytes = ByteArray(HEADER_SIZE)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC)
        buffer.putInt(VERSION_MAJOR)
        buffer.putInt(VERSION_MINOR)
        buffer.putInt(HEADER_SIZE)
        buffer.putInt(if (header.finalized) HEADER_FLAG_FINALIZED else 0)
        buffer.putLong(header.sessionId.mostSignificantBits)
        buffer.putLong(header.sessionId.leastSignificantBits)
        buffer.putLong(header.createdAtEpochMs)
        buffer.putInt(header.orientationDegrees)
        buffer.putInt(header.trackCount)
        buffer.putLong(header.sampleCount)
        buffer.putLong(header.durationUs)
        buffer.putLong(FIRST_CHUNK_OFFSET)
        buffer.putLong(header.firstSampleOffset)
        buffer.putLong(header.indexOffset)
        buffer.putLong(header.footerOffset)
        buffer.putLong(header.fileLength)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.putLong(0L)
        val crc = NivukxCrc32c.compute(bytes)
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(HEADER_CRC_OFFSET, crc)
        return bytes
    }

    fun decodeHeader(bytes: ByteArray): Header {
        if (bytes.size != HEADER_SIZE) throw IOException("Invalid NIVUKX header size ${bytes.size}")
        val crcBytes = bytes.copyOf()
        val storedCrc = ByteBuffer.wrap(crcBytes).order(ByteOrder.BIG_ENDIAN).getInt(HEADER_CRC_OFFSET)
        ByteBuffer.wrap(crcBytes).order(ByteOrder.BIG_ENDIAN).putInt(HEADER_CRC_OFFSET, 0)
        if (NivukxCrc32c.compute(crcBytes) != storedCrc) {
            throw IOException("NIVUKX header CRC32C mismatch")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a NIVUKX v5 stream")
        val major = buffer.int
        val minor = buffer.int
        if (major != VERSION_MAJOR || minor > VERSION_MINOR) {
            throw IOException("Unsupported NIVUKX version $major.$minor")
        }
        if (buffer.int != HEADER_SIZE) throw IOException("Unsupported NIVUKX header layout")
        val flags = buffer.int
        val sessionId = UUID(buffer.long, buffer.long)
        val createdAtEpochMs = buffer.long
        val orientationDegrees = buffer.int
        val trackCount = buffer.int
        val sampleCount = buffer.long
        val durationUs = buffer.long
        val firstChunkOffset = buffer.long
        val firstSampleOffset = buffer.long
        val indexOffset = buffer.long
        val footerOffset = buffer.long
        val fileLength = buffer.long
        if (firstChunkOffset != FIRST_CHUNK_OFFSET) throw IOException("Invalid NIVUKX first chunk offset")
        if (orientationDegrees !in 0..359) throw IOException("Invalid NIVUKX orientation")
        if (trackCount !in 0..MAX_TRACKS || sampleCount < 0L || durationUs < 0L) {
            throw IOException("Invalid NIVUKX header counters")
        }
        return Header(
            sessionId = sessionId,
            createdAtEpochMs = createdAtEpochMs,
            orientationDegrees = orientationDegrees,
            trackCount = trackCount,
            sampleCount = sampleCount,
            durationUs = durationUs,
            firstSampleOffset = firstSampleOffset,
            indexOffset = indexOffset,
            footerOffset = footerOffset,
            fileLength = fileLength,
            finalized = flags and HEADER_FLAG_FINALIZED != 0
        )
    }

    fun encodeChunkHeader(type: Int, sequence: Long, payloadLength: Long, payloadCrc32c: Int): ByteArray {
        require(sequence >= 0L)
        require(payloadLength >= 0L)
        val bytes = ByteArray(CHUNK_HEADER_SIZE)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(CHUNK_MARKER)
        buffer.putInt(type)
        buffer.putLong(sequence)
        buffer.putLong(payloadLength)
        buffer.putInt(payloadCrc32c)
        buffer.putInt(0)
        val headerCrc = NivukxCrc32c.compute(bytes, 0, CHUNK_HEADER_CRC_OFFSET)
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(CHUNK_HEADER_CRC_OFFSET, headerCrc)
        return bytes
    }

    fun decodeChunkHeader(bytes: ByteArray): ChunkHeader {
        if (bytes.size != CHUNK_HEADER_SIZE) throw IOException("Truncated NIVUKX chunk header")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != CHUNK_MARKER) throw IOException("Invalid NIVUKX chunk marker")
        val type = buffer.int
        val sequence = buffer.long
        val payloadLength = buffer.long
        val payloadCrc = buffer.int
        val storedHeaderCrc = buffer.int
        if (sequence < 0L || payloadLength < 0L || payloadLength > maxPayloadFor(type)) {
            throw IOException("Invalid NIVUKX chunk bounds type=${fourCc(type)} length=$payloadLength")
        }
        val computedHeaderCrc = NivukxCrc32c.compute(bytes, 0, CHUNK_HEADER_CRC_OFFSET)
        if (computedHeaderCrc != storedHeaderCrc) throw IOException("NIVUKX chunk header CRC32C mismatch")
        return ChunkHeader(type, sequence, payloadLength, payloadCrc, bytes.copyOf())
    }

    fun encodeTrack(track: TrackDescriptor): ByteArray {
        validateTrack(track)
        val mimeBytes = track.mime.toByteArray(Charsets.UTF_8)
        val hdrBytes = track.color?.hdrStaticInfo ?: ByteArray(0)
        val fixedBytes = 16 * Int.SIZE_BYTES
        val totalBytes = checkedIntSize(
            fixedBytes.toLong() + mimeBytes.size + track.codecConfig.size + hdrBytes.size,
            MAX_TRACK_CHUNK_BYTES,
            "track"
        )
        return ByteBuffer.allocate(totalBytes).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(TRACK_SCHEMA)
            putInt(track.index)
            putInt(track.kind.id)
            putInt(0)
            putInt(track.width)
            putInt(track.height)
            putInt(track.fps)
            putInt(track.sampleRate)
            putInt(track.channelCount)
            putInt(track.color?.colorPrimaries ?: 0)
            putInt(track.color?.transferCharacteristics ?: 0)
            putInt(track.color?.matrixCoefficients ?: 0)
            putInt(if (track.color?.fullRange == true) 1 else 0)
            putInt(mimeBytes.size)
            putInt(track.codecConfig.size)
            putInt(hdrBytes.size)
            put(mimeBytes)
            put(track.codecConfig)
            put(hdrBytes)
        }.array()
    }

    fun decodeTrack(bytes: ByteArray): TrackDescriptor {
        if (bytes.size < 16 * Int.SIZE_BYTES || bytes.size.toLong() > MAX_TRACK_CHUNK_BYTES) {
            throw IOException("Invalid NIVUKX track payload size ${bytes.size}")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != TRACK_SCHEMA) throw IOException("Unsupported NIVUKX track schema")
        val index = buffer.int
        val kind = TrackKind.fromId(buffer.int)
        buffer.int // reserved flags
        val width = buffer.int
        val height = buffer.int
        val fps = buffer.int
        val sampleRate = buffer.int
        val channelCount = buffer.int
        val colorPrimaries = buffer.int
        val transfer = buffer.int
        val matrix = buffer.int
        val fullRange = buffer.int != 0
        val mimeLength = buffer.int
        val codecLength = buffer.int
        val hdrLength = buffer.int
        if (mimeLength !in 1..MAX_MIME_BYTES || codecLength !in 0..MAX_CODEC_CONFIG_BYTES ||
            hdrLength !in 0..MAX_HDR_STATIC_INFO_BYTES
        ) {
            throw IOException("Invalid NIVUKX track variable-field lengths")
        }
        val required = mimeLength.toLong() + codecLength.toLong() + hdrLength.toLong()
        if (required != buffer.remaining().toLong()) throw IOException("Malformed NIVUKX track payload")
        val mimeBytes = ByteArray(mimeLength).also(buffer::get)
        val codecConfig = ByteArray(codecLength).also(buffer::get)
        val hdrStaticInfo = ByteArray(hdrLength).also(buffer::get)
        val color = if (kind == TrackKind.VIDEO) {
            ColorDescriptor(colorPrimaries, transfer, matrix, fullRange, hdrStaticInfo)
        } else {
            null
        }
        return TrackDescriptor(
            index = index,
            kind = kind,
            mime = mimeBytes.toString(Charsets.UTF_8),
            codecConfig = codecConfig,
            width = width,
            height = height,
            fps = fps,
            sampleRate = sampleRate,
            channelCount = channelCount,
            color = color
        ).also(::validateTrack)
    }

    fun encodeSampleHeader(trackIndex: Int, flags: Int, presentationTimeUs: Long, dataSize: Long): ByteArray {
        require(trackIndex in 0 until MAX_TRACKS)
        require(presentationTimeUs >= 0L)
        require(dataSize in 1..MAX_SAMPLE_DATA_BYTES)
        return ByteBuffer.allocate(SAMPLE_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(SAMPLE_SCHEMA)
            putInt(trackIndex)
            putInt(flags)
            putInt(0)
            putLong(presentationTimeUs)
            putLong(dataSize)
        }.array()
    }

    fun decodeSampleHeader(bytes: ByteArray, chunkOffset: Long, dataOffset: Long): SampleInfo {
        if (bytes.size != SAMPLE_HEADER_SIZE) throw IOException("Truncated NIVUKX sample header")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != SAMPLE_SCHEMA) throw IOException("Unsupported NIVUKX sample schema")
        val trackIndex = buffer.int
        val flags = buffer.int
        buffer.int
        val presentationTimeUs = buffer.long
        val dataSize = buffer.long
        if (trackIndex !in 0 until MAX_TRACKS || presentationTimeUs < 0L ||
            dataSize !in 1..MAX_SAMPLE_DATA_BYTES
        ) {
            throw IOException("Invalid NIVUKX sample metadata")
        }
        return SampleInfo(trackIndex, flags, presentationTimeUs, dataSize, chunkOffset, dataOffset)
    }

    fun encodeIndex(entries: List<IndexEntry>, compactionLevel: Int): ByteArray {
        require(entries.size <= MAX_INDEX_ENTRIES)
        require(compactionLevel >= 0)
        val size = 4 * Int.SIZE_BYTES + entries.size * INDEX_ENTRY_SIZE
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(INDEX_SCHEMA)
            putInt(entries.size)
            putInt(compactionLevel)
            putInt(0)
            entries.forEach { entry ->
                putInt(entry.trackIndex)
                putInt(entry.flags)
                putLong(entry.presentationTimeUs)
                putLong(entry.chunkOffset)
            }
        }.array()
    }

    fun decodeIndex(bytes: ByteArray): List<IndexEntry> {
        if (bytes.size < 4 * Int.SIZE_BYTES || bytes.size.toLong() > MAX_INDEX_CHUNK_BYTES) {
            throw IOException("Invalid NIVUKX index payload size")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != INDEX_SCHEMA) throw IOException("Unsupported NIVUKX index schema")
        val count = buffer.int
        buffer.int // compaction level
        buffer.int
        if (count !in 0..MAX_INDEX_ENTRIES || buffer.remaining() != count * INDEX_ENTRY_SIZE) {
            throw IOException("Malformed NIVUKX index")
        }
        return List(count) {
            val trackIndex = buffer.int
            val flags = buffer.int
            val pts = buffer.long
            val offset = buffer.long
            if (trackIndex !in 0 until MAX_TRACKS || pts < 0L || offset < FIRST_CHUNK_OFFSET) {
                throw IOException("Invalid NIVUKX index entry")
            }
            IndexEntry(trackIndex, flags, pts, offset)
        }
    }

    fun encodeFooter(footer: Footer): ByteArray {
        require(footer.sampleCount >= 0L && footer.durationUs >= 0L)
        require(footer.trackCount in 0..MAX_TRACKS)
        require(footer.sessionDigest.size == 32)
        return ByteBuffer.allocate(80).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(FOOTER_SCHEMA)
            putInt(DIGEST_SHA256)
            putLong(footer.sessionId.mostSignificantBits)
            putLong(footer.sessionId.leastSignificantBits)
            putLong(footer.sampleCount)
            putLong(footer.durationUs)
            putInt(footer.trackCount)
            putInt(footer.sessionDigest.size)
            put(footer.sessionDigest)
        }.array()
    }

    fun decodeFooter(bytes: ByteArray): Footer {
        if (bytes.size != 80) throw IOException("Invalid NIVUKX footer size")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != FOOTER_SCHEMA || buffer.int != DIGEST_SHA256) {
            throw IOException("Unsupported NIVUKX footer schema")
        }
        val sessionId = UUID(buffer.long, buffer.long)
        val sampleCount = buffer.long
        val durationUs = buffer.long
        val trackCount = buffer.int
        val digestLength = buffer.int
        if (sampleCount < 0L || durationUs < 0L || trackCount !in 0..MAX_TRACKS || digestLength != 32) {
            throw IOException("Invalid NIVUKX footer counters")
        }
        return Footer(
            sessionId = sessionId,
            sampleCount = sampleCount,
            durationUs = durationUs,
            trackCount = trackCount,
            sessionDigest = ByteArray(digestLength).also(buffer::get)
        )
    }

    fun updateSessionDigest(digest: MessageDigest, chunkHeader: ByteArray, payloadSha256: ByteArray) {
        require(chunkHeader.size == CHUNK_HEADER_SIZE)
        require(payloadSha256.size == 32)
        digest.update(chunkHeader)
        digest.update(payloadSha256)
    }

    fun fourCc(value: Int): String = buildString(4) {
        append(((value ushr 24) and 0xFF).toChar())
        append(((value ushr 16) and 0xFF).toChar())
        append(((value ushr 8) and 0xFF).toChar())
        append((value and 0xFF).toChar())
    }

    fun hasMagic(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    private fun validateTrack(track: TrackDescriptor) {
        require(track.index in 0 until MAX_TRACKS) { "Invalid NIVUKX track index" }
        val mimeBytes = track.mime.toByteArray(Charsets.UTF_8)
        require(mimeBytes.size in 1..MAX_MIME_BYTES) { "Invalid NIVUKX MIME length" }
        require(track.codecConfig.size <= MAX_CODEC_CONFIG_BYTES) { "NIVUKX codec setup is too large" }
        require((track.color?.hdrStaticInfo?.size ?: 0) <= MAX_HDR_STATIC_INFO_BYTES) {
            "NIVUKX HDR static metadata is too large"
        }
        when (track.kind) {
            TrackKind.VIDEO -> {
                require(track.width in 1..131_072 && track.height in 1..131_072) {
                    "Invalid NIVUKX video dimensions"
                }
                require(track.fps in 1..1_000) { "Invalid NIVUKX video frame rate" }
                require(track.color != null) { "NIVUKX video track requires colour metadata" }
            }
            TrackKind.AUDIO -> {
                require(track.sampleRate in 1..768_000) { "Invalid NIVUKX audio sample rate" }
                require(track.channelCount in 1..64) { "Invalid NIVUKX channel count" }
            }
            TrackKind.DEPTH,
            TrackKind.TELEMETRY -> Unit
        }
    }

    private fun maxPayloadFor(type: Int): Long = when (type) {
        CHUNK_TRACK -> MAX_TRACK_CHUNK_BYTES
        CHUNK_SAMPLE -> MAX_SAMPLE_CHUNK_BYTES
        CHUNK_INDEX -> MAX_INDEX_CHUNK_BYTES
        CHUNK_METADATA,
        CHUNK_FOOTER -> MAX_SMALL_CHUNK_BYTES
        else -> throw IOException("Unsupported NIVUKX chunk type ${fourCc(type)}")
    }

    private fun checkedIntSize(size: Long, maximum: Long, label: String): Int {
        if (size < 0L || size > maximum || size > Int.MAX_VALUE) {
            throw IllegalArgumentException("NIVUKX $label payload is too large: $size")
        }
        return size.toInt()
    }
}

/** CRC32C with the platform implementation when present and a Castagnoli fallback for API 29/30. */
internal object NivukxCrc32c {
    private val platformConstructor: Constructor<*>? = runCatching {
        Class.forName("java.util.zip.CRC32C").getDeclaredConstructor()
    }.getOrNull()

    fun newAccumulator(): Accumulator {
        val platform = runCatching { platformConstructor?.newInstance() as? Checksum }.getOrNull()
        return if (platform != null) PlatformAccumulator(platform) else SoftwareAccumulator()
    }

    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int =
        newAccumulator().apply { update(bytes, offset, length) }.value()

    internal fun computeSoftware(bytes: ByteArray): Int =
        SoftwareAccumulator().apply { update(bytes, 0, bytes.size) }.value()

    interface Accumulator {
        fun update(bytes: ByteArray, offset: Int, length: Int)
        fun value(): Int
    }

    private class PlatformAccumulator(private val checksum: Checksum) : Accumulator {
        override fun update(bytes: ByteArray, offset: Int, length: Int) {
            checksum.update(bytes, offset, length)
        }

        override fun value(): Int = checksum.value.toInt()
    }

    private class SoftwareAccumulator : Accumulator {
        private var crc = -1

        override fun update(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            var current = crc
            for (index in offset until offset + length) {
                current = TABLE[(current xor bytes[index].toInt()) and 0xFF] xor (current ushr 8)
            }
            crc = current
        }

        override fun value(): Int = crc xor -1
    }

    private val TABLE = IntArray(256) { seed ->
        var value = seed
        repeat(8) {
            value = if (value and 1 != 0) (value ushr 1) xor 0x82F63B78.toInt() else value ushr 1
        }
        value
    }
}
