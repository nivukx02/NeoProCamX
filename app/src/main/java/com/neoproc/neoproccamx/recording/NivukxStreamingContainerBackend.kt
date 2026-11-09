package com.neoproc.neoproccamx.recording

import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max

/** Recording backend that stores encoded access units without transcoding or pixel conversion. */
internal class NivukxStreamingContainerBackend : RecordingContainerBackend {
    private var writer: NivukxStreamingWriter? = null

    override fun open(outputPath: String, containerLabel: String) {
        check(writer == null) { "NIVUKX backend is already open" }
        writer = NivukxStreamingWriter(outputPath, containerLabel)
    }

    override fun addVideoTrack(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        codecConfig: ByteArray,
        colorConfig: QuickTimeColorAtomConfig
    ): Int = requiredWriter().addVideoTrack(
        mime = mime,
        width = width,
        height = height,
        fps = fps,
        codecConfig = codecConfig,
        colorConfig = colorConfig
    )

    override fun addAudioTrack(
        mime: String,
        sampleRate: Int,
        channelCount: Int,
        codecConfig: ByteArray
    ): Int = requiredWriter().addAudioTrack(
        mime = mime,
        sampleRate = sampleRate,
        channelCount = channelCount,
        codecConfig = codecConfig
    )

    override fun writeSample(
        trackIndex: Int,
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        flags: Int
    ) {
        requiredWriter().writeSample(trackIndex, buffer.slice(), presentationTimeUs, flags)
    }

    override fun setOrientationHint(degrees: Int) {
        requiredWriter().setOrientationHint(degrees)
    }

    override fun close() {
        val active = writer ?: return
        writer = null
        active.close()
    }

    override fun abort() {
        val active = writer ?: return
        writer = null
        active.abort()
    }

    private fun requiredWriter(): NivukxStreamingWriter =
        checkNotNull(writer) { "NIVUKX backend is not open" }
}

/**
 * Crash-scannable NIVUKX writer. It keeps only a bounded sparse keyframe index in memory; encoded
 * frame payloads stream directly from the muxer buffer to storage.
 */
internal class NivukxStreamingWriter(
    outputPath: String,
    containerLabel: String,
    private val createdAtEpochMs: Long = System.currentTimeMillis(),
    private val sessionId: UUID = UUID.randomUUID()
) : Closeable {
    private val randomAccessFile = RandomAccessFile(outputPath, "rw")
    private val channel: FileChannel = randomAccessFile.channel
    private val sessionDigest = MessageDigest.getInstance("SHA-256")
    private val scratch = ByteArray(256 * 1024)
    private val tracks = LinkedHashMap<Int, NivukxContainerFormat.TrackDescriptor>()
    private val indexEntries = ArrayList<NivukxContainerFormat.IndexEntry>()
    private var nextTrackIndex = 0
    private var chunkSequence = 0L
    private var sampleCount = 0L
    private var durationUs = 0L
    private var firstSampleOffset = 0L
    private var orientationDegrees = 0
    private var indexCompactionLevel = 0
    private var videoKeyframeOrdinal = 0L
    private var closed = false

    init {
        require(createdAtEpochMs >= 0L)
        randomAccessFile.setLength(0L)
        writeHeader(finalized = false, indexOffset = 0L, footerOffset = 0L, fileLength = 0L)
        writeContainerMetadata(containerLabel)
    }

    @Synchronized
    fun setOrientationHint(degrees: Int) {
        ensureOpen()
        require(degrees in 0..359 && degrees % 90 == 0) { "NIVUKX orientation must be 0/90/180/270" }
        orientationDegrees = degrees
        // Persist the hint immediately so an interrupted recording remains correctly oriented.
        val returnPosition = channel.position()
        writeHeader(finalized = false, indexOffset = 0L, footerOffset = 0L, fileLength = 0L)
        channel.position(returnPosition)
    }

    @Synchronized
    fun addVideoTrack(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        codecConfig: ByteArray,
        colorConfig: QuickTimeColorAtomConfig
    ): Int {
        val hdrStaticInfo = colorConfig.masteringDisplay?.let { masteringDisplay ->
            HdrStaticMetadataPolicy.buildHdr10StaticInfo(masteringDisplay).let { source ->
                ByteArray(source.remaining()).also { destination -> source.get(destination) }
            }
        } ?: ByteArray(0)
        return addTrack(
            NivukxContainerFormat.TrackDescriptor(
                index = nextTrackIndex,
                kind = NivukxContainerFormat.TrackKind.VIDEO,
                mime = mime,
                codecConfig = codecConfig.copyOf(),
                width = width,
                height = height,
                fps = fps,
                color = NivukxContainerFormat.ColorDescriptor(
                    colorPrimaries = colorConfig.colorPrimaries,
                    transferCharacteristics = colorConfig.transferCharacteristics,
                    matrixCoefficients = colorConfig.matrixCoefficients,
                    fullRange = colorConfig.fullRange,
                    hdrStaticInfo = hdrStaticInfo
                )
            )
        )
    }

    @Synchronized
    fun addAudioTrack(
        mime: String,
        sampleRate: Int,
        channelCount: Int,
        codecConfig: ByteArray
    ): Int = addTrack(
        NivukxContainerFormat.TrackDescriptor(
            index = nextTrackIndex,
            kind = NivukxContainerFormat.TrackKind.AUDIO,
            mime = mime,
            codecConfig = codecConfig.copyOf(),
            sampleRate = sampleRate,
            channelCount = channelCount
        )
    )

    @Synchronized
    fun addAuxiliaryTrack(
        kind: NivukxContainerFormat.TrackKind,
        mime: String,
        codecConfig: ByteArray = ByteArray(0)
    ): Int {
        require(kind == NivukxContainerFormat.TrackKind.DEPTH ||
            kind == NivukxContainerFormat.TrackKind.TELEMETRY
        ) { "Auxiliary NIVUKX tracks must be depth or telemetry" }
        return addTrack(
            NivukxContainerFormat.TrackDescriptor(
                index = nextTrackIndex,
                kind = kind,
                mime = mime,
                codecConfig = codecConfig.copyOf()
            )
        )
    }

    @Synchronized
    fun writeSample(
        trackIndex: Int,
        encodedSample: ByteBuffer,
        presentationTimeUs: Long,
        flags: Int
    ) {
        ensureOpen()
        val track = tracks[trackIndex] ?: error("Unknown NIVUKX track $trackIndex")
        val sample = encodedSample.slice()
        require(sample.hasRemaining()) { "NIVUKX sample must not be empty" }
        require(sample.remaining().toLong() <= NivukxContainerFormat.MAX_SAMPLE_DATA_BYTES) {
            "NIVUKX encoded sample exceeds the 1 GiB safety bound"
        }
        require(presentationTimeUs >= 0L) { "NIVUKX PTS must not be negative" }

        val sampleHeader = NivukxContainerFormat.encodeSampleHeader(
            trackIndex = trackIndex,
            flags = flags,
            presentationTimeUs = presentationTimeUs,
            dataSize = sample.remaining().toLong()
        )
        val chunkOffset = channel.position()
        if (firstSampleOffset == 0L) firstSampleOffset = chunkOffset
        writeChunk(
            type = NivukxContainerFormat.CHUNK_SAMPLE,
            payloads = listOf(ByteBuffer.wrap(sampleHeader), sample)
        )
        sampleCount += 1L
        durationUs = max(durationUs, presentationTimeUs)

        if (track.kind == NivukxContainerFormat.TrackKind.VIDEO && flags and KEY_FRAME_FLAG != 0) {
            recordKeyframeIndex(trackIndex, flags, presentationTimeUs, chunkOffset)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        try {
            val indexOffset = channel.position()
            val indexPayload = NivukxContainerFormat.encodeIndex(indexEntries, indexCompactionLevel)
            writeChunk(NivukxContainerFormat.CHUNK_INDEX, listOf(ByteBuffer.wrap(indexPayload)))

            val footerOffset = channel.position()
            val digestBeforeFooter = sessionDigest.digest()
            val footerPayload = NivukxContainerFormat.encodeFooter(
                NivukxContainerFormat.Footer(
                    sessionId = sessionId,
                    sampleCount = sampleCount,
                    durationUs = durationUs,
                    trackCount = tracks.size,
                    sessionDigest = digestBeforeFooter
                )
            )
            writeChunk(
                type = NivukxContainerFormat.CHUNK_FOOTER,
                payloads = listOf(ByteBuffer.wrap(footerPayload)),
                includeInSessionDigest = false
            )
            val finalLength = channel.position()
            randomAccessFile.setLength(finalLength)
            writeHeader(
                finalized = true,
                indexOffset = indexOffset,
                footerOffset = footerOffset,
                fileLength = finalLength
            )
            channel.force(true)
        } catch (error: Throwable) {
            failure = error
        } finally {
            runCatching { channel.close() }.onFailure { if (failure == null) failure = it }
            runCatching { randomAccessFile.close() }.onFailure { if (failure == null) failure = it }
        }
        failure?.let { throw it }
    }

    @Synchronized
    fun abort() {
        if (closed) return
        closed = true
        runCatching { channel.close() }
        runCatching { randomAccessFile.close() }
    }

    private fun addTrack(track: NivukxContainerFormat.TrackDescriptor): Int {
        ensureOpen()
        check(sampleCount == 0L) { "NIVUKX tracks must be declared before samples" }
        check(tracks.size < NivukxContainerFormat.MAX_TRACKS) { "NIVUKX track limit reached" }
        check(track.index == nextTrackIndex) { "NIVUKX track indices must be contiguous" }
        val payload = NivukxContainerFormat.encodeTrack(track)
        writeChunk(NivukxContainerFormat.CHUNK_TRACK, listOf(ByteBuffer.wrap(payload)))
        tracks[track.index] = track
        nextTrackIndex += 1
        return track.index
    }

    private fun recordKeyframeIndex(
        trackIndex: Int,
        flags: Int,
        presentationTimeUs: Long,
        chunkOffset: Long
    ) {
        val interval = 1L shl indexCompactionLevel.coerceAtMost(30)
        val ordinal = videoKeyframeOrdinal++
        if (ordinal % interval == 0L) {
            indexEntries += NivukxContainerFormat.IndexEntry(
                trackIndex = trackIndex,
                flags = flags,
                presentationTimeUs = presentationTimeUs,
                chunkOffset = chunkOffset
            )
        }
        if (indexEntries.size >= NivukxContainerFormat.MAX_INDEX_ENTRIES) {
            var writeIndex = 0
            for (readIndex in indexEntries.indices step 2) {
                indexEntries[writeIndex++] = indexEntries[readIndex]
            }
            while (indexEntries.size > writeIndex) indexEntries.removeAt(indexEntries.lastIndex)
            indexCompactionLevel += 1
        }
    }

    private fun writeContainerMetadata(containerLabel: String) {
        val label = containerLabel.trim().take(4_096).toByteArray(Charsets.UTF_8)
        val writerName = "NeoProCamX NIVUKX v5".toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(4 + 4 + 8 + 4 + label.size + writerName.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putInt(1)
                putInt(label.size)
                putLong(createdAtEpochMs)
                putInt(writerName.size)
                put(label)
                put(writerName)
            }
            .array()
        writeChunk(NivukxContainerFormat.CHUNK_METADATA, listOf(ByteBuffer.wrap(payload)))
    }

    private fun writeHeader(
        finalized: Boolean,
        indexOffset: Long,
        footerOffset: Long,
        fileLength: Long
    ) {
        val bytes = NivukxContainerFormat.encodeHeader(
            NivukxContainerFormat.Header(
                sessionId = sessionId,
                createdAtEpochMs = createdAtEpochMs,
                orientationDegrees = orientationDegrees,
                trackCount = tracks.size,
                sampleCount = sampleCount,
                durationUs = durationUs,
                firstSampleOffset = firstSampleOffset,
                indexOffset = indexOffset,
                footerOffset = footerOffset,
                fileLength = fileLength,
                finalized = finalized
            )
        )
        val returnPosition = channel.position()
        channel.position(0L)
        writeFully(ByteBuffer.wrap(bytes))
        channel.position(max(returnPosition, NivukxContainerFormat.FIRST_CHUNK_OFFSET))
    }

    private fun writeChunk(
        type: Int,
        payloads: List<ByteBuffer>,
        includeInSessionDigest: Boolean = true
    ): Long {
        val payloadLength = payloads.fold(0L) { total, payload ->
            Math.addExact(total, payload.remaining().toLong())
        }
        val payloadCrc = NivukxCrc32c.newAccumulator()
        val payloadDigest = MessageDigest.getInstance("SHA-256")
        payloads.forEach { source ->
            val duplicate = source.duplicate()
            while (duplicate.hasRemaining()) {
                val count = minOf(duplicate.remaining(), scratch.size)
                duplicate.get(scratch, 0, count)
                payloadCrc.update(scratch, 0, count)
                payloadDigest.update(scratch, 0, count)
            }
        }
        val chunkHeader = NivukxContainerFormat.encodeChunkHeader(
            type = type,
            sequence = chunkSequence++,
            payloadLength = payloadLength,
            payloadCrc32c = payloadCrc.value()
        )
        val chunkOffset = channel.position()
        writeFully(ByteBuffer.wrap(chunkHeader))
        payloads.forEach { payload -> writeFully(payload.duplicate()) }
        if (includeInSessionDigest) {
            NivukxContainerFormat.updateSessionDigest(sessionDigest, chunkHeader, payloadDigest.digest())
        }
        return chunkOffset
    }

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer)
            if (written <= 0) error("NIVUKX storage write made no progress")
        }
    }

    private fun ensureOpen() {
        check(!closed) { "NIVUKX writer is closed" }
    }

    private companion object {
        // android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME; kept numeric so the file writer remains a
        // host-testable streaming component with no codec lifecycle dependency.
        const val KEY_FRAME_FLAG = 1
    }
}
