package com.neoproc.neoproccamx.recording

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlin.math.max

internal data class NivukxPacketCopyProgress(
    val completedSamples: Long,
    val consumedBytes: Long,
    val totalBytes: Long,
    val presentationTimeUs: Long
) {
    val fraction: Double
        get() = if (totalBytes <= 0L) 0.0 else
            (consumedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
}

internal data class NivukxPacketCopyResult(
    val sampleCount: Long,
    val videoSampleCount: Long,
    val audioSampleCount: Long,
    val encodedPayloadBytes: Long,
    val durationUs: Long,
    val elapsedMs: Long,
    val peakReusableSampleBytes: Int,
    val integrityVerified: Boolean,
    val codecMimes: Set<String>,
    val transcoded: Boolean = false,
    val codedNalUnitsPreserved: Boolean = true
) {
    val payloadThroughputMiBPerSecond: Double
        get() = if (elapsedMs <= 0L) 0.0 else
            encodedPayloadBytes.toDouble() * 1_000.0 / elapsedMs.toDouble() / (1024.0 * 1024.0)
}

/**
 * Single-pass NIVUKX -> QuickTime MOV packet-copy engine.
 *
 * There is deliberately no MediaCodec decoder or encoder here. Encoded AVC/HEVC/AV1 and AAC access
 * units are streamed into MOV in original presentation order. For Annex-B AVC/HEVC, only the start
 * code is replaced by the mandatory MOV length prefix; every coded NAL payload (including HDR SEI)
 * remains intact. CRC32C and the final SHA-256 session digest are verified during that same pass.
 */
internal object NivukxPacketCopyMovRemuxer {
    private const val CONTAINER_LABEL = "NIVUKX Packet-Copy MOV"

    fun remux(
        input: File,
        output: File,
        preallocationPlan: RecordingPreallocationPolicy.Plan =
            RecordingPreallocationPolicy.resolve(availableBytes = 0L, requestedBytes = 0L),
        shouldContinue: () -> Boolean = { true },
        onProgress: (NivukxPacketCopyProgress) -> Unit = {}
    ): NivukxPacketCopyResult {
        require(input.isFile && input.length() >= NivukxContainerFormat.HEADER_SIZE) {
            "NIVUKX input is missing or truncated"
        }
        require(input.absolutePath != output.absolutePath) { "NIVUKX input and MOV output must differ" }
        val cleanupRegularOutput = !output.absolutePath.startsWith("/proc/self/fd/")
        if (cleanupRegularOutput) {
            require(!output.exists() || output.length() == 0L) {
                "Packet-copy output must be a new or empty file"
            }
            output.parentFile?.let { parent ->
                require(parent.exists() || parent.mkdirs()) { "Unable to create MOV output directory" }
            }
        }

        val startedNs = System.nanoTime()
        var writer: QuickTimeMovFileWriter? = null
        val outputTrackByInputIndex = HashMap<Int, Int>()
        val trackKindByInputIndex = HashMap<Int, NivukxContainerFormat.TrackKind>()
        val lastPtsByInputIndex = HashMap<Int, Long>()
        val codecMimes = linkedSetOf<String>()
        var encodedPayloadBytes = 0L
        var videoSampleCount = 0L
        var audioSampleCount = 0L
        var peakReusableSampleBytes = 0

        try {
            val inspection = NivukxStreamingReader(input).streamSamples(
                shouldContinue = shouldContinue,
                onTracksReady = { header, tracks ->
                    if (!shouldContinue()) throw CancellationException("NIVUKX packet-copy export cancelled")
                    require(tracks.isNotEmpty()) { "NIVUKX stream has no tracks" }
                    val activeWriter = QuickTimeMovFileWriter(
                        outputPath = output.absolutePath,
                        containerLabel = CONTAINER_LABEL,
                        preallocationPlan = preallocationPlan,
                        videoPacketPolicy = QuickTimeVideoPacketPolicy.PRESERVE_CODED_ACCESS_UNITS
                    )
                    writer = activeWriter
                    activeWriter.setOrientationHint(header.orientationDegrees)

                    tracks.forEach { track ->
                        codecMimes += track.mime
                        val outputTrack = when (track.kind) {
                            NivukxContainerFormat.TrackKind.VIDEO -> activeWriter.addVideoTrack(
                                QuickTimeVideoTrackConfig(
                                    mime = track.mime,
                                    width = track.width,
                                    height = track.height,
                                    fps = track.fps,
                                    codecConfig = track.codecConfig.copyOf(),
                                    colorConfig = track.toQuickTimeColorConfig()
                                )
                            )
                            NivukxContainerFormat.TrackKind.AUDIO -> activeWriter.addAudioTrack(
                                QuickTimeAudioTrackConfig(
                                    mime = track.mime,
                                    sampleRate = track.sampleRate,
                                    channelCount = track.channelCount,
                                    codecConfig = track.codecConfig.copyOf()
                                )
                            )
                            NivukxContainerFormat.TrackKind.DEPTH,
                            NivukxContainerFormat.TrackKind.TELEMETRY -> throw IOException(
                                "MOV packet-copy cannot represent NIVUKX ${track.kind.name.lowercase()} " +
                                    "track ${track.index} without dropping functionality"
                            )
                        }
                        outputTrackByInputIndex[track.index] = outputTrack
                        trackKindByInputIndex[track.index] = track.kind
                    }
                },
                onSample = { info, payload ->
                    if (!shouldContinue()) throw CancellationException("NIVUKX packet-copy export cancelled")
                    val activeWriter = writer ?: error("NIVUKX tracks were not initialized")
                    val outputTrack = outputTrackByInputIndex[info.trackIndex]
                        ?: throw IOException("NIVUKX sample references unmapped track ${info.trackIndex}")
                    lastPtsByInputIndex[info.trackIndex]?.let { previousPts ->
                        if (info.presentationTimeUs <= previousPts) {
                            throw IOException(
                                "NIVUKX track ${info.trackIndex} PTS is not strictly increasing: " +
                                    "$previousPts -> ${info.presentationTimeUs}"
                            )
                        }
                    }
                    lastPtsByInputIndex[info.trackIndex] = info.presentationTimeUs
                    val sampleBytes = payload.remaining()
                    activeWriter.writeSampleBuffer(
                        trackIndex = outputTrack,
                        buffer = payload,
                        presentationTimeUs = info.presentationTimeUs,
                        flags = info.flags
                    )
                    encodedPayloadBytes = Math.addExact(encodedPayloadBytes, sampleBytes.toLong())
                    peakReusableSampleBytes = max(peakReusableSampleBytes, sampleBytes)
                    when (trackKindByInputIndex[info.trackIndex]) {
                        NivukxContainerFormat.TrackKind.VIDEO -> videoSampleCount += 1L
                        NivukxContainerFormat.TrackKind.AUDIO -> audioSampleCount += 1L
                        else -> Unit
                    }
                },
                onProgress = { progress ->
                    onProgress(
                        NivukxPacketCopyProgress(
                            completedSamples = progress.completedSamples,
                            consumedBytes = progress.consumedBytes,
                            totalBytes = progress.totalBytes,
                            presentationTimeUs = progress.presentationTimeUs
                        )
                    )
                }
            )

            val activeWriter = writer ?: throw IOException("Cannot packet-copy an empty NIVUKX stream")
            if (!inspection.integrityVerified || inspection.recoveryRequired) {
                throw IOException("NIVUKX session integrity was not proven")
            }
            activeWriter.close()
            writer = null

            val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
            return NivukxPacketCopyResult(
                sampleCount = inspection.recoveredSampleCount,
                videoSampleCount = videoSampleCount,
                audioSampleCount = audioSampleCount,
                encodedPayloadBytes = encodedPayloadBytes,
                durationUs = inspection.header.durationUs,
                elapsedMs = elapsedMs,
                peakReusableSampleBytes = peakReusableSampleBytes,
                integrityVerified = true,
                codecMimes = codecMimes.toSet()
            )
        } catch (failure: Throwable) {
            runCatching { writer?.abort() }
            if (cleanupRegularOutput) runCatching { output.delete() }
            throw failure
        }
    }

    private fun NivukxContainerFormat.TrackDescriptor.toQuickTimeColorConfig(): QuickTimeColorAtomConfig {
        val descriptor = color
            ?: throw IOException("NIVUKX video track $index has no colour descriptor")
        val masteringDisplay = if (descriptor.hdrStaticInfo.isEmpty()) {
            null
        } else {
            HdrStaticMetadataPolicy.parseHdr10StaticInfo(descriptor.hdrStaticInfo)
                ?: throw IOException("NIVUKX HDR static metadata is malformed")
        }
        return QuickTimeColorAtomConfig(
            colorPrimaries = descriptor.colorPrimaries,
            transferCharacteristics = descriptor.transferCharacteristics,
            matrixCoefficients = descriptor.matrixCoefficients,
            fullRange = descriptor.fullRange,
            masteringDisplay = masteringDisplay
        )
    }
}
