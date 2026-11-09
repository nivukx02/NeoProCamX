package com.neoproc.neoproccamx.recording

import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.io.IOException

/** Media3 extractor that streams NIVUKX encoded samples directly into Android's hardware decoders. */
@AndroidxOptIn(markerClass = [UnstableApi::class])
internal class NivukxMedia3Extractor : Extractor {
    private enum class State {
        HEADER,
        DISCOVER_TRACKS,
        LOAD_INDEX,
        READ_SAMPLES,
        FINISHED
    }

    private lateinit var extractorOutput: ExtractorOutput
    private var state = State.HEADER
    private var header: NivukxContainerFormat.Header? = null
    private val tracks = LinkedHashMap<Int, NivukxContainerFormat.TrackDescriptor>()
    private val trackOutputs = HashMap<Int, TrackOutput>()
    private var firstSampleOffset = 0L
    private var tracksEnded = false
    private var seekMapPublished = false
    private val scratch = ByteArray(256 * 1024)

    override fun sniff(input: ExtractorInput): Boolean {
        val magic = ByteArray(8)
        return try {
            val available = input.peekFully(magic, 0, magic.size, true)
            input.resetPeekPosition()
            available && NivukxContainerFormat.hasMagic(magic)
        } catch (_: IOException) {
            input.resetPeekPosition()
            false
        }
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        return when (state) {
            State.HEADER -> readHeader(input, seekPosition)
            State.DISCOVER_TRACKS -> discoverTracks(input, seekPosition)
            State.LOAD_INDEX -> loadIndex(input, seekPosition)
            State.READ_SAMPLES -> readNextSample(input)
            State.FINISHED -> Extractor.RESULT_END_OF_INPUT
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        state = if (position == 0L && header == null) State.HEADER else State.READ_SAMPLES
    }

    override fun release() = Unit

    private fun readHeader(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (input.position != 0L) {
            seekPosition.position = 0L
            return Extractor.RESULT_SEEK
        }
        val bytes = ByteArray(NivukxContainerFormat.HEADER_SIZE)
        input.readFully(bytes, 0, bytes.size)
        header = NivukxContainerFormat.decodeHeader(bytes)
        state = State.DISCOVER_TRACKS
        return Extractor.RESULT_CONTINUE
    }

    private fun discoverTracks(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val chunkOffset = input.position
        val chunk = readChunkHeader(input) ?: return finishAtEndOfInput()
        return when (chunk.type) {
            NivukxContainerFormat.CHUNK_METADATA -> {
                readAndValidateSmallPayload(input, chunk)
                Extractor.RESULT_CONTINUE
            }
            NivukxContainerFormat.CHUNK_TRACK -> {
                val descriptor = NivukxContainerFormat.decodeTrack(readAndValidateSmallPayload(input, chunk))
                if (descriptor.index != tracks.size || tracks.put(descriptor.index, descriptor) != null) {
                    throw IOException("NIVUKX track declarations are not contiguous")
                }
                publishTrack(descriptor)
                Extractor.RESULT_CONTINUE
            }
            NivukxContainerFormat.CHUNK_SAMPLE -> {
                firstSampleOffset = chunkOffset
                val streamHeader = requireNotNull(header)
                if (streamHeader.indexOffset > chunkOffset) {
                    state = State.LOAD_INDEX
                    seekPosition.position = streamHeader.indexOffset
                    Extractor.RESULT_SEEK
                } else {
                    finishTrackDiscovery(emptyList())
                    state = State.READ_SAMPLES
                    readSamplePayload(input, chunk, chunkOffset)
                    Extractor.RESULT_CONTINUE
                }
            }
            NivukxContainerFormat.CHUNK_INDEX -> {
                val index = NivukxContainerFormat.decodeIndex(readAndValidateSmallPayload(input, chunk))
                finishTrackDiscovery(index)
                state = State.READ_SAMPLES
                Extractor.RESULT_CONTINUE
            }
            NivukxContainerFormat.CHUNK_FOOTER -> {
                readAndValidateSmallPayload(input, chunk)
                finishTrackDiscovery(emptyList())
                state = State.FINISHED
                Extractor.RESULT_END_OF_INPUT
            }
            else -> throw IOException("Unsupported NIVUKX discovery chunk ${NivukxContainerFormat.fourCc(chunk.type)}")
        }
    }

    private fun loadIndex(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val indexOffset = requireNotNull(header).indexOffset
        if (input.position != indexOffset) {
            seekPosition.position = indexOffset
            return Extractor.RESULT_SEEK
        }
        val chunk = readChunkHeader(input) ?: throw EOFException("Missing NIVUKX keyframe index")
        if (chunk.type != NivukxContainerFormat.CHUNK_INDEX) {
            throw IOException("NIVUKX header index offset does not point to INDX")
        }
        val entries = NivukxContainerFormat.decodeIndex(readAndValidateSmallPayload(input, chunk))
        finishTrackDiscovery(entries)
        state = State.READ_SAMPLES
        seekPosition.position = firstSampleOffset
        return Extractor.RESULT_SEEK
    }

    private fun readNextSample(input: ExtractorInput): Int {
        val chunkOffset = input.position
        val chunk = readChunkHeader(input) ?: return finishAtEndOfInput()
        when (chunk.type) {
            NivukxContainerFormat.CHUNK_SAMPLE -> readSamplePayload(input, chunk, chunkOffset)
            NivukxContainerFormat.CHUNK_INDEX,
            NivukxContainerFormat.CHUNK_METADATA,
            NivukxContainerFormat.CHUNK_TRACK -> readAndValidateSmallPayload(input, chunk)
            NivukxContainerFormat.CHUNK_FOOTER -> {
                readAndValidateSmallPayload(input, chunk)
                state = State.FINISHED
                return Extractor.RESULT_END_OF_INPUT
            }
            else -> throw IOException("Unsupported NIVUKX playback chunk ${NivukxContainerFormat.fourCc(chunk.type)}")
        }
        return Extractor.RESULT_CONTINUE
    }

    private fun readSamplePayload(
        input: ExtractorInput,
        chunk: NivukxContainerFormat.ChunkHeader,
        chunkOffset: Long
    ) {
        if (chunk.payloadLength <= NivukxContainerFormat.SAMPLE_HEADER_SIZE) {
            throw IOException("NIVUKX sample payload is too small")
        }
        val crc = NivukxCrc32c.newAccumulator()
        val sampleHeader = ByteArray(NivukxContainerFormat.SAMPLE_HEADER_SIZE)
        input.readFully(sampleHeader, 0, sampleHeader.size)
        crc.update(sampleHeader, 0, sampleHeader.size)
        val info = NivukxContainerFormat.decodeSampleHeader(
            bytes = sampleHeader,
            chunkOffset = chunkOffset,
            dataOffset = input.position
        )
        if (info.dataSize != chunk.payloadLength - NivukxContainerFormat.SAMPLE_HEADER_SIZE) {
            throw IOException("NIVUKX sample length mismatch")
        }

        val output = trackOutputs[info.trackIndex]
        var remaining = info.dataSize
        while (remaining > 0L) {
            val count = minOf(remaining, scratch.size.toLong()).toInt()
            input.readFully(scratch, 0, count)
            crc.update(scratch, 0, count)
            if (output != null) {
                output.sampleData(ParsableByteArray(scratch, count), count)
            }
            remaining -= count.toLong()
        }
        if (crc.value() != chunk.payloadCrc32c) throw IOException("NIVUKX sample CRC32C mismatch")
        output?.sampleMetadata(
            info.presentationTimeUs,
            if (info.flags and MEDIA_CODEC_KEY_FRAME_FLAG != 0) C.BUFFER_FLAG_KEY_FRAME else 0,
            info.dataSize.toInt(),
            0,
            null
        )
    }

    private fun publishTrack(track: NivukxContainerFormat.TrackDescriptor) {
        val trackType = when (track.kind) {
            NivukxContainerFormat.TrackKind.VIDEO -> C.TRACK_TYPE_VIDEO
            NivukxContainerFormat.TrackKind.AUDIO -> C.TRACK_TYPE_AUDIO
            NivukxContainerFormat.TrackKind.DEPTH,
            NivukxContainerFormat.TrackKind.TELEMETRY -> C.TRACK_TYPE_METADATA
        }
        val output = extractorOutput.track(track.index, trackType)
        val format = Format.Builder()
            .setId(track.index)
            .setContainerMimeType(NivukxContainerFormat.MIME_TYPE)
            .setSampleMimeType(track.mime)
            .setInitializationData(
                if (track.codecConfig.isEmpty()) emptyList() else listOf(track.codecConfig.copyOf())
            )
            .apply {
                when (track.kind) {
                    NivukxContainerFormat.TrackKind.VIDEO -> {
                        setWidth(track.width)
                        setHeight(track.height)
                        setFrameRate(track.fps.toFloat())
                        setRotationDegrees(header?.orientationDegrees ?: 0)
                        track.color?.let { setColorInfo(it.toMedia3ColorInfo()) }
                    }
                    NivukxContainerFormat.TrackKind.AUDIO -> {
                        setSampleRate(track.sampleRate)
                        setChannelCount(track.channelCount)
                    }
                    NivukxContainerFormat.TrackKind.DEPTH,
                    NivukxContainerFormat.TrackKind.TELEMETRY -> Unit
                }
            }
            .build()
        output.format(format)
        trackOutputs[track.index] = output
    }

    private fun finishTrackDiscovery(indexEntries: List<NivukxContainerFormat.IndexEntry>) {
        if (!tracksEnded) {
            extractorOutput.endTracks()
            tracksEnded = true
        }
        if (!seekMapPublished) {
            val durationUs = header?.durationUs ?: C.TIME_UNSET
            val seekMap = if (indexEntries.isEmpty()) {
                SeekMap.Unseekable(durationUs, firstSampleOffset)
            } else {
                NivukxSeekMap(indexEntries.sortedBy { it.presentationTimeUs }, durationUs)
            }
            extractorOutput.seekMap(seekMap)
            seekMapPublished = true
        }
    }

    private fun finishAtEndOfInput(): Int {
        finishTrackDiscovery(emptyList())
        state = State.FINISHED
        return Extractor.RESULT_END_OF_INPUT
    }

    private fun readChunkHeader(input: ExtractorInput): NivukxContainerFormat.ChunkHeader? {
        val bytes = ByteArray(NivukxContainerFormat.CHUNK_HEADER_SIZE)
        if (!input.readFully(bytes, 0, bytes.size, true)) return null
        return NivukxContainerFormat.decodeChunkHeader(bytes)
    }

    private fun readAndValidateSmallPayload(
        input: ExtractorInput,
        chunk: NivukxContainerFormat.ChunkHeader
    ): ByteArray {
        if (chunk.payloadLength > Int.MAX_VALUE) throw IOException("NIVUKX metadata payload is too large")
        val bytes = ByteArray(chunk.payloadLength.toInt())
        input.readFully(bytes, 0, bytes.size)
        if (NivukxCrc32c.compute(bytes) != chunk.payloadCrc32c) {
            throw IOException("NIVUKX ${NivukxContainerFormat.fourCc(chunk.type)} CRC32C mismatch")
        }
        return bytes
    }

    private fun NivukxContainerFormat.ColorDescriptor.toMedia3ColorInfo(): ColorInfo {
        val colorSpace = when (colorPrimaries) {
            QuickTimeColorAtomPolicy.PRIMARIES_BT2020 -> C.COLOR_SPACE_BT2020
            else -> C.COLOR_SPACE_BT709
        }
        val transfer = when (transferCharacteristics) {
            QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084 -> C.COLOR_TRANSFER_ST2084
            QuickTimeColorAtomPolicy.TRANSFER_HLG -> C.COLOR_TRANSFER_HLG
            QuickTimeColorAtomPolicy.TRANSFER_SRGB -> C.COLOR_TRANSFER_SRGB
            else -> C.COLOR_TRANSFER_SDR
        }
        return ColorInfo.Builder()
            .setColorSpace(colorSpace)
            .setColorTransfer(transfer)
            .setColorRange(if (fullRange) C.COLOR_RANGE_FULL else C.COLOR_RANGE_LIMITED)
            .apply { if (hdrStaticInfo.isNotEmpty()) setHdrStaticInfo(hdrStaticInfo.copyOf()) }
            .build()
    }

    private class NivukxSeekMap(
        private val entries: List<NivukxContainerFormat.IndexEntry>,
        private val durationUs: Long
    ) : SeekMap {
        override fun isSeekable(): Boolean = entries.isNotEmpty()

        override fun getDurationUs(): Long = durationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            var low = 0
            var high = entries.lastIndex
            while (low <= high) {
                val middle = (low + high) ushr 1
                if (entries[middle].presentationTimeUs <= timeUs) low = middle + 1 else high = middle - 1
            }
            val floorIndex = high.coerceIn(0, entries.lastIndex)
            val floor = entries[floorIndex].toSeekPoint()
            val nextIndex = (floorIndex + 1).coerceAtMost(entries.lastIndex)
            val next = entries[nextIndex].toSeekPoint()
            return if (floor == next) SeekMap.SeekPoints(floor) else SeekMap.SeekPoints(floor, next)
        }

        private fun NivukxContainerFormat.IndexEntry.toSeekPoint(): SeekPoint =
            SeekPoint(presentationTimeUs, chunkOffset)
    }

    private companion object {
        const val MEDIA_CODEC_KEY_FRAME_FLAG = 1
    }
}

@AndroidxOptIn(markerClass = [UnstableApi::class])
internal object NivukxExtractorsFactory : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = arrayOf(NivukxMedia3Extractor())
}
