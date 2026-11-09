package com.neoproc.neoproccamx.recording

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal data class NivukxRecoveredSample(
    val info: NivukxContainerFormat.SampleInfo,
    /** Present only when explicitly requested for export/tests; normal inspection remains streaming. */
    val encodedPayload: ByteArray?
)

internal data class NivukxInspection(
    val header: NivukxContainerFormat.Header,
    val tracks: List<NivukxContainerFormat.TrackDescriptor>,
    val samples: List<NivukxRecoveredSample>,
    val recoveredSampleCount: Long,
    val indexEntries: List<NivukxContainerFormat.IndexEntry>,
    val lastValidOffset: Long,
    val footerPresent: Boolean,
    val integrityVerified: Boolean,
    val recoveryRequired: Boolean,
    val diagnostic: String?
)

internal data class NivukxStreamProgress(
    val completedSamples: Long,
    val consumedBytes: Long,
    val totalBytes: Long,
    val presentationTimeUs: Long
)

/** Strict streaming validator and crash-recovery scanner for NIVUKX v5 files. */
internal class NivukxStreamingReader(private val file: File) {
    fun inspect(
        recoverTruncatedTail: Boolean = false,
        collectSamplePayloads: Boolean = false
    ): NivukxInspection = scan(
        recoverTruncatedTail = recoverTruncatedTail,
        collectSamplePayloads = collectSamplePayloads
    )

    /**
     * Validate and deliver encoded samples in one sequential pass.
     *
     * The payload buffer is reused after [onSample] returns and must not be retained. This keeps the
     * working set bounded by the largest encoded access unit instead of the complete recording.
     */
    fun streamSamples(
        shouldContinue: () -> Boolean = { true },
        onTracksReady: (
            header: NivukxContainerFormat.Header,
            tracks: List<NivukxContainerFormat.TrackDescriptor>
        ) -> Unit,
        onSample: (
            info: NivukxContainerFormat.SampleInfo,
            encodedPayload: ByteBuffer
        ) -> Unit,
        onProgress: (NivukxStreamProgress) -> Unit = {}
    ): NivukxInspection = scan(
        recoverTruncatedTail = false,
        collectSamplePayloads = false,
        shouldContinue = shouldContinue,
        onTracksReady = onTracksReady,
        onSample = onSample,
        onProgress = onProgress
    )

    private fun scan(
        recoverTruncatedTail: Boolean,
        collectSamplePayloads: Boolean,
        shouldContinue: () -> Boolean = { true },
        onTracksReady: ((
            header: NivukxContainerFormat.Header,
            tracks: List<NivukxContainerFormat.TrackDescriptor>
        ) -> Unit)? = null,
        onSample: ((
            info: NivukxContainerFormat.SampleInfo,
            encodedPayload: ByteBuffer
        ) -> Unit)? = null,
        onProgress: (NivukxStreamProgress) -> Unit = {}
    ): NivukxInspection {
        require(!(collectSamplePayloads && onSample != null)) {
            "NIVUKX payload collection and streaming consumption are mutually exclusive"
        }
        RandomAccessFile(file, "r").use { input ->
            val fileLength = input.length()
            if (fileLength < NivukxContainerFormat.HEADER_SIZE) {
                throw EOFException("Truncated NIVUKX global header")
            }
            val headerBytes = ByteArray(NivukxContainerFormat.HEADER_SIZE)
            input.readFully(headerBytes)
            val header = NivukxContainerFormat.decodeHeader(headerBytes)
            val tracks = LinkedHashMap<Int, NivukxContainerFormat.TrackDescriptor>()
            val samples = ArrayList<NivukxRecoveredSample>()
            var indexEntries: List<NivukxContainerFormat.IndexEntry> = emptyList()
            val sessionDigest = MessageDigest.getInstance("SHA-256")
            val scratch = ByteArray(256 * 1024)
            var expectedSequence = 0L
            var lastValidOffset = NivukxContainerFormat.FIRST_CHUNK_OFFSET
            var footer: NivukxContainerFormat.Footer? = null
            var integrityVerified = false
            var collectedSampleBytes = 0L
            var recoveredSampleCount = 0L
            var recoveryDiagnostic: String? = null
            var reusableSampleBytes = ByteArray(0)
            var tracksDelivered = false
            var sampleChunksStarted = false
            var lastPresentationTimeUs = 0L

            input.seek(NivukxContainerFormat.FIRST_CHUNK_OFFSET)
            while (input.filePointer < fileLength) {
                if (!shouldContinue()) throw CancellationException("NIVUKX packet-copy export cancelled")
                val chunkOffset = input.filePointer
                val remaining = fileLength - chunkOffset
                if (remaining < NivukxContainerFormat.CHUNK_HEADER_SIZE) {
                    recoveryDiagnostic = "truncated chunk header at $chunkOffset"
                    if (!recoverTruncatedTail) throw EOFException(recoveryDiagnostic)
                    break
                }

                try {
                    val chunkHeaderBytes = ByteArray(NivukxContainerFormat.CHUNK_HEADER_SIZE)
                    input.readFully(chunkHeaderBytes)
                    val chunk = NivukxContainerFormat.decodeChunkHeader(chunkHeaderBytes)
                    if (chunk.sequence != expectedSequence) {
                        throw IOException(
                            "NIVUKX chunk sequence discontinuity: expected=$expectedSequence actual=${chunk.sequence}"
                        )
                    }
                    expectedSequence += 1L
                    val payloadEnd = Math.addExact(input.filePointer, chunk.payloadLength)
                    if (payloadEnd > fileLength) throw EOFException("Truncated NIVUKX ${NivukxContainerFormat.fourCc(chunk.type)} payload")

                    when (chunk.type) {
                        NivukxContainerFormat.CHUNK_SAMPLE -> {
                            sampleChunksStarted = true
                            if (!tracksDelivered && onTracksReady != null) {
                                if (tracks.isEmpty()) throw IOException("NIVUKX samples precede track declarations")
                                onTracksReady(header, tracks.values.toList())
                                tracksDelivered = true
                            }
                            val recovered = readSample(
                                input = input,
                                chunk = chunk,
                                chunkOffset = chunkOffset,
                                scratch = scratch,
                                collectPayload = collectSamplePayloads,
                                collectedBytesBefore = collectedSampleBytes,
                                reusableStreamBytes = reusableSampleBytes,
                                streamConsumer = onSample
                            )
                            reusableSampleBytes = recovered.reusableStreamBytes
                            lastPresentationTimeUs = recovered.sample.info.presentationTimeUs
                            if (!tracks.containsKey(recovered.sample.info.trackIndex)) {
                                throw IOException("NIVUKX sample references an undeclared track")
                            }
                            collectedSampleBytes = Math.addExact(
                                collectedSampleBytes,
                                recovered.sample.encodedPayload?.size?.toLong() ?: 0L
                            )
                            recoveredSampleCount += 1L
                            if (collectSamplePayloads) samples += recovered.sample
                            NivukxContainerFormat.updateSessionDigest(
                                sessionDigest,
                                chunk.encodedBytes,
                                recovered.payloadSha256
                            )
                        }
                        NivukxContainerFormat.CHUNK_FOOTER -> {
                            if (footer != null) throw IOException("Duplicate NIVUKX footer")
                            val payload = readSmallPayload(input, chunk, scratch)
                            val decodedFooter = NivukxContainerFormat.decodeFooter(payload.bytes)
                            val computedSessionDigest = sessionDigest.digest()
                            if (!decodedFooter.sessionDigest.contentEquals(computedSessionDigest)) {
                                throw IOException("NIVUKX SHA-256 session integrity mismatch")
                            }
                            if (decodedFooter.sessionId != header.sessionId) {
                                throw IOException("NIVUKX footer session identity mismatch")
                            }
                            footer = decodedFooter
                            integrityVerified = true
                        }
                        else -> {
                            if (footer != null) throw IOException("NIVUKX data appears after the final footer")
                            val payload = readSmallPayload(input, chunk, scratch)
                            when (chunk.type) {
                                NivukxContainerFormat.CHUNK_METADATA -> Unit
                                NivukxContainerFormat.CHUNK_TRACK -> {
                                    if (sampleChunksStarted) {
                                        throw IOException("NIVUKX track declaration appears after media samples")
                                    }
                                    val track = NivukxContainerFormat.decodeTrack(payload.bytes)
                                    if (track.index != tracks.size || tracks.put(track.index, track) != null) {
                                        throw IOException("NIVUKX track declarations must be unique and contiguous")
                                    }
                                }
                                NivukxContainerFormat.CHUNK_INDEX -> {
                                    indexEntries = NivukxContainerFormat.decodeIndex(payload.bytes)
                                }
                                else -> throw IOException(
                                    "Unsupported NIVUKX chunk ${NivukxContainerFormat.fourCc(chunk.type)}"
                                )
                            }
                            NivukxContainerFormat.updateSessionDigest(
                                sessionDigest,
                                chunk.encodedBytes,
                                payload.payloadSha256
                            )
                        }
                    }
                    if (input.filePointer != payloadEnd) throw IOException("NIVUKX chunk reader lost alignment")
                    lastValidOffset = payloadEnd
                    onProgress(
                        NivukxStreamProgress(
                            completedSamples = recoveredSampleCount,
                            consumedBytes = lastValidOffset,
                            totalBytes = fileLength,
                            presentationTimeUs = lastPresentationTimeUs
                        )
                    )
                } catch (error: Exception) {
                    recoveryDiagnostic = "${error.message ?: error.javaClass.simpleName} at chunk offset $chunkOffset"
                    if (!recoverTruncatedTail) throw error
                    input.seek(chunkOffset)
                    break
                }
            }

            val decodedFooter = footer
            if (!recoverTruncatedTail) {
                if (!header.finalized || decodedFooter == null || !integrityVerified) {
                    throw IOException("NIVUKX recording is not finalized")
                }
                if (header.fileLength != fileLength || lastValidOffset != fileLength) {
                    throw IOException("NIVUKX finalized length mismatch")
                }
                if (header.trackCount != tracks.size || decodedFooter.trackCount != tracks.size) {
                    throw IOException("NIVUKX track-count integrity mismatch")
                }
                if (header.sampleCount != recoveredSampleCount ||
                    decodedFooter.sampleCount != recoveredSampleCount
                ) {
                    throw IOException("NIVUKX sample-count integrity mismatch")
                }
                if (header.durationUs != decodedFooter.durationUs) {
                    throw IOException("NIVUKX duration integrity mismatch")
                }
            }

            val recoveryRequired = !header.finalized || !integrityVerified || lastValidOffset != fileLength
            return NivukxInspection(
                header = header,
                tracks = tracks.values.toList(),
                samples = samples,
                recoveredSampleCount = recoveredSampleCount,
                indexEntries = indexEntries,
                lastValidOffset = lastValidOffset,
                footerPresent = decodedFooter != null,
                integrityVerified = integrityVerified,
                recoveryRequired = recoveryRequired,
                diagnostic = recoveryDiagnostic
            )
        }
    }

    private fun readSample(
        input: RandomAccessFile,
        chunk: NivukxContainerFormat.ChunkHeader,
        chunkOffset: Long,
        scratch: ByteArray,
        collectPayload: Boolean,
        collectedBytesBefore: Long,
        reusableStreamBytes: ByteArray,
        streamConsumer: ((NivukxContainerFormat.SampleInfo, ByteBuffer) -> Unit)?
    ): SampleReadResult {
        if (chunk.payloadLength <= NivukxContainerFormat.SAMPLE_HEADER_SIZE) {
            throw IOException("NIVUKX sample chunk is too small")
        }
        val crc = NivukxCrc32c.newAccumulator()
        val sha = MessageDigest.getInstance("SHA-256")
        val sampleHeaderBytes = ByteArray(NivukxContainerFormat.SAMPLE_HEADER_SIZE)
        input.readFully(sampleHeaderBytes)
        crc.update(sampleHeaderBytes, 0, sampleHeaderBytes.size)
        sha.update(sampleHeaderBytes)
        val dataOffset = input.filePointer
        val info = NivukxContainerFormat.decodeSampleHeader(sampleHeaderBytes, chunkOffset, dataOffset)
        if (info.dataSize != chunk.payloadLength - NivukxContainerFormat.SAMPLE_HEADER_SIZE) {
            throw IOException("NIVUKX sample length does not match its chunk")
        }
        if (collectPayload && Math.addExact(collectedBytesBefore, info.dataSize) > MAX_COLLECTED_SAMPLE_BYTES) {
            throw IOException("Explicit NIVUKX sample collection exceeds the 64 MiB audit bound")
        }
        val sampleSize = info.dataSize.toInt()
        val payload = if (collectPayload) ByteArray(sampleSize) else null
        val streamBytes = if (streamConsumer != null) {
            ensureStreamCapacity(reusableStreamBytes, sampleSize)
        } else {
            reusableStreamBytes
        }

        when {
            payload != null -> {
                input.readFully(payload)
                crc.update(payload, 0, payload.size)
                sha.update(payload)
            }
            streamConsumer != null -> {
                input.readFully(streamBytes, 0, sampleSize)
                crc.update(streamBytes, 0, sampleSize)
                sha.update(streamBytes, 0, sampleSize)
            }
            else -> {
                var remaining = info.dataSize
                while (remaining > 0L) {
                    val count = minOf(remaining, scratch.size.toLong()).toInt()
                    input.readFully(scratch, 0, count)
                    crc.update(scratch, 0, count)
                    sha.update(scratch, 0, count)
                    remaining -= count.toLong()
                }
            }
        }
        if (crc.value() != chunk.payloadCrc32c) throw IOException("NIVUKX sample CRC32C mismatch")
        if (streamConsumer != null) {
            val view = ByteBuffer.wrap(streamBytes, 0, sampleSize).slice().asReadOnlyBuffer()
            streamConsumer(info, view)
        }
        return SampleReadResult(
            sample = NivukxRecoveredSample(info = info, encodedPayload = payload),
            payloadSha256 = sha.digest(),
            reusableStreamBytes = streamBytes
        )
    }

    private fun readSmallPayload(
        input: RandomAccessFile,
        chunk: NivukxContainerFormat.ChunkHeader,
        scratch: ByteArray
    ): SmallPayloadReadResult {
        if (chunk.payloadLength > Int.MAX_VALUE) throw IOException("NIVUKX metadata payload is too large")
        val bytes = ByteArray(chunk.payloadLength.toInt())
        val crc = NivukxCrc32c.newAccumulator()
        val sha = MessageDigest.getInstance("SHA-256")
        var copied = 0
        while (copied < bytes.size) {
            val count = minOf(bytes.size - copied, scratch.size)
            input.readFully(scratch, 0, count)
            crc.update(scratch, 0, count)
            sha.update(scratch, 0, count)
            scratch.copyInto(bytes, destinationOffset = copied, startIndex = 0, endIndex = count)
            copied += count
        }
        if (crc.value() != chunk.payloadCrc32c) {
            throw IOException("NIVUKX ${NivukxContainerFormat.fourCc(chunk.type)} CRC32C mismatch")
        }
        return SmallPayloadReadResult(bytes = bytes, payloadSha256 = sha.digest())
    }

    private data class SampleReadResult(
        val sample: NivukxRecoveredSample,
        val payloadSha256: ByteArray,
        val reusableStreamBytes: ByteArray
    )

    private data class SmallPayloadReadResult(
        val bytes: ByteArray,
        val payloadSha256: ByteArray
    )

    private companion object {
        const val MAX_COLLECTED_SAMPLE_BYTES = 64L * 1024L * 1024L
        const val INITIAL_STREAM_BUFFER_BYTES = 1024 * 1024

        fun ensureStreamCapacity(current: ByteArray, required: Int): ByteArray {
            if (current.size >= required) return current
            var capacity = maxOf(INITIAL_STREAM_BUFFER_BYTES, current.size.coerceAtLeast(1))
            while (capacity < required && capacity <= Int.MAX_VALUE / 2) capacity *= 2
            if (capacity < required) capacity = required
            return ByteArray(capacity)
        }

    }
}
