package com.neoproc.neoproccamx.recording

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Lossless timeline companion for an NPV10 private master.
 *
 * NPV10 video packets remain byte-for-byte unchanged. Encoded AAC access units are written to a
 * separately checksummed sidecar so capture never competes for the already saturated P010 writer
 * lock. The sidecar is promoted only after a durable footer has been written; conversion fails
 * closed when the NPV10 header says audio is required but this file is absent or invalid.
 */
internal object Npv10AudioSidecarPolicy {
    const val VERSION = 1
    const val SAMPLE_RATE = 48_000
    const val CHANNEL_COUNT = 2
    const val BIT_RATE = 256_000
    const val MIME = "audio/mp4a-latm"
    const val AAC_FRAME_SAMPLES = 1_024L

    private const val SUFFIX = ".audio.npv10a"
    private const val PART_SUFFIX = ".part"

    fun sidecarFor(master: File): File = File(master.parentFile, master.name + SUFFIX)

    fun temporarySidecarFor(master: File): File =
        File(master.parentFile, master.name + SUFFIX + PART_SUFFIX)

    fun deleteBundleAudio(master: File): Boolean {
        val final = sidecarFor(master)
        val temporary = temporarySidecarFor(master)
        val finalDeleted = !final.exists() || final.delete()
        val temporaryDeleted = !temporary.exists() || temporary.delete()
        return finalDeleted && temporaryDeleted
    }

    fun nominalPacketDurationUs(sampleRate: Int): Long =
        RecordingAudioSyncPolicy.durationUsForFrames(AAC_FRAME_SAMPLES, sampleRate)
            .coerceAtLeast(1L)
}

internal data class Npv10AudioSidecarHeader(
    val sampleRate: Int,
    val channelCount: Int,
    val bitRate: Int,
    val videoOriginSensorTimestampNs: Long,
    val codecConfig: ByteArray
)

internal data class Npv10AudioPacket(
    val presentationTimeUs: Long,
    val flags: Int,
    val payload: ByteArray
)

internal data class Npv10AudioSidecarAudit(
    val packetCount: Long,
    val firstPresentationTimeUs: Long,
    val finalPresentationTimeUs: Long,
    val payloadBytes: Long,
    val monotonicPts: Boolean,
    val crcValidated: Boolean
)

internal class Npv10AudioSidecarWriter(
    private val master: File,
    val header: Npv10AudioSidecarHeader
) : Closeable {
    private data class PacketBoundary(
        val fileEndOffset: Long,
        val packetCount: Long,
        val payloadBytes: Long,
        val presentationTimeUs: Long,
        val endPresentationTimeUs: Long
    )

    private val temporaryFile = Npv10AudioSidecarPolicy.temporarySidecarFor(master)
    private val finalFile = Npv10AudioSidecarPolicy.sidecarFor(master)
    private val file: RandomAccessFile
    private val packetBoundaries = ArrayList<PacketBoundary>()
    private var packetCount = 0L
    private var payloadBytes = 0L
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L
    private var closed = false

    init {
        require(header.sampleRate > 0) { "NPV10 audio sample rate must be positive" }
        require(header.channelCount > 0) { "NPV10 audio channel count must be positive" }
        require(header.bitRate > 0) { "NPV10 audio bitrate must be positive" }
        require(header.videoOriginSensorTimestampNs > 0L) {
            "NPV10 audio requires the exact first-video sensor timestamp"
        }
        require(header.codecConfig.isNotEmpty()) { "NPV10 AAC codec configuration is required" }
        master.parentFile?.mkdirs()
        check(!finalFile.exists()) { "NPV10 audio sidecar already exists: ${finalFile.name}" }
        if (temporaryFile.exists()) check(temporaryFile.delete()) {
            "Unable to remove stale NPV10 audio temporary file: ${temporaryFile.name}"
        }
        file = RandomAccessFile(temporaryFile, "rw")
        file.setLength(0L)
        file.write(MAGIC)
        file.writeInt(Npv10AudioSidecarPolicy.VERSION)
        file.writeInt(header.sampleRate)
        file.writeInt(header.channelCount)
        file.writeInt(header.bitRate)
        file.writeLong(header.videoOriginSensorTimestampNs)
        file.writeInt(header.codecConfig.size)
        file.write(header.codecConfig)
    }

    @Synchronized
    fun writePacket(packet: Npv10AudioPacket) {
        check(!closed) { "NPV10 audio sidecar is closed" }
        require(packet.payload.isNotEmpty()) { "NPV10 AAC packet must not be empty" }
        require(packet.presentationTimeUs >= 0L) { "NPV10 AAC PTS must be non-negative" }
        require(lastPtsUs < 0L || packet.presentationTimeUs > lastPtsUs) {
            "NPV10 AAC PTS must be strictly monotonic: previous=$lastPtsUs current=${packet.presentationTimeUs}"
        }
        val crc = CRC32().apply { update(packet.payload) }.value.toInt()
        file.write(PACKET_MAGIC)
        file.writeLong(packet.presentationTimeUs)
        file.writeInt(packet.flags)
        file.writeInt(packet.payload.size)
        file.writeInt(crc)
        file.write(packet.payload)
        if (firstPtsUs < 0L) firstPtsUs = packet.presentationTimeUs
        lastPtsUs = packet.presentationTimeUs
        packetCount = Math.addExact(packetCount, 1L)
        payloadBytes = Math.addExact(payloadBytes, packet.payload.size.toLong())
        packetBoundaries += PacketBoundary(
            fileEndOffset = file.filePointer,
            packetCount = packetCount,
            payloadBytes = payloadBytes,
            presentationTimeUs = packet.presentationTimeUs,
            endPresentationTimeUs = Math.addExact(
                packet.presentationTimeUs,
                Npv10AudioSidecarPolicy.nominalPacketDurationUs(header.sampleRate)
            )
        )
    }

    @Synchronized
    fun finish(): Npv10AudioSidecarAudit = finishInternal(alignment = null)

    /**
     * Finalizes at the complete AAC access-unit boundary nearest the immutable video duration.
     *
     * The capture worker intentionally records through the Camera2 stop fence. Trimming only whole
     * terminal AAC packets removes microphone overhang without fabricating silence, stretching PTS,
     * transcoding payloads, or weakening the strict A/V delta. Every retained packet keeps its
     * original bytes, timestamp and CRC.
     */
    @Synchronized
    fun finishAlignedToDuration(
        targetDurationUs: Long,
        maximumAllowedDeltaUs: Long
    ): Npv10AudioSidecarAudit {
        require(targetDurationUs > 0L) { "NPV10 audio alignment target must be positive" }
        require(maximumAllowedDeltaUs >= 0L) {
            "NPV10 audio alignment tolerance must be non-negative"
        }
        return finishInternal(
            alignment = TerminalAlignment(targetDurationUs, maximumAllowedDeltaUs)
        )
    }

    private fun finishInternal(alignment: TerminalAlignment?): Npv10AudioSidecarAudit {
        check(!closed) { "NPV10 audio sidecar is closed" }
        require(packetCount > 0L) { "NPV10 audio sidecar contains no AAC packets" }
        alignment?.let(::alignTerminalPacketBoundary)
        val finalPtsUs = Math.addExact(
            lastPtsUs,
            Npv10AudioSidecarPolicy.nominalPacketDurationUs(header.sampleRate)
        )
        file.write(FOOTER_MAGIC)
        file.writeLong(packetCount)
        file.writeLong(finalPtsUs)
        file.writeLong(payloadBytes)
        file.fd.sync()
        file.close()
        closed = true
        check(temporaryFile.renameTo(finalFile)) {
            "Unable to publish NPV10 audio sidecar: ${temporaryFile.name}"
        }
        return Npv10AudioSidecarAudit(
            packetCount = packetCount,
            firstPresentationTimeUs = firstPtsUs,
            finalPresentationTimeUs = finalPtsUs,
            payloadBytes = payloadBytes,
            monotonicPts = true,
            crcValidated = true
        )
    }

    private fun alignTerminalPacketBoundary(alignment: TerminalAlignment) {
        val selected = packetBoundaries.minWithOrNull(
            compareBy<PacketBoundary> {
                absoluteDifference(it.endPresentationTimeUs, alignment.targetDurationUs)
            }.thenBy { it.endPresentationTimeUs }
        ) ?: error("NPV10 audio sidecar contains no complete packet boundary")
        val deltaUs = absoluteDifference(
            selected.endPresentationTimeUs,
            alignment.targetDurationUs
        )
        require(deltaUs <= alignment.maximumAllowedDeltaUs) {
            "NPV10 real AAC coverage cannot align to video within " +
                "${alignment.maximumAllowedDeltaUs}us: target=${alignment.targetDurationUs} " +
                "nearest=${selected.endPresentationTimeUs} delta=$deltaUs"
        }
        if (selected.packetCount != packetCount) {
            file.setLength(selected.fileEndOffset)
            file.seek(selected.fileEndOffset)
            packetCount = selected.packetCount
            payloadBytes = selected.payloadBytes
            lastPtsUs = selected.presentationTimeUs
        }
    }

    @Synchronized
    fun abort() {
        if (!closed) {
            runCatching { file.close() }
            closed = true
        }
        runCatching { temporaryFile.delete() }
        runCatching { finalFile.delete() }
    }

    override fun close() {
        if (!closed) abort()
    }

    private companion object {
        val MAGIC = byteArrayOf('N'.code.toByte(), 'P'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte(), '0'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte(), 0)
        val PACKET_MAGIC = byteArrayOf('A'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), '0'.code.toByte())
        val FOOTER_MAGIC = byteArrayOf('A'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte())
    }

    private data class TerminalAlignment(
        val targetDurationUs: Long,
        val maximumAllowedDeltaUs: Long
    )

    private fun absoluteDifference(first: Long, second: Long): Long =
        if (first >= second) first - second else second - first
}

internal class Npv10AudioSidecarReader(master: File) : Closeable {
    private val file = RandomAccessFile(Npv10AudioSidecarPolicy.sidecarFor(master), "r")
    val header: Npv10AudioSidecarHeader
    private var packetCount = 0L
    private var payloadBytes = 0L
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L
    private var footerAudit: Npv10AudioSidecarAudit? = null

    init {
        val magic = ByteArray(8).also(file::readFully)
        require(magic.contentEquals(MAGIC)) { "Invalid NPV10 audio sidecar magic" }
        val version = file.readInt()
        require(version == Npv10AudioSidecarPolicy.VERSION) {
            "Unsupported NPV10 audio sidecar version: $version"
        }
        val sampleRate = file.readInt()
        val channels = file.readInt()
        val bitRate = file.readInt()
        val videoOriginNs = file.readLong()
        val codecConfigBytes = file.readInt()
        require(sampleRate > 0 && channels > 0 && bitRate > 0 && videoOriginNs > 0L) {
            "Invalid NPV10 audio sidecar header"
        }
        require(codecConfigBytes in 1..MAX_CODEC_CONFIG_BYTES) {
            "Invalid NPV10 AAC codec configuration length: $codecConfigBytes"
        }
        val codecConfig = ByteArray(codecConfigBytes).also(file::readFully)
        header = Npv10AudioSidecarHeader(
            sampleRate = sampleRate,
            channelCount = channels,
            bitRate = bitRate,
            videoOriginSensorTimestampNs = videoOriginNs,
            codecConfig = codecConfig
        )
    }

    fun readNextPacket(): Npv10AudioPacket? {
        check(footerAudit == null) { "NPV10 audio sidecar was already fully consumed" }
        val marker = ByteArray(4)
        try {
            file.readFully(marker)
        } catch (e: EOFException) {
            throw EOFException("NPV10 audio sidecar is missing its durable footer")
        }
        if (marker.contentEquals(FOOTER_MAGIC)) {
            val footerPackets = file.readLong()
            val footerEndPtsUs = file.readLong()
            val footerPayloadBytes = file.readLong()
            require(footerPackets == packetCount) {
                "NPV10 audio packet count mismatch: footer=$footerPackets read=$packetCount"
            }
            require(footerPayloadBytes == payloadBytes) {
                "NPV10 audio payload byte mismatch: footer=$footerPayloadBytes read=$payloadBytes"
            }
            require(packetCount > 0L && footerEndPtsUs > lastPtsUs) {
                "NPV10 audio footer has an invalid final PTS"
            }
            require(file.filePointer == file.length()) { "Unexpected bytes after NPV10 audio footer" }
            footerAudit = Npv10AudioSidecarAudit(
                packetCount = packetCount,
                firstPresentationTimeUs = firstPtsUs,
                finalPresentationTimeUs = footerEndPtsUs,
                payloadBytes = payloadBytes,
                monotonicPts = true,
                crcValidated = true
            )
            return null
        }
        require(marker.contentEquals(PACKET_MAGIC)) { "Invalid NPV10 audio packet marker" }
        val ptsUs = file.readLong()
        val flags = file.readInt()
        val size = file.readInt()
        val expectedCrc = file.readInt()
        require(ptsUs >= 0L && (lastPtsUs < 0L || ptsUs > lastPtsUs)) {
            "NPV10 AAC PTS is not strictly monotonic: previous=$lastPtsUs current=$ptsUs"
        }
        require(size in 1..MAX_PACKET_BYTES) { "Invalid NPV10 AAC packet size: $size" }
        val payload = ByteArray(size).also(file::readFully)
        val actualCrc = CRC32().apply { update(payload) }.value.toInt()
        require(actualCrc == expectedCrc) { "NPV10 AAC packet CRC mismatch" }
        if (firstPtsUs < 0L) firstPtsUs = ptsUs
        lastPtsUs = ptsUs
        packetCount = Math.addExact(packetCount, 1L)
        payloadBytes = Math.addExact(payloadBytes, size.toLong())
        return Npv10AudioPacket(ptsUs, flags, payload)
    }

    fun requireAudit(): Npv10AudioSidecarAudit =
        footerAudit ?: error("NPV10 audio sidecar has not been consumed through its footer")

    override fun close() = file.close()

    private companion object {
        val MAGIC = byteArrayOf('N'.code.toByte(), 'P'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte(), '0'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte(), 0)
        val PACKET_MAGIC = byteArrayOf('A'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), '0'.code.toByte())
        val FOOTER_MAGIC = byteArrayOf('A'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte())
        const val MAX_CODEC_CONFIG_BYTES = 4 * 1024
        const val MAX_PACKET_BYTES = 1024 * 1024
    }
}

internal data class Npv10AudioMuxAudit(
    val expected: Boolean,
    val present: Boolean,
    val packetCount: Long = 0L,
    val muxedPacketCount: Long = packetCount,
    val firstPresentationTimeUs: Long = -1L,
    val sourceFirstPresentationTimeUs: Long = firstPresentationTimeUs,
    val finalPresentationTimeUs: Long = -1L,
    val payloadBytes: Long = 0L,
    val monotonicPts: Boolean = false,
    val crcValidated: Boolean = false,
    val leadingSilencePacketCount: Int = 0,
    val leadingSilenceCoveredDurationUs: Long = 0L,
    val leadingSilenceResidualGapUs: Long = 0L,
    val originalPacketsPreserved: Boolean = false,
    val zeroOrigin: Boolean = false,
    val originCodecContract: String = "NONE"
) {
    fun toCertificateText(): String = buildString {
        appendLine("audioExpected=$expected")
        appendLine("audioPresent=$present")
        appendLine("audioSourcePacketCount=$packetCount")
        appendLine("audioMuxedPacketCount=$muxedPacketCount")
        appendLine("audioFirstPresentationTimeUs=$firstPresentationTimeUs")
        appendLine("audioSourceFirstPresentationTimeUs=$sourceFirstPresentationTimeUs")
        appendLine("audioFinalPresentationTimeUs=$finalPresentationTimeUs")
        appendLine("audioPayloadBytes=$payloadBytes")
        appendLine("audioMonotonicPts=$monotonicPts")
        appendLine("audioCrcValidated=$crcValidated")
        appendLine("audioLeadingSilencePacketCount=$leadingSilencePacketCount")
        appendLine("audioLeadingSilenceCoveredDurationUs=$leadingSilenceCoveredDurationUs")
        appendLine("audioLeadingSilenceResidualGapUs=$leadingSilenceResidualGapUs")
        appendLine("audioOriginalPacketsPreserved=$originalPacketsPreserved")
        appendLine("audioZeroOrigin=$zeroOrigin")
        appendLine("audioOriginCodecContract=$originCodecContract")
        appendLine(
            "audioTimelinePolicy=ZERO_ORIGIN_CODEC_VALID_SILENCE_PREFIX_THEN_EXACT_SOURCE_PTS"
        )
    }
}

internal object Npv10AudioSidecarMuxer {
    fun muxInto(
        master: File,
        writer: QuickTimeMovFileWriter,
        audioExpected: Boolean
    ): Npv10AudioMuxAudit {
        val sidecar = Npv10AudioSidecarPolicy.sidecarFor(master)
        if (!sidecar.isFile) {
            check(!audioExpected) { "Required NPV10 audio sidecar is missing: ${sidecar.name}" }
            return Npv10AudioMuxAudit(expected = false, present = false)
        }
        Npv10AudioSidecarReader(master).use { reader ->
            val header = reader.header
            val firstSourcePacket = requireNotNull(reader.readNextPacket()) {
                "NPV10 audio sidecar contains no AAC packets"
            }
            val originPlan = Npv10AacTimelineOriginPolicy.plan(
                firstSourcePresentationTimeUs = firstSourcePacket.presentationTimeUs,
                sampleRate = header.sampleRate,
                channelCount = header.channelCount,
                codecConfig = header.codecConfig
            )
            val track = writer.addAudioTrack(
                QuickTimeAudioTrackConfig(
                    mime = Npv10AudioSidecarPolicy.MIME,
                    sampleRate = header.sampleRate,
                    channelCount = header.channelCount,
                    codecConfig = header.codecConfig
                )
            )
            originPlan.packetPresentationTimesUs.forEach { ptsUs ->
                writer.writeSample(
                    trackIndex = track,
                    sample = QuickTimeSampleBuffer(
                        bytes = originPlan.newSilenceAccessUnit(),
                        presentationTimeUs = ptsUs,
                        flags = 0
                    )
                )
            }
            writer.writeSample(
                trackIndex = track,
                sample = QuickTimeSampleBuffer(
                    bytes = firstSourcePacket.payload,
                    presentationTimeUs = firstSourcePacket.presentationTimeUs,
                    flags = firstSourcePacket.flags
                )
            )
            while (true) {
                val packet = reader.readNextPacket() ?: break
                writer.writeSample(
                    trackIndex = track,
                    sample = QuickTimeSampleBuffer(
                        bytes = packet.payload,
                        presentationTimeUs = packet.presentationTimeUs,
                        flags = packet.flags
                    )
                )
            }
            val audit = reader.requireAudit()
            writer.setTrackEndPresentationTimeUs(track, audit.finalPresentationTimeUs)
            return Npv10AudioMuxAudit(
                expected = audioExpected,
                present = true,
                packetCount = audit.packetCount,
                muxedPacketCount = Math.addExact(
                    audit.packetCount,
                    originPlan.packetCount.toLong()
                ),
                firstPresentationTimeUs = originPlan.outputFirstPresentationTimeUs,
                sourceFirstPresentationTimeUs = audit.firstPresentationTimeUs,
                finalPresentationTimeUs = audit.finalPresentationTimeUs,
                payloadBytes = audit.payloadBytes,
                monotonicPts = audit.monotonicPts,
                crcValidated = audit.crcValidated,
                leadingSilencePacketCount = originPlan.packetCount,
                leadingSilenceCoveredDurationUs = originPlan.coveredDurationUs,
                leadingSilenceResidualGapUs = originPlan.residualGapUs,
                originalPacketsPreserved = true,
                zeroOrigin = originPlan.outputFirstPresentationTimeUs == 0L,
                originCodecContract = originPlan.codecContract
            )
        }
    }
}
