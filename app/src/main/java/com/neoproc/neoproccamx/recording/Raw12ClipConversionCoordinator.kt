package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.niv99.Niv99OfflineConversionPolicy
import java.io.File
import java.io.InputStream

/**
 * Raw12ClipConversionCoordinator — drives a full offline RAW clip → 10-bit NeoLog .MOV conversion:
 * reads the develop.meta sidecar, streams `stream.npraw` through [RawToNeoLogMovConverter.convertStream],
 * and muxes each developed 10-bit sample into a QuickTime MOV via [QuickTimeMovFileWriter] (v210 4:2:2
 * or r210 4:4:4). Every uncompressed frame is a sync sample.
 *
 * [QuickTimeMovFileWriter] is pure RandomAccessFile, so this whole convert is JVM-testable end-to-end
 * (it produces a real .mov whose box structure can be asserted). The remaining device-runtime concerns
 * are: on-disk/MediaStore file access, MediaStore album publish, and MOV playback validation in NLEs
 * (the r210 sample-entry / colr tagging in particular should be checked on a device).
 */
class Raw12ClipConversionCoordinator(
    private val converter: RawToNeoLogMovConverter = RawToNeoLogMovConverter()
) {

    companion object {
        private const val CONTAINER_LABEL_PREFIX = "NeoProCamX RAW develop"
        private const val SYNC_SAMPLE_FLAG = 1 // every uncompressed frame is a keyframe
    }

    /**
     * Convert a clip's decoded sidecar + open NPRAW stream into [outputPath] (.mov).
     *
     * [parallelism] > 1 develops frames across that many CPU worker threads (byte-identical output, only
     * faster); [onFrame] fires once per developed frame (in order) with the running count, so the caller
     * can drive a progress bar / ETA.
     */
    fun convert(
        streamInput: InputStream,
        meta: RawDevelopMetadata,
        options: RawToNeoLogMovConverter.Options,
        outputPath: String,
        parallelism: Int = 1,
        onFrame: ((framesDone: Int) -> Unit)? = null,
        viewingLutAnchorPath: String = outputPath,
        outputOwnership: MovOutputOwnership = MovOutputOwnership.OWNED_REGULAR_FILE,
        expectedFrameCount: Long? = null,
        audioSidecarMaster: File? = null
    ): RawToNeoLogMovConverter.ConvertResult {
        val outputFile = File(outputPath)
        val audioSidecar = audioSidecarMaster?.let(Npv10AudioSidecarPolicy::sidecarFor)
        check(!meta.audioExpected || audioSidecar?.isFile == true) {
            "Required RAW microphone sidecar is missing; refusing silent conversion"
        }
        require(audioSidecar == null || audioSidecar.canonicalFile != outputFile.canonicalFile) {
            "RAW conversion output must not overwrite its source audio"
        }
        val niv99Plan = Niv99OfflineConversionPolicy.forRawDelivery(options.deliveryProfile.name)
        if (options.niv99Enabled) {
            niv99Plan.requireFrameContract(
                sourceWidth = meta.width,
                sourceHeight = meta.height,
                outputWidth = meta.width,
                outputHeight = meta.height,
                sourceFps = meta.fps.coerceAtLeast(1),
                outputFps = meta.fps.coerceAtLeast(1),
                outputBitDepth = 10
            )
        }
        val plannedViewingLut = NeoLogRec709LutWriter.sidecarFile(viewingLutAnchorPath)
        val plannedQualityCertificate = File("$viewingLutAnchorPath.elite-conversion-certificate.txt")
        val transactionArtifacts = buildList {
            add(plannedViewingLut)
            add(plannedQualityCertificate)
            if (outputOwnership.converterMayDeleteOutput) add(outputFile)
        }.distinctBy { it.absoluteFile.normalize().path }
        var generatedViewingLut: File? = null
        var minimumBitrateBps: Long? = null
        var configuredBitrateBps: Long? = null
        var policyTargetBitrateBps: Long? = null
        var advertisedBitrateCeilingBps: Long? = null
        var encoderCapabilityBound: Boolean? = null
        var advancedEncoderControlsAccepted: Boolean? = null
        var audioMuxAudit: Npv10AudioMuxAudit? = null
        val presentationTimeline = RawConversionPresentationTimeline()
        var openWriter: QuickTimeMovFileWriter? = null
        try {
            deleteStaleArtifactsStrict(transactionArtifacts)
            val writer = QuickTimeMovFileWriter(
                outputPath = outputPath,
                containerLabel = "$CONTAINER_LABEL_PREFIX ${options.deliveryProfile.displayName}",
                preallocationPlan = RecordingPreallocationPolicy.offlineConversionOutput(
                    output = outputFile,
                    estimatedPayloadBytes = expectedUncompressedPayloadBytes(
                        meta = meta,
                        options = options,
                        expectedFrameCount = expectedFrameCount
                    )
                )
            )
            openWriter = writer
            writer.setOrientationHint(meta.orientationHintDegrees)
            val fps = meta.fps.coerceAtLeast(1)
            val frameDurationUs = (1_000_000L / fps).coerceAtLeast(1L)
            val result = if (options.format == RawToNeoLogMovConverter.OutputFormat.HEVC_MAIN10_420) {
                val workloadTelemetry = RawConversionStageTelemetry(
                    route = "${options.deliveryProfile.name}:${meta.width}x${meta.height}@${fps}"
                )
                RecordDevelopHevcMovEncoder(
                    writer = writer,
                    width = meta.width,
                    height = meta.height,
                    fps = fps,
                    colorConfig = colorConfigFor(options),
                    deliveryFormat = RecordDevelopDeliveryFormat.HEVC_MAIN10_420_MOV
                ).use { encoder ->
                    check(encoder.advancedQualityControlsAccepted) {
                        "RAW HEVC publication requires encoder acceptance of every elite quality control"
                    }
                    minimumBitrateBps = encoder.minimumViableBitrateBps
                    configuredBitrateBps = encoder.configuredBitrateBps
                    policyTargetBitrateBps = encoder.policyTargetBitrateBps
                    advertisedBitrateCeilingBps = encoder.advertisedBitrateCeilingBps
                    encoderCapabilityBound = encoder.capabilityBound
                    val sink: (java.nio.ByteBuffer, Int, Long) -> Unit = { p010, index, sourceTimestampNs ->
                        encoder.encodeFrameDirect(
                            p010,
                            presentationTimeline.normalize(index, sourceTimestampNs),
                            endOfStream = false
                        )
                    }
                    val converted = try {
                        converter.convertStreamDirectP010Timed(
                            input = streamInput,
                            meta = meta,
                            options = options,
                            telemetry = workloadTelemetry,
                            onFrame = onFrame,
                            sink = sink
                        ).also {
                            if (it.framesConverted > 0) {
                                val finishStarted = System.nanoTime()
                                encoder.sealDirectInput()
                                encoder.finish(presentationTimeline.endPresentationTimeUs(frameDurationUs))
                                workloadTelemetry.addEncoder(
                                    (System.nanoTime() - finishStarted).coerceAtLeast(0L)
                                )
                            }
                        }
                    } finally {
                        workloadTelemetry.logFinal()
                    }
                    advancedEncoderControlsAccepted = encoder.advancedQualityControlsAccepted
                    converted
                }
            } else {
                val trackIndex = writer.addVideoTrack(
                    QuickTimeVideoTrackConfig(
                        mime = options.format.movMime,
                        width = meta.width,
                        height = meta.height,
                        fps = fps,
                        codecConfig = ByteArray(0), // uncompressed: no codec-specific config
                        colorConfig = colorConfigFor(options)
                    )
                )
                val sink: (ByteArray, Int, Long) -> Unit = { sample, index, sourceTimestampNs ->
                    writer.writeSample(
                        trackIndex,
                        QuickTimeSampleBuffer(
                            bytes = sample,
                            presentationTimeUs = presentationTimeline.normalize(index, sourceTimestampNs),
                            flags = SYNC_SAMPLE_FLAG
                        )
                    )
                }
                val converted = if (parallelism > 1) {
                    converter.convertStreamParallelTimed(
                        streamInput,
                        meta,
                        options,
                        parallelism,
                        onFrame,
                        sink
                    )
                } else {
                    converter.convertStreamTimed(streamInput, meta, options) { sample, index, sourceTimestampNs ->
                        sink(sample, index, sourceTimestampNs)
                        onFrame?.invoke(index + 1)
                    }
                }
                if (converted.framesConverted > 0) {
                    writer.setTrackEndPresentationTimeUs(
                        trackIndex,
                        presentationTimeline.endPresentationTimeUs(frameDurationUs)
                    )
                }
                converted
            }
            require(result.framesConverted > 0) { "Cannot convert an empty RAW clip" }
            if (audioSidecar?.isFile == true) {
                val audioMaster = requireNotNull(audioSidecarMaster)
                Npv10AudioSidecarReader(audioMaster).use { reader ->
                    check(reader.header.videoOriginSensorTimestampNs ==
                        presentationTimeline.sourceOriginTimestampNs) {
                        "RAW audio belongs to a different recorded sensor timeline"
                    }
                }
                val audit = Npv10AudioSidecarMuxer.muxInto(audioMaster, writer, audioExpected = true)
                val expectedAudioEndUs = presentationTimeline.endPresentationTimeUs(frameDurationUs)
                check(audit.present && audit.crcValidated && audit.monotonicPts &&
                    kotlin.math.abs(audit.finalPresentationTimeUs - expectedAudioEndUs) <=
                        Npv10AudioVideoDurationPolicy.MAXIMUM_ALLOWED_DELTA_US) {
                    "RAW conversion audio does not match its source video timeline: $audit"
                }
                audioMuxAudit = audit
            }
            if (options.niv99Enabled) {
                val pixelEvidence = requireNotNull(result.niv99Evidence) {
                    "RAW conversion completed without NIV99 pixel evidence"
                }
                check(pixelEvidence.processedFrames == result.framesConverted.toLong()) {
                    "RAW NIV99 frame mismatch processed=${pixelEvidence.processedFrames} " +
                        "converted=${result.framesConverted}"
                }
            }
            // Ship the professional companion viewing LUT next to the log master so the NeoLog(ACEScg)
            // .mov reads correctly in an NLE (drop the .cube on the clip). The master is never modified.
            if (options.deliveryProfile == RawToNeoLogMovConverter.DeliveryProfile.NEOLOG) {
                generatedViewingLut = NeoLogRec709LutWriter.writeSidecar(
                    viewingLutAnchorPath,
                    options.variant
                ) ?: error("Failed to create the required NeoLog ACEScg-to-Rec.709 companion LUT")
            }
            val qualityAudit = EliteConversionQualityEvidenceFactory.enforceRawDevelop(
                meta = meta,
                options = options,
                framesConverted = result.framesConverted.toLong(),
                containerColor = colorConfigFor(options),
                companionLutPresent = generatedViewingLut?.isFile == true,
                minimumBitrateBps = minimumBitrateBps,
                configuredBitrateBps = configuredBitrateBps,
                policyTargetBitrateBps = policyTargetBitrateBps,
                advertisedBitrateCeilingBps = advertisedBitrateCeilingBps,
                encoderCapabilityBound = encoderCapabilityBound,
                advancedEncoderControlsAccepted = advancedEncoderControlsAccepted
            )
            // Finalize first; publication evidence must come from the immutable on-disk MOV, never from
            // requested settings or an open writer's in-memory state.
            writer.close()
            RecordDevelopMovPublicationGate.publishFinalized(
                output = outputFile,
                certificateFile = plannedQualityCertificate,
                expectation = RecordDevelopMovPublicationExpectation(
                    sampleEntry = sampleEntryFor(options.format),
                    width = meta.width,
                    height = meta.height,
                    rotationDegrees = meta.orientationHintDegrees,
                    color = colorConfigFor(options).toMovNclxExpectation(),
                    frameCount = result.framesConverted.toLong(),
                    expectedDurationUs = presentationTimeline.endPresentationTimeUs(frameDurationUs),
                    expectedAverageFps = null,
                    // KEY_BIT_RATE is a codec control, not a mandatory encoded-payload size. A short
                    // or spatially simple clip can be represented far below that rate without losing
                    // frames or exceeding the enforced QP ceiling. The finalized certificate retains
                    // the independently measured rate; configured/floor/ceiling admission is enforced
                    // above, while frame, timing, Main10 and decoded-image integrity are verified by
                    // the artifact gate and LQ-031 decode oracle.
                    minimumMeasuredVideoBitrateBps = null
                ),
                declaredPipelineAuditText = linkedMapOf<String, String>().apply {
                    putAll(qualityAudit.toMetadata())
                    putAll(niv99Plan.evidence())
                    put("niv99OfflineEnabled", options.niv99Enabled.toString())
                    put("rawAudioExpected", meta.audioExpected.toString())
                    put("rawAudioPresent", (audioMuxAudit?.present == true).toString())
                    audioMuxAudit?.let { audio ->
                        put("rawAudioPackets", audio.packetCount.toString())
                        put("rawAudioEndUs", audio.finalPresentationTimeUs.toString())
                        put("rawAudioOriginalPacketsPreserved", audio.originalPacketsPreserved.toString())
                    }
                    result.niv99Evidence?.metadata()?.let(::putAll)
                }.toSortedMap()
                    .entries
                    .joinToString(separator = "\n") { (key, value) -> "$key=$value" },
                outputOwnership = outputOwnership
            )
            return result
        } catch (t: Throwable) {
            // #21 fix: on failure abort() (close the file WITHOUT writing the moov, so no truncated master
            // is left) and skip the companion LUT; then rethrow so the caller reports a genuine failure.
            runCatching { openWriter?.abort() }
                .exceptionOrNull()
                ?.let(t::addSuppressed)
            cleanupFailedArtifacts(transactionArtifacts, t)
            throw t
        }
    }

    /** Convenience: convert a [Raw12Clip] from disk (reads develop.meta, streams stream.npraw). */
    fun convert(
        clip: Raw12Clip,
        options: RawToNeoLogMovConverter.Options,
        outputPath: String,
        parallelism: Int = 1,
        onFrame: ((framesDone: Int) -> Unit)? = null,
        viewingLutAnchorPath: String = outputPath,
        outputOwnership: MovOutputOwnership = MovOutputOwnership.OWNED_REGULAR_FILE,
        expectedFrameCount: Long? = null
    ): RawToNeoLogMovConverter.ConvertResult {
        require(clip.streamFile.canonicalFile != File(outputPath).canonicalFile) {
            "RAW conversion output must not overwrite the NPRAW source stream"
        }
        require(clip.metaFile.canonicalFile != File(outputPath).canonicalFile) {
            "RAW conversion output must not overwrite the develop metadata sidecar"
        }
        val meta = RawDevelopMetadataSidecar.decode(clip.metaFile.readBytes())
        return clip.streamFile.inputStream().buffered().use { input ->
            convert(
                input,
                meta,
                options,
                outputPath,
                parallelism,
                onFrame,
                viewingLutAnchorPath,
                outputOwnership,
                expectedFrameCount ?: exactFrameCount(clip),
                audioSidecarMaster = clip.streamFile
            )
        }
    }

    private fun expectedUncompressedPayloadBytes(
        meta: RawDevelopMetadata,
        options: RawToNeoLogMovConverter.Options,
        expectedFrameCount: Long?
    ): Long? {
        val frameCount = expectedFrameCount?.takeIf { it > 0L } ?: return null
        val packerFormat = options.format.packerFormat ?: return null
        return Math.multiplyExact(
            RawTenBitPacker.payloadSize(packerFormat, meta.width, meta.height).toLong(),
            frameCount
        )
    }

    private fun exactFrameCount(clip: Raw12Clip): Long? {
        val fixed = clip.streamFile.inputStream().buffered().use { input ->
            com.neoproc.neoproccamx.pipeline.NprawFrameCodec.estimateFixedFrameCount(
                input,
                clip.streamFile.length()
            )
        }
        return (fixed ?: clip.streamFile.inputStream().buffered().use(
            com.neoproc.neoproccamx.pipeline.NprawFrameCodec::countFrames
        )).toLong().takeIf { it > 0L }
    }

    private fun deleteStaleArtifactsStrict(artifacts: List<File>) {
        artifacts.forEach { artifact ->
            if (artifact.exists()) {
                check(artifact.isFile) {
                    "Conversion artifact path is not a regular file: ${artifact.absolutePath}"
                }
                check(artifact.delete() && !artifact.exists()) {
                    "Failed to remove stale conversion artifact ${artifact.absolutePath}"
                }
            }
        }
    }

    private fun cleanupFailedArtifacts(artifacts: List<File>, failure: Throwable) {
        artifacts.forEach { artifact ->
            if (artifact.exists() && (!artifact.isFile || !artifact.delete() || artifact.exists())) {
                failure.addSuppressed(
                    IllegalStateException(
                        "Failed to remove partial conversion artifact ${artifact.absolutePath}"
                    )
                )
            }
        }
    }

    // NeoLog is tagged BT.2020 primaries + BT.709 transfer (matches the NPV10 log color-tag policy).
    // v210 is Y'CbCr (limited range, BT.2020 NCL matrix); r210 is RGB (full range, RGB matrix).
    // The exact RGB tagging for r210 is device-verified against target NLEs.
    internal fun colorConfigFor(options: RawToNeoLogMovConverter.Options): QuickTimeColorAtomConfig {
        val profile = options.deliveryProfile
        if (profile != RawToNeoLogMovConverter.DeliveryProfile.NEOLOG) {
            val mode = requireNotNull(profile.ndrMode)
            val staticMode = when (profile) {
                RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS ->
                    HdrStaticMetadataPolicy.StaticMetadataMode.HDR10_PLUS
                RawToNeoLogMovConverter.DeliveryProfile.NIVUKX_VISION ->
                    HdrStaticMetadataPolicy.StaticMetadataMode.CERTIFIED_VENDOR_FALLBACK
                RawToNeoLogMovConverter.DeliveryProfile.NEOLOG -> error("unreachable")
            }
            val masteringDisplay = HdrStaticMetadataPolicy.resolveMasteringDisplay(
                staticMode,
                HdrStaticMetadataPolicy.SceneMetadata.DEFAULT.copy(
                    masteringPeakNits = com.neoproc.neoproccamx.color.NdrNivukxDeliveryEngine
                        .targetPeakNits(mode)
                )
            )
            return QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                matrixCoefficients = if (options.format == RawToNeoLogMovConverter.OutputFormat.MASTER_444) {
                    QuickTimeColorAtomPolicy.MATRIX_RGB_IDENTITY
                } else {
                    QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL
                },
                fullRange = options.format == RawToNeoLogMovConverter.OutputFormat.MASTER_444,
                masteringDisplay = masteringDisplay
            )
        }
        return when (options.format) {
            RawToNeoLogMovConverter.OutputFormat.MASTER_444 -> QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT709,
                fullRange = true
            )
            RawToNeoLogMovConverter.OutputFormat.COMPAT_V210 -> QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = false
            )
            RawToNeoLogMovConverter.OutputFormat.HEVC_MAIN10_420 -> QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = false
            )
        }
    }

    private fun sampleEntryFor(format: RawToNeoLogMovConverter.OutputFormat): String = when (format) {
        RawToNeoLogMovConverter.OutputFormat.MASTER_444 -> "r210"
        RawToNeoLogMovConverter.OutputFormat.COMPAT_V210 -> "v210"
        RawToNeoLogMovConverter.OutputFormat.HEVC_MAIN10_420 -> "hvc1"
    }

    private fun QuickTimeColorAtomConfig.toMovNclxExpectation(): QuickTimeMovNclxExpectation =
        QuickTimeMovNclxExpectation(
            colorPrimaries = colorPrimaries,
            transferCharacteristics = transferCharacteristics,
            matrixCoefficients = matrixCoefficients,
            fullRange = fullRange
        )

    /** Where a converted clip's .mov should be staged before album publish (mirrors NPV10). */
    fun defaultOutputFile(clip: Raw12Clip, cacheDir: File, format: RawToNeoLogMovConverter.OutputFormat): File {
        return File(cacheDir, "${clip.name}_neolog_${format.fileSuffix}.mov")
    }

    fun defaultOutputFile(clip: Raw12Clip, cacheDir: File, options: RawToNeoLogMovConverter.Options): File =
        File(
            cacheDir,
            "${clip.name}_${options.deliveryProfile.fileSuffix}_${options.format.fileSuffix}.mov"
        )
}
