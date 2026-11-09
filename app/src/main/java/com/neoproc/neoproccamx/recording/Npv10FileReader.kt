package com.neoproc.neoproccamx.recording

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32

data class Npv10FileHeader(
    val version: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitDepth: Int,
    val metadata: String,
    val metadataValues: Map<String, String>,
    val payloadFormat: Npv10PayloadFormat,
    val sourceFormat: String,
    val sourceBitDepth: Int,
    val storageBitDepth: Int,
    val exactSelectedResolution: Boolean,
    val orientationHintDegrees: Int = 0,
    val packetCodec: Npv10PacketCodec = Npv10PacketCodec.LEGACY_UNCOMPRESSED
)

data class Npv10FrameRecord(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val payload: ByteArray
)

/** Encoded on-disk packet view used by the integrity auditor without allocating or decoding a frame. */
internal data class Npv10StoredFrameRecord(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val storedPayloadBytes: Int,
    val decodedPayloadBytes: Int,
    val expectedCrc: Int,
    val payload: ByteBuffer
)

/** Caller-owned decoded frame view used by the bounded-memory export path. */
internal data class Npv10DirectFrameRecord(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val payload: ByteBuffer
)

class Npv10FileReader(file: File) : Closeable {
    private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)

    val header: Npv10FileHeader = try {
        readHeader()
    } catch (t: Throwable) {
        runCatching { channel.close() }
        throw t
    }

    /** Byte offset of the first frame packet, retained so playback seeks never reparse the header. */
    private val firstFrameBytePosition: Long = channel.position()

    /**
     * Packet-boundary index built by the playback pre-pass. Entry `n` is frame `n`; the final entry
     * is the clean end-of-stream boundary. Deadline catch-up and scrubbing therefore become one
     * FileChannel position change instead of repeatedly scanning from frame zero.
     */
    @Volatile
    private var playbackFrameBytePositions: LongArray? = null

    internal val bytePosition: Long
        get() = channel.position()

    fun readNextFrame(): Npv10FrameRecord? =
        readNextFrameInternal(null)

    fun readNextFrameInto(target: ByteBuffer): Npv10FrameRecord? =
        readNextFrameInternal(target)

    /**
     * Counts packets without decoding or allocating their payloads, then restores the caller's read
     * position. This is intentionally separate from conversion: the private-master player uses it to
     * expose an exact timeline before playback starts.
     */
    @Synchronized
    fun countFramesForPlayback(): Int {
        playbackFrameBytePositions?.let { return (it.size - 1).coerceAtLeast(0) }
        val savedPosition = channel.position()
        return try {
            channel.position(firstFrameBytePosition)
            var positions = LongArray(PLAYBACK_INDEX_INITIAL_CAPACITY)
            var count = 0
            while (true) {
                if (count + 1 >= positions.size) {
                    positions = positions.copyOf(positions.size shl 1)
                }
                positions[count] = channel.position()
                if (!skipNextFrameForPlayback()) break
                count += 1
            }
            positions[count] = channel.position()
            playbackFrameBytePositions = positions.copyOf(count + 1)
            count
        } finally {
            channel.position(savedPosition.coerceAtMost(channel.size()))
        }
    }

    /**
     * Positions the reader at [frameIndex] using packet headers only. No frame is decoded and no
     * master bytes are changed. Returns the actual bounded frame index reached.
     */
    @Synchronized
    fun seekToFrameForPlayback(frameIndex: Int): Int {
        val positions = playbackFrameBytePositions ?: run {
            countFramesForPlayback()
            checkNotNull(playbackFrameBytePositions)
        }
        val target = frameIndex.coerceIn(0, (positions.size - 1).coerceAtLeast(0))
        channel.position(positions[target])
        return target
    }

    /** Skip one complete packet while still validating its shape against the immutable header. */
    @Synchronized
    fun skipNextFrameForPlayback(): Boolean {
        val magic = readBytesOrNull(
            length = Npv10ContainerPolicy.FRAME_MAGIC_BYTES.size,
            context = "frame marker"
        ) ?: return false
        require(magic.contentEquals(Npv10ContainerPolicy.FRAME_MAGIC_BYTES)) {
            "Invalid NPV10 frame marker"
        }
        readPositiveFrameTimestamp()
        val width = readInt("frame width")
        val height = readInt("frame height")
        val payloadSize = readInt("frame payload size")
        readInt("frame crc")
        require(width == header.width && height == header.height) {
            "NPV10 frame ${width}x$height does not match header ${header.width}x${header.height}"
        }
        val decodedPayloadSize = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(width, height, header.payloadFormat)
            .toInt()
        val expectedStoredSize = Npv10PacketCodecEngine
            .storedBytes(decodedPayloadSize.toLong(), header.packetCodec)
            .toInt()
        require(Npv10PacketCodecEngine.isValidStoredBytes(
            payloadSize.toLong(),
            decodedPayloadSize.toLong(),
            header.packetCodec
        )) {
            "NPV10 frame packet size $payloadSize is outside the valid ${header.packetCodec} " +
                "envelope ending at $expectedStoredSize bytes"
        }
        val nextPosition = channel.position() + payloadSize.toLong()
        require(nextPosition <= channel.size()) { "Truncated NPV10 frame packet" }
        channel.position(nextPosition)
        return true
    }

    /**
     * Reads and CRC-validates the exact stored packet into a caller-owned reusable buffer. Unlike
     * [readNextFrame], this does not allocate a full packet or decode packed-10 P010. It is intended
     * for post-record integrity verification where the on-disk bytes are the evidence of interest.
     */
    internal fun readNextStoredFrameInto(target: ByteBuffer): Npv10StoredFrameRecord? {
        val magic = readBytesOrNull(
            length = Npv10ContainerPolicy.FRAME_MAGIC_BYTES.size,
            context = "frame marker"
        ) ?: return null
        require(magic.contentEquals(Npv10ContainerPolicy.FRAME_MAGIC_BYTES)) {
            "Invalid NPV10 frame marker"
        }
        val timestampNs = readPositiveFrameTimestamp()
        val width = readInt("frame width")
        val height = readInt("frame height")
        val payloadSize = readInt("frame payload size")
        val expectedCrc = readInt("frame crc")
        require(width == header.width && height == header.height) {
            "NPV10 frame ${width}x$height does not match header ${header.width}x${header.height}"
        }
        val decodedPayloadSize = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(width, height, header.payloadFormat)
            .toInt()
        val expectedStoredSize = Npv10PacketCodecEngine
            .storedBytes(decodedPayloadSize.toLong(), header.packetCodec)
            .toInt()
        require(Npv10PacketCodecEngine.isValidStoredBytes(
            payloadSize.toLong(),
            decodedPayloadSize.toLong(),
            header.packetCodec
        )) {
            "NPV10 frame packet size $payloadSize is outside the valid ${header.packetCodec} " +
                "envelope ending at $expectedStoredSize bytes"
        }
        require(target.capacity() >= payloadSize) {
            "NPV10 stored-packet audit buffer is too small"
        }
        target.clear()
        target.limit(payloadSize)
        readBuffer(target, "frame packet")
        target.flip()
        require(crc32(target) == expectedCrc) { "NPV10 frame CRC mismatch" }
        return Npv10StoredFrameRecord(
            timestampNs = timestampNs,
            width = width,
            height = height,
            storedPayloadBytes = payloadSize,
            decodedPayloadBytes = decodedPayloadSize,
            expectedCrc = expectedCrc,
            payload = target.asReadOnlyBuffer()
        )
    }

    /**
     * Reads, CRC-validates, and decodes into reusable caller-owned buffers. No frame-sized heap array
     * is created, including for packed-10 masters.
     */
    internal fun readNextFrameDirect(
        storedPacketTarget: ByteBuffer,
        decodedTarget: ByteBuffer
    ): Npv10DirectFrameRecord? {
        val magic = readBytesOrNull(
            length = Npv10ContainerPolicy.FRAME_MAGIC_BYTES.size,
            context = "frame marker"
        ) ?: return null
        require(magic.contentEquals(Npv10ContainerPolicy.FRAME_MAGIC_BYTES)) {
            "Invalid NPV10 frame marker"
        }
        val timestampNs = readPositiveFrameTimestamp()
        val width = readInt("frame width")
        val height = readInt("frame height")
        val payloadSize = readInt("frame payload size")
        val expectedCrc = readInt("frame crc")
        require(width == header.width && height == header.height) {
            "NPV10 frame ${width}x$height does not match header ${header.width}x${header.height}"
        }
        val decodedPayloadSize = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(width, height, header.payloadFormat)
            .toInt()
        val expectedStoredSize = Npv10PacketCodecEngine
            .storedBytes(decodedPayloadSize.toLong(), header.packetCodec)
            .toInt()
        require(Npv10PacketCodecEngine.isValidStoredBytes(
            payloadSize.toLong(),
            decodedPayloadSize.toLong(),
            header.packetCodec
        )) {
            "NPV10 frame packet size $payloadSize is outside the valid ${header.packetCodec} " +
                "envelope ending at $expectedStoredSize bytes"
        }
        require(storedPacketTarget.capacity() >= payloadSize) {
            "NPV10 stored packet buffer is too small"
        }
        require(decodedTarget.capacity() >= decodedPayloadSize) {
            "NPV10 decoded frame buffer is too small"
        }
        storedPacketTarget.clear()
        storedPacketTarget.limit(payloadSize)
        readBuffer(storedPacketTarget, "frame packet")
        storedPacketTarget.flip()
        require(crc32(storedPacketTarget) == expectedCrc) { "NPV10 frame CRC mismatch" }
        Npv10PacketCodecEngine.decodeInto(
            encoded = storedPacketTarget,
            encodedBytes = payloadSize,
            decodedBytes = decodedPayloadSize,
            codec = header.packetCodec,
            target = decodedTarget
        )
        return Npv10DirectFrameRecord(
            timestampNs = timestampNs,
            width = width,
            height = height,
            payload = decodedTarget.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        )
    }

    override fun close() {
        channel.close()
    }

    private fun readNextFrameInternal(target: ByteBuffer?): Npv10FrameRecord? {
        val magic = readBytesOrNull(
            length = Npv10ContainerPolicy.FRAME_MAGIC_BYTES.size,
            context = "frame marker"
        ) ?: return null
        require(magic.contentEquals(Npv10ContainerPolicy.FRAME_MAGIC_BYTES)) {
            "Invalid NPV10 frame marker"
        }
        val timestampNs = readPositiveFrameTimestamp()
        val width = readInt("frame width")
        val height = readInt("frame height")
        val payloadSize = readInt("frame payload size")
        val expectedCrc = readInt("frame crc")
        require(width == header.width && height == header.height) {
            "NPV10 frame ${width}x${height} does not match header ${header.width}x${header.height}"
        }
        val decodedPayloadSize = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(width, height, header.payloadFormat)
            .toInt()
        val expectedStoredSize = Npv10PacketCodecEngine
            .storedBytes(decodedPayloadSize.toLong(), header.packetCodec)
            .toInt()
        require(Npv10PacketCodecEngine.isValidStoredBytes(
            payloadSize.toLong(),
            decodedPayloadSize.toLong(),
            header.packetCodec
        )) {
            "NPV10 frame packet size $payloadSize is outside the valid ${header.packetCodec} " +
                "envelope ending at $expectedStoredSize bytes"
        }
        val encodedPacket = readBytes(payloadSize, "frame packet")
        require(crc32(encodedPacket) == expectedCrc) { "NPV10 frame CRC mismatch" }
        val payload = Npv10PacketCodecEngine.decode(
            encoded = encodedPacket,
            decodedBytes = decodedPayloadSize,
            codec = header.packetCodec
        )
        target?.let { directTarget ->
            directTarget.clear()
            require(directTarget.remaining() >= payload.size) {
                "NPV10 target buffer is too small for frame payload"
            }
            directTarget.put(payload)
            directTarget.flip()
        }
        return Npv10FrameRecord(
            timestampNs = timestampNs,
            width = width,
            height = height,
            payload = payload
        )
    }

    private fun readPositiveFrameTimestamp(): Long {
        val timestampNs = readLong("frame timestamp")
        require(timestampNs > 0L) { "NPV10 frame timestamp must be positive: $timestampNs" }
        return timestampNs
    }

    private fun readHeader(): Npv10FileHeader {
        val magic = readBytes(Npv10ContainerPolicy.MAGIC_BYTES.size, "file header magic")
        require(magic.contentEquals(Npv10ContainerPolicy.MAGIC_BYTES)) {
            "Invalid NPV10 file header"
        }
        val version = readInt("container version")
        require(version in Npv10ContainerPolicy.LEGACY_VERSION..Npv10ContainerPolicy.VERSION) {
            "Unsupported NPV10 container version: $version"
        }
        val width = readInt("width")
        val height = readInt("height")
        val fps = readInt("fps")
        val bitDepth = readInt("bit depth")
        val metadataLength = readInt("metadata length")
        require(metadataLength >= 0) { "NPV10 metadata length is negative" }
        val metadataBytes = readBytes(metadataLength, "metadata")
        val metadata = String(metadataBytes, StandardCharsets.UTF_8)
        val values = parseMetadataValues(metadata)
        val payloadFormat = Npv10PayloadFormat.fromMetadata(values["payloadformat"] ?: values["format"])
        val sourceFormat = values["sourceformat"]
            ?: throw IllegalArgumentException("Missing NPV10 source format metadata")
        val sourceBitDepth = values["sourcebitdepth"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Missing NPV10 source bit depth metadata")
        val storageBitDepth = values["storagebitdepth"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Missing NPV10 storage bit depth metadata")
        val packetCodec = Npv10PacketCodec.fromMetadata(values["packetcodec"])
        val orientationHintDegrees = values["orientationhintdegrees"]
            ?.toIntOrNull()
            ?.let(::normalizeRightAngle)
            ?: 0
        return Npv10FileHeader(
            version = version,
            width = width,
            height = height,
            fps = fps,
            bitDepth = bitDepth,
            metadata = metadata,
            metadataValues = values,
            payloadFormat = payloadFormat,
            sourceFormat = sourceFormat,
            sourceBitDepth = sourceBitDepth,
            storageBitDepth = storageBitDepth,
            exactSelectedResolution = parseMetadataBoolean(values["exactselectedresolution"]),
            orientationHintDegrees = orientationHintDegrees,
            packetCodec = packetCodec
        )
    }

    private fun normalizeRightAngle(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        require(normalized % 90 == 0) { "Invalid NPV10 orientation: $degrees" }
        return normalized
    }

    private fun readInt(context: String): Int =
        ByteBuffer.wrap(readBytes(Int.SIZE_BYTES, context))
            .order(ByteOrder.BIG_ENDIAN)
            .int

    private fun readLong(context: String): Long =
        ByteBuffer.wrap(readBytes(Long.SIZE_BYTES, context))
            .order(ByteOrder.BIG_ENDIAN)
            .long

    private fun readBytes(length: Int, context: String): ByteArray =
        readBytesOrNull(length, context)
            ?: throw IllegalArgumentException("Truncated NPV10 $context")

    private fun readBytesOrNull(length: Int, context: String): ByteArray? {
        require(length >= 0) { "NPV10 $context length is negative" }
        val bytes = ByteArray(length)
        val buffer = ByteBuffer.wrap(bytes)
        var readAny = false
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer)
            if (read < 0) {
                if (!readAny && buffer.position() == 0) return null
                throw IllegalArgumentException("Truncated NPV10 $context")
            }
            if (read == 0) {
                Thread.yield()
            } else {
                readAny = true
            }
        }
        return bytes
    }

    private fun readBuffer(buffer: ByteBuffer, context: String) {
        var zeroReadRetries = 0
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer)
            if (read < 0) throw IllegalArgumentException("Truncated NPV10 $context")
            if (read == 0) {
                zeroReadRetries += 1
                require(zeroReadRetries <= MAX_ZERO_READ_RETRIES) { "NPV10 $context read stalled" }
                Thread.yield()
            } else {
                zeroReadRetries = 0
            }
        }
    }

    private fun parseMetadataValues(metadata: String): Map<String, String> =
        metadata
            .lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }
            }
            .toMap()

    private fun parseMetadataBoolean(value: String?): Boolean =
        value?.equals("true", ignoreCase = true) == true

    private fun crc32(payload: ByteArray): Int {
        val crc = CRC32()
        crc.update(payload)
        return crc.value.toInt()
    }

    private fun crc32(payload: ByteBuffer): Int {
        val crc = CRC32()
        crc.update(payload.duplicate())
        return crc.value.toInt()
    }

    private companion object {
        const val MAX_ZERO_READ_RETRIES = 8
        const val PLAYBACK_INDEX_INITIAL_CAPACITY = 1_024
    }
}
