package com.neoproc.neoproccamx.recording

import android.system.Os
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max

/**
 * Controls how already-encoded video access units are placed in MOV.
 *
 * [NORMALIZE_FOR_RECORDING] retains the established live-recording behaviour: HEVC colour metadata
 * is made consistent with the selected output profile and duplicate prefix SEI is removed.
 * [PRESERVE_CODED_ACCESS_UNITS] is exclusively for NIVUKX packet-copy export. It never rewrites or
 * removes a coded NAL unit; Annex-B start codes are replaced by MOV length fields only because that
 * is container framing, not a decode/re-encode operation.
 */
internal enum class QuickTimeVideoPacketPolicy {
    NORMALIZE_FOR_RECORDING,
    PRESERVE_CODED_ACCESS_UNITS
}

internal class QuickTimeMovFileWriter(
    outputPath: String,
    private val containerLabel: String,
    preallocationPlan: RecordingPreallocationPolicy.Plan = RecordingPreallocationPolicy.processed(0L),
    private val fileReservation: QuickTimeFileReservation = AndroidQuickTimeFileReservation,
    private val videoPacketPolicy: QuickTimeVideoPacketPolicy =
        QuickTimeVideoPacketPolicy.NORMALIZE_FOR_RECORDING,
    /** Preserve a real audio capture offset relative to a zero-based video track. */
    private val preserveTrackPresentationOffsets: Boolean = false,
    initializationProbe: (RandomAccessFile) -> Unit = {}
) {
    companion object {
        private const val MOVIE_TIMESCALE = 1_000_000
        private const val DEFAULT_VIDEO_DURATION_US = 33_333L
        private const val DEFAULT_AUDIO_DURATION_US = 21_333L
        private const val SYNC_FRAME_FLAG = 1
        /**
         * ISO 639-2/T "und" (undetermined) language code, packed per
         * ISO/IEC 14496-12 §8.4.2: each lowercase letter biased by 0x60 then
         * laid out as three 5-bit fields = ((u-0x60) << 10) | ((n-0x60) << 5) |
         * (d-0x60) = (0x15 << 10) | (0x0E << 5) | 0x04 = 0x55C4. A literal 0 is
         * not a valid ISO 639-2 code and confuses strict players/inspectors.
         */
        private const val LANGUAGE_CODE_UND = 0x55C4
        /** Threshold above which 64-bit (version=1) box headers are required. */
        private const val DURATION_VERSION1_THRESHOLD: Long = Int.MAX_VALUE.toLong()
        private const val MAX_ZERO_WRITE_RETRIES = 8
        private const val MOV_SAMPLE_FRAMING_HEADROOM_BYTES = 64L * 1024L
        private const val TAG = "QuickTimeMovWriter"
    }

    private enum class TrackKind {
        VIDEO,
        AUDIO
    }

    private data class SampleMeta(
        val offset: Long,
        val size: Int,
        val presentationTimeUs: Long,
        val flags: Int
    )

    private data class TrackState(
        val kind: TrackKind,
        val videoConfig: QuickTimeVideoTrackConfig? = null,
        val audioConfig: QuickTimeAudioTrackConfig? = null,
        val samples: MutableList<SampleMeta> = mutableListOf(),
        var explicitEndPresentationTimeUs: Long? = null
    ) {
        fun defaultSampleDurationUs(): Long {
            return when (kind) {
                TrackKind.VIDEO -> {
                    val fps = videoConfig?.fps ?: 0
                    if (fps > 0) max(1L, 1_000_000L / fps) else DEFAULT_VIDEO_DURATION_US
                }

                TrackKind.AUDIO -> {
                    val sampleRate = audioConfig?.sampleRate ?: 0
                    if (sampleRate > 0) {
                        max(1L, (1_024L * 1_000_000L) / sampleRate)
                    } else {
                        DEFAULT_AUDIO_DURATION_US
                    }
                }
            }
        }

        fun firstPresentationTimeUs(): Long? = samples.firstOrNull()?.presentationTimeUs

        fun durationUs(useEncodedAudioTimestamps: Boolean = false): Long {
            if (samples.isEmpty()) return 0L
            val durations = sampleDurationsUs(useEncodedAudioTimestamps)
            return durations.sum()
        }

        fun sampleDurationsUs(useEncodedAudioTimestamps: Boolean = false): LongArray {
            if (samples.isEmpty()) return LongArray(0)
            if (kind == TrackKind.AUDIO && !useEncodedAudioTimestamps) {
                val sampleRate = audioConfig?.sampleRate ?: 0
                if (sampleRate > 0) {
                    return LongArray(samples.size) { index ->
                        val previousTotalUs =
                            index.toLong() * 1_024L * MOVIE_TIMESCALE / sampleRate
                        val nextTotalUs =
                            (index.toLong() + 1L) * 1_024L * MOVIE_TIMESCALE / sampleRate
                        (nextTotalUs - previousTotalUs).coerceAtLeast(1L)
                    }
                }
            }
            val durations = LongArray(samples.size)
            val defaultDuration = defaultSampleDurationUs()
            for (index in 0 until samples.lastIndex) {
                val delta = samples[index + 1].presentationTimeUs - samples[index].presentationTimeUs
                durations[index] = if (delta > 0L) delta else defaultDuration
            }
            durations[durations.lastIndex] = explicitEndPresentationTimeUs?.let { endPresentationTimeUs ->
                val finalDurationUs = Math.subtractExact(
                    endPresentationTimeUs,
                    samples.last().presentationTimeUs
                )
                require(finalDurationUs > 0L) {
                    "Explicit track end must follow the final sample PTS: " +
                        "last=${samples.last().presentationTimeUs} end=$endPresentationTimeUs"
                }
                finalDurationUs
            } ?: defaultDuration
            return durations
        }
    }

    private val outputFile = File(outputPath)
    private val file = RandomAccessFile(outputFile, "rw")
    private val tracks = mutableListOf<TrackState>()
    private val mdatStartOffset: Long
    private val mdatLargeSizeOffset: Long
    private var closed = false
    private var orientationDegrees = 0
    private val rollingExtensionBytes = preallocationPlan.rollingExtensionBytes
    private val rollingLeadBytes = preallocationPlan.rollingLeadBytes
    private val rollingReservationExecutor: ExecutorService? =
        if (preallocationPlan.enabled && rollingExtensionBytes > 0L) {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "mov-reservation-${outputFile.name.hashCode().toUInt()}").apply {
                    isDaemon = true
                }
            }
        } else {
            null
        }
    @Volatile
    private var pendingRollingReservation: Future<*>? = null
    @Volatile
    private var rollingReservationFailure: Throwable? = null
    @Volatile
    private var physicallyReservedBytes: Long = 0L
    private var rollingExtensionCount: Long = 0L
    @Volatile
    var preallocationResult: RecordingPreallocationResult
        private set

    init {
        try {
            initializationProbe(file)
            file.setLength(0L)
            writeFtyp()
            mdatStartOffset = file.filePointer
            file.writeInt(1)
            writeFourCc("mdat")
            mdatLargeSizeOffset = file.filePointer
            file.writeLong(16L)
            val committedHeaderBytes = file.filePointer
            preallocationResult = reserveRecordingSpace(preallocationPlan, committedHeaderBytes)
            physicallyReservedBytes = preallocationResult.reservedBytes
            file.seek(committedHeaderBytes)
            safeLog(
                "preallocation method=${preallocationResult.method} " +
                    "requested=${preallocationResult.plan.requestedBytes} " +
                    "reserved=${preallocationResult.reservedBytes} reason=${preallocationResult.plan.reason}"
            )
        } catch (failure: Throwable) {
            rollingReservationExecutor?.shutdownNow()
            runCatching { file.close() }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
    }

    fun addVideoTrack(config: QuickTimeVideoTrackConfig): Int {
        tracks += TrackState(kind = TrackKind.VIDEO, videoConfig = config)
        return tracks.lastIndex
    }

    fun addAudioTrack(config: QuickTimeAudioTrackConfig): Int {
        tracks += TrackState(kind = TrackKind.AUDIO, audioConfig = config)
        return tracks.lastIndex
    }

    fun setOrientationHint(degrees: Int) {
        val normalized = ((degrees % 360) + 360) % 360
        require(normalized % 90 == 0) { "MOV orientation must be a right angle: $degrees" }
        orientationDegrees = normalized
    }

    /**
     * Finalizes the duration of a track's last real sample from its source timeline. This is optional;
     * callers that do not provide an explicit end retain the established nominal-duration fallback.
     */
    fun setTrackEndPresentationTimeUs(trackIndex: Int, endPresentationTimeUs: Long) {
        check(!closed) { "Writer already closed" }
        val track = tracks[trackIndex]
        val finalSample = track.samples.lastOrNull()
            ?: error("Cannot set an end PTS for an empty MOV track")
        require(endPresentationTimeUs > finalSample.presentationTimeUs) {
            "Track end PTS must follow the final sample: " +
                "last=${finalSample.presentationTimeUs} end=$endPresentationTimeUs"
        }
        track.explicitEndPresentationTimeUs = endPresentationTimeUs
    }

    fun writeSample(trackIndex: Int, sample: QuickTimeSampleBuffer) {
        check(!closed) { "Writer already closed" }
        val track = tracks[trackIndex]
        val sampleBytes = if (sample.offset == 0 && sample.length == sample.bytes.size) {
            sample.bytes
        } else {
            sample.bytes.copyOfRange(sample.offset, sample.offset + sample.length)
        }
        val payload = when (track.kind) {
            TrackKind.VIDEO -> convertVideoSampleToMovPayload(
                mime = track.videoConfig?.mime.orEmpty(),
                sampleBytes = sampleBytes,
                colorConfig = track.videoConfig?.colorConfig
            )

            TrackKind.AUDIO -> sampleBytes
        }
        val offset = file.filePointer
        ensureRollingReservationForWrite(
            Math.addExact(offset, payload.size.toLong())
        )
        file.write(payload)
        track.samples += SampleMeta(
            offset = offset,
            size = payload.size,
            presentationTimeUs = sample.presentationTimeUs,
            flags = sample.flags
        )
    }

    fun writeSampleBuffer(
        trackIndex: Int,
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        flags: Int
    ) {
        check(!closed) { "Writer already closed" }
        val track = tracks[trackIndex]
        val payloadBuffer = buffer.slice()
        val offset = file.filePointer
        ensureRollingReservationForWrite(
            Math.addExact(
                Math.addExact(offset, payloadBuffer.remaining().toLong()),
                MOV_SAMPLE_FRAMING_HEADROOM_BYTES
            )
        )
        val payloadSize = when (track.kind) {
            TrackKind.VIDEO -> writeVideoSamplePayload(
                mime = track.videoConfig?.mime.orEmpty(),
                sampleBuffer = payloadBuffer,
                colorConfig = track.videoConfig?.colorConfig
            )

            TrackKind.AUDIO -> {
                val size = payloadBuffer.remaining()
                writeBuffer(payloadBuffer)
                size
            }
        }
        track.samples += SampleMeta(
            offset = offset,
            size = payloadSize,
            presentationTimeUs = presentationTimeUs,
            flags = flags
        )
    }

    fun close() {
        if (closed) return
        try {
            awaitPendingRollingReservation()
            val moovStart = file.filePointer
            writeMoov()
            val committedLength = file.filePointer
            val mdatSize = moovStart - mdatStartOffset
            file.seek(mdatLargeSizeOffset)
            file.writeLong(mdatSize)
            val reservationTrimmed = file.length() > committedLength
            file.setLength(committedLength)
            file.fd.sync()
            preallocationResult = preallocationResult.copy(
                committedBytes = committedLength,
                reservationTrimmed = reservationTrimmed,
                synced = true
            )
            safeLog(
                "finalized committed=$committedLength reserved=${preallocationResult.reservedBytes} " +
                    "rollingExtensions=$rollingExtensionCount trimmed=$reservationTrimmed synced=true"
            )
        } catch (failure: Throwable) {
            runCatching { file.close() }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        } finally {
            rollingReservationExecutor?.shutdown()
            if (file.channel.isOpen) file.close()
            closed = true
        }
    }

    fun abort() {
        if (closed) return
        try {
            awaitPendingRollingReservation()
            val committedLength = file.filePointer.coerceAtLeast(0L)
            val reservationTrimmed = file.length() > committedLength
            runCatching {
                file.setLength(committedLength)
                file.fd.sync()
                preallocationResult = preallocationResult.copy(
                    committedBytes = committedLength,
                    reservationTrimmed = reservationTrimmed,
                    synced = true
                )
            }
        } finally {
            rollingReservationExecutor?.shutdown()
            file.close()
            closed = true
        }
    }

    /**
     * Keeps storage allocation off the muxer lane. A new 10 GiB physical extent starts one GiB before
     * the active extent is consumed, so encoder samples continue landing in allocated blocks while the
     * filesystem prepares the next range. Conversion writers use this same mechanism because the rolling
     * controls live in their explicit [RecordingPreallocationPolicy.Plan], not in a recording-only caller.
     */
    private fun ensureRollingReservationForWrite(requiredEndBytes: Long) {
        val executor = rollingReservationExecutor ?: return
        if (physicallyReservedBytes <= 0L || rollingReservationFailure != null) return
        if (!RecordingPreallocationPolicy.shouldScheduleMovGrowth(
                requiredEndBytes = requiredEndBytes,
                reservedBytes = physicallyReservedBytes,
                leadBytes = rollingLeadBytes
            )
        ) {
            return
        }
        val active = pendingRollingReservation
        if (active == null || active.isDone) {
            scheduleRollingReservation(executor)
        }
        if (requiredEndBytes > physicallyReservedBytes) {
            awaitPendingRollingReservation()
        }
    }

    private fun scheduleRollingReservation(executor: ExecutorService) {
        if (rollingReservationFailure != null) return
        val active = pendingRollingReservation
        if (active != null && !active.isDone) return
        val reservedAtSubmission = physicallyReservedBytes
        pendingRollingReservation = executor.submit {
            try {
                val plan = RecordingPreallocationPolicy.rollingMovExtension(
                    availableBytes = RecordingPreallocationPolicy.destinationAvailableBytes(outputFile),
                    currentReservedBytes = reservedAtSubmission,
                    requestedGrowthBytes = rollingExtensionBytes
                )
                check(plan.growthBytes > 0L) {
                    "MOV cannot extend its reservation while preserving " +
                        "${plan.safetyReserveBytes} safety bytes: ${plan.reason}"
                }
                val method = fileReservation.reserve(
                    file = file,
                    offsetBytes = reservedAtSubmission,
                    lengthBytes = plan.growthBytes
                )
                physicallyReservedBytes = plan.targetReservedBytes
                rollingExtensionCount += 1L
                preallocationResult = preallocationResult.copy(
                    method = method,
                    reservedBytes = plan.targetReservedBytes,
                    rollingExtensionCount = rollingExtensionCount,
                    rollingExtendedBytes =
                        (plan.targetReservedBytes - preallocationResult.initialReservedBytes)
                            .coerceAtLeast(0L),
                    reservationTrimmed = false,
                    synced = false,
                    failure = null
                )
                safeLog(
                    "rolling preallocation extension=$rollingExtensionCount method=$method " +
                        "offset=$reservedAtSubmission bytes=${plan.growthBytes} " +
                        "reserved=${plan.targetReservedBytes} reason=${plan.reason}"
                )
            } catch (failure: Throwable) {
                rollingReservationFailure = failure
                preallocationResult = preallocationResult.copy(
                    failure = "rolling:${failure.javaClass.simpleName}:${failure.message}"
                )
                safeLog(
                    "rolling preallocation failed offset=$reservedAtSubmission " +
                        "bytes=$rollingExtensionBytes failure=${failure.javaClass.simpleName}:${failure.message}"
                )
            }
        }
    }

    private fun awaitPendingRollingReservation() {
        val pending = pendingRollingReservation ?: return
        runCatching { pending.get() }
            .onFailure { failure ->
                rollingReservationFailure = failure
                safeLog("rolling preallocation join failed ${failure.javaClass.simpleName}:${failure.message}")
            }
    }

    private fun reserveRecordingSpace(
        plan: RecordingPreallocationPolicy.Plan,
        committedHeaderBytes: Long
    ): RecordingPreallocationResult {
        if (!plan.enabled) {
            return RecordingPreallocationResult(
                plan = plan,
                method = RecordingPreallocationMethod.NONE,
                reservedBytes = 0L,
                committedBytes = committedHeaderBytes
            )
        }
        return try {
            val method = fileReservation.reserve(
                file = file,
                offsetBytes = 0L,
                lengthBytes = plan.reservationBytes
            )
            RecordingPreallocationResult(
                plan = plan,
                method = method,
                reservedBytes = plan.reservationBytes,
                committedBytes = committedHeaderBytes
            )
        } catch (failure: Throwable) {
            // Reservation is a performance optimisation. Never prevent capture when storage can still
            // accept the streaming writes normally.
            RecordingPreallocationResult(
                plan = plan,
                method = RecordingPreallocationMethod.NONE,
                reservedBytes = 0L,
                failure = "${failure.javaClass.simpleName}:${failure.message}",
                committedBytes = committedHeaderBytes
            )
        }
    }

    private fun safeLog(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun writeFtyp() {
        box("ftyp") {
            writeFourCc("qt  ")
            file.writeInt(0)
            writeFourCc("qt  ")
            writeFourCc("mp42")
        }
    }

    private fun writeMoov() {
        val sourceTimeline =
            videoPacketPolicy == QuickTimeVideoPacketPolicy.PRESERVE_CODED_ACCESS_UNITS ||
                preserveTrackPresentationOffsets
        val timelineOriginUs = if (sourceTimeline) {
            tracks.mapNotNull(TrackState::firstPresentationTimeUs).minOrNull() ?: 0L
        } else {
            0L
        }
        val movieDuration = tracks.maxOfOrNull { track ->
            val startDelayUs = if (sourceTimeline) {
                ((track.firstPresentationTimeUs() ?: timelineOriginUs) - timelineOriginUs).coerceAtLeast(0L)
            } else {
                0L
            }
            Math.addExact(startDelayUs, track.durationUs(useEncodedAudioTimestamps = sourceTimeline))
        } ?: 0L
        box("moov") {
            writeMvhd(movieDuration)
            tracks.forEachIndexed { index, track ->
                val startDelayUs = if (sourceTimeline) {
                    ((track.firstPresentationTimeUs() ?: timelineOriginUs) - timelineOriginUs)
                        .coerceAtLeast(0L)
                } else {
                    0L
                }
                writeTrak(
                    trackId = index + 1,
                    track = track,
                    startDelayUs = startDelayUs,
                    useEncodedAudioTimestamps = sourceTimeline
                )
            }
        }
    }

    private fun writeMvhd(movieDurationUs: Long) {
        val use64 = movieDurationUs > DURATION_VERSION1_THRESHOLD
        val version = if (use64) 1 else 0
        fullBox("mvhd", version = version, flags = 0) {
            // creation_time, modification_time — zero per privacy; width depends on version.
            if (use64) {
                file.writeLong(0L)
                file.writeLong(0L)
                file.writeInt(MOVIE_TIMESCALE)
                file.writeLong(movieDurationUs)
            } else {
                file.writeInt(0)
                file.writeInt(0)
                file.writeInt(MOVIE_TIMESCALE)
                file.writeInt(movieDurationUs.toInt())
            }
            writeFixed1616(1f)
            file.writeShort(0x0100)
            file.writeShort(0)
            file.writeInt(0)
            file.writeInt(0)
            writeIdentityMatrix()
            repeat(6) { file.writeInt(0) }
            file.writeInt(tracks.size + 1)
        }
    }

    private fun writeTrak(
        trackId: Int,
        track: TrackState,
        startDelayUs: Long,
        useEncodedAudioTimestamps: Boolean
    ) {
        val mediaDurationUs = track.durationUs(useEncodedAudioTimestamps)
        val trackDurationUs = Math.addExact(startDelayUs, mediaDurationUs)
        box("trak") {
            writeTkhd(trackId, track, trackDurationUs)
            if (startDelayUs > 0L) writeEditList(startDelayUs, mediaDurationUs)
            box("mdia") {
                writeMdhd(track, mediaDurationUs)
                writeHdlr(track)
                writeMinf(track, useEncodedAudioTimestamps)
            }
        }
    }

    private fun writeTkhd(trackId: Int, track: TrackState, durationUs: Long) {
        val use64 = durationUs > DURATION_VERSION1_THRESHOLD
        val version = if (use64) 1 else 0
        fullBox("tkhd", version = version, flags = 0x0007) {
            if (use64) {
                file.writeLong(0L)             // creation_time u64
                file.writeLong(0L)             // modification_time u64
                file.writeInt(trackId)         // track_ID u32
                file.writeInt(0)               // reserved u32
                file.writeLong(durationUs)     // duration u64
            } else {
                file.writeInt(0)               // creation_time u32
                file.writeInt(0)               // modification_time u32
                file.writeInt(trackId)         // track_ID u32
                file.writeInt(0)               // reserved u32
                file.writeInt(durationUs.toInt()) // duration u32
            }
            file.writeInt(0)
            file.writeInt(0)
            file.writeShort(0)
            file.writeShort(0)
            file.writeShort(if (track.kind == TrackKind.AUDIO) 0x0100 else 0)
            file.writeShort(0)
            // VIDEO ROTATION FIX: Write rotation display matrix for video tracks
            // so players correctly orient the playback. Audio tracks keep identity.
            if (track.kind == TrackKind.VIDEO && orientationDegrees != 0) {
                writeRotationMatrix(orientationDegrees)
            } else {
                writeIdentityMatrix()
            }
            val width = track.videoConfig?.width ?: 0
            val height = track.videoConfig?.height ?: 0
            writeFixed1616(width.toFloat())
            writeFixed1616(height.toFloat())
        }
    }

    private fun writeEditList(startDelayUs: Long, mediaDurationUs: Long) {
        val use64 = startDelayUs > DURATION_VERSION1_THRESHOLD ||
            mediaDurationUs > DURATION_VERSION1_THRESHOLD
        box("edts") {
            fullBox("elst", version = if (use64) 1 else 0, flags = 0) {
                file.writeInt(2)
                if (use64) {
                    file.writeLong(startDelayUs)
                    file.writeLong(-1L) // Empty edit: keep this track silent/blank until its source PTS.
                } else {
                    file.writeInt(startDelayUs.toInt())
                    file.writeInt(-1)
                }
                file.writeShort(1)
                file.writeShort(0)

                if (use64) {
                    file.writeLong(mediaDurationUs)
                    file.writeLong(0L)
                } else {
                    file.writeInt(mediaDurationUs.toInt())
                    file.writeInt(0)
                }
                file.writeShort(1)
                file.writeShort(0)
            }
        }
    }

    private fun writeMdhd(track: TrackState, durationUs: Long) {
        val use64 = durationUs > DURATION_VERSION1_THRESHOLD
        val version = if (use64) 1 else 0
        fullBox("mdhd", version = version, flags = 0) {
            if (use64) {
                file.writeLong(0L)            // creation_time u64
                file.writeLong(0L)            // modification_time u64
                file.writeInt(MOVIE_TIMESCALE)
                file.writeLong(durationUs)    // duration u64
            } else {
                file.writeInt(0)              // creation_time u32
                file.writeInt(0)              // modification_time u32
                file.writeInt(MOVIE_TIMESCALE)
                file.writeInt(durationUs.toInt())
            }
            file.writeShort(LANGUAGE_CODE_UND) // ISO 639-2 packed ("und")
            file.writeShort(0)                  // pre_defined / quality
        }
    }

    private fun writeHdlr(track: TrackState) {
        fullBox("hdlr", version = 0, flags = 0) {
            file.writeInt(0)
            when (track.kind) {
                TrackKind.VIDEO -> writeFourCc("vide")
                TrackKind.AUDIO -> writeFourCc("soun")
            }
            file.writeInt(0)
            file.writeInt(0)
            file.writeInt(0)
            val name = if (track.kind == TrackKind.VIDEO) {
                "VideoHandler"
            } else {
                "SoundHandler"
            }
            file.write(name.toByteArray(Charsets.US_ASCII))
            file.writeByte(0)
        }
    }

    private fun writeMinf(track: TrackState, useEncodedAudioTimestamps: Boolean) {
        box("minf") {
            when (track.kind) {
                TrackKind.VIDEO -> fullBox("vmhd", version = 0, flags = 1) {
                    file.writeShort(0)
                    file.writeShort(0)
                    file.writeShort(0)
                    file.writeShort(0)
                }

                TrackKind.AUDIO -> fullBox("smhd", version = 0, flags = 0) {
                    file.writeShort(0)
                    file.writeShort(0)
                }
            }
            writeDinf()
            writeStbl(track, useEncodedAudioTimestamps)
        }
    }

    private fun writeDinf() {
        box("dinf") {
            fullBox("dref", version = 0, flags = 0) {
                file.writeInt(1)
                fullBox("url ", version = 0, flags = 1) {}
            }
        }
    }

    private fun writeStbl(track: TrackState, useEncodedAudioTimestamps: Boolean) {
        box("stbl") {
            writeStsd(track)
            writeStts(track, useEncodedAudioTimestamps)
            writeStsc()
            writeStsz(track)
            writeCo64(track)
            if (track.kind == TrackKind.VIDEO) {
                writeStss(track)
            }
        }
    }

    private fun writeStsd(track: TrackState) {
        fullBox("stsd", version = 0, flags = 0) {
            file.writeInt(1)
            when (track.kind) {
                TrackKind.VIDEO -> writeVideoSampleEntry(track.videoConfig!!)
                TrackKind.AUDIO -> writeAudioSampleEntry(track.audioConfig!!)
            }
        }
    }

    private fun writeVideoSampleEntry(config: QuickTimeVideoTrackConfig) {
        val sampleEntryType = when {
            config.mime.equals("video/avc", ignoreCase = true) -> "avc1"
            config.mime.equals("video/hevc", ignoreCase = true) -> "hvc1"
            config.mime.equals("video/av01", ignoreCase = true) -> "av01"
            config.mime.equals("video/v210", ignoreCase = true) -> "v210"
            config.mime.equals("video/r210", ignoreCase = true) -> "r210" // 4:4:4 10-bit RGB master
            config.mime.equals("video/x-npv10", ignoreCase = true) -> "p010"
            else -> "encv"
        }
        box(sampleEntryType) {
            repeat(6) { file.writeByte(0) }
            file.writeShort(1)
            file.writeShort(0)
            file.writeShort(0)
            file.writeInt(0)
            file.writeInt(0)
            file.writeInt(0)
            file.writeShort(config.width)
            file.writeShort(config.height)
            writeFixed1616(72f)
            writeFixed1616(72f)
            file.writeInt(0)
            file.writeShort(1)
            writePascalString32(containerLabel)
            file.writeShort(0x0018)
            file.writeShort(0xFFFF.toShort().toInt())
            when (sampleEntryType) {
                "avc1" -> writeAvcC(config.codecConfig)
                "hvc1" -> writeHvcC(config.codecConfig, config.colorConfig)
                "av01" -> writeAv1C(config.codecConfig)
            }
            writeColorAtom(config.colorConfig)
            config.colorConfig.masteringDisplay?.let { md ->
                writeMasteringDisplayColourVolume(md)
                writeContentLightLevel(md)
            }
        }
    }

    private fun writeColorAtom(config: QuickTimeColorAtomConfig) {
        box("colr") {
            writeFourCc("nclx")
            file.writeShort(config.colorPrimaries)
            file.writeShort(config.transferCharacteristics)
            file.writeShort(config.matrixCoefficients)
            file.writeByte(if (config.fullRange) 0x80 else 0x00)
        }
    }

    /**
     * ST.2086 Mastering Display Colour Volume box ('mdcv'), placed inside the visual sample entry after
     * `colr`. Chromaticities are unsigned 16-bit in 0.00002 units (×50000); luminance is unsigned 32-bit
     * in 0.0001 cd/m² units. Primaries are written in G, B, R order per ST.2086. All fields big-endian.
     */
    private fun writeMasteringDisplayColourVolume(md: HdrStaticMetadataPolicy.MasteringDisplay) {
        fun chroma(v: Double): Int = Math.round(v * 50000.0).coerceIn(0L, 65535L).toInt()
        fun lum(nits: Double): Int = Math.round(nits * 10000.0).coerceIn(0L, 4294967295L).toInt()
        box("mdcv") {
            // Green, Blue, Red (ST.2086 order)
            file.writeShort(chroma(md.greenX)); file.writeShort(chroma(md.greenY))
            file.writeShort(chroma(md.blueX)); file.writeShort(chroma(md.blueY))
            file.writeShort(chroma(md.redX)); file.writeShort(chroma(md.redY))
            file.writeShort(chroma(md.whiteX)); file.writeShort(chroma(md.whiteY))
            file.writeInt(lum(md.maxLuminanceNits))
            file.writeInt(lum(md.minLuminanceNits))
        }
    }

    /** Content Light Level box ('clli'): MaxCLL + MaxFALL, unsigned 16-bit cd/m², big-endian. */
    private fun writeContentLightLevel(md: HdrStaticMetadataPolicy.MasteringDisplay) {
        box("clli") {
            file.writeShort(md.maxContentLightLevelNits.coerceIn(0, 65535))
            file.writeShort(md.maxFrameAverageLightLevelNits.coerceIn(0, 65535))
        }
    }

    private fun writeAudioSampleEntry(config: QuickTimeAudioTrackConfig) {
        box("mp4a") {
            repeat(6) { file.writeByte(0) }
            file.writeShort(1)
            file.writeInt(0)
            file.writeInt(0)
            file.writeShort(config.channelCount)
            file.writeShort(16)
            file.writeShort(0)
            file.writeShort(0)
            writeFixed1616(config.sampleRate.toFloat())
            writeEsds(config.codecConfig)
        }
    }

    private fun writeStts(track: TrackState, useEncodedAudioTimestamps: Boolean) {
        val entries = buildTimeToSampleEntries(track.sampleDurationsUs(useEncodedAudioTimestamps))
        fullBox("stts", version = 0, flags = 0) {
            file.writeInt(entries.size)
            entries.forEach { (count, delta) ->
                file.writeInt(count)
                file.writeInt(delta.toInt())
            }
        }
    }

    private fun writeStsc() {
        fullBox("stsc", version = 0, flags = 0) {
            file.writeInt(1)
            file.writeInt(1)
            file.writeInt(1)
            file.writeInt(1)
        }
    }

    private fun writeStsz(track: TrackState) {
        fullBox("stsz", version = 0, flags = 0) {
            file.writeInt(0)
            file.writeInt(track.samples.size)
            track.samples.forEach { file.writeInt(it.size) }
        }
    }

    private fun writeCo64(track: TrackState) {
        fullBox("co64", version = 0, flags = 0) {
            file.writeInt(track.samples.size)
            track.samples.forEach { file.writeLong(it.offset) }
        }
    }

    private fun writeStss(track: TrackState) {
        val syncSamples = track.samples.mapIndexedNotNull { index, sample ->
            if ((sample.flags and SYNC_FRAME_FLAG) != 0 || index == 0) index + 1 else null
        }
        if (syncSamples.isEmpty()) return
        fullBox("stss", version = 0, flags = 0) {
            file.writeInt(syncSamples.size)
            syncSamples.forEach(file::writeInt)
        }
    }

    private fun writeAvcC(codecConfig: ByteArray) {
        val parameterSets = splitAnnexB(codecConfig)
        val sps = parameterSets.filter { (it.firstOrNull()?.toInt()?.and(0x1F)) == 7 }
        val pps = parameterSets.filter { (it.firstOrNull()?.toInt()?.and(0x1F)) == 8 }
        val primarySps = sps.firstOrNull()
        box("avcC") {
            file.writeByte(1)
            file.writeByte(primarySps?.getOrNull(1)?.toInt() ?: 100)
            file.writeByte(primarySps?.getOrNull(2)?.toInt() ?: 0)
            file.writeByte(primarySps?.getOrNull(3)?.toInt() ?: 40)
            file.writeByte(0xFF)
            file.writeByte(0xE0 or sps.size.coerceAtMost(31))
            sps.forEach { nal ->
                file.writeShort(nal.size)
                file.write(nal)
            }
            file.writeByte(pps.size.coerceAtMost(255))
            pps.forEach { nal ->
                file.writeShort(nal.size)
                file.write(nal)
            }
        }
    }

    private fun writeHvcC(codecConfig: ByteArray, colorConfig: QuickTimeColorAtomConfig) {
        val parameterSetBytes = when (videoPacketPolicy) {
            QuickTimeVideoPacketPolicy.NORMALIZE_FOR_RECORDING ->
                HevcColorMetadataRewriter.rewriteAnnexBColorMetadata(codecConfig, colorConfig)
            QuickTimeVideoPacketPolicy.PRESERVE_CODED_ACCESS_UNITS -> codecConfig
        }
        val parameterSets = splitAnnexB(parameterSetBytes)
        val vps = parameterSets.filter { hevcNalType(it) == 32 }
        val sps = parameterSets.filter { hevcNalType(it) == 33 }
        val pps = parameterSets.filter { hevcNalType(it) == 34 }
        val arrays = listOf(32 to vps, 33 to sps, 34 to pps).filter { it.second.isNotEmpty() }
        val recordFields = HevcDecoderConfigurationRecordPolicy.fromAnnexBParameterSets(parameterSets)
        box("hvcC") {
            file.writeByte(1)
            file.writeByte(recordFields.packedProfileByte)
            file.writeInt(recordFields.profileCompatibilityFlags.toInt())
            writeUInt48(recordFields.constraintIndicatorFlags)
            file.writeByte(recordFields.levelIdc)
            file.writeShort(0xF000)
            file.writeByte(0xFC)
            file.writeByte(0xFC or (recordFields.chromaFormatIdc and 0x03))
            file.writeByte(0xF8 or (recordFields.bitDepthLumaMinus8 and 0x07))
            file.writeByte(0xF8 or (recordFields.bitDepthChromaMinus8 and 0x07))
            file.writeShort(0)
            file.writeByte(0x0F)
            file.writeByte(arrays.size)
            arrays.forEach { (nalType, nalUnits) ->
                file.writeByte(0x80 or nalType)
                file.writeShort(nalUnits.size)
                nalUnits.forEach { nal ->
                    file.writeShort(nal.size)
                    file.write(nal)
                }
            }
        }
    }

    private fun writeAv1C(codecConfig: ByteArray) {
        box("av1C") {
            if (codecConfig.isNotEmpty()) {
                file.write(codecConfig)
            } else {
                file.writeByte(0x81)
                file.writeByte(0)
                file.writeByte(0)
                file.writeByte(0)
            }
        }
    }

    private fun writeEsds(codecConfig: ByteArray) {
        fullBox("esds", version = 0, flags = 0) {
            writeDescriptor(tag = 0x03) {
                file.writeShort(1)
                file.writeByte(0)
                writeDescriptor(tag = 0x04) {
                    file.writeByte(0x40)
                    file.writeByte(0x15)
                    writeUInt24(0)
                    file.writeInt(0)
                    file.writeInt(0)
                    writeDescriptor(tag = 0x05) {
                        file.write(codecConfig)
                    }
                    writeDescriptor(tag = 0x06) {
                        file.writeByte(0x02)
                    }
                }
            }
        }
    }

    private fun buildTimeToSampleEntries(durations: LongArray): List<Pair<Int, Long>> {
        if (durations.isEmpty()) return emptyList()
        val entries = mutableListOf<Pair<Int, Long>>()
        var currentDelta = durations.first()
        var count = 1
        for (index in 1 until durations.size) {
            if (durations[index] == currentDelta) {
                count += 1
            } else {
                entries += count to currentDelta
                currentDelta = durations[index]
                count = 1
            }
        }
        entries += count to currentDelta
        return entries
    }

    private fun convertVideoSampleToMovPayload(
        mime: String,
        sampleBytes: ByteArray,
        colorConfig: QuickTimeColorAtomConfig?
    ): ByteArray {
        return when {
            mime.equals("video/hevc", ignoreCase = true) ->
                colorConfig?.let {
                    HevcSamplePayloadPolicy.convertForMovSample(sampleBytes, it)
                } ?: convertAnnexBToLengthPrefixed(sampleBytes)

            mime.equals("video/avc", ignoreCase = true) -> convertAnnexBToLengthPrefixed(sampleBytes)

            else -> sampleBytes
        }
    }

    private fun writeVideoSamplePayload(
        mime: String,
        sampleBuffer: ByteBuffer,
        colorConfig: QuickTimeColorAtomConfig?
    ): Int {
        val source = sampleBuffer.slice()
        if (videoPacketPolicy == QuickTimeVideoPacketPolicy.PRESERVE_CODED_ACCESS_UNITS) {
            return when {
                mime.equals("video/hevc", ignoreCase = true) ||
                    mime.equals("video/avc", ignoreCase = true) ->
                    writeAnnexBPreservingCodedNalUnits(source)

                else -> {
                    val size = source.remaining()
                    writeBuffer(source)
                    size
                }
            }
        }
        return when {
            mime.equals("video/hevc", ignoreCase = true) && colorConfig != null ->
                HevcSamplePayloadPolicy.writeForMovSample(source, colorConfig) { writeBuffer(it) }

            mime.equals("video/hevc", ignoreCase = true) ||
                mime.equals("video/avc", ignoreCase = true) ->
                writeAnnexBConvertedOrOriginal(source)

            else -> {
                val size = source.remaining()
                writeBuffer(source)
                size
            }
        }
    }

    private fun writeAnnexBConvertedOrOriginal(sampleBuffer: ByteBuffer): Int {
        val source = sampleBuffer.slice()
        // A valid 256-byte MOV NAL starts with 00 00 01 00. Prove complete 4-byte length framing before
        // interpreting that prefix as a 3-byte Annex-B start code, otherwise the access unit is corrupted.
        if (isCompleteLengthPrefixedAccessUnit(source)) {
            val size = source.remaining()
            writeBuffer(source)
            return size
        }
        if (!startsWithAnnexBStartCode(source)) {
            val size = source.remaining()
            writeBuffer(source)
            return size
        }
        val converted = convertAnnexBToLengthPrefixed(source.toByteArray())
        writeBuffer(ByteBuffer.wrap(converted))
        return converted.size
    }

    /**
     * MOV stores AVC/HEVC NAL units with a 32-bit length prefix. Encoders may supply the identical
     * NAL payloads with Annex-B start codes. Stream the framing conversion directly to storage while
     * retaining every payload byte and every SEI unit; no frame-sized conversion array is allocated.
     */
    private fun writeAnnexBPreservingCodedNalUnits(sampleBuffer: ByteBuffer): Int {
        val source = sampleBuffer.slice()
        // A valid 256-byte length-prefixed NAL starts with 00 00 01 00, which superficially resembles
        // a 3-byte Annex-B start code. Prove complete length framing first and copy it byte-for-byte.
        if (isCompleteLengthPrefixedAccessUnit(source)) {
            val size = source.remaining()
            writeBuffer(source)
            return size
        }
        if (!startsWithAnnexBStartCode(source)) {
            val size = source.remaining()
            writeBuffer(source)
            return size
        }
        val startCodes = findAnnexBStartCodes(source)
        if (startCodes.isEmpty()) {
            val size = source.remaining()
            writeBuffer(source)
            return size
        }

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

            file.writeInt(payloadSize)
            val payload = source.duplicate().apply {
                position(payloadStart)
                limit(payloadEnd)
            }.slice()
            writeBuffer(payload)
            bytesWritten += Int.SIZE_BYTES + payloadSize
        }
        return bytesWritten
    }

    private fun isCompleteLengthPrefixedAccessUnit(buffer: ByteBuffer): Boolean {
        val size = buffer.remaining()
        var offset = 0
        var nalCount = 0
        while (offset < size) {
            if (offset + Int.SIZE_BYTES > size) return false
            val length =
                ((buffer.get(offset).toInt() and 0xFF) shl 24) or
                    ((buffer.get(offset + 1).toInt() and 0xFF) shl 16) or
                    ((buffer.get(offset + 2).toInt() and 0xFF) shl 8) or
                    (buffer.get(offset + 3).toInt() and 0xFF)
            if (length <= 0 || length > size - offset - Int.SIZE_BYTES) return false
            offset += Int.SIZE_BYTES + length
            nalCount += 1
        }
        return nalCount > 0 && offset == size
    }

    private data class AnnexBBufferStartCode(
        val offset: Int,
        val prefixSize: Int
    )

    private fun findAnnexBStartCodes(source: ByteBuffer): List<AnnexBBufferStartCode> {
        val startCodes = ArrayList<AnnexBBufferStartCode>(4)
        val size = source.remaining()
        var index = 0
        while (index <= size - 3) {
            val fourByte =
                index <= size - 4 &&
                    source.get(index) == 0.toByte() &&
                    source.get(index + 1) == 0.toByte() &&
                    source.get(index + 2) == 0.toByte() &&
                    source.get(index + 3) == 1.toByte()
            val threeByte =
                source.get(index) == 0.toByte() &&
                    source.get(index + 1) == 0.toByte() &&
                    source.get(index + 2) == 1.toByte()
            when {
                fourByte -> {
                    startCodes += AnnexBBufferStartCode(index, 4)
                    index += 4
                }
                threeByte -> {
                    startCodes += AnnexBBufferStartCode(index, 3)
                    index += 3
                }
                else -> index += 1
            }
        }
        return startCodes
    }

    private fun startsWithAnnexBStartCode(buffer: ByteBuffer): Boolean {
        if (buffer.remaining() < 3) return false
        val first = buffer.get(0)
        val second = buffer.get(1)
        val third = buffer.get(2)
        val threeByteStartCode =
            first == 0.toByte() &&
                second == 0.toByte() &&
                third == 1.toByte()
        val fourByteStartCode =
            buffer.remaining() >= 4 &&
                first == 0.toByte() &&
                second == 0.toByte() &&
                third == 0.toByte() &&
                buffer.get(3) == 1.toByte()
        return threeByteStartCode || fourByteStartCode
    }

    private fun convertAnnexBToLengthPrefixed(sampleBytes: ByteArray): ByteArray {
        if (isCompleteLengthPrefixedAccessUnit(ByteBuffer.wrap(sampleBytes))) return sampleBytes
        val nalUnits = splitAnnexB(sampleBytes)
        if (nalUnits.isEmpty()) return sampleBytes
        val totalSize = nalUnits.sumOf { 4 + it.size }
        val out = ByteArray(totalSize)
        var offset = 0
        nalUnits.forEach { nal ->
            val size = nal.size
            out[offset] = (size ushr 24).toByte()
            out[offset + 1] = (size ushr 16).toByte()
            out[offset + 2] = (size ushr 8).toByte()
            out[offset + 3] = size.toByte()
            offset += 4
            nal.copyInto(out, destinationOffset = offset)
            offset += size
        }
        return out
    }

    private fun splitAnnexB(bytes: ByteArray): List<ByteArray> {
        val startCodes = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index <= bytes.size - 3) {
            val isThreeByteStartCode =
                bytes[index] == 0.toByte() &&
                    bytes[index + 1] == 0.toByte() &&
                    bytes[index + 2] == 1.toByte()
            val isFourByteStartCode =
                index <= bytes.size - 4 &&
                    bytes[index] == 0.toByte() &&
                    bytes[index + 1] == 0.toByte() &&
                    bytes[index + 2] == 0.toByte() &&
                    bytes[index + 3] == 1.toByte()
            when {
                isFourByteStartCode -> {
                    startCodes += index to 4
                    index += 4
                }

                isThreeByteStartCode -> {
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
                bytes.copyOfRange(payloadStart, payloadEnd)
            }
        }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val copy = duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    private fun writeBuffer(buffer: ByteBuffer) {
        var zeroWriteRetries = 0
        while (buffer.hasRemaining()) {
            val written = file.channel.write(buffer)
            if (written == 0) {
                zeroWriteRetries += 1
                if (zeroWriteRetries > MAX_ZERO_WRITE_RETRIES) {
                    error("QuickTime MOV writer stalled while writing ${buffer.remaining()} bytes")
                }
                Thread.yield()
            } else {
                zeroWriteRetries = 0
            }
        }
    }

    private fun hevcNalType(nal: ByteArray): Int {
        if (nal.size < 2) return -1
        return (nal[0].toInt() ushr 1) and 0x3F
    }

    private fun writeDescriptor(tag: Int, body: () -> Unit) {
        file.writeByte(tag)
        val bodyBytes = captureBytes(body)
        writeMp4DescriptorLength(bodyBytes.size)
        file.write(bodyBytes)
    }

    private fun writeMp4DescriptorLength(length: Int) {
        var value = length
        val stack = IntArray(4)
        var count = 0
        stack[count++] = value and 0x7F
        value = value ushr 7
        while (value > 0) {
            stack[count++] = 0x80 or (value and 0x7F)
            value = value ushr 7
        }
        for (index in count - 1 downTo 0) {
            file.writeByte(stack[index])
        }
    }

    private fun writeUInt24(value: Int) {
        file.writeByte((value ushr 16) and 0xFF)
        file.writeByte((value ushr 8) and 0xFF)
        file.writeByte(value and 0xFF)
    }

    private fun writeUInt48(value: Long) {
        for (shift in 40 downTo 0 step 8) {
            file.writeByte(((value ushr shift) and 0xFF).toInt())
        }
    }

    private fun captureBytes(body: () -> Unit): ByteArray {
        val start = file.filePointer
        val originalLength = file.length()
        body()
        val end = file.filePointer
        val size = (end - start).toInt()
        val bytes = ByteArray(size)
        file.seek(start)
        file.readFully(bytes)
        // Without preallocation originalLength == start, preserving the original behavior. With an
        // active reservation, keep its tail intact until close() performs and reports the one explicit
        // committed-length trim. AAC descriptor assembly must not silently consume that evidence early.
        file.setLength(originalLength)
        file.seek(start)
        return bytes
    }

    private fun box(type: String, body: () -> Unit) {
        val start = file.filePointer
        file.writeInt(0)
        writeFourCc(type)
        body()
        val end = file.filePointer
        file.seek(start)
        file.writeInt((end - start).toInt())
        file.seek(end)
    }

    private fun fullBox(type: String, version: Int, flags: Int, body: () -> Unit) {
        box(type) {
            file.writeInt((version shl 24) or (flags and 0x00FFFFFF))
            body()
        }
    }

    private fun writeFourCc(type: String) {
        require(type.length == 4) { "FourCC must be 4 chars: $type" }
        file.write(type.toByteArray(Charsets.US_ASCII))
    }

    private fun writeIdentityMatrix() {
        file.writeInt(0x00010000)  // a = 1.0 (16.16 fixed)
        file.writeInt(0)           // b = 0
        file.writeInt(0)           // u = 0
        file.writeInt(0)           // c = 0
        file.writeInt(0x00010000)  // d = 1.0 (16.16 fixed)
        file.writeInt(0)           // v = 0
        file.writeInt(0)           // tx = 0
        file.writeInt(0)           // ty = 0
        file.writeInt(0x40000000)  // w = 1.0 (2.30 fixed)
    }

    /**
     * Writes a 3×3 rotation display matrix per ISO/IEC 14496-12 §8.3.2.
     * The matrix is stored as { a, b, u, c, d, v, tx, ty, w } where:
     *   a,b,c,d are 16.16 fixed-point and tx,ty are 16.16 fixed-point,
     *   u,v are 2.30 fixed-point, w is 2.30 fixed-point.
     * For 90° CW:  { 0, 1, 0, -1, 0, 0, height, 0, 1 }
     * For 180°:    { -1, 0, 0, 0, -1, 0, width, height, 1 }
     * For 270° CW: { 0, -1, 0, 1, 0, 0, 0, width, 1 }
     */
    private fun writeRotationMatrix(degrees: Int) {
        when (((degrees % 360) + 360) % 360) {
            90 -> {
                file.writeInt(0)           // a = 0
                file.writeInt(0x00010000)  // b = 1.0
                file.writeInt(0)           // u = 0
                file.writeInt(-0x00010000) // c = -1.0 (0xFFFF0000 as signed int)
                file.writeInt(0)           // d = 0
                file.writeInt(0)           // v = 0
                // tx, ty: translation to keep content visible after rotation
                // For 90° CW, tx = height (16.16), ty = 0
                val height = tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.videoConfig?.height ?: 0
                writeFixed1616(height.toFloat())
                file.writeInt(0)
                file.writeInt(0x40000000)  // w = 1.0
            }
            180 -> {
                file.writeInt(-0x00010000) // a = -1.0
                file.writeInt(0)           // b = 0
                file.writeInt(0)           // u = 0
                file.writeInt(0)           // c = 0
                file.writeInt(-0x00010000) // d = -1.0
                file.writeInt(0)           // v = 0
                val width = tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.videoConfig?.width ?: 0
                val height = tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.videoConfig?.height ?: 0
                writeFixed1616(width.toFloat())
                writeFixed1616(height.toFloat())
                file.writeInt(0x40000000)  // w = 1.0
            }
            270 -> {
                file.writeInt(0)           // a = 0
                file.writeInt(-0x00010000) // b = -1.0
                file.writeInt(0)           // u = 0
                file.writeInt(0x00010000)  // c = 1.0
                file.writeInt(0)           // d = 0
                file.writeInt(0)           // v = 0
                val width = tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.videoConfig?.width ?: 0
                file.writeInt(0)
                writeFixed1616(width.toFloat())
                file.writeInt(0x40000000)  // w = 1.0
            }
            else -> writeIdentityMatrix()
        }
    }

    private fun writeFixed1616(value: Float) {
        // Pack the 16.16 fixed-point value using Long math: value * 65536 can exceed
        // Int.MAX_VALUE (e.g. AUDIO_SAMPLE_RATE 48000 -> 3,145,728,000), and Float.roundToInt()
        // clamps out-of-range results to 0x7FFFFFFF, corrupting the field. Math.round(Double)
        // returns a Long (no clamp); we then write the low 32 bits as a big-endian UInt32.
        // Byte-identical to the previous (value * 65536f).roundToInt() for all values < 32768.
        val fixed = Math.round(value.toDouble() * 65_536.0)
        file.writeInt((fixed and 0xFFFF_FFFFL).toInt())
    }

    private fun writePascalString32(value: String) {
        val raw = value.toByteArray(Charsets.US_ASCII)
        val clipped = if (raw.size > 31) raw.copyOf(31) else raw
        file.writeByte(clipped.size)
        file.write(clipped)
        repeat(31 - clipped.size) { file.writeByte(0) }
    }

}

/** Injectable reservation seam keeps the MOV writer host-testable while production uses real fallocate. */
internal fun interface QuickTimeFileReservation {
    fun reserve(
        file: RandomAccessFile,
        offsetBytes: Long,
        lengthBytes: Long
    ): RecordingPreallocationMethod
}

internal object AndroidQuickTimeFileReservation : QuickTimeFileReservation {
    override fun reserve(
        file: RandomAccessFile,
        offsetBytes: Long,
        lengthBytes: Long
    ): RecordingPreallocationMethod {
        require(offsetBytes >= 0L) { "Reservation offset must be non-negative" }
        require(lengthBytes > 0L) { "Reservation length must be positive" }
        val targetLength = Math.addExact(offsetBytes, lengthBytes)
        return try {
            Os.posix_fallocate(file.fd, offsetBytes, lengthBytes)
            RecordingPreallocationMethod.POSIX_FALLOCATE
        } catch (fallocateFailure: Throwable) {
            try {
                // Sparse length remains useful on filesystems/devices that reject fallocate. The final
                // close always truncates this dummy tail back to the exact committed MOV byte count.
                if (file.length() < targetLength) file.setLength(targetLength)
                RecordingPreallocationMethod.SPARSE_LENGTH_FALLBACK
            } catch (fallbackFailure: Throwable) {
                fallbackFailure.addSuppressed(fallocateFailure)
                throw fallbackFailure
            }
        }
    }
}
