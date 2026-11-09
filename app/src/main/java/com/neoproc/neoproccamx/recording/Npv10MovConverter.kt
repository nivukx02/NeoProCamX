package com.neoproc.neoproccamx.recording

import android.os.SystemClock
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.niv99.Niv99OfflineConversionPlan
import com.neoproc.neoproccamx.niv99.Niv99OfflineConversionPolicy
import com.neoproc.neoproccamx.niv99.Niv99OfflineWhiteBalanceAuthorityPolicy
import com.neoproc.neoproccamx.pipeline.SceneRenderProfile
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics
import com.neoproc.neoproccamx.color.NormalSdrVideoQualityPolicy
import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.rendering.GpuActiveTransport
import com.neoproc.neoproccamx.rendering.GpuImageEnhancementUniforms
import com.neoproc.neoproccamx.rendering.GpuSignalFamily
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

object Npv10MovConverter {
    const val MOV_EXTENSION = "mov"
    private const val SOURCE_FORMAT_P010 = "YCBCR_P010"
    private const val PIPELINE_TAG = "Npv10ExportPipeline"
    private const val NIV99_COLOR_EFFECT_EPSILON = 0.000_001f

    enum class OutputFormat(
        val uiLabel: String,
        val movMime: String,
        val sampleEntry: String,
        val fileSuffix: String
    ) {
        R210_444(
            uiLabel = "4:4:4 RGB master (r210)",
            movMime = "video/r210",
            sampleEntry = "r210",
            fileSuffix = "r210_444"
        ),
        V210_422(
            uiLabel = "4:2:2 NLE master (v210)",
            movMime = "video/v210",
            sampleEntry = "v210",
            fileSuffix = "v210_422"
        ),
        HEVC_MAIN10_420(
            uiLabel = "HEVC Main10 10-bit 4:2:0",
            movMime = "video/hevc",
            sampleEntry = "hvc1",
            fileSuffix = "hevc_main10_420"
        )
    }

    fun defaultOutputFile(
        input: File,
        outputDirectory: File,
        outputFormat: OutputFormat = OutputFormat.V210_422
    ): File {
        outputDirectory.mkdirs()
        return File(
            outputDirectory,
            "${input.nameWithoutExtension}_${outputFormat.fileSuffix}.$MOV_EXTENSION"
        )
    }

    fun convert(
        input: File,
        output: File,
        gradeLut: Pair<FloatArray, Int>? = null,
        // Fires once per converted frame (in order) with the running count, so a caller can drive a
        // progress bar / ETA. Null keeps the silent behavior.
        onFrame: ((framesDone: Int) -> Unit)? = null,
        outputFormat: OutputFormat = OutputFormat.V210_422
    ): Npv10MovConversionAudit {
        val audit = convertWithAudit(input, output, gradeLut, onFrame, outputFormat)
        writeMovCertificateSidecar(output, audit)
        return audit
    }

    /**
     * Estimate the EXACT frame count of an NPV10 master from its on-disk geometry (fixed-size frames),
     * without scanning — so a caller can size a determinate progress bar before conversion starts.
     * Returns 0 when the file cannot be read or is malformed (caller falls back to indeterminate).
     */
    fun estimateFrameCount(input: File): Int =
        runCatching {
            Npv10FileReader(input).use { reader ->
                requireExactFrameCount(input, reader)
            }
        }.getOrDefault(0)

    private fun requireExactFrameCount(input: File, reader: Npv10FileReader): Int {
        val header = reader.header
        if (header.packetCodec == Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2) {
            // NLC2 packets are independently sized. The reader validates each packet envelope while
            // building an exact header-only index and restores its original stream position.
            return reader.countFramesForPlayback()
        }
        val payloadBytes = Npv10ContainerPolicy.estimatedStoredBytesPerFrame(
            header.width,
            header.height,
            header.payloadFormat,
            header.packetCodec
        )
        val frameStride = Math.addExact(
            Npv10ContainerPolicy.FRAME_MAGIC_BYTES.size.toLong(),
            Math.addExact(NPV10_FRAME_HEADER_FIXED_BYTES, payloadBytes)
        )
        val headerBytes = Math.addExact(
            Npv10ContainerPolicy.MAGIC_BYTES.size.toLong(),
            Math.addExact(
                NPV10_FILE_HEADER_FIXED_BYTES,
                header.metadata.toByteArray(Charsets.UTF_8).size.toLong()
            )
        )
        val bodyBytes = Math.subtractExact(input.length(), headerBytes)
        require(frameStride > 0L && bodyBytes >= 0L) {
            "Malformed NPV10 geometry: file=${input.length()} header=$headerBytes stride=$frameStride"
        }
        require(bodyBytes % frameStride == 0L) {
            "Malformed NPV10 geometry: body=$bodyBytes is not an exact multiple of frameStride=$frameStride"
        }
        val frameCount = bodyBytes / frameStride
        require(frameCount <= Int.MAX_VALUE.toLong()) {
            "NPV10 frame count exceeds supported conversion range: $frameCount"
        }
        return frameCount.toInt()
    }

    fun convertWithAudit(
        input: File,
        output: File,
        // P4 Phase A: live ColorScience preview grade (display-referred R'G'B' cube). Null = today's
        // behavior (SDR gate applied inside; LOG/PQ/HLG never graded).
        gradeLut: Pair<FloatArray, Int>? = null,
        onFrame: ((framesDone: Int) -> Unit)? = null,
        outputFormat: OutputFormat = OutputFormat.V210_422
    ): Npv10MovConversionAudit {
        output.parentFile?.mkdirs()
        var convertedFrameCount = 0L
        var sourcePayloadFormat = Npv10PayloadFormat.YUV420P10_LE
        var temporalThresholdFilterApplied = false
        var chromaSuppressionApplied = false
        var shadowChromaDeadbandApplied = false
        var localChromaSpikeSmoothingApplied = false
        var edgeDetailPolicyApplied = false
        var accumulatedNoiseMetrics = VideoNoiseMetrics.EMPTY
        var measuredMetricFrames = 0
        var colorTagPlan = Npv10MovColorTagPolicy.rec709()
        var niv99Plan: Niv99OfflineConversionPlan = Niv99OfflineConversionPolicy.npv10Sdr()
        var niv99PixelEvidence: Niv99OfflineP010Evidence? = null
        var niv99WhiteBalanceAuthority =
            Niv99OfflineWhiteBalanceAuthorityPolicy.Authority.UNKNOWN_CAPTURE_PRESERVED
        var exportProcessingEvidence = Npv10ExportProcessingEvidence.cpuFallback("")
        var selectedDetailProfile = "LEGACY_UNSPECIFIED"
        var audioMuxAudit = Npv10AudioMuxAudit(expected = false, present = false)
        val exportSubjectMaskAvailable = false
        val exportSubjectDetailApplied = false
        var detailProcessingPath = "LEGACY_GLOBAL_EDGE_DETAIL"
        var sourceFormat = "UNKNOWN"
        var sourceBitDepth = 0
        var storageBitDepth = 0
        var inputContainerVersion = 0
        val inputMasterBytesBefore = input.length()
        val inputMasterSha256Before = input.sha256Hex()
        Npv10FileReader(input).use { reader ->
            val header = reader.header
            sourcePayloadFormat = header.payloadFormat
            sourceFormat = header.sourceFormat
            sourceBitDepth = header.sourceBitDepth
            storageBitDepth = header.storageBitDepth
            inputContainerVersion = header.version
            colorTagPlan = Npv10MovColorTagPolicy.resolve(header)
            validateMovConvertible(header)
            niv99Plan = Niv99OfflineConversionPolicy.npv10(colorTagPlan.activeTransport)
            niv99WhiteBalanceAuthority =
                Niv99OfflineWhiteBalanceAuthorityPolicy.resolve(header.metadataValues)
            val residualWhiteBalanceAllowed =
                niv99WhiteBalanceAuthority ==
                    Niv99OfflineWhiteBalanceAuthorityPolicy.Authority.AUTOMATIC_REFINEMENT_ALLOWED
            niv99Plan.requireFrameContract(
                sourceWidth = header.width,
                sourceHeight = header.height,
                outputWidth = header.width,
                outputHeight = header.height,
                sourceFps = header.fps,
                outputFps = header.fps,
                outputBitDepth = 10
            )
            // The current v210/HEVC paths copy P010 codes without a proven range transform. Reject
            // legacy full-range masters before creating an output instead of relabelling them limited.
            val outputColorConfig = colorConfigForOutput(colorTagPlan.colorConfig, outputFormat)
            val expectedFrameCount = requireExactFrameCount(input, reader)
            val niv99PixelProcessor = Niv99OfflineP010PixelProcessor(
                plan = niv99Plan,
                width = header.width,
                height = header.height,
                fps = header.fps,
                expectedFrames = expectedFrameCount.toLong()
            )
            if (outputFormat == OutputFormat.HEVC_MAIN10_420) {
                // Prove the requested strict HEVC transport before creating a writer or launching any
                // export workers. Explicit R210/V210 requests bypass this gate and are never selected
                // as an implicit substitute for a rejected HEVC request.
                RecordDevelopHevcMovEncoder.requireStrictCapability(
                    width = header.width,
                    height = header.height,
                    fps = header.fps
                )
            }
            val writer = QuickTimeMovFileWriter(
                outputPath = output.absolutePath,
                containerLabel = "NPV10 P010 Master",
                preallocationPlan = RecordingPreallocationPolicy.offlineConversionOutput(
                    output = output,
                    estimatedPayloadBytes = when (outputFormat) {
                        OutputFormat.HEVC_MAIN10_420 -> null
                        OutputFormat.R210_444,
                        OutputFormat.V210_422 -> Math.multiplyExact(
                            DirectP010MovPayloadConverter.requiredOutputBytes(
                                header.width,
                                header.height,
                                outputFormat
                            ).toLong(),
                            expectedFrameCount.toLong()
                        )
                    }
                ),
                preserveTrackPresentationOffsets =
                    Npv10AudioSidecarPolicy.sidecarFor(input).isFile ||
                        header.metadataValues["audiostoredinnpv10bundle"].toBooleanLenient()
            )
            var qualityInstructions: NeoQualityInstructionSession? = null
            try {
                writer.setOrientationHint(header.orientationHintDegrees)
                val track = if (outputFormat == OutputFormat.HEVC_MAIN10_420) {
                    -1
                } else {
                    writer.addVideoTrack(
                        QuickTimeVideoTrackConfig(
                            mime = outputFormat.movMime,
                            width = header.width,
                            height = header.height,
                            fps = header.fps,
                            codecConfig = ByteArray(0),
                            colorConfig = outputColorConfig
                        )
                    )
                }
                val requestedDetailProfile = header.metadataValues["selecteddetailprofile"]
                    .toPersistedDetailProfileOrNull()
                val resolvedDetailProfile = requestedDetailProfile ?: VideoDetailProfile.NATURAL_DETAIL
                selectedDetailProfile = resolvedDetailProfile.name
                val recordingMode = header.metadataValues["recordingmode"]
                    ?: header.metadataValues["capturemode"]
                    ?: header.metadataValues["mode"]
                val sdrQualityIntent = recordingMode.toSdrQualityIntentOrNull()
                val baseEnhancementProfile = Npv10ExportEnhancementProfile.fromColorTagPlan(
                    plan = colorTagPlan,
                    recordingMode = recordingMode
                )
                val enhancementProfile = when (requestedDetailProfile) {
                    VideoDetailProfile.EXTREME_DETAIL -> {
                        detailProcessingPath = "NPV10_SCENE_ADAPTIVE_LIVE_CINEMA_EXTREME"
                        baseEnhancementProfile.withoutAdditiveDetail()
                    }
                    VideoDetailProfile.FOCUSED_DETAIL,
                    VideoDetailProfile.NATURAL_DETAIL -> {
                        detailProcessingPath = "NPV10_SCENE_ADAPTIVE_LIVE_CINEMA"
                        baseEnhancementProfile.withoutAdditiveDetail()
                    }
                    null -> {
                        detailProcessingPath = "NPV10_SCENE_ADAPTIVE_LIVE_CINEMA_DEFAULT"
                        baseEnhancementProfile.withoutAdditiveDetail()
                    }
                }
                val signalFamily = signalFamilyFor(colorTagPlan)
                val exportUniforms = buildExportUniforms(
                    enhancementProfile = enhancementProfile,
                    colorTagPlan = colorTagPlan,
                    sdrQualityIntent = sdrQualityIntent
                )
                exportProcessingEvidence = Npv10ExportProcessingEvidence.cpuFallback(
                    signature = exportUniforms.signature,
                    reason = "NIV99_NATIVE_SIMD_NOT_SELECTED"
                )
                chromaSuppressionApplied = hasChromaPlane(header.width, header.height)
                shadowChromaDeadbandApplied = chromaSuppressionApplied
                localChromaSpikeSmoothingApplied = chromaSampleCount(header.width, header.height) > 1
                edgeDetailPolicyApplied = header.width >= 3 && header.height >= 3
                // P4 Phase A: apply the live preview grade only for SDR exports (LOG/PQ/HLG excluded so
                // NDR/NIVUKX PQ delivery and flat log are never double-toned), mirroring the signalFamily
                // gate in buildExportUniforms.
                val activeGradeLut = gradeLut?.takeIf {
                    val transport = colorTagPlan.activeTransport
                    !transport.contains("LOG", ignoreCase = true) &&
                        !transport.contains("PQ", ignoreCase = true) &&
                        !transport.contains("HLG", ignoreCase = true)
                }
                // GPU-sequential path (native Vulkan accelerator + per-frame SNCE nudge) is used whenever
                // the accelerator is actually available. SDR grading is a second Vulkan compute pass, so
                // selecting a creative LUT no longer sends the complete 4K conversion back to the CPU;
                // that path is order-sensitive (SNCE carries live per-frame state) so it stays on the
                // single "npv10-gpu" thread. Every other case — no native accelerator (e.g. vivo) or an
                // SDR grade LUT — runs the byte-identical CPU enhancer, which is embarrassingly parallel
                // (each frame is a pure function of its source + the previous source), so it is fanned
                // out across CPU cores. Output is identical either way (no-downgrade); only speed differs.
                val useGpuSequential =
                    header.payloadFormat == Npv10PayloadFormat.P010_LE &&
                        Npv10ExportGpuAccelerator.isAccelerationAvailable()
                // SNCE v2 HVC per-frame nudge reaches export pixels only for non-PQ deliveries with no SDR
                // grade LUT (a grade re-derives chroma) and only when the engine flag is on. When true BOTH
                // lanes apply the SAME per-frame nudge (GPU via the accelerator, CPU via enhanceP010), so a
                // clip's GPU-accelerated and CPU-developed frames carry identical colour. The GPU shader
                // ignores sat/warmth on its PQ branch — this gate mirrors that on the CPU side.
                val snceChromaEnabled = com.neoproc.neoproccamx.snce.SnceColorEngineFlag.enabled &&
                    activeGradeLut == null &&
                    !colorTagPlan.activeTransport.contains("PQ", ignoreCase = true)
                qualityInstructions = NeoQualityInstructionCache.open(
                    source = input,
                    fingerprint = NeoQualityInstructionCache.Fingerprint(
                        sourceLength = inputMasterBytesBefore,
                        sourceLastModified = input.lastModified(),
                        sourceSha256 = inputMasterSha256Before,
                        width = header.width,
                        height = header.height,
                        signalFamily = signalFamily,
                        detailProfile = resolvedDetailProfile,
                        snceChromaEnabled = snceChromaEnabled,
                        frameCount = expectedFrameCount
                    )
                )
                val asyncExport = if (outputFormat == OutputFormat.HEVC_MAIN10_420 && !useGpuSequential) {
                    // HEVC still requires serialized MediaCodec submission, but its expensive scene
                    // analysis + quality-preserving P010 develop is the same pure per-frame work used
                    // by the uncompressed CPU path. Develop those frames in parallel and feed the
                    // strict Main10 encoder in index order; pixels and audit evidence stay identical.
                    runParallelCpuExport(
                        reader = reader,
                        writer = writer,
                        track = -1,
                        header = header,
                        enhancementProfile = enhancementProfile,
                        exportUniforms = exportUniforms,
                        gradeLut = activeGradeLut,
                        snceChromaEnabled = snceChromaEnabled,
                        detailProfile = resolvedDetailProfile,
                        signalFamily = signalFamily,
                        qualityInstructions = requireNotNull(qualityInstructions),
                        outputFormat = outputFormat,
                        hevcColorConfig = outputColorConfig,
                        expectedFrameCount = expectedFrameCount,
                        niv99PixelProcessor = niv99PixelProcessor,
                        residualWhiteBalanceAllowed = residualWhiteBalanceAllowed,
                        onFrame = onFrame
                    )
                } else if (useGpuSequential && header.payloadFormat == Npv10PayloadFormat.P010_LE) {
                    runBoundedDirectGpuExport(
                        reader = reader,
                        writer = writer,
                        track = track,
                        header = header,
                        enhancementProfile = enhancementProfile,
                        exportUniforms = exportUniforms,
                        gradeLut = activeGradeLut,
                        snceChromaEnabled = snceChromaEnabled,
                        detailProfile = resolvedDetailProfile,
                        signalFamily = signalFamily,
                        qualityInstructions = requireNotNull(qualityInstructions),
                        outputFormat = outputFormat,
                        hevcColorConfig = outputColorConfig,
                        expectedFrameCount = expectedFrameCount,
                        niv99PixelProcessor = niv99PixelProcessor,
                        residualWhiteBalanceAllowed = residualWhiteBalanceAllowed,
                        onFrame = onFrame
                    )
                } else if (useGpuSequential) {
                    runTripleBufferedExport(
                        reader = reader,
                        writer = writer,
                        track = track,
                        header = header,
                        enhancementProfile = enhancementProfile,
                        exportUniforms = exportUniforms,
                        gradeLut = activeGradeLut,
                        snceChromaEnabled = snceChromaEnabled,
                        detailProfile = resolvedDetailProfile,
                        signalFamily = signalFamily,
                        qualityInstructions = requireNotNull(qualityInstructions),
                        outputFormat = outputFormat,
                        expectedFrameCount = expectedFrameCount,
                        niv99PixelProcessor = niv99PixelProcessor,
                        residualWhiteBalanceAllowed = residualWhiteBalanceAllowed,
                        onFrame = onFrame
                    )
                } else {
                    runParallelCpuExport(
                        reader = reader,
                        writer = writer,
                        track = track,
                        header = header,
                        enhancementProfile = enhancementProfile,
                        exportUniforms = exportUniforms,
                        gradeLut = activeGradeLut,
                        snceChromaEnabled = snceChromaEnabled,
                        detailProfile = resolvedDetailProfile,
                        signalFamily = signalFamily,
                        qualityInstructions = requireNotNull(qualityInstructions),
                        outputFormat = outputFormat,
                        expectedFrameCount = expectedFrameCount,
                        niv99PixelProcessor = niv99PixelProcessor,
                        residualWhiteBalanceAllowed = residualWhiteBalanceAllowed,
                        onFrame = onFrame
                    )
                }
                require(asyncExport.frameCount > 0L) { "Cannot convert empty NPV10 master without frames" }
                check(asyncExport.frameCount == expectedFrameCount.toLong()) {
                    "NPV10 conversion frame mismatch expected=$expectedFrameCount " +
                        "converted=${asyncExport.frameCount}"
                }
                // A cache write is an optional repeat-export speed-up. Failure never fails or alters the MOV.
                requireNotNull(qualityInstructions).commit(asyncExport.frameCount)
                convertedFrameCount = asyncExport.frameCount
                niv99PixelEvidence = niv99PixelProcessor.requireComplete(asyncExport.frameCount)
                accumulatedNoiseMetrics = asyncExport.accumulatedNoiseMetrics
                measuredMetricFrames = asyncExport.measuredMetricFrames
                temporalThresholdFilterApplied = asyncExport.temporalFilterApplied
                exportProcessingEvidence = asyncExport.processingEvidence
                audioMuxAudit = Npv10AudioSidecarMuxer.muxInto(
                    master = input,
                    writer = writer,
                    audioExpected = header.metadataValues["audiostoredinnpv10bundle"].toBooleanLenient()
                )
                writer.close()
            } catch (t: Throwable) {
                qualityInstructions?.abort()
                writer.abort()
                throw t
            }
        }
        val exportNoiseMetrics = accumulatedNoiseMetrics.dividedBy(measuredMetricFrames)
        val inputMasterBytesAfter = input.length()
        val inputMasterSha256After = input.sha256Hex()
        val baseAudit = Npv10IntegrityValidator.auditMovFile(
            output = output,
            frameCount = convertedFrameCount,
            payloadFormat = sourcePayloadFormat,
            sourceFormat = sourceFormat,
            sourceBitDepth = sourceBitDepth,
            storageBitDepth = storageBitDepth,
            inputContainerVersion = inputContainerVersion,
            inputMasterBytes = inputMasterBytesBefore,
            inputMasterSha256Before = inputMasterSha256Before,
            inputMasterSha256After = inputMasterSha256After,
            inputMasterHashUnchanged = inputMasterBytesBefore == inputMasterBytesAfter &&
                inputMasterSha256Before == inputMasterSha256After,
            movExportTranscodePolicy =
                "DERIVED_${outputFormat.sampleEntry.uppercase(Locale.US)}_MOV_EXPORT_PRIVATE_NPV10_MASTER_UNMODIFIED",
            expectedSampleEntry = outputFormat.sampleEntry,
            enhancementAppliedStage = "NPV10_EXPORT_ONLY",
            exportChromaSuppressionApplied = chromaSuppressionApplied,
            exportEdgeAwareDetailApplied = edgeDetailPolicyApplied,
            exportMotionCompensatedDenoiseApplied = temporalThresholdFilterApplied,
            exportTemporalChromaSmoothingApplied = temporalThresholdFilterApplied,
            exportTexturePreservationGuardApplied = edgeDetailPolicyApplied,
            exportAdaptiveDetailReconstructionApplied = edgeDetailPolicyApplied,
            exportLocalChromaSpikeSmoothingApplied = localChromaSpikeSmoothingApplied,
            exportAntiAliasEdgeProtectionApplied = edgeDetailPolicyApplied,
            exportShadowChromaDeadbandApplied = shadowChromaDeadbandApplied,
            exportMotionSafeTemporalFilterApplied = temporalThresholdFilterApplied,
            exportEdgeMicroContrastLiftApplied = edgeDetailPolicyApplied,
            exportFineTextureDetailBoostApplied = edgeDetailPolicyApplied,
            exportHaloSafeSharpeningLiftApplied = edgeDetailPolicyApplied,
            exportDetailReconstructionApplied = edgeDetailPolicyApplied,
            exportResolutionTruth = "PERCEPTUAL_DETAIL_ONLY_NATIVE_RESOLUTION_UNCHANGED",
            movColorPrimaries = colorTagPlan.colorConfig.colorPrimaries,
            movTransferCharacteristics = colorTagPlan.colorConfig.transferCharacteristics,
            movMatrixCoefficients = colorConfigForOutput(colorTagPlan.colorConfig, outputFormat).matrixCoefficients,
            movFullRange = colorConfigForOutput(colorTagPlan.colorConfig, outputFormat).fullRange,
            movSelectedProfile = colorTagPlan.selectedProfile,
            movSelectedColorSpace = colorTagPlan.selectedColorSpace,
            movActiveTransport = colorTagPlan.activeTransport,
            exportNoiseMetrics = exportNoiseMetrics,
            exportProcessingPath = exportProcessingEvidence.processingPath,
            exportZeroCopy = exportProcessingEvidence.zeroCopy,
            exportGpuFallbackReason = exportProcessingEvidence.gpuFallbackReason,
            exportGpuUniformSignature = exportProcessingEvidence.gpuUniformSignature,
            masterEnhancementDestructive = false,
            proprietaryProfileAuthority = colorTagPlan.proprietaryProfileAuthority,
            bt2100TransportCarrierOnly = colorTagPlan.bt2100TransportCarrierOnly,
            bt2100CarrierMode = colorTagPlan.bt2100CarrierMode,
            vendorDynamicRangeProfileUsed = colorTagPlan.vendorDynamicRangeProfileUsed,
            vendorDynamicMetadataWritten = colorTagPlan.vendorDynamicMetadataWritten,
            dolbyVisionOrHdr10PlusSubstitutionBlocked = colorTagPlan.dolbyVisionOrHdr10PlusSubstitutionBlocked,
            npv10PrivateMasterUnmodified = colorTagPlan.npv10PrivateMasterUnmodified,
            previewRecordingNpv10ParityPolicy = colorTagPlan.previewRecordingNpv10ParityPolicy,
            selectedDetailProfile = selectedDetailProfile,
            exportSubjectMaskAvailable = exportSubjectMaskAvailable,
            exportSubjectDetailApplied = exportSubjectDetailApplied,
            detailProcessingPath = detailProcessingPath
        )
        return baseAudit.copy(
            conversionCertificate = baseAudit.conversionCertificate +
                audioMuxAudit.toCertificateText() +
                niv99Plan.evidence().toSortedMap().entries.joinToString(
                    separator = "\n",
                    prefix = "NIV99 Offline Conversion\n",
                    postfix = "\n"
                ) { (key, value) -> "$key=$value" } +
                "niv99OfflineWhiteBalanceAuthority=${niv99WhiteBalanceAuthority.name}\n" +
                requireNotNull(niv99PixelEvidence) {
                    "NPV10 conversion completed without NIV99 pixel evidence"
                }.metadata().toSortedMap().entries.joinToString(
                    separator = "\n",
                    prefix = "NIV99 Pixel Evidence\n",
                    postfix = "\n"
                ) { (key, value) -> "$key=$value" },
            audioExpected = audioMuxAudit.expected,
            audioPresent = audioMuxAudit.present,
            audioPacketCount = audioMuxAudit.packetCount,
            audioMuxedPacketCount = audioMuxAudit.muxedPacketCount,
            audioFirstPresentationTimeUs = audioMuxAudit.firstPresentationTimeUs,
            audioSourceFirstPresentationTimeUs = audioMuxAudit.sourceFirstPresentationTimeUs,
            audioFinalPresentationTimeUs = audioMuxAudit.finalPresentationTimeUs,
            audioPayloadBytes = audioMuxAudit.payloadBytes,
            audioMonotonicPts = audioMuxAudit.monotonicPts,
            audioCrcValidated = audioMuxAudit.crcValidated,
            audioLeadingSilencePacketCount = audioMuxAudit.leadingSilencePacketCount,
            audioLeadingSilenceCoveredDurationUs = audioMuxAudit.leadingSilenceCoveredDurationUs,
            audioLeadingSilenceResidualGapUs = audioMuxAudit.leadingSilenceResidualGapUs,
            audioOriginalPacketsPreserved = audioMuxAudit.originalPacketsPreserved,
            audioZeroOrigin = audioMuxAudit.zeroOrigin
        )
    }

    private fun String?.toBooleanLenient(): Boolean =
        this?.trim()?.equals("true", ignoreCase = true) == true

    private fun writeMovCertificateSidecar(output: File, audit: Npv10MovConversionAudit): File {
        val sidecar = certificateSidecarFile(output)
        sidecar.parentFile?.mkdirs()
        sidecar.writeTextDurable(audit.toCertificateText())
        return sidecar
    }

    internal fun certificateSidecarFile(output: File): File =
        File(output.parentFile, "${output.name}.certificate.txt")

    internal fun focusMapSidecarFile(output: File): File =
        File(output.parentFile, "${output.name}.focusmap.bin")

    private fun String?.toPersistedDetailProfileOrNull(): VideoDetailProfile? {
        val persistedValue = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return VideoDetailProfile.entries.firstOrNull { profile ->
            profile.name.equals(persistedValue, ignoreCase = true) ||
                profile.displayName.equals(persistedValue, ignoreCase = true)
        }
    }

    private fun String?.toSdrQualityIntentOrNull(): SdrQualityIntent? =
        when (this.orEmpty().trim().uppercase(Locale.US).replace(' ', '_').replace('-', '_')) {
            "NORMAL", "NORMAL_SDR", "NORMAL_VIDEO", "SDR", "STANDARD_SDR" ->
                SdrQualityIntent.NORMAL
            "PRO", "PRO_SDR", "PRO_VIDEO" ->
                SdrQualityIntent.PRO
            else -> null
        }

    private fun buildExportUniforms(
        enhancementProfile: Npv10ExportEnhancementProfile,
        colorTagPlan: Npv10MovColorTagPlan,
        sdrQualityIntent: SdrQualityIntent?
    ): GpuImageEnhancementUniforms {
        val signalFamily = when {
            colorTagPlan.activeTransport.contains("LOG", ignoreCase = true) -> GpuSignalFamily.LOG
            colorTagPlan.activeTransport.contains("PQ", ignoreCase = true) ||
                colorTagPlan.activeTransport.contains("HLG", ignoreCase = true) -> GpuSignalFamily.HDR
            else -> GpuSignalFamily.SDR
        }
        val activeSdrQualityIntent = sdrQualityIntent.takeIf {
            signalFamily == GpuSignalFamily.SDR
        }
        return GpuImageEnhancementUniforms.fromSceneProfile(
            profile = SceneRenderProfile(
                temporalBlend = enhancementProfile.temporalLumaBlend.toFloat(),
                spatialDenoise = 0f,
                chromaDenoise = 1f - enhancementProfile.chromaSuppression.toFloat(),
                shadowBoost = 0f,
                sharpening = enhancementProfile.strongDetailGain.toFloat(),
                warmth = 0f,
                contrast = 1f,
                saturation = 1f,
                highlightCompression = 0f,
                logStrength = if (signalFamily == GpuSignalFamily.LOG) 0.68f else 0f,
                ditherStrength = 0f,
                videoHdrFusionStrength = if (signalFamily == GpuSignalFamily.HDR) 0.82f else 0f
            ),
            signalFamily = signalFamily,
            colorSpaceName = colorTagPlan.selectedColorSpace,
            colorScienceName = colorTagPlan.selectedProfile,
            activeTransport = colorTagPlan.exportGpuActiveTransport(),
            exportProfile = enhancementProfile,
            sdrQualityIntent = activeSdrQualityIntent
        )
    }

    private fun signalFamilyFor(colorTagPlan: Npv10MovColorTagPlan): GpuSignalFamily = when {
        colorTagPlan.activeTransport.contains("LOG", ignoreCase = true) -> GpuSignalFamily.LOG
        colorTagPlan.activeTransport.contains("PQ", ignoreCase = true) ||
            colorTagPlan.activeTransport.contains("HLG", ignoreCase = true) -> GpuSignalFamily.HDR
        else -> GpuSignalFamily.SDR
    }

    /**
     * Production P010 export path. All frame-sized storage is caller-owned and reused for the whole
     * clip: one encoded packet buffer, two decoded P010 buffers that swap raw/output roles, and one
     * uncompressed MOV buffer (HEVC feeds the developed P010 directly). Processing is deliberately
     * sequential because temporal quality analysis and SNCE are ordered state machines.
     */
    private fun runBoundedDirectGpuExport(
        reader: Npv10FileReader,
        writer: QuickTimeMovFileWriter,
        track: Int,
        header: Npv10FileHeader,
        enhancementProfile: Npv10ExportEnhancementProfile,
        exportUniforms: GpuImageEnhancementUniforms,
        gradeLut: Pair<FloatArray, Int>?,
        snceChromaEnabled: Boolean,
        detailProfile: VideoDetailProfile,
        signalFamily: GpuSignalFamily,
        qualityInstructions: NeoQualityInstructionSession,
        outputFormat: OutputFormat,
        hevcColorConfig: QuickTimeColorAtomConfig,
        expectedFrameCount: Int,
        niv99PixelProcessor: Niv99OfflineP010PixelProcessor,
        residualWhiteBalanceAllowed: Boolean,
        onFrame: ((framesDone: Int) -> Unit)?
    ): Npv10AsyncExportResult {
        require(header.payloadFormat == Npv10PayloadFormat.P010_LE) {
            "Direct export requires a P010 master"
        }
        require(expectedFrameCount > 0) { "Cannot convert empty NPV10 master without frames" }
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            header.width,
            header.height,
            header.payloadFormat
        ).toInt()
        val storedBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes.toLong(),
            header.packetCodec
        ).toInt()
        val legacyColorPlan = Npv10MovColorTagPolicy.resolve(header)
        val sourceColorConfig = Npv10StoredPixelColorPolicy.sourceColorConfig(
            header = header,
            legacyFallback = legacyColorPlan.colorConfig
        )
        val snceExport = if (snceChromaEnabled) {
            com.neoproc.neoproccamx.snce.SnceLiveColorController(confidenceGatingEnabled = true)
        } else {
            null
        }
        val movOutputBytes = if (outputFormat == OutputFormat.HEVC_MAIN10_420) {
            null
        } else {
            DirectP010MovPayloadConverter.requiredOutputBytes(
                header.width,
                header.height,
                outputFormat
            )
        }
        val nativeBuffers = ArrayList<Npv10NativeFrameBuffer>(5)
        fun allocateNative(size: Int): ByteBuffer = try {
            Npv10NativeFrameBuffer.allocate(size).also(nativeBuffers::add).buffer
        } catch (failure: Throwable) {
            nativeBuffers.asReversed().forEach(Npv10NativeFrameBuffer::close)
            throw failure
        }
        val storedPacket = allocateNative(storedBytes)
        // Keep both source frames intact while NIV99 writes the developed frame. Two buffers are
        // insufficient once temporal processing is active because the prior source and output would
        // alias on every frame after the first. This bounded three-buffer layout has no per-frame
        // allocation and lets native reject motion without ever reading its own output as history.
        val sourceP010Buffers = arrayOf(allocateNative(decodedBytes), allocateNative(decodedBytes))
        val enhancedP010Buffer = allocateNative(decodedBytes)
        val movOutput = movOutputBytes?.let(::allocateNative)
        val hevcEncoder = try {
            if (outputFormat == OutputFormat.HEVC_MAIN10_420) {
                RecordDevelopHevcMovEncoder(
                    writer = writer,
                    width = header.width,
                    height = header.height,
                    fps = header.fps,
                    colorConfig = hevcColorConfig,
                    deliveryFormat = RecordDevelopDeliveryFormat.HEVC_MAIN10_420_MOV
                )
            } else {
                null
            }
        } catch (failure: Throwable) {
            nativeBuffers.asReversed().forEach(Npv10NativeFrameBuffer::close)
            throw failure
        }

        var writtenFrames = 0L
        var accumulatedNoiseMetrics = VideoNoiseMetrics.EMPTY
        var measuredMetricFrames = 0
        var temporalFilterApplied = false
        var gpuAcceleratedFrames = 0L
        var cpuFallbackFrames = 0L
        var lastGpuFallbackReason = "NONE"
        var lastGpuEvidence: Npv10ExportProcessingEvidence? = null
        var gpuLaneHealthy = true
        try {
            while (writtenFrames < expectedFrameCount.toLong()) {
                val currentIndex = (writtenFrames and 1L).toInt()
                val outputIndex = currentIndex xor 1
                val frame = reader.readNextFrameDirect(storedPacket, sourceP010Buffers[currentIndex])
                    ?: error(
                        "NPV10 master ended early at frame $writtenFrames of $expectedFrameCount"
                    )
                val current = frame.payload
                DirectP010MovPayloadConverter.validateP010PayloadLayout(
                    current,
                    header.width,
                    header.height
                )
                val previous = if (writtenFrames == 0L) {
                    null
                } else {
                    sourceP010Buffers[outputIndex].duplicate().apply {
                        position(0)
                        limit(decodedBytes)
                    }.slice().order(ByteOrder.LITTLE_ENDIAN)
                }

                var snceSaturation = 1f
                var snceWarmth = 0f
                if (snceExport != null && !qualityInstructions.isCacheHit) {
                    snceExport.analyzeP010(current, header.width, header.height)
                    snceSaturation = snceExport.p010SaturationMul()
                    snceWarmth = if (residualWhiteBalanceAllowed) {
                        snceExport.p010WarmthBias()
                    } else {
                        0f
                    }
                }
                val instruction = qualityInstructions.resolve(
                    frameIndex = writtenFrames,
                    payload = current,
                    previousP010Payload = previous,
                    saturation = snceSaturation,
                    warmth = snceWarmth
                )
                accumulatedNoiseMetrics = accumulatedNoiseMetrics.plus(instruction.noiseMetrics)
                measuredMetricFrames += 1
                val frameUniforms = exportUniforms.copy(
                    saturation = instruction.saturation,
                    warmth = instruction.warmth,
                    sharpening = instruction.quality.sharpen,
                    spatialDenoise = instruction.quality.denoise
                )

                val enhancedTarget = enhancedP010Buffer.apply {
                    clear()
                    limit(decodedBytes)
                }
                val gpuStartedNs = System.nanoTime()
                val gpuEvidence = if (gpuLaneHealthy) {
                    Npv10ExportGpuAccelerator.enhanceP010Direct(
                        current = current,
                        previous = previous,
                        output = enhancedTarget,
                        width = header.width,
                        height = header.height,
                        uniforms = frameUniforms,
                        gradeLut = gradeLut?.first,
                        gradeLutSize = gradeLut?.second ?: 0
                    )
                } else {
                    null
                }
                if (gpuEvidence != null) {
                    val gpuNanos = (System.nanoTime() - gpuStartedNs).coerceAtLeast(0L)
                    niv99PixelProcessor.recordGpuFrame(
                        frameIndex = writtenFrames,
                        sensorTimestampNs = frame.timestampNs,
                        output = enhancedTarget,
                        temporalHistoryUsed = previous != null,
                        residualWhiteBalanceApplied = residualWhiteBalanceAllowed &&
                            kotlin.math.abs(frameUniforms.warmth) > 0.000_001f,
                        processingTimeNs = gpuNanos
                    )
                    gpuAcceleratedFrames += 1L
                    lastGpuEvidence = gpuEvidence
                } else {
                    // The reference CPU implementation is the explicit quality-equivalent fallback.
                    // Disable native for the rest of this clip after a runtime failure so every later
                    // frame follows one deterministic route and the failure remains visible in audit.
                    if (gpuLaneHealthy) {
                        lastGpuFallbackReason = if (gradeLut != null) {
                            "VULKAN_P010_GRADE_LUT_FRAME_PROCESSING_FAILED"
                        } else {
                            "VULKAN_P010_FRAME_PROCESSING_FAILED"
                        }
                    }
                    gpuLaneHealthy = false
                    cpuFallbackFrames += 1L
                    val sourcePayload = current.copyFrameBytes(decodedBytes)
                    val previousSourcePayload = previous?.copyFrameBytes(decodedBytes)
                    val cpuEnhanced = niv99PixelProcessor.processCpu(
                        frameIndex = writtenFrames,
                        sensorTimestampNs = frame.timestampNs,
                        sourceP010 = sourcePayload,
                        previousSourceP010 = previousSourcePayload,
                        profile = enhancementProfile,
                        liveCinemaSharpness = instruction.quality.sharpen.toDouble(),
                        liveCinemaDenoise = instruction.quality.denoise.toDouble(),
                        gradeLut = gradeLut?.first,
                        gradeLutSize = gradeLut?.second ?: 0,
                        saturation = frameUniforms.saturation,
                        warmth = frameUniforms.warmth
                    )
                    check(cpuEnhanced.size == decodedBytes) {
                        "CPU P010 fallback size mismatch expected=$decodedBytes actual=${cpuEnhanced.size}"
                    }
                    enhancedTarget.clear()
                    enhancedTarget.put(cpuEnhanced)
                    enhancedTarget.flip()
                }
                val enhanced = enhancedTarget.duplicate().apply {
                    position(0)
                    limit(decodedBytes)
                }.slice().order(ByteOrder.LITTLE_ENDIAN)
                val presentationUs = Npv10ExportCadencePolicy.presentationTimeUs(
                    frameIndex = writtenFrames,
                    fps = header.fps
                )
                if (hevcEncoder != null) {
                    DirectP010MovPayloadConverter.validateP010PayloadLayout(
                        enhanced,
                        header.width,
                        header.height
                    )
                    hevcEncoder.encodeFrameDirect(
                        p010 = enhanced,
                        presentationTimeUs = presentationUs,
                        endOfStream = writtenFrames + 1L == expectedFrameCount.toLong()
                    )
                } else {
                    val movPayload = DirectP010MovPayloadConverter.convert(
                        p010Payload = enhanced,
                        width = header.width,
                        height = header.height,
                        outputFormat = outputFormat,
                        colorConfig = sourceColorConfig,
                        target = requireNotNull(movOutput)
                    )
                    writer.writeSampleBuffer(
                        trackIndex = track,
                        buffer = movPayload,
                        presentationTimeUs = presentationUs,
                        flags = 1
                    )
                }
                if (previous != null) temporalFilterApplied = true
                writtenFrames += 1L
                onFrame?.invoke(writtenFrames.toInt())
            }

            val trailingFrame = reader.readNextFrameDirect(
                storedPacket,
                sourceP010Buffers[(writtenFrames and 1L).toInt()]
            )
            check(trailingFrame == null) {
                "NPV10 master contains more frames than its verified packet index reports"
            }
            val endPresentationTimeUs = Npv10ExportCadencePolicy.endPresentationTimeUs(
                frameCount = writtenFrames,
                fps = header.fps
            )
            val finalizedFrames = if (hevcEncoder != null) {
                hevcEncoder.finish(endPresentationTimeUs)
            } else {
                writer.setTrackEndPresentationTimeUs(track, endPresentationTimeUs)
                writtenFrames
            }
            Npv10ExportFrameCountPolicy.requireExactMatch(
                expectedFrameCount = expectedFrameCount,
                sourceFrameCount = writtenFrames,
                submittedFrameCount = writtenFrames,
                finalizedFrameCount = finalizedFrames
            )
        } finally {
            hevcEncoder?.close()
            nativeBuffers.asReversed().forEach(Npv10NativeFrameBuffer::close)
        }

        val processingEvidence = when {
            gpuAcceleratedFrames > 0L && cpuFallbackFrames == 0L && lastGpuEvidence != null ->
                requireNotNull(lastGpuEvidence)
            else -> Npv10ExportProcessingEvidence.fromFrameCounts(
            signature = exportUniforms.signature,
            gpuFrames = gpuAcceleratedFrames,
            cpuFrames = cpuFallbackFrames,
            lastFallbackReason = lastGpuFallbackReason
            )
        }
        return Npv10AsyncExportResult(
            frameCount = writtenFrames,
            accumulatedNoiseMetrics = accumulatedNoiseMetrics,
            measuredMetricFrames = measuredMetricFrames,
            temporalFilterApplied = temporalFilterApplied,
            processingEvidence = processingEvidence
        )
    }

    private fun ByteBuffer.copyFrameBytes(expectedBytes: Int): ByteArray {
        require(expectedBytes > 0 && limit() >= expectedBytes) {
            "P010 frame buffer is smaller than expected: limit=${limit()} expected=$expectedBytes"
        }
        return ByteArray(expectedBytes).also { output ->
            duplicate().apply {
                position(0)
                limit(expectedBytes)
            }.get(output)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun runTripleBufferedExport(
        reader: Npv10FileReader,
        writer: QuickTimeMovFileWriter,
        track: Int,
        header: Npv10FileHeader,
        enhancementProfile: Npv10ExportEnhancementProfile,
        exportUniforms: GpuImageEnhancementUniforms,
        gradeLut: Pair<FloatArray, Int>? = null,
        snceChromaEnabled: Boolean = false,
        detailProfile: VideoDetailProfile,
        signalFamily: GpuSignalFamily,
        qualityInstructions: NeoQualityInstructionSession,
        outputFormat: OutputFormat,
        expectedFrameCount: Int,
        niv99PixelProcessor: Niv99OfflineP010PixelProcessor,
        residualWhiteBalanceAllowed: Boolean,
        onFrame: ((framesDone: Int) -> Unit)? = null
    ): Npv10AsyncExportResult {
        var writtenFrames = 0L
        val sourceFramesRead = AtomicLong(0L)
        val finalizedFrames = AtomicLong(0L)
        var temporalFilterApplied = false
        var accumulatedNoiseMetrics = VideoNoiseMetrics.EMPTY
        var measuredMetricFrames = 0
        var gpuAcceleratedFrames = 0L
        var cpuFallbackFrames = 0L
        var lastGpuFallbackReason = Npv10ExportGpuAccelerator.unavailableReason()
        var lastGpuEvidence: Npv10ExportProcessingEvidence? = null
        val legacyColorPlan = Npv10MovColorTagPolicy.resolve(header)
        val sourceColorConfig = Npv10StoredPixelColorPolicy.sourceColorConfig(
            header = header,
            legacyFallback = legacyColorPlan.colorConfig
        )

        val readerDispatcher = newFixedThreadPoolContext(1, "npv10-reader")
        val gpuDispatcher = newFixedThreadPoolContext(1, "npv10-gpu")
        val writerDispatcher = newFixedThreadPoolContext(1, "npv10-writer")
        try {
            runBlocking {
                val rawFrames = Channel<Npv10RawExportFrame>(capacity = PIPELINE_BUFFER_CAPACITY)
                val enhancedFrames = Channel<Npv10EnhancedExportFrame>(capacity = PIPELINE_BUFFER_CAPACITY)

                val readerJob = launch(readerDispatcher) {
                    var index = 0L
                    while (true) {
                        val frame = reader.readNextFrame() ?: break
                        sourceFramesRead.incrementAndGet()
                        val sourcePayload = payloadForMovSample(header, frame.payload)
                        rawFrames.send(
                            Npv10RawExportFrame(
                                index = index,
                                timestampNs = frame.timestampNs,
                                payload = sourcePayload
                            )
                        )
                        index += 1L
                    }
                    rawFrames.close()
                }

                val gpuJob = launch(gpuDispatcher) {
                    var previousSourcePayload: ByteArray? = null
                    // SNCE v2.0 HVC per-frame nudge now feeds BOTH the GPU accelerator's frameUniforms AND
                    // this path's per-frame CPU fallback (enhanceP010 saturation/warmth), so a GPU-
                    // accelerated frame and a CPU-fallback frame of the same clip carry identical colour.
                    // [snceChromaEnabled] is the single per-clip gate (engine flag on, no SDR grade LUT,
                    // non-PQ delivery); when false the nudge is a strict no-op, so skipping SnceV2Engine
                    // here is not just an optimisation but keeps output byte-identical.
                    val snceExport = if (snceChromaEnabled) {
                        com.neoproc.neoproccamx.snce.SnceLiveColorController(confidenceGatingEnabled = true) // CP47 ON: confidence-scaled WB nudge (only reduces)
                    } else {
                        null
                    }
                    try {
                        for (rawFrame in rawFrames) {
                            val sourcePayload = rawFrame.payload
                            val sourceTemporalPrior = previousSourcePayload
                            var snceSaturation = 1f
                            var snceWarmth = 0f
                            if (snceExport != null && !qualityInstructions.isCacheHit) {
                                snceExport.analyzeP010(sourcePayload, header.width, header.height)
                                snceSaturation = snceExport.p010SaturationMul()
                                snceWarmth = if (residualWhiteBalanceAllowed) {
                                    snceExport.p010WarmthBias()
                                } else {
                                    0f
                                }
                            }
                            val instruction = qualityInstructions.resolve(
                                frameIndex = rawFrame.index,
                                payload = sourcePayload,
                                previousP010Payload = sourceTemporalPrior,
                                saturation = snceSaturation,
                                warmth = snceWarmth
                            )
                            val noiseMetrics = instruction.noiseMetrics
                            accumulatedNoiseMetrics = accumulatedNoiseMetrics.plus(noiseMetrics)
                            measuredMetricFrames += 1
                            val quality = instruction.quality
                            // SNCE v2.0 HVC: per-frame bounded perceptual nudge into the export ABI
                            // (slot 11 = saturation, slot 9 = warmth). Skipped when it cannot reach the
                            // exported pixels (no native accelerator / SDR-grade CPU path).
                            val frameUniforms = exportUniforms.copy(
                                saturation = instruction.saturation,
                                warmth = instruction.warmth,
                                sharpening = quality.sharpen,
                                spatialDenoise = quality.denoise
                            )
                            // P4 Phase A: the GPU export accelerator has no color-grade LUT stage yet
                            // (Phase B). When an SDR grade LUT is present we route to the CPU path, which
                            // DOES apply it, so the exported MOV is WYSIWYG with preview. Offline export
                            // is not real-time, so the CPU cost is acceptable; a null LUT keeps the
                            // GPU-accelerated path byte-identical to before.
                            val niv99StartedNs = System.nanoTime()
                            val acceleration = if (gradeLut != null) {
                                null
                            } else {
                                Npv10ExportGpuAccelerator.enhanceP010(
                                    payload = sourcePayload,
                                    previousP010Payload = sourceTemporalPrior,
                                    width = header.width,
                                    height = header.height,
                                    uniforms = frameUniforms
                                )
                            }
                            val movPayload = if (
                                acceleration != null &&
                                isValidEnhancedP010Payload(acceleration.payload, sourcePayload.size)
                            ) {
                                gpuAcceleratedFrames += 1L
                                lastGpuEvidence = acceleration.evidence
                                niv99PixelProcessor.recordGpuFrame(
                                    frameIndex = rawFrame.index,
                                    sensorTimestampNs = rawFrame.timestampNs,
                                    output = acceleration.payload,
                                    temporalHistoryUsed = sourceTemporalPrior != null,
                                    residualWhiteBalanceApplied =
                                        kotlin.math.abs(frameUniforms.warmth) > NIV99_COLOR_EFFECT_EPSILON,
                                    processingTimeNs =
                                        (System.nanoTime() - niv99StartedNs).coerceAtLeast(0L)
                                )
                                acceleration.payload
                            } else {
                                cpuFallbackFrames += 1L
                                lastGpuFallbackReason = when {
                                    gradeLut != null -> "SDR_GRADE_LUT_CPU_PATH"
                                    acceleration == null -> Npv10ExportGpuAccelerator.unavailableReason()
                                    acceleration.payload.size != sourcePayload.size ->
                                        "NATIVE_PAYLOAD_SIZE_MISMATCH(expected=${sourcePayload.size},actual=${acceleration.payload.size})"
                                    else -> "NATIVE_PAYLOAD_VALIDATION_FAILED"
                                }
                                niv99PixelProcessor.processCpu(
                                    frameIndex = rawFrame.index,
                                    sensorTimestampNs = rawFrame.timestampNs,
                                    sourceP010 = sourcePayload,
                                    previousSourceP010 = sourceTemporalPrior,
                                    profile = enhancementProfile,
                                    liveCinemaSharpness = quality.sharpen.toDouble(),
                                    liveCinemaDenoise = quality.denoise.toDouble(),
                                    gradeLut = gradeLut?.first,
                                    gradeLutSize = gradeLut?.second ?: 0,
                                    // frameUniforms carries this frame's SNCE nudge (or the identity
                                    // baseline when SNCE is off), so a CPU-fallback frame matches the
                                    // GPU-accelerated frames of the same clip.
                                    saturation = frameUniforms.saturation,
                                    warmth = frameUniforms.warmth
                                )
                            }
                            if (sourceTemporalPrior != null) {
                                temporalFilterApplied = true
                            }
                            previousSourcePayload = sourcePayload.copyOf()

                            // HEVC receives the developed P010 directly; r210/v210 expand that same
                            // developed frame into their uncompressed MOV sample layout.
                            val playableMovPayload = if (outputFormat == OutputFormat.HEVC_MAIN10_420) {
                                movPayload
                            } else {
                                p010ToMovPayload(
                                    p010Payload = movPayload,
                                    width = header.width,
                                    height = header.height,
                                    outputFormat = outputFormat,
                                    colorConfig = sourceColorConfig
                                )
                            }

                            enhancedFrames.send(
                                Npv10EnhancedExportFrame(
                                    index = rawFrame.index,
                                    timestampNs = rawFrame.timestampNs,
                                    payload = playableMovPayload
                                )
                            )
                        }
                    } finally {
                        enhancedFrames.close()
                    }
                }

                val writerJob = launch(writerDispatcher) {
                    val hevcEncoder = if (outputFormat == OutputFormat.HEVC_MAIN10_420) {
                        RecordDevelopHevcMovEncoder(
                            writer = writer,
                            width = header.width,
                            height = header.height,
                            fps = header.fps,
                            colorConfig = colorConfigForOutput(sourceColorConfig, outputFormat),
                            deliveryFormat = RecordDevelopDeliveryFormat.HEVC_MAIN10_420_MOV
                        )
                    } else {
                        null
                    }
                    try {
                        for (enhancedFrame in enhancedFrames) {
                            val presentationUs = Npv10ExportCadencePolicy.presentationTimeUs(
                                frameIndex = enhancedFrame.index,
                                fps = header.fps
                            )
                            if (hevcEncoder != null) {
                                hevcEncoder.encodeFrame(enhancedFrame.payload, presentationUs)
                            } else {
                                writer.writeSample(
                                    trackIndex = track,
                                    sample = QuickTimeSampleBuffer(
                                        bytes = enhancedFrame.payload,
                                        presentationTimeUs = presentationUs,
                                        flags = 1
                                    )
                                )
                            }
                            writtenFrames += 1L
                            onFrame?.invoke(writtenFrames.toInt())
                        }
                        val finalized = if (writtenFrames > 0L) {
                            val endPresentationTimeUs = Npv10ExportCadencePolicy.endPresentationTimeUs(
                                frameCount = writtenFrames,
                                fps = header.fps
                            )
                            if (hevcEncoder != null) {
                                hevcEncoder.finish(endPresentationTimeUs)
                            } else {
                                writer.setTrackEndPresentationTimeUs(track, endPresentationTimeUs)
                                writtenFrames
                            }
                        } else {
                            0L
                        }
                        finalizedFrames.set(finalized)
                    } finally {
                        hevcEncoder?.close()
                    }
                }
                joinAll(readerJob, gpuJob, writerJob)
            }
            Npv10ExportFrameCountPolicy.requireExactMatch(
                expectedFrameCount = expectedFrameCount,
                sourceFrameCount = sourceFramesRead.get(),
                submittedFrameCount = writtenFrames,
                finalizedFrameCount = finalizedFrames.get()
            )
        } finally {
            readerDispatcher.close()
            gpuDispatcher.close()
            writerDispatcher.close()
        }

        val processingEvidence = when {
            gpuAcceleratedFrames > 0L && cpuFallbackFrames == 0L && lastGpuEvidence != null -> lastGpuEvidence
            else -> Npv10ExportProcessingEvidence.fromFrameCounts(
                signature = exportUniforms.signature,
                gpuFrames = gpuAcceleratedFrames,
                cpuFrames = cpuFallbackFrames,
                lastFallbackReason = lastGpuFallbackReason
            )
        }
        return Npv10AsyncExportResult(
            frameCount = writtenFrames,
            accumulatedNoiseMetrics = accumulatedNoiseMetrics,
            measuredMetricFrames = measuredMetricFrames,
            temporalFilterApplied = temporalFilterApplied,
            processingEvidence = processingEvidence
        )
    }

    /**
     * CPU-parallel export path — used whenever the native Vulkan accelerator is unavailable (e.g. vivo)
     * or an SDR grade LUT forces the CPU enhancer. The per-frame CPU enhance is a pure function of
     * (source, previous-source, profile, gradeLut), so frames are developed concurrently across a
     * memory-bounded worker pool and then muxed STRICTLY IN FRAME ORDER via a reorder buffer whose size
     * is bounded by an in-flight window (a straggler worker can never grow it without bound). The
     * produced .MOV bytes and the audit are IDENTICAL to the single-threaded fallback (no-downgrade):
     *   • the enhancer runs with the exact same inputs per frame, and
     *   • the accumulated noise metrics are summed in strict frame order after the workers finish, so
     *     the (float) accumulation is not perturbed by nondeterministic completion order.
     * Only the wall-clock changes. Reader I/O and the muxer stay single-threaded (both are inherently
     * serial); the CPU-heavy enhance is the only stage fanned out.
     */
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun runParallelCpuExport(
        reader: Npv10FileReader,
        writer: QuickTimeMovFileWriter,
        track: Int,
        header: Npv10FileHeader,
        enhancementProfile: Npv10ExportEnhancementProfile,
        exportUniforms: GpuImageEnhancementUniforms,
        gradeLut: Pair<FloatArray, Int>? = null,
        snceChromaEnabled: Boolean = false,
        detailProfile: VideoDetailProfile,
        signalFamily: GpuSignalFamily,
        qualityInstructions: NeoQualityInstructionSession,
        outputFormat: OutputFormat,
        hevcColorConfig: QuickTimeColorAtomConfig? = null,
        expectedFrameCount: Int,
        niv99PixelProcessor: Niv99OfflineP010PixelProcessor,
        residualWhiteBalanceAllowed: Boolean,
        onFrame: ((framesDone: Int) -> Unit)? = null
    ): Npv10AsyncExportResult {
        val legacyColorPlan = Npv10MovColorTagPolicy.resolve(header)
        val sourceColorConfig = Npv10StoredPixelColorPolicy.sourceColorConfig(
            header = header,
            legacyFallback = legacyColorPlan.colorConfig
        )
        val payloadBytes = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(header.width, header.height, header.payloadFormat)
        // Clamp the fixed budget to a fraction of the ACTUAL JVM/ART heap, so "within budget" is real on
        // small-heap devices instead of trusting a hardcoded constant that may exceed the device heap.
        val memoryBudget = minOf(
            NPV10_CPU_MEMORY_BUDGET_BYTES,
            Runtime.getRuntime().maxMemory() / NPV10_CPU_HEAP_FRACTION
        )
        // Source P010 + enhanced P010 + v210 output (plus the reorder buffer) are resident per in-flight
        // frame, so bound the worker count to keep peak memory in check — cinema P010 frames are large.
        val workers = ConversionParallelismPolicy.workerCount(
            frameBytes = payloadBytes,
            memoryBudgetBytes = memoryBudget,
            pipelineDepth = CPU_PIPELINE_CHANNEL_CAPACITY,
            residentCopiesPerFrame = 3
        )
        // Bound read-but-not-yet-written frames by both (workers + channel depth) and the active DMC
        // thermal window. This caps the writer's reorder buffer when one worker straggles and lets a
        // warm-started conversion avoid allocating an unnecessarily deep frame window. The permit is a
        // coroutine Semaphore so a cancellation on the error path unblocks the reader (a blocking
        // java.util.concurrent.Semaphore.acquire would not be cancellable and could hang on failure).
        val reorderWindow = minOf(
            workers + CPU_PIPELINE_CHANNEL_CAPACITY,
            DmcConversionTuningRegistry.current().inFlightLimit
        ).coerceAtLeast(1)
        val inFlightPermits = Semaphore(permits = reorderWindow)
        // Mirrors the per-frame fallback reason recorded by the sequential path, so the audit matches.
        val cpuFallbackReason = if (gradeLut != null) {
            "SDR_GRADE_LUT_CPU_PATH"
        } else {
            Npv10ExportGpuAccelerator.unavailableReason()
        }

        var writtenFrames = 0L
        val sourceFramesRead = AtomicLong(0L)
        val finalizedFrames = AtomicLong(0L)
        var temporalFilterApplied = false
        val metricsByIndex = java.util.concurrent.ConcurrentHashMap<Long, VideoNoiseMetrics>()

        val readerDispatcher = newFixedThreadPoolContext(1, "npv10-cpu-reader")
        val cpuDispatcher = newFixedThreadPoolContext(workers, "npv10-cpu")
        val writerDispatcher = newFixedThreadPoolContext(1, "npv10-cpu-writer")
        NeoLogger.i(
            PIPELINE_TAG,
            "CPU_EXPORT_START geometry=${header.width}x${header.height}@${header.fps} " +
                "format=$outputFormat expectedFrames=$expectedFrameCount workers=$workers " +
                "reorderWindow=$reorderWindow payloadBytes=$payloadBytes ${heapSnapshot()}"
        )
        try {
            runBlocking {
                val rawFrames = Channel<Npv10RawExportFrame>(capacity = CPU_PIPELINE_CHANNEL_CAPACITY)
                val enhancedFrames = Channel<Npv10EnhancedExportFrame>(capacity = CPU_PIPELINE_CHANNEL_CAPACITY)

                // SNCE v2 HVC nudge is STATEFUL (per-frame EWMA) and must be computed strictly in frame
                // order — so it runs here in the single-threaded reader, exactly like the noise metrics,
                // and the resulting (saturation, warmth) scalars are snapshotted onto each frame for the
                // parallel workers to apply. This mirrors the GPU-sequential path's nudge, keeping both
                // lanes colour-identical. Null when the per-clip gate is off (strict no-op downstream).
                val snceExport = if (snceChromaEnabled) {
                    com.neoproc.neoproccamx.snce.SnceLiveColorController(confidenceGatingEnabled = true)
                } else {
                    null
                }
                val readerJob = launch(readerDispatcher) {
                    var index = 0L
                    var previousSourcePayload: ByteArray? = null
                    while (true) {
                        val frame = reader.readNextFrame() ?: break
                        sourceFramesRead.incrementAndGet()
                        // Backpressure: block reading further ahead than the writer has drained, bounding
                        // the reorder buffer. Balanced 1:1 with the writer's release per written frame.
                        inFlightPermits.acquire()
                        val sourcePayload = payloadForMovSample(header, frame.payload)
                        // The first export computes SNCE in order and stores its bounded controls in NQIS.
                        // A validated repeat-export hit consumes those exact controls without re-analysis.
                        var snceSaturation = 1f
                        var snceWarmth = 0f
                        if (snceExport != null && !qualityInstructions.isCacheHit) {
                            snceExport.analyzeP010(sourcePayload, header.width, header.height)
                            snceSaturation = snceExport.p010SaturationMul()
                            snceWarmth = if (residualWhiteBalanceAllowed) {
                                snceExport.p010WarmthBias()
                            } else {
                                0f
                            }
                        }
                        // Metrics computed here (sequential) exactly as the single-threaded path would.
                        val qualityInstruction = qualityInstructions.resolve(
                            frameIndex = index,
                            payload = sourcePayload,
                            previousP010Payload = previousSourcePayload,
                            saturation = snceSaturation,
                            warmth = snceWarmth
                        )
                        if (index == 0L) {
                            NeoLogger.i(
                                PIPELINE_TAG,
                                "CPU_EXPORT_READER_FRAME_READY index=0 payloadBytes=${sourcePayload.size} " +
                                    heapSnapshot()
                            )
                        }
                        metricsByIndex[index] = qualityInstruction.noiseMetrics
                        if (previousSourcePayload != null) temporalFilterApplied = true
                        rawFrames.send(
                            Npv10RawExportFrame(
                                index = index,
                                timestampNs = frame.timestampNs,
                                payload = sourcePayload,
                                previous = previousSourcePayload,
                                snceSaturation = qualityInstruction.saturation,
                                snceWarmth = qualityInstruction.warmth,
                                qualityInstruction = qualityInstruction
                            )
                        )
                        previousSourcePayload = sourcePayload
                        index += 1L
                    }
                    rawFrames.close()
                }

                val workerJobs = (0 until workers).map {
                    launch(cpuDispatcher) {
                        for (rawFrame in rawFrames) {
                            val frameStartedAtMs = SystemClock.elapsedRealtime()
                            if (rawFrame.index == 0L) {
                                NeoLogger.i(
                                    PIPELINE_TAG,
                                    "CPU_EXPORT_LIVE_ENHANCE_START index=0 ${heapSnapshot()}"
                                )
                            }
                            val quality = requireNotNull(rawFrame.qualityInstruction) {
                                "NQIS instruction missing for NPV10 frame ${rawFrame.index}"
                            }.quality
                            val enhanced = niv99PixelProcessor.processCpu(
                                frameIndex = rawFrame.index,
                                sensorTimestampNs = rawFrame.timestampNs,
                                sourceP010 = rawFrame.payload,
                                previousSourceP010 = rawFrame.previous,
                                profile = enhancementProfile,
                                liveCinemaSharpness = quality.sharpen.toDouble(),
                                liveCinemaDenoise = quality.denoise.toDouble(),
                                gradeLut = gradeLut?.first,
                                gradeLutSize = gradeLut?.second ?: 0,
                                // Per-frame SNCE nudge snapshotted by the in-order reader (identity when
                                // SNCE is off), applied here so parallel CPU frames match the GPU lane.
                                saturation = rawFrame.snceSaturation,
                                warmth = rawFrame.snceWarmth
                            )
                            if (rawFrame.index == 0L) {
                                NeoLogger.i(
                                    PIPELINE_TAG,
                                    "CPU_EXPORT_NIV99_END index=0 elapsedMs=" +
                                        "${SystemClock.elapsedRealtime() - frameStartedAtMs} ${heapSnapshot()}"
                                )
                            }
                            val playableMovPayload = if (outputFormat == OutputFormat.HEVC_MAIN10_420) {
                                enhanced
                            } else {
                                p010ToMovPayload(
                                    p010Payload = enhanced,
                                    width = header.width,
                                    height = header.height,
                                    outputFormat = outputFormat,
                                    colorConfig = sourceColorConfig
                                )
                            }
                            enhancedFrames.send(
                                Npv10EnhancedExportFrame(
                                    index = rawFrame.index,
                                    timestampNs = rawFrame.timestampNs,
                                    payload = playableMovPayload
                                )
                            )
                            if (rawFrame.index == 0L) {
                                NeoLogger.i(
                                    PIPELINE_TAG,
                                    "CPU_EXPORT_WORKER_PUBLISHED index=0 totalElapsedMs=" +
                                        "${SystemClock.elapsedRealtime() - frameStartedAtMs} ${heapSnapshot()}"
                                )
                            }
                        }
                    }
                }

                // Close the enhanced channel once every worker has drained the raw channel.
                launch(cpuDispatcher) {
                    workerJobs.joinAll()
                    enhancedFrames.close()
                }

                val writerJob = launch(writerDispatcher) {
                    // Workers finish out of order; uncompressed samples must be muxed in frame order, so
                    // hold out-of-order frames until the next expected index arrives.
                    val pending = HashMap<Long, Npv10EnhancedExportFrame>()
                    var nextIndex = 0L
                    val hevcEncoder = if (outputFormat == OutputFormat.HEVC_MAIN10_420) {
                        RecordDevelopHevcMovEncoder(
                            writer = writer,
                            width = header.width,
                            height = header.height,
                            fps = header.fps,
                            colorConfig = requireNotNull(hevcColorConfig) {
                                "HEVC Main10 export requires an explicit color configuration"
                            },
                            deliveryFormat = RecordDevelopDeliveryFormat.HEVC_MAIN10_420_MOV
                        )
                    } else {
                        null
                    }
                    try {
                        for (enhancedFrame in enhancedFrames) {
                            if (enhancedFrame.index == 0L) {
                                NeoLogger.i(
                                    PIPELINE_TAG,
                                    "CPU_EXPORT_WRITER_RECEIVED index=0 payloadBytes=" +
                                        "${enhancedFrame.payload.size} ${heapSnapshot()}"
                                )
                            }
                            pending[enhancedFrame.index] = enhancedFrame
                            while (true) {
                                val ready = pending.remove(nextIndex) ?: break
                                val presentationUs = Npv10ExportCadencePolicy.presentationTimeUs(
                                    frameIndex = ready.index,
                                    fps = header.fps
                                )
                                if (hevcEncoder != null) {
                                    if (ready.index == 0L) {
                                        NeoLogger.i(PIPELINE_TAG, "CPU_EXPORT_HEVC_SUBMIT_START index=0")
                                    }
                                    hevcEncoder.encodeFrame(ready.payload, presentationUs)
                                    if (ready.index == 0L) {
                                        NeoLogger.i(PIPELINE_TAG, "CPU_EXPORT_HEVC_SUBMIT_BUFFERED index=0")
                                    }
                                } else {
                                    writer.writeSample(
                                        trackIndex = track,
                                        sample = QuickTimeSampleBuffer(
                                            bytes = ready.payload,
                                            presentationTimeUs = presentationUs,
                                            flags = 1
                                        )
                                    )
                                }
                                writtenFrames += 1L
                                inFlightPermits.release() // let the reader advance one more frame
                                onFrame?.invoke(writtenFrames.toInt())
                                nextIndex += 1L
                            }
                        }
                        check(pending.isEmpty()) {
                            "NPV10 CPU export reorder queue ended with ${pending.size} missing earlier frame(s)"
                        }
                        val finalized = if (writtenFrames > 0L) {
                            val endPresentationTimeUs = Npv10ExportCadencePolicy.endPresentationTimeUs(
                                frameCount = writtenFrames,
                                fps = header.fps
                            )
                            if (hevcEncoder != null) {
                                NeoLogger.i(
                                    PIPELINE_TAG,
                                    "CPU_EXPORT_HEVC_FINISH_START frames=$writtenFrames ${heapSnapshot()}"
                                )
                                hevcEncoder.finish(endPresentationTimeUs)
                            } else {
                                writer.setTrackEndPresentationTimeUs(track, endPresentationTimeUs)
                                writtenFrames
                            }
                        } else {
                            0L
                        }
                        finalizedFrames.set(finalized)
                        NeoLogger.i(
                            PIPELINE_TAG,
                            "CPU_EXPORT_WRITER_FINALIZED frames=$finalized ${heapSnapshot()}"
                        )
                    } finally {
                        hevcEncoder?.close()
                    }
                }
                joinAll(readerJob, writerJob)
            }
            Npv10ExportFrameCountPolicy.requireExactMatch(
                expectedFrameCount = expectedFrameCount,
                sourceFrameCount = sourceFramesRead.get(),
                submittedFrameCount = writtenFrames,
                finalizedFrameCount = finalizedFrames.get()
            )
        } finally {
            readerDispatcher.close()
            cpuDispatcher.close()
            writerDispatcher.close()
        }

        // Sum per-frame metrics in strict frame order for a deterministic (float-identical) accumulation.
        var accumulatedNoiseMetrics = VideoNoiseMetrics.EMPTY
        var measuredMetricFrames = 0
        var metricIndex = 0L
        while (true) {
            val metric = metricsByIndex[metricIndex] ?: break
            accumulatedNoiseMetrics = accumulatedNoiseMetrics.plus(metric)
            measuredMetricFrames += 1
            metricIndex += 1L
        }

        val processingEvidence = Npv10ExportProcessingEvidence.fromFrameCounts(
            signature = exportUniforms.signature,
            gpuFrames = 0L,
            cpuFrames = writtenFrames,
            lastFallbackReason = cpuFallbackReason
        )
        return Npv10AsyncExportResult(
            frameCount = writtenFrames,
            accumulatedNoiseMetrics = accumulatedNoiseMetrics,
            measuredMetricFrames = measuredMetricFrames,
            temporalFilterApplied = temporalFilterApplied,
            processingEvidence = processingEvidence
        )
    }

    private fun heapSnapshot(): String {
        val runtime = Runtime.getRuntime()
        val mib = 1024L * 1024L
        return "heapUsedMiB=${(runtime.totalMemory() - runtime.freeMemory()) / mib} " +
            "heapCommittedMiB=${runtime.totalMemory() / mib} heapMaxMiB=${runtime.maxMemory() / mib}"
    }

    private fun validateMovConvertible(header: Npv10FileHeader) {
        val requiredStorageBitDepth = header.payloadFormat.rawBitDepth ?: Npv10ContainerPolicy.BIT_DEPTH
        require(header.storageBitDepth == requiredStorageBitDepth) {
            "Unsupported NPV10 storage bit depth for MOV conversion: ${header.storageBitDepth}"
        }
        when (header.payloadFormat) {
            Npv10PayloadFormat.P010_LE -> {
                require(header.sourceBitDepth >= 10) {
                    "P010 MOV conversion requires native 10-bit source metadata"
                }
                require(header.sourceFormat.equals(SOURCE_FORMAT_P010, ignoreCase = true)) {
                    "P010 MOV conversion requires YCBCR_P010 source metadata"
                }
            }
            Npv10PayloadFormat.YUV420_8BIT_EXPANDED -> {
                require(header.sourceBitDepth <= 8) {
                    "YUV420_8BIT_EXPANDED fallback must never be labeled native 10-bit"
                }
            }
            Npv10PayloadFormat.YUV420P10_LE -> {
                require(header.sourceBitDepth >= 10) {
                    "YUV420P10_LE MOV conversion requires native 10-bit source metadata"
                }
            }
            Npv10PayloadFormat.RAW12_NPV10_PACKED,
            Npv10PayloadFormat.RAW14_NPV10_PACKED -> {
                throw IllegalArgumentException(
                    "RAW packed NPV10 masters require Vulkan demosaic export before MOV conversion"
                )
            }
        }
    }

    private fun payloadForMovSample(header: Npv10FileHeader, payload: ByteArray): ByteArray {
        val expectedBytes = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(header.width, header.height, header.payloadFormat)
            .toInt()
        require(payload.size == expectedBytes) {
            "NPV10 payload size ${payload.size} does not match expected $expectedBytes"
        }
        return when (header.payloadFormat) {
            Npv10PayloadFormat.P010_LE -> payload.also { validateP010PayloadLayout(it) }
            Npv10PayloadFormat.YUV420P10_LE,
            Npv10PayloadFormat.YUV420_8BIT_EXPANDED -> planar420P10ToP010(
                payload = payload,
                width = header.width,
                height = header.height
            )
            Npv10PayloadFormat.RAW12_NPV10_PACKED,
            Npv10PayloadFormat.RAW14_NPV10_PACKED -> throw IllegalArgumentException(
                "RAW packed NPV10 payload cannot be written as direct P010 sample without demosaic"
            )
        }
    }

    private fun validateP010PayloadLayout(payload: ByteArray) {
        require(payload.size % Short.SIZE_BYTES == 0) {
            "P010 payload layout must be 16-bit little-endian samples"
        }
        var offset = 0
        while (offset + 1 < payload.size) {
            val value = (payload[offset].toInt() and 0xFF) or
                ((payload[offset + 1].toInt() and 0xFF) shl 8)
            require(value and P010_LOW_BITS_MASK == 0) {
                "P010 payload layout mismatch: low padding bits are not zero"
            }
            offset += Short.SIZE_BYTES
        }
    }

    private fun readP010Sample(payload: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 1 < payload.size) { "P010 payload is truncated" }
        val value = (payload[offset].toInt() and 0xFF) or
            ((payload[offset + 1].toInt() and 0xFF) shl 8)
        return (value ushr P010_VALID_BITS_SHIFT).coerceIn(0, 1023)
    }

    internal fun colorConfigForOutput(
        source: QuickTimeColorAtomConfig,
        outputFormat: OutputFormat
    ): QuickTimeColorAtomConfig {
        if (outputFormat == OutputFormat.R210_444) return source.copy(fullRange = true)
        require(!source.fullRange) {
            "Full-range P010 conversion to ${outputFormat.sampleEntry} requires an explicit " +
                "full-to-limited code-value transform; refusing metadata-only range downgrade"
        }
        return source
    }

    private fun p010ToMovPayload(
        p010Payload: ByteArray,
        width: Int,
        height: Int,
        outputFormat: OutputFormat,
        colorConfig: QuickTimeColorAtomConfig
    ): ByteArray = when (outputFormat) {
        OutputFormat.R210_444 -> p010ToR210Payload(p010Payload, width, height, colorConfig)
        OutputFormat.V210_422 -> p010ToV210Payload(p010Payload, width, height)
        OutputFormat.HEVC_MAIN10_420 -> error("HEVC Main10 samples must be produced by the strict P010 encoder")
    }

    /**
     * Expand the recorded P010 signal into a full-range RGB 10-bit r210 sample. This preserves every
     * decoded RGB code without adding another chroma-subsampling stage; the source's native 4:2:0 chroma
     * is sampled at its recorded location and expanded to RGB for each pixel.
     */
    internal fun p010ToR210Payload(
        p010Payload: ByteArray,
        width: Int,
        height: Int,
        colorConfig: QuickTimeColorAtomConfig
    ): ByteArray {
        validateP010PayloadLayout(p010Payload)
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val rowBytes = RawTenBitPacker.rowBytes(RawTenBitPacker.Format.R210_444, safeWidth)
        val output = ByteArray(rowBytes * safeHeight)
        val bt2020 = colorConfig.matrixCoefficients == QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL
        val kr = if (bt2020) 0.2627 else 0.2126
        val kb = if (bt2020) 0.0593 else 0.0722
        val kg = 1.0 - kr - kb
        val redCr = 2.0 * (1.0 - kr)
        val blueCb = 2.0 * (1.0 - kb)
        val greenCb = 2.0 * kb * (1.0 - kb) / kg
        val greenCr = 2.0 * kr * (1.0 - kr) / kg

        for (row in 0 until safeHeight) {
            var outputOffset = row * rowBytes
            for (column in 0 until safeWidth) {
                val yCode = readP010Luma(p010Payload, safeWidth, safeHeight, row, column)
                val cbCode = readP010Chroma(
                    p010Payload,
                    safeWidth,
                    safeHeight,
                    row,
                    column,
                    chromaComponent = 0
                )
                val crCode = readP010Chroma(
                    p010Payload,
                    safeWidth,
                    safeHeight,
                    row,
                    column,
                    chromaComponent = 1
                )
                val y = if (colorConfig.fullRange) {
                    yCode / 1023.0
                } else {
                    ((yCode - 64) / 876.0).coerceIn(0.0, 1.0)
                }
                val cb = if (colorConfig.fullRange) {
                    (cbCode - 512) / 1023.0
                } else {
                    (cbCode - 512) / 896.0
                }
                val cr = if (colorConfig.fullRange) {
                    (crCode - 512) / 1023.0
                } else {
                    (crCode - 512) / 896.0
                }
                val r = ((y + redCr * cr).coerceIn(0.0, 1.0) * 1023.0).roundToInt()
                val g = ((y - greenCb * cb - greenCr * cr).coerceIn(0.0, 1.0) * 1023.0).roundToInt()
                val b = ((y + blueCb * cb).coerceIn(0.0, 1.0) * 1023.0).roundToInt()
                val word = (r shl 20) or (g shl 10) or b
                output[outputOffset] = (word ushr 24).toByte()
                output[outputOffset + 1] = (word ushr 16).toByte()
                output[outputOffset + 2] = (word ushr 8).toByte()
                output[outputOffset + 3] = word.toByte()
                outputOffset += Int.SIZE_BYTES
            }
        }
        return output
    }

    internal fun p010ToV210Payload(p010Payload: ByteArray, width: Int, height: Int): ByteArray {
        validateP010PayloadLayout(p010Payload)
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val rowBytes = v210RowBytes(safeWidth)
        val roundedWidth = ((safeWidth + V210_PIXELS_PER_GROUP - 1) / V210_PIXELS_PER_GROUP) *
            V210_PIXELS_PER_GROUP
        val output = ByteArray(rowBytes * safeHeight)
        for (row in 0 until safeHeight) {
            var outOffset = row * rowBytes
            var column = 0
            while (column < roundedWidth) {
                val y0 = readP010Luma(p010Payload, safeWidth, safeHeight, row, column)
                val y1 = readP010Luma(p010Payload, safeWidth, safeHeight, row, column + 1)
                val y2 = readP010Luma(p010Payload, safeWidth, safeHeight, row, column + 2)
                val y3 = readP010Luma(p010Payload, safeWidth, safeHeight, row, column + 3)
                val y4 = readP010Luma(p010Payload, safeWidth, safeHeight, row, column + 4)
                val y5 = readP010Luma(p010Payload, safeWidth, safeHeight, row, column + 5)
                val u0 = readP010Chroma(p010Payload, safeWidth, safeHeight, row, column, chromaComponent = 0)
                val v0 = readP010Chroma(p010Payload, safeWidth, safeHeight, row, column, chromaComponent = 1)
                val u1 = readP010Chroma(p010Payload, safeWidth, safeHeight, row, column + 2, chromaComponent = 0)
                val v1 = readP010Chroma(p010Payload, safeWidth, safeHeight, row, column + 2, chromaComponent = 1)
                val u2 = readP010Chroma(p010Payload, safeWidth, safeHeight, row, column + 4, chromaComponent = 0)
                val v2 = readP010Chroma(p010Payload, safeWidth, safeHeight, row, column + 4, chromaComponent = 1)
                writeV210Word(output, outOffset, u0, y0, v0)
                writeV210Word(output, outOffset + 4, y1, u1, y2)
                writeV210Word(output, outOffset + 8, v1, y3, u2)
                writeV210Word(output, outOffset + 12, y4, v2, y5)
                outOffset += V210_BYTES_PER_GROUP
                column += V210_PIXELS_PER_GROUP
            }
        }
        return output
    }

    private fun v210RowBytes(width: Int): Int =
        ((width.coerceAtLeast(1) + V210_ROW_ALIGNMENT_PIXELS - 1) / V210_ROW_ALIGNMENT_PIXELS) *
            V210_ROW_ALIGNMENT_BYTES

    private fun readP010Luma(
        payload: ByteArray,
        width: Int,
        height: Int,
        row: Int,
        column: Int
    ): Int {
        val y = row.coerceIn(0, height - 1)
        val x = column.coerceIn(0, width - 1)
        return readP010Sample(payload, (y * width + x) * Short.SIZE_BYTES)
    }

    private fun readP010Chroma(
        payload: ByteArray,
        width: Int,
        height: Int,
        row: Int,
        column: Int,
        chromaComponent: Int
    ): Int {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val chromaRow = (row / 2).coerceIn(0, chromaHeight - 1)
        val chromaColumn = (column / 2).coerceIn(0, chromaWidth - 1)
        val componentOffset = chromaComponent.coerceIn(0, 1) * Short.SIZE_BYTES
        val offset = width * height * Short.SIZE_BYTES +
            (chromaRow * chromaWidth + chromaColumn) * Short.SIZE_BYTES * 2 +
            componentOffset
        return readP010Sample(payload, offset)
    }

    private fun writeV210Word(output: ByteArray, offset: Int, first: Int, second: Int, third: Int) {
        val word = (first and V210_SAMPLE_MASK) or
            ((second and V210_SAMPLE_MASK) shl 10) or
            ((third and V210_SAMPLE_MASK) shl 20)
        output[offset] = (word and 0xFF).toByte()
        output[offset + 1] = ((word ushr 8) and 0xFF).toByte()
        output[offset + 2] = ((word ushr 16) and 0xFF).toByte()
        output[offset + 3] = ((word ushr 24) and 0xFF).toByte()
    }

    private fun isValidEnhancedP010Payload(payload: ByteArray, expectedSize: Int): Boolean {
        if (payload.size != expectedSize) return false
        return runCatching {
            validateP010PayloadLayout(payload)
            true
        }.getOrDefault(false)
    }

    private fun planar420P10ToP010(payload: ByteArray, width: Int, height: Int): ByteArray {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val chromaWidth = (safeWidth + 1) / 2
        val chromaHeight = (safeHeight + 1) / 2
        val lumaSamples = safeWidth * safeHeight
        val chromaSamples = chromaWidth * chromaHeight
        val yBytes = lumaSamples * Short.SIZE_BYTES
        val uOffset = yBytes
        val vOffset = uOffset + chromaSamples * Short.SIZE_BYTES
        val output = ByteArray(yBytes + chromaSamples * Short.SIZE_BYTES * 2)

        for (sampleIndex in 0 until lumaSamples) {
            writeP010Sample(output, sampleIndex * Short.SIZE_BYTES, readPlanarTenBitSample(payload, sampleIndex * Short.SIZE_BYTES))
        }
        var outputOffset = yBytes
        for (sampleIndex in 0 until chromaSamples) {
            writeP010Sample(
                output = output,
                offset = outputOffset,
                sample = readPlanarTenBitSample(payload, uOffset + sampleIndex * Short.SIZE_BYTES)
            )
            writeP010Sample(
                output = output,
                offset = outputOffset + Short.SIZE_BYTES,
                sample = readPlanarTenBitSample(payload, vOffset + sampleIndex * Short.SIZE_BYTES)
            )
            outputOffset += Short.SIZE_BYTES * 2
        }
        return output
    }

    private fun readPlanarTenBitSample(payload: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 1 < payload.size) { "NPV10 planar payload is truncated" }
        val sample = (payload[offset].toInt() and 0xFF) or
            ((payload[offset + 1].toInt() and 0xFF) shl 8)
        require(sample in 0..1023) {
            "NPV10 planar payload contains non-planar 10-bit sample data"
        }
        return sample
    }

    private fun writeP010Sample(output: ByteArray, offset: Int, sample: Int) {
        val value = sample.coerceIn(0, 1023) shl P010_VALID_BITS_SHIFT
        output[offset] = (value and 0xFF).toByte()
        output[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun hasChromaPlane(width: Int, height: Int): Boolean =
        chromaSampleCount(width, height) > 0

    private fun chromaSampleCount(width: Int, height: Int): Int {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        return ((safeWidth + 1) / 2) * ((safeHeight + 1) / 2)
    }

    private data class Npv10RawExportFrame(
        val index: Long,
        val timestampNs: Long,
        val payload: ByteArray,
        // Previous frame's SOURCE payload (temporal prior for the enhancer). A shared read-only
        // reference to the prior frame's [payload] — never mutated, so no extra copy is made.
        val previous: ByteArray? = null,
        // Per-frame SNCE nudge computed in order by the reader; identity (1f/0f) when SNCE is off.
        val snceSaturation: Float = 1f,
        val snceWarmth: Float = 0f,
        // NQIS is computed once by the ordered producer and consumed unchanged by CPU/GPU-quality paths.
        val qualityInstruction: NeoQualityInstruction? = null
    )

    private data class Npv10EnhancedExportFrame(
        val index: Long,
        val timestampNs: Long,
        val payload: ByteArray
    )

    private fun computeFocusMap(p010Payload: ByteArray, width: Int, height: Int): FloatArray {
        val focusMap = FloatArray(256)
        val tileWidth = width / 16
        val tileHeight = height / 16
        if (tileWidth <= 0 || tileHeight <= 0) return focusMap

        for (row in 0 until 16) {
            for (col in 0 until 16) {
                val startX = col * tileWidth
                val startY = row * tileHeight
                val centerX = startX + tileWidth / 2
                val centerY = startY + tileHeight / 2

                val luma = Array(4) { FloatArray(4) }
                for (r in 0 until 4) {
                    for (c in 0 until 4) {
                        val px = (centerX - 2 + c).coerceIn(0, width - 1)
                        val py = (centerY - 2 + r).coerceIn(0, height - 1)
                        val offset = (py * width + px) * 2
                        if (offset + 1 < p010Payload.size) {
                            val b0 = p010Payload[offset].toInt() and 0xFF
                            val b1 = p010Payload[offset + 1].toInt() and 0xFF
                            val val16 = b0 or (b1 shl 8)
                            luma[r][c] = val16.toFloat()
                        } else {
                            luma[r][c] = 0f
                        }
                    }
                }

                var diffSum = 0f
                var diffCount = 0
                for (r in 0 until 4) {
                    for (c in 0 until 3) {
                        diffSum += kotlin.math.abs(luma[r][c] - luma[r][c + 1])
                        diffCount++
                    }
                }
                for (r in 0 until 3) {
                    for (c in 0 until 4) {
                        diffSum += kotlin.math.abs(luma[r][c] - luma[r + 1][c])
                        diffCount++
                    }
                }

                val avgDiff = if (diffCount > 0) diffSum / diffCount else 0f
                val normalizedContrast = (avgDiff / 65535f).coerceIn(0f, 1f)
                focusMap[row * 16 + col] = normalizedContrast
            }
        }
        return focusMap
    }

    private fun buildFocusMapT35Payload(focusMap: FloatArray): ByteArray {
        val payload = ByteArray(1029)
        payload[0] = 0xB5.toByte()
        payload[1] = 0x00.toByte()
        payload[2] = 0x3C.toByte()
        payload[3] = 0x00.toByte()
        payload[4] = 0x01.toByte()

        val buffer = ByteBuffer.wrap(payload, 5, 1024).order(ByteOrder.LITTLE_ENDIAN)
        for (value in focusMap) {
            buffer.putFloat(value)
        }
        return payload
    }

    private fun isHevcPayload(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] == 0.toByte() && bytes[1] == 0.toByte()) {
            if (bytes[2] == 1.toByte()) return true
            if (bytes[2] == 0.toByte() && bytes[3] == 1.toByte()) return true
        }
        return false
    }

    private data class Npv10AsyncExportResult(
        val frameCount: Long,
        val accumulatedNoiseMetrics: VideoNoiseMetrics,
        val measuredMetricFrames: Int,
        val temporalFilterApplied: Boolean,
        val processingEvidence: Npv10ExportProcessingEvidence
    )

    private const val P010_VALID_BITS_SHIFT = 6
    private const val P010_LOW_BITS_MASK = (1 shl P010_VALID_BITS_SHIFT) - 1
    private const val V210_SAMPLE_MASK = 0x3FF
    private const val V210_PIXELS_PER_GROUP = 6
    private const val V210_BYTES_PER_GROUP = 16
    private const val V210_ROW_ALIGNMENT_PIXELS = 48
    private const val V210_ROW_ALIGNMENT_BYTES = 128
    private const val PIPELINE_BUFFER_CAPACITY = 3

    // ── Frame-count estimation (fixed on-disk field widths, see Npv10FileReader/-FileWriter) ──
    // File header after the magic: version + width + height + fps + bitDepth + metadataLength (6 × Int).
    private const val NPV10_FILE_HEADER_FIXED_BYTES = 6L * Int.SIZE_BYTES
    // Per-frame header after the frame magic: timestamp(Long) + width + height + payloadSize + crc.
    private const val NPV10_FRAME_HEADER_FIXED_BYTES = Long.SIZE_BYTES.toLong() + 4L * Int.SIZE_BYTES

    // ── CPU-parallel export bounds ──
    // Peak resident conversion buffers are capped to this so large P010 frames can't OOM the parallel
    // path; the sequential fallback (1 worker) is used when a single frame already exceeds the budget.
    // The budget is additionally clamped at runtime to 1/NPV10_CPU_HEAP_FRACTION of the device heap.
    private const val NPV10_CPU_MEMORY_BUDGET_BYTES = 512L * 1024L * 1024L
    private const val NPV10_CPU_HEAP_FRACTION = 3L
    private const val CPU_PIPELINE_CHANNEL_CAPACITY = 2
}

private fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { stream ->
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(Locale.US, byte.toInt() and 0xFF)
    }
}

private fun File.writeTextDurable(text: String) {
    FileOutputStream(this).use { stream ->
        stream.write(text.toByteArray(Charsets.UTF_8))
        stream.fd.sync()
    }
}

private const val HASH_BUFFER_BYTES = 1024 * 1024

private fun Npv10MovColorTagPlan.exportGpuActiveTransport(): GpuActiveTransport? =
    when (activeTransport.uppercase(Locale.US)) {
        GpuActiveTransport.REC709_SDR.name -> GpuActiveTransport.REC709_SDR
        GpuActiveTransport.BT2020_SDR.name -> GpuActiveTransport.BT2020_SDR
        GpuActiveTransport.BT2020_LOG_SDR.name -> GpuActiveTransport.BT2020_LOG_SDR
        GpuActiveTransport.BT2100_PQ_MAIN10.name -> GpuActiveTransport.BT2100_PQ_MAIN10
        Bt2100TransportPolicy.TRANSPORT_PQ_MAIN10 -> GpuActiveTransport.BT2100_PQ_MAIN10
        GpuActiveTransport.ANDROID_PQ_BT2020_MAIN10.name -> GpuActiveTransport.ANDROID_PQ_BT2020_MAIN10
        else -> null
    }

internal data class Npv10MovColorTagPlan(
    val colorConfig: QuickTimeColorAtomConfig,
    val selectedProfile: String,
    val selectedColorSpace: String,
    val activeTransport: String,
    val proprietaryProfileAuthority: String = SovereignVideoProfilePolicy.authorityForMovPlan(
        selectedProfile = selectedProfile,
        activeTransport = activeTransport
    ),
    val bt2100TransportCarrierOnly: Boolean = SovereignVideoProfilePolicy.isBt2100CarrierOnly(
        selectedProfile = selectedProfile,
        activeTransport = activeTransport
    ),
    val bt2100CarrierMode: String = SovereignVideoProfilePolicy.carrierFor(activeTransport),
    val vendorDynamicRangeProfileUsed: Boolean = false,
    val vendorDynamicMetadataWritten: Boolean = false,
    val dolbyVisionOrHdr10PlusSubstitutionBlocked: Boolean = true,
    val npv10PrivateMasterUnmodified: Boolean = true,
    val previewRecordingNpv10ParityPolicy: String = SovereignVideoProfilePolicy.PARITY_LOCK
)

internal object Npv10MovColorTagPolicy {
    private val signalTokenDelimiter = Regex("[^A-Za-z0-9]+")

    fun rec709(): Npv10MovColorTagPlan =
        Npv10MovColorTagPlan(
            colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited(),
            selectedProfile = "Rec.709 Standard",
            selectedColorSpace = "Rec.709",
            activeTransport = "REC709_SDR"
        )

    fun resolve(header: Npv10FileHeader): Npv10MovColorTagPlan {
        val values = header.metadataValues
        val selectedColorSpace = ManualVideoColorSelectionPolicy.normalizeColorSpace(
            values["selectedcolorspace"] ?: values["colorspace"] ?: values["colorprofile"]
        )
        val selectedProfile = ManualVideoColorSelectionPolicy.normalizeColorScience(
            values["selectedcolorscience"] ?: values["selectedprofile"] ?: values["colorprofile"] ?: values["mode"]
        )
        val recordingMode = values["recordingmode"] ?: values["capturemode"] ?: values["mode"]
        val key = listOf(selectedColorSpace, selectedProfile, recordingMode.orEmpty(), values["colorprofile"].orEmpty())
            .joinToString(separator = "|")
            .lowercase(Locale.US)
        val profileEvidence = listOf(
            selectedColorSpace,
            selectedProfile,
            values["colorprofile"].orEmpty()
        ).joinToString(separator = "|")
        val declaredTransport = metadataValue(
            values,
            "transport",
            "activetransport",
            "recordingtransport"
        )
        val declaredTransfer = metadataValue(
            values,
            "transfer",
            "transfercharacteristics",
            "colortransfer"
        )
        val capturedPixelTransfer = metadataValue(
            values,
            "pixeltransferencoding",
            "payloadtransferencoding",
            "capturedpixeltransfer"
        )
        val capturedPixelTransferApplied = metadataValue(
            values,
            "pixeltransferapplied",
            "payloadtransferapplied",
            "capturedpixeltransferapplied"
        ).equals("true", ignoreCase = true)
        val declaredSignalFamily = metadataValue(values, "signalfamily", "signalsourcefamily")
        val declaredHdrMode = metadataValue(
            values,
            "hdrmode",
            "videohdrmode",
            "requestedhdrmode",
            "effectivehdrmode"
        )
        val requestedSignalEvidence = listOf(
            declaredTransport,
            declaredTransfer,
            declaredSignalFamily,
            declaredHdrMode
        ).joinToString(separator = "|").lowercase(Locale.US)
        val capturedTransferEvidence = capturedPixelTransfer.lowercase(Locale.US)
        // Container HDR tags describe encoded pixels, not a requested look. Proprietary NDR/NIVUKX
        // P010 masters currently use Camera2 STANDARD/SDR transport and are developed from the RAW
        // ACEScg path elsewhere; a requested profile alone must never relabel those SDR codes as PQ.
        val explicitHlg = capturedPixelTransferApplied && capturedTransferEvidence.contains("hlg")
        val explicitPq = capturedPixelTransferApplied && (
            capturedTransferEvidence.contains("pq") ||
                capturedTransferEvidence.contains("st2084") ||
                capturedTransferEvidence.contains("st.2084")
            )
        val explicitLog = declaredTransport.contains("log", ignoreCase = true) ||
            declaredSignalFamily.equals("log", ignoreCase = true)
        val explicitSdr = (
            declaredTransport.contains("sdr", ignoreCase = true) ||
                declaredSignalFamily.equals("sdr", ignoreCase = true)
            ) && !explicitPq && !explicitHlg
        val hdrModeRequested = isHdrMode(recordingMode) ||
            isEnabledHdrMetadata(declaredHdrMode) ||
            declaredSignalFamily.equals("hdr", ignoreCase = true) ||
            (
                key.contains("apex") &&
                    containsSignalToken(profileEvidence, "HDR")
                )
        val logRequested = isLogMode(recordingMode)
        val normalOrProSdrMode = isNormalOrProSdrMode(recordingMode)
        val proprietaryHdrProfile = key.contains("ndr10") || key.contains("nivukx")
        val wideGamut = selectedColorSpace.contains("2020", ignoreCase = true) ||
            selectedColorSpace.contains("neo wide", ignoreCase = true) ||
            selectedColorSpace.contains("neogamut", ignoreCase = true) ||
            selectedColorSpace.contains("neo gamut", ignoreCase = true) ||
            selectedColorSpace.contains("aces", ignoreCase = true) ||
            selectedColorSpace.contains("wide gamut", ignoreCase = true)
        val requestedPlan = when {
            explicitHlg -> hdrPlan(
                selectedProfile = selectedProfile,
                selectedColorSpace = selectedColorSpace,
                transfer = QuickTimeColorAtomPolicy.TRANSFER_HLG,
                activeTransport = "BT.2100_HLG_MAIN10"
            )
            explicitPq -> hdrPlan(
                selectedProfile = selectedProfile,
                selectedColorSpace = selectedColorSpace,
                transfer = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                activeTransport = Bt2100TransportPolicy.TRANSPORT_PQ_MAIN10
            )
            explicitLog -> logPlan(selectedProfile, selectedColorSpace)
            explicitSdr -> sdrPlan(selectedProfile, selectedColorSpace, wideGamut)
            hdrModeRequested -> sdrPlan(
                selectedProfile = selectedProfile,
                selectedColorSpace = selectedColorSpace,
                wideGamut = wideGamut || requestedSignalEvidence.contains("2020")
            )
            logRequested -> logPlan(selectedProfile, selectedColorSpace)
            proprietaryHdrProfile -> sdrPlan(
                selectedProfile = selectedProfile,
                selectedColorSpace = selectedColorSpace,
                wideGamut = true
            )
            normalOrProSdrMode && wideGamut -> sdrPlan(
                selectedProfile = selectedProfile,
                selectedColorSpace = selectedColorSpace,
                wideGamut = true
            )
            normalOrProSdrMode -> rec709().copy(
                selectedProfile = selectedProfile,
                selectedColorSpace = selectedColorSpace
            )
            wideGamut -> sdrPlan(selectedProfile, selectedColorSpace, wideGamut = true)
            selectedColorSpace.contains("p3", ignoreCase = true) -> Npv10MovColorTagPlan(
                colorConfig = QuickTimeColorAtomConfig.displayP3SdrLimited(),
                selectedProfile = selectedProfile,
                selectedColorSpace = selectedColorSpace,
                activeTransport = "DISPLAY_P3_SDR"
            )
            else -> rec709().copy(
                selectedProfile = selectedProfile,
                selectedColorSpace = selectedColorSpace
            )
        }
        return Npv10StoredPixelColorPolicy.applyOutputTruth(header, requestedPlan)
    }

    private fun hdrPlan(
        selectedProfile: String,
        selectedColorSpace: String,
        transfer: Int,
        activeTransport: String
    ): Npv10MovColorTagPlan =
        Npv10MovColorTagPlan(
            colorConfig = QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = transfer,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = false
            ),
            selectedProfile = selectedProfile,
            selectedColorSpace = selectedColorSpace,
            activeTransport = activeTransport
        )

    private fun logPlan(
        selectedProfile: String,
        selectedColorSpace: String
    ): Npv10MovColorTagPlan =
        Npv10MovColorTagPlan(
            colorConfig = QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = false
            ),
            selectedProfile = selectedProfile,
            selectedColorSpace = selectedColorSpace,
            activeTransport = "BT2020_LOG_SDR"
        )

    private fun sdrPlan(
        selectedProfile: String,
        selectedColorSpace: String,
        wideGamut: Boolean
    ): Npv10MovColorTagPlan =
        if (wideGamut) {
            Npv10MovColorTagPlan(
                colorConfig = QuickTimeColorAtomConfig(
                    colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                    transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                    matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                    fullRange = false
                ),
                selectedProfile = selectedProfile,
                selectedColorSpace = selectedColorSpace,
                activeTransport = "BT2020_SDR"
            )
        } else {
            rec709().copy(
                selectedProfile = selectedProfile,
                selectedColorSpace = selectedColorSpace
            )
        }

    private fun metadataValue(values: Map<String, String>, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            values.entries.firstOrNull { entry -> entry.key.equals(key, ignoreCase = true) }?.value
        }.orEmpty()

    private fun isHdrMode(mode: String?): Boolean =
        mode.orEmpty().trim().uppercase(Locale.US).replace(" ", "_") in
            setOf("HDR", "HDR_VIDEO", "NDR", "NDR10+", "NIVUKX", "NIVUKX_VISION")

    private fun isEnabledHdrMetadata(value: String): Boolean {
        val normalized = value.trim().uppercase(Locale.US).replace(" ", "_")
        return normalized.isNotBlank() &&
            normalized !in setOf("OFF", "NONE", "SDR", "FALSE", "DISABLED")
    }

    private fun isLogMode(mode: String?): Boolean =
        mode.orEmpty().trim().uppercase(Locale.US).replace(" ", "_") in
            setOf("LOG", "LOG_VIDEO", "N_LOG", "N-LOG")

    private fun containsSignalToken(value: String, token: String): Boolean =
        value.split(signalTokenDelimiter)
            .any { part -> part.equals(token, ignoreCase = true) }

    private fun isNormalOrProSdrMode(mode: String?): Boolean =
        when (mode.orEmpty().trim().uppercase(Locale.US).replace(" ", "_")) {
            "NORMAL", "NORMAL_SDR", "PRO", "PRO_SDR", "SDR", "STANDARD_SDR" -> true
            else -> false
        }
}

internal data class Npv10ExportProcessingEvidence(
    val processingPath: String,
    val zeroCopy: Boolean,
    val gpuFallbackReason: String,
    val gpuUniformSignature: String
) {
    companion object {
        fun cpuFallback(
            signature: String,
            reason: String = "NATIVE_ACCELERATOR_UNAVAILABLE"
        ): Npv10ExportProcessingEvidence =
            Npv10ExportProcessingEvidence(
                processingPath = "CPU_P010_FALLBACK",
                zeroCopy = false,
                gpuFallbackReason = reason,
                gpuUniformSignature = signature
            )

        fun vulkanP010ComputeStaging(signature: String): Npv10ExportProcessingEvidence =
            Npv10ExportProcessingEvidence(
                processingPath = Npv10ExportGpuAccelerator.VULKAN_STAGING_PATH,
                zeroCopy = false,
                gpuFallbackReason = "NONE",
                gpuUniformSignature = signature
            )

        fun zeroCopyVulkanP010DoubleBuffer(signature: String): Npv10ExportProcessingEvidence =
            Npv10ExportProcessingEvidence(
                processingPath = Npv10ExportGpuAccelerator.ZERO_COPY_VULKAN_PATH,
                zeroCopy = true,
                gpuFallbackReason = Npv10ExportGpuAccelerator.PERSISTENT_MAPPED_HOST_COHERENT,
                gpuUniformSignature = signature
            )

        fun nativeSimdP010Direct(signature: String): Npv10ExportProcessingEvidence =
            Npv10ExportProcessingEvidence(
                processingPath = "NIV99_NATIVE_SIMD_P010_DIRECT",
                zeroCopy = false,
                gpuFallbackReason = "NONE",
                gpuUniformSignature = signature
            )

        fun mixedNativeSimdCpuFallback(
            signature: String,
            nativeSimdFrames: Long,
            cpuFrames: Long,
            lastFallbackReason: String
        ): Npv10ExportProcessingEvidence =
            Npv10ExportProcessingEvidence(
                processingPath = "NIV99_NATIVE_SIMD_P010_DIRECT_CPU_FALLBACK",
                zeroCopy = false,
                gpuFallbackReason =
                    "nativeSimdFrames=$nativeSimdFrames,cpuFallbackFrames=$cpuFrames," +
                        "lastReason=$lastFallbackReason",
                gpuUniformSignature = signature
            )

        fun fromNativeSimdFrameCounts(
            signature: String,
            nativeSimdFrames: Long,
            cpuFrames: Long,
            lastFallbackReason: String
        ): Npv10ExportProcessingEvidence {
            require(nativeSimdFrames >= 0L && cpuFrames >= 0L) {
                "NIV99 export frame counts must be non-negative"
            }
            return when {
                nativeSimdFrames > 0L && cpuFrames == 0L -> nativeSimdP010Direct(signature)
                nativeSimdFrames > 0L && cpuFrames > 0L -> mixedNativeSimdCpuFallback(
                    signature = signature,
                    nativeSimdFrames = nativeSimdFrames,
                    cpuFrames = cpuFrames,
                    lastFallbackReason = lastFallbackReason
                )
                else -> cpuFallback(signature, lastFallbackReason)
            }
        }

        fun mixedVulkanP010ComputeStagingCpuFallback(
            signature: String,
            gpuFrames: Long,
            cpuFrames: Long,
            lastFallbackReason: String
        ): Npv10ExportProcessingEvidence =
            Npv10ExportProcessingEvidence(
                processingPath = Npv10ExportGpuAccelerator.MIXED_STAGING_CPU_PATH,
                zeroCopy = false,
                gpuFallbackReason = "gpuFrames=$gpuFrames,cpuFallbackFrames=$cpuFrames,lastReason=$lastFallbackReason",
                gpuUniformSignature = signature
            )

        fun fromFrameCounts(
            signature: String,
            gpuFrames: Long,
            cpuFrames: Long,
            lastFallbackReason: String
        ): Npv10ExportProcessingEvidence =
            when {
                gpuFrames > 0L && cpuFrames == 0L -> vulkanP010ComputeStaging(signature)
                gpuFrames > 0L && cpuFrames > 0L -> mixedVulkanP010ComputeStagingCpuFallback(
                    signature = signature,
                    gpuFrames = gpuFrames,
                    cpuFrames = cpuFrames,
                    lastFallbackReason = lastFallbackReason
                )
                else -> cpuFallback(signature, lastFallbackReason)
            }
    }
}

internal data class Npv10ExportEnhancementProfile(
    val chromaSuppression: Double,
    val shadowChromaDeadband: Int,
    val fineTextureThreshold: Int,
    val mediumEdgeThreshold: Int,
    val strongEdgeThreshold: Int,
    val fineTextureDetailGain: Double,
    val mediumDetailGain: Double,
    val strongDetailGain: Double,
    val aliasSafeMaxDetailDelta: Int,
    val temporalLumaBlend: Double,
    val temporalChromaBlend: Double,
    val ditherAmplitude: Double,
    val normalSdrCalibrationStrength: Double = 0.0,
    val cumulativeDetailBudget: Int = Int.MAX_VALUE
) {
    fun withoutAdditiveDetail(): Npv10ExportEnhancementProfile =
        copy(
            fineTextureDetailGain = 0.0,
            mediumDetailGain = 0.0,
            strongDetailGain = 0.0
        )

    fun withExtremeDetailAuthority(): Npv10ExportEnhancementProfile {
        val boundedSdr = cumulativeDetailBudget <=
            NormalSdrVideoQualityPolicy.EXPORT_CUMULATIVE_DETAIL_BUDGET
        return copy(
            fineTextureThreshold = minOf(fineTextureThreshold, 4),
            mediumEdgeThreshold = minOf(mediumEdgeThreshold, 14),
            fineTextureDetailGain = maxOf(fineTextureDetailGain + 0.24, 2.24)
                .let { if (boundedSdr) minOf(it, 2.40) else it },
            mediumDetailGain = maxOf(mediumDetailGain + 0.48, 2.48)
                .let { if (boundedSdr) minOf(it, 2.40) else it },
            strongDetailGain = maxOf(strongDetailGain + 0.72, 2.72)
                .let { if (boundedSdr) minOf(it, 2.40) else it },
            aliasSafeMaxDetailDelta = minOf(
                aliasSafeMaxDetailDelta,
                if (boundedSdr) NormalSdrVideoQualityPolicy.EXPORT_CUMULATIVE_DETAIL_BUDGET else 240
            ),
            chromaSuppression = minOf(chromaSuppression, 0.002),
            ditherAmplitude = if (boundedSdr) ditherAmplitude else 0.0
        )
    }

    companion object {
        val DEFAULT = Npv10ExportEnhancementProfile(
            chromaSuppression = 0.006,
            shadowChromaDeadband = 36,
            fineTextureThreshold = 12,
            mediumEdgeThreshold = 24,
            strongEdgeThreshold = 76,
            fineTextureDetailGain = 1.50,
            mediumDetailGain = 1.66,
            strongDetailGain = 1.92,
            aliasSafeMaxDetailDelta = 220,
            temporalLumaBlend = 1.0,
            temporalChromaBlend = 1.0,
            ditherAmplitude = 0.0
        )

        val NDR10_PLUS = Npv10ExportEnhancementProfile(
            chromaSuppression = 0.003,
            shadowChromaDeadband = 36,
            fineTextureThreshold = 8,
            mediumEdgeThreshold = 22,
            strongEdgeThreshold = 74,
            fineTextureDetailGain = 1.56,
            mediumDetailGain = 1.94,
            strongDetailGain = 2.08,
            aliasSafeMaxDetailDelta = 220,
            temporalLumaBlend = 1.0,
            temporalChromaBlend = 1.0,
            ditherAmplitude = 0.0
        )

        val SDR_VIDEO = Npv10ExportEnhancementProfile(
            chromaSuppression = NormalSdrVideoQualityPolicy.EXPORT_CHROMA_SUPPRESSION,
            shadowChromaDeadband = NormalSdrVideoQualityPolicy.EXPORT_SHADOW_CHROMA_DEADBAND,
            fineTextureThreshold = NormalSdrVideoQualityPolicy.EXPORT_FINE_TEXTURE_THRESHOLD,
            mediumEdgeThreshold = NormalSdrVideoQualityPolicy.EXPORT_MEDIUM_EDGE_THRESHOLD,
            strongEdgeThreshold = NormalSdrVideoQualityPolicy.EXPORT_STRONG_EDGE_THRESHOLD,
            fineTextureDetailGain = NormalSdrVideoQualityPolicy.EXPORT_FINE_TEXTURE_DETAIL_GAIN,
            mediumDetailGain = NormalSdrVideoQualityPolicy.EXPORT_MEDIUM_DETAIL_GAIN,
            strongDetailGain = NormalSdrVideoQualityPolicy.EXPORT_STRONG_DETAIL_GAIN,
            aliasSafeMaxDetailDelta = NormalSdrVideoQualityPolicy.EXPORT_ALIAS_SAFE_MAX_DETAIL_DELTA,
            temporalLumaBlend = NormalSdrVideoQualityPolicy.EXPORT_TEMPORAL_LUMA_BLEND,
            temporalChromaBlend = NormalSdrVideoQualityPolicy.EXPORT_TEMPORAL_CHROMA_BLEND,
            ditherAmplitude = NormalSdrVideoQualityPolicy.EXPORT_DITHER_AMPLITUDE,
            normalSdrCalibrationStrength = NormalSdrVideoQualityPolicy.EXPORT_COLOR_CALIBRATION_STRENGTH,
            cumulativeDetailBudget = NormalSdrVideoQualityPolicy.EXPORT_CUMULATIVE_DETAIL_BUDGET
        )

        val PRO_SDR_VIDEO = SDR_VIDEO.copy(
            fineTextureDetailGain = NormalSdrVideoQualityPolicy.EXPORT_PRO_FINE_TEXTURE_DETAIL_GAIN,
            mediumDetailGain = NormalSdrVideoQualityPolicy.EXPORT_PRO_MEDIUM_DETAIL_GAIN,
            strongDetailGain = NormalSdrVideoQualityPolicy.EXPORT_PRO_STRONG_DETAIL_GAIN,
            temporalLumaBlend = NormalSdrVideoQualityPolicy.EXPORT_PRO_TEMPORAL_LUMA_BLEND,
            temporalChromaBlend = NormalSdrVideoQualityPolicy.EXPORT_PRO_TEMPORAL_CHROMA_BLEND,
            ditherAmplitude = NormalSdrVideoQualityPolicy.EXPORT_PRO_DITHER_AMPLITUDE
        )

        val NIVUKX_VISION = Npv10ExportEnhancementProfile(
            chromaSuppression = 0.002,
            shadowChromaDeadband = 40,
            fineTextureThreshold = 5,
            mediumEdgeThreshold = 16,
            strongEdgeThreshold = 68,
            fineTextureDetailGain = 1.72,
            mediumDetailGain = 1.92,
            strongDetailGain = 2.06,
            aliasSafeMaxDetailDelta = 220,
            temporalLumaBlend = 1.0,
            temporalChromaBlend = 1.0,
            ditherAmplitude = 0.0
        )

        val LOG_VIDEO = Npv10ExportEnhancementProfile(
            chromaSuppression = 0.003,
            shadowChromaDeadband = 40,
            fineTextureThreshold = 6,
            mediumEdgeThreshold = 18,
            strongEdgeThreshold = 72,
            fineTextureDetailGain = 1.44,
            mediumDetailGain = 1.88,
            strongDetailGain = 2.00,
            aliasSafeMaxDetailDelta = 220,
            temporalLumaBlend = 1.0,
            temporalChromaBlend = 1.0,
            ditherAmplitude = 0.0
        )

        fun fromColorTagPlan(
            plan: Npv10MovColorTagPlan,
            recordingMode: String? = null
        ): Npv10ExportEnhancementProfile {
            val key = listOf(plan.selectedProfile, plan.selectedColorSpace, plan.activeTransport)
                .joinToString(separator = "|")
                .lowercase(Locale.US)
            val transportKey = plan.activeTransport.lowercase(Locale.US)
            val sdrModeKey = listOf(recordingMode.orEmpty(), plan.selectedProfile)
                .joinToString(separator = "|")
                .lowercase(Locale.US)
            val sdrProfile = if (sdrModeKey.contains("pro")) PRO_SDR_VIDEO else SDR_VIDEO
            return when {
                transportKey == "rec709_sdr" ||
                    transportKey == "bt2020_sdr" ||
                    transportKey == "display_p3_sdr" -> sdrProfile
                key.contains("nivukx") -> NIVUKX_VISION
                transportKey.contains("log") -> LOG_VIDEO
                transportKey.contains("pq") -> NDR10_PLUS
                key.contains("log") -> LOG_VIDEO
                key.contains("ndr10") || key.contains("android_pq_bt2020_main10") -> NDR10_PLUS
                key.contains("rec709") ||
                    key.contains("rec.709") ||
                    key.contains("bt709") ||
                    key.contains("rec2020") ||
                    key.contains("rec.2020") ||
                    key.contains("bt2020_sdr") ||
                    key.contains("normal") ||
                    key.contains("natural") -> sdrProfile
                else -> DEFAULT
            }
        }
    }
}

internal object Npv10ExportEnhancementPolicy {
    fun isDitheringRequiredFor(mode: String): Boolean {
        val key = mode.uppercase(Locale.US)
        return key.contains("SDR") && (key.contains("NORMAL") || key.contains("PRO"))
    }

    internal val BLUE_NOISE_8X8: IntArray = intArrayOf(
        0, 48, 12, 60, 3, 51, 15, 63,
        32, 16, 44, 28, 35, 19, 47, 31,
        8, 56, 4, 52, 11, 59, 7, 55,
        40, 24, 36, 20, 43, 27, 39, 23,
        2, 50, 14, 62, 1, 49, 13, 61,
        34, 18, 46, 30, 33, 17, 45, 29,
        10, 58, 6, 54, 9, 57, 5, 53,
        42, 26, 38, 22, 41, 25, 37, 21
    )

    private const val P010_VALID_BITS_SHIFT = 6
    private const val NEUTRAL_CHROMA = 512
    private const val LOCAL_CHROMA_SPIKE_THRESHOLD = 80
    private const val STRICT_LOCAL_CHROMA_SPIKE_THRESHOLD = 44
    private const val LOCAL_CHROMA_NEIGHBOR_NEUTRAL_WINDOW = 72
    private const val STRICT_LOCAL_CHROMA_NEIGHBOR_NEUTRAL_WINDOW = 96
    private const val LOCAL_CHROMA_SPIKE_RETAIN = 0.16
    private const val STRICT_LOCAL_CHROMA_SPIKE_RETAIN = 0.012
    private const val LOCAL_CHROMA_SIGNAL_MIN_DELTA = 8
    private const val COHERENT_CHROMA_SUPPORT_WINDOW = 48
    private const val COHERENT_CHROMA_RETAIN_FLOOR = 0.52
    private const val TEMPORAL_LUMA_MOTION_THRESHOLD = 22
    private const val TEMPORAL_CHROMA_MOTION_THRESHOLD = 34

    /**
     * The CPU multi-core row-band scheduler for the CURRENT [enhanceP010] call, published per-thread so the
     * ~12 kernel passes below can pick it up WITHOUT changing their `pass(output, width, height, profile)`
     * signatures (which the source-inspection tests lock, and which keep the call sites byte-identical).
     * DEFAULT [EnhanceRowScheduler.SEQUENTIAL] ⇒ the exact original single-threaded behaviour; [enhanceP010]
     * sets it for the duration of one frame and restores it in a `finally`. Only ever read on the calling
     * develop-worker thread (the fan-out band threads run pure pixel arithmetic and never touch it).
     */
    private val activeRowScheduler: ThreadLocal<EnhanceRowScheduler> =
        ThreadLocal.withInitial { EnhanceRowScheduler.SEQUENTIAL }

    /** Non-null view of [activeRowScheduler] (a Java `ThreadLocal.get()` is a platform/nullable type). */
    private fun currentRowScheduler(): EnhanceRowScheduler =
        activeRowScheduler.get() ?: EnhanceRowScheduler.SEQUENTIAL

    fun enhanceP010(
        payload: ByteArray,
        width: Int,
        height: Int,
        previousP010Payload: ByteArray? = null,
        profile: Npv10ExportEnhancementProfile = Npv10ExportEnhancementProfile.DEFAULT,
        // P4 Phase A: live ColorScience preview grade LUT (display-referred R'G'B', size N). Null unless
        // the caller resolved an SDR grade for WYSIWYG parity; HDR/LOG never pass one (no double-tone).
        gradeLut: FloatArray? = null,
        gradeLutSize: Int = 0,
        // SNCE v2 HVC per-frame nudge (export ABI slot 11 = saturation multiplier about neutral,
        // slot 9 = warmth Cb/Cr bias). Defaults are the identity so every existing caller/test is a
        // strict byte no-op; the converter feeds the per-frame SNCE values so the CPU/hybrid lane matches
        // the GPU export accelerator (which applies the same math in neo_p010_export_enhance.comp).
        saturation: Float = 1f,
        warmth: Float = 0f,
        // Row-band CPU parallelism for the offline single-worker SDR/NDR develop. DEFAULT SEQUENTIAL keeps
        // every existing caller/test byte-identical; the record-develop worker passes a per-clip parallel
        // scheduler so the heavy kernel passes fan out across all CPU cores (same bytes, less wall-clock).
        rowScheduler: EnhanceRowScheduler = EnhanceRowScheduler.SEQUENTIAL
    ): ByteArray {
        val previousRowScheduler = currentRowScheduler()
        activeRowScheduler.set(rowScheduler)
        try {
            val output = payload.copyOf()
            stabilizeLowMotionTemporalNoise(output, previousP010Payload, width, height, profile)
            suppressChromaNoise(output, width, height, profile)
            applyShadowChromaDeadband(output, width, height, profile)
            smoothLocalChromaSpikes(output, width, height, profile)
            applyNormalSdrColorCalibration(output, width, height, profile)
            // Final chroma-only op BEFORE the luma-detail lanes — byte-for-byte mirror of the GPU shader's
            // SNCE stage, so a GPU-accelerated frame and a CPU frame of the same clip carry identical colour.
            applySnceChromaNudge(output, width, height, saturation, warmth)
            val preDetailPayload = output.copyOf() // Always capture baseline for chroma ratio math
            liftEdgeAwareLumaDetail(output, width, height, profile)
            recoverApexMicroTextureLuma(output, width, height, profile)
            applyApexLocalContrastLuma(output, width, height, profile)
            applyApexShadowNoiseFloorLuma(output, width, height, profile)
            applyApexHighlightTextureLuma(output, width, height, profile)
            applyApexEdgeCoherenceLuma(output, width, height, profile)
            applyApexTonalDensityLuma(output, width, height, profile)
            applyApexLongDistanceDetailLuma(output, width, height, profile)

            // Chroma Sharpening Compensation: Maintain Luma-Chroma ratio so micro-contrast boosts
            // don't desaturate the edges (fixing the 'white halo' / color fringing bug).
            applyChromaSharpeningCompensation(preDetailPayload, output, width, height)

            applyBlueNoiseDither(output, width, height, profile)
            if (hasBoundedSdrDetailBudget(profile)) {
                enforceCumulativeDetailBudget(
                    payload = output,
                    baseline = preDetailPayload,
                    width = width,
                    height = height,
                    budget = profile.cumulativeDetailBudget
                )
            }
            // P4 Phase A: apply the live preview grade LAST (the color look on top of detail/denoise),
            // so the exported frame carries the same Oklab/creative/profile grade the user saw in preview.
            if (gradeLut != null && gradeLutSize >= 2) {
                applyPreviewGradeLut(output, width, height, gradeLut, gradeLutSize)
            }
            return output
        } finally {
            activeRowScheduler.set(previousRowScheduler)
        }
    }

    /**
     * The 7 scalar uniforms the GPU enhance-lane shader (record_develop_enhance_lane_p010.comp) needs to
     * reproduce this [profile]'s live-parity chroma passes. Order matches the shader's EnhanceLaneUniforms
     * block exactly: temporalLumaBlend, temporalChromaBlend, chromaSuppression, shadowChromaDeadband,
     * ditherAmplitude, isStrictNormalSdrExport (0/1), isStrictVisibleCleanExport (0/1).
     */
    internal fun enhanceLaneGpuUniforms(profile: Npv10ExportEnhancementProfile): FloatArray = floatArrayOf(
        profile.temporalLumaBlend.toFloat(),
        profile.temporalChromaBlend.toFloat(),
        profile.chromaSuppression.toFloat(),
        profile.shadowChromaDeadband.toFloat(),
        profile.ditherAmplitude.toFloat(),
        if (isStrictNormalSdrExportProfile(profile)) 1f else 0f,
        if (isStrictVisibleCleanExportProfile(profile)) 1f else 0f
    )

    /**
     * LIVE-CINEMA parity luma enhance — a faithful CPU port of the live offscreen-EGL record shader's
     * edge-aware 5-tap bilateral denoise + halo-guarded unsharp (offscreen_egl_encoder_bridge.cpp:860-906),
     * applied to the P010 LUMA plane only. The SDR/NDR develop converter routes every frame through this
     * (default-ON RecordDevelopLiveParityFlag) so the converted file's sharpness / edges / noise match the
     * cinema-camera record output instead of the over-sharpened lift+Apex stack.
     *
     * Math runs in the encoded (non-linear) signal domain in normalized [0,1] = code/1023, exactly like the
     * shader. The gaussian low-pass reference is built from the ORIGINAL neighbours while detail =
     * (denoised - gauss), mirroring the shader (a naive denoised - blur(denoised) would not match). The
     * unsharp amount is uSharpness·0.85 with a halo guard that rolls it off on strong edges, clamped to
     * ±(0.05 + 0.05·amt). Chroma (UV) is untouched — the develop kernel's chroma-NR handles it.
     *
     * Returns a NEW array; [payload] (the temporal prior for the successor frame) is never mutated.
     */
    fun enhanceLiveCinemaLumaP010(
        payload: ByteArray,
        width: Int,
        height: Int,
        uSharpness: Double,
        uDenoise: Double,
        rowScheduler: EnhanceRowScheduler = EnhanceRowScheduler.SEQUENTIAL
    ): ByteArray {
        val previousRowScheduler = currentRowScheduler()
        activeRowScheduler.set(rowScheduler)
        try {
            val output = payload.copyOf()
            val safeWidth = width.coerceAtLeast(1)
            val safeHeight = height.coerceAtLeast(1)
            if (safeWidth < 3 || safeHeight < 3) return output
            val dn = uDenoise.coerceIn(0.0, 1.0)
            val amt = uSharpness.coerceIn(0.0, 1.0) * 0.85       // shader: amt = clamp(uSharpness,0,1)*0.85
            if (dn <= 0.0 && amt <= 0.0) return output
            val sw = 1.11                                        // shader axial spatial weight
            val sigmaR = (0.010 + 0.045 * dn).coerceIn(0.010, 0.05)
            val invR2 = 1.0 / (2.0 * sigmaR * sigmaR)
            val cap = 0.05 + 0.05 * amt
            val gaussWeight = 1.0 + 4.0 * sw                     // 5.44
            val original = currentRowScheduler().snapshot(payload)

            fun luma(row: Int, column: Int): Double {
                val r = row.coerceIn(0, safeHeight - 1)
                val c = column.coerceIn(0, safeWidth - 1)
                val offset = (r * safeWidth + c) * Short.SIZE_BYTES
                return readP010Sample(original, offset) / 1023.0
            }

            currentRowScheduler().forEachRow(0, safeHeight) { row ->
                for (column in 0 until safeWidth) {
                    val c = luma(row, column)
                    val n = luma(row - 1, column)
                    val s = luma(row + 1, column)
                    val w = luma(row, column - 1)
                    val e = luma(row, column + 1)
                    // Edge-aware 5-tap bilateral (center + 4 axial), range weight (1/(1+x))^2.
                    var accBil = c
                    var wBil = 1.0
                    // Keep the shader-order n/s/w/e accumulation exactly, but do not allocate a
                    // four-element DoubleArray for every pixel. At 4096x3072 that temporary-array
                    // pattern creates more than twelve million objects in one pass and can trap ART
                    // in blocking allocation/GC before the first Main10 frame reaches MediaCodec.
                    var dl = n - c
                    var rwBase = 1.0 / (1.0 + dl * dl * invR2)
                    var wb = sw * rwBase * rwBase
                    accBil += n * wb
                    wBil += wb
                    dl = s - c
                    rwBase = 1.0 / (1.0 + dl * dl * invR2)
                    wb = sw * rwBase * rwBase
                    accBil += s * wb
                    wBil += wb
                    dl = w - c
                    rwBase = 1.0 / (1.0 + dl * dl * invR2)
                    wb = sw * rwBase * rwBase
                    accBil += w * wb
                    wBil += wb
                    dl = e - c
                    rwBase = 1.0 / (1.0 + dl * dl * invR2)
                    wb = sw * rwBase * rwBase
                    accBil += e * wb
                    wBil += wb
                    val denoised = c * (1.0 - dn) + (accBil / wBil) * dn
                    // Fixed spatial-only gaussian reference from the ORIGINAL neighbours.
                    val gauss = (c + (n + s + w + e) * sw) / gaussWeight
                    val detail = denoised - gauss
                    val haloGuard = 1.0 - smoothStep(0.06, 0.20, kotlin.math.abs(detail))
                    var boost = detail * amt * haloGuard
                    if (boost > cap) boost = cap else if (boost < -cap) boost = -cap
                    val sharpened = (denoised + boost).coerceIn(0.0, 1.0)
                    val offset = (row * safeWidth + column) * Short.SIZE_BYTES
                    writeP010Sample(output, offset, (sharpened * 1023.0 + 0.5).toInt().coerceIn(0, 1023))
                }
            }
            return output
        } finally {
            activeRowScheduler.set(previousRowScheduler)
        }
    }

    fun measureP010NoiseMetrics(
        payload: ByteArray,
        previousP010Payload: ByteArray?,
        width: Int,
        height: Int
    ): VideoNoiseMetrics {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val lumaSamples = safeWidth * safeHeight
        if (payload.size < lumaSamples * Short.SIZE_BYTES) return VideoNoiseMetrics.EMPTY
        val lumaStride = max(1, lumaSamples / 4096)
        var lumaNoiseSum = 0.0
        var lumaNoiseCount = 0
        var temporalSum = 0.0
        var rgbVarianceSum = 0.0
        var rgbVarianceCount = 0
        var temporalCount = 0
        var sampleIndex = 0
        while (sampleIndex < lumaSamples) {
            val offset = sampleIndex * Short.SIZE_BYTES
            val y = readP010Sample(payload, offset)
            val row = sampleIndex / safeWidth
            val column = sampleIndex % safeWidth
            if (row > 0 && row < safeHeight - 1 && column > 0 && column < safeWidth - 1) {
                val left = readP010Sample(payload, offset - Short.SIZE_BYTES)
                val right = readP010Sample(payload, offset + Short.SIZE_BYTES)
                val above = readP010Sample(payload, offset - safeWidth * Short.SIZE_BYTES)
                val below = readP010Sample(payload, offset + safeWidth * Short.SIZE_BYTES)
                // A whole-frame standard deviation is scene contrast, not sensor noise. Estimate only the
                // isotropic high-frequency residual: a real edge is strong on one axis and therefore rejected
                // by min(horizontal, vertical), while uncorrelated grain remains on both axes. The cap prevents
                // an isolated hot pixel from making the entire frame look noisy to the quality policy.
                val horizontalResidual = kotlin.math.abs(y * 2 - left - right) * 0.5
                val verticalResidual = kotlin.math.abs(y * 2 - above - below) * 0.5
                lumaNoiseSum += minOf(horizontalResidual, verticalResidual, 96.0)
                lumaNoiseCount += 1
            }
            if (previousP010Payload != null && previousP010Payload.size == payload.size) {
                val previousY = readP010Sample(previousP010Payload, offset)
                val lumaDelta = kotlin.math.abs(y - previousY)
                val chromaIndex = chromaPairOffsetForLumaSample(safeWidth, safeHeight, sampleIndex)
                if (chromaIndex + Short.SIZE_BYTES < payload.size) {
                    val u = readP010Sample(payload, chromaIndex)
                    val v = readP010Sample(payload, chromaIndex + Short.SIZE_BYTES)
                    val previousU = readP010Sample(previousP010Payload, chromaIndex)
                    val previousV = readP010Sample(previousP010Payload, chromaIndex + Short.SIZE_BYTES)
                    val chromaDelta = maxOf(
                        kotlin.math.abs(u - previousU),
                        kotlin.math.abs(v - previousV)
                    )
                    // Coherent movement is not noise. Measure temporal instability only where the same
                    // low-motion gate used by the real temporal filter can safely operate; otherwise moving
                    // subjects were assigned extra denoise and lost texture during conversion.
                    if (lumaDelta <= TEMPORAL_LUMA_MOTION_THRESHOLD &&
                        chromaDelta <= TEMPORAL_CHROMA_MOTION_THRESHOLD
                    ) {
                        temporalSum += lumaDelta.toDouble()
                        rgbVarianceSum += rgbFrameDifference(
                            y = y,
                            u = u,
                            v = v,
                            previousY = previousY,
                            previousU = previousU,
                            previousV = previousV
                        )
                        rgbVarianceCount += 1
                        temporalCount += 1
                    }
                }
            }
            sampleIndex += lumaStride
        }

        val chromaBase = lumaSamples * Short.SIZE_BYTES
        val chromaSamples = (payload.size - chromaBase) / (Short.SIZE_BYTES * 2)
        val chromaStride = max(1, chromaSamples / 2048)
        var chromaNoiseSum = 0.0
        var chromaCount = 0
        var chromaIndex = 0
        while (chromaIndex < chromaSamples) {
            val offset = chromaBase + chromaIndex * Short.SIZE_BYTES * 2
            val u = readP010Sample(payload, offset)
            val v = readP010Sample(payload, offset + Short.SIZE_BYTES)
            val chromaRowWidth = (safeWidth + 1) / 2
            val chromaRow = chromaIndex / chromaRowWidth
            val chromaColumn = chromaIndex % chromaRowWidth
            val chromaHeight = (safeHeight + 1) / 2
            if (chromaRow > 0 && chromaRow < chromaHeight - 1 &&
                chromaColumn > 0 && chromaColumn < chromaRowWidth - 1
            ) {
                val leftOffset = offset - Short.SIZE_BYTES * 2
                val rightOffset = offset + Short.SIZE_BYTES * 2
                val aboveOffset = offset - chromaRowWidth * Short.SIZE_BYTES * 2
                val belowOffset = offset + chromaRowWidth * Short.SIZE_BYTES * 2
                val uHorizontal = kotlin.math.abs(
                    u * 2 - readP010Sample(payload, leftOffset) - readP010Sample(payload, rightOffset)
                ) * 0.5
                val uVertical = kotlin.math.abs(
                    u * 2 - readP010Sample(payload, aboveOffset) - readP010Sample(payload, belowOffset)
                ) * 0.5
                val vHorizontal = kotlin.math.abs(
                    v * 2 - readP010Sample(payload, leftOffset + Short.SIZE_BYTES) -
                        readP010Sample(payload, rightOffset + Short.SIZE_BYTES)
                ) * 0.5
                val vVertical = kotlin.math.abs(
                    v * 2 - readP010Sample(payload, aboveOffset + Short.SIZE_BYTES) -
                        readP010Sample(payload, belowOffset + Short.SIZE_BYTES)
                ) * 0.5
                // Distance from neutral is saturation, not noise. Local second-order residual preserves a
                // coherent rich colour field while still detecting alternating/random chroma contamination.
                chromaNoiseSum += minOf(uHorizontal, uVertical, 128.0)
                chromaNoiseSum += minOf(vHorizontal, vVertical, 128.0)
                chromaCount += 2
            } else if (chromaSamples < 9 && (chromaRow > 0 || chromaColumn > 0)) {
                // Tiny unit/diagnostic frames have no interior chroma sample. Keep their evidence useful
                // without restoring the old saturation-as-noise bug: measure neighbour variation only.
                var uResidual = 0
                var vResidual = 0
                if (chromaColumn > 0) {
                    val leftOffset = offset - Short.SIZE_BYTES * 2
                    uResidual = maxOf(uResidual, kotlin.math.abs(u - readP010Sample(payload, leftOffset)))
                    vResidual = maxOf(
                        vResidual,
                        kotlin.math.abs(v - readP010Sample(payload, leftOffset + Short.SIZE_BYTES))
                    )
                }
                if (chromaRow > 0) {
                    val aboveOffset = offset - chromaRowWidth * Short.SIZE_BYTES * 2
                    uResidual = maxOf(uResidual, kotlin.math.abs(u - readP010Sample(payload, aboveOffset)))
                    vResidual = maxOf(
                        vResidual,
                        kotlin.math.abs(v - readP010Sample(payload, aboveOffset + Short.SIZE_BYTES))
                    )
                }
                chromaNoiseSum += minOf(uResidual.toDouble(), 128.0)
                chromaNoiseSum += minOf(vResidual.toDouble(), 128.0)
                chromaCount += 2
            }
            if (previousP010Payload != null && previousP010Payload.size == payload.size) {
                val uDelta = kotlin.math.abs(u - readP010Sample(previousP010Payload, offset))
                val vDelta = kotlin.math.abs(
                    v - readP010Sample(previousP010Payload, offset + Short.SIZE_BYTES)
                )
                if (uDelta <= TEMPORAL_CHROMA_MOTION_THRESHOLD) {
                    temporalSum += uDelta.toDouble()
                    temporalCount += 1
                }
                if (vDelta <= TEMPORAL_CHROMA_MOTION_THRESHOLD) {
                    temporalSum += vDelta.toDouble()
                    temporalCount += 1
                }
            }
            chromaIndex += chromaStride
        }

        return VideoNoiseMetrics(
            chromaNoise = if (chromaCount == 0) 0f else (chromaNoiseSum / chromaCount / 512.0).toFloat(),
            lumaNoise = if (lumaNoiseCount == 0) 0f else (lumaNoiseSum / lumaNoiseCount / 1023.0).toFloat(),
            temporalInstability = if (temporalCount == 0) 0f else (temporalSum / temporalCount / 1023.0).toFloat(),
            frameToFrameRgbVariance = if (rgbVarianceCount == 0) 0f else (rgbVarianceSum / rgbVarianceCount).toFloat()
        ).clamped()
    }

    private fun stabilizeLowMotionTemporalNoise(
        payload: ByteArray,
        previous: ByteArray?,
        width: Int,
        height: Int,
        profile: Npv10ExportEnhancementProfile
    ) {
        if (previous == null || previous.size != payload.size) return
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val lumaSamples = safeWidth * safeHeight
        val lumaBytes = lumaSamples * Short.SIZE_BYTES
        // Point-wise per luma sample: reads/writes only its own offset ⇒ row-band-parallel byte-identically.
        currentRowScheduler().forEachStrided(0, lumaBytes, Short.SIZE_BYTES) { offset ->
            val current = readP010Sample(payload, offset)
            val prior = readP010Sample(previous, offset)
            if (kotlin.math.abs(current - prior) <= TEMPORAL_LUMA_MOTION_THRESHOLD) {
                writeP010Sample(payload, offset, blend(current, prior, profile.temporalLumaBlend))
            }
        }
        // Chroma: every remaining short. This stride is ONE short (2 bytes), so the inclusive guard
        // `offset + Short.SIZE_BYTES <= payload.size` is deliberate — it covers the FINAL chroma short
        // (endExclusive = size - Short.SIZE_BYTES + 1). (The exclusive `<` used by the full-pair loops
        // below would skip that last short here; this matches the current pre-parallelisation behaviour.)
        // Point-wise ⇒ identical bytes across any band split.
        currentRowScheduler().forEachStrided(
            lumaBytes,
            payload.size - Short.SIZE_BYTES + 1,
            Short.SIZE_BYTES
        ) { offset ->
            val current = readP010Sample(payload, offset)
            val prior = readP010Sample(previous, offset)
            if (kotlin.math.abs(current - prior) <= TEMPORAL_CHROMA_MOTION_THRESHOLD) {
                writeP010Sample(payload, offset, blend(current, prior, profile.temporalChromaBlend))
            }
        }
    }

    private fun suppressChromaNoise(
        payload: ByteArray,
        width: Int,
        height: Int,
        profile: Npv10ExportEnhancementProfile
    ) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val chromaWidth = (safeWidth + 1) / 2
        val chromaHeight = (safeHeight + 1) / 2
        val chromaBase = safeWidth * safeHeight * Short.SIZE_BYTES
        val original = currentRowScheduler().snapshot(payload)
        currentRowScheduler().forEachRow(0, chromaHeight) { row ->
            for (column in 0 until chromaWidth) {
                val offset = chromaBase + (row * chromaWidth + column) * Short.SIZE_BYTES * 2
                val u = readP010Sample(original, offset)
                val v = readP010Sample(original, offset + Short.SIZE_BYTES)
                val neighborU = chromaNeighborAverage(
                    original,
                    chromaBase,
                    chromaWidth,
                    chromaHeight,
                    row,
                    column,
                    componentOffset = 0
                )
                val neighborV = chromaNeighborAverage(
                    original,
                    chromaBase,
                    chromaWidth,
                    chromaHeight,
                    row,
                    column,
                    componentOffset = Short.SIZE_BYTES
                )
                writeP010Sample(payload, offset, suppressChromaSample(u, neighborU, profile))
                writeP010Sample(payload, offset + Short.SIZE_BYTES, suppressChromaSample(v, neighborV, profile))
            }
        }
    }

    private fun applyShadowChromaDeadband(
        payload: ByteArray,
        width: Int,
        height: Int,
        profile: Npv10ExportEnhancementProfile
    ) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val chromaWidth = (safeWidth + 1) / 2
        val chromaHeight = (safeHeight + 1) / 2
        val chromaBase = safeWidth * safeHeight * Short.SIZE_BYTES
        val original = currentRowScheduler().snapshot(payload)
        currentRowScheduler().forEachRow(0, chromaHeight) { row ->
            for (column in 0 until chromaWidth) {
                val offset = chromaBase + (row * chromaWidth + column) * Short.SIZE_BYTES * 2
                val neighborU = chromaNeighborAverage(
                    original,
                    chromaBase,
                    chromaWidth,
                    chromaHeight,
                    row,
                    column,
                    componentOffset = 0
                )
                val neighborV = chromaNeighborAverage(
                    original,
                    chromaBase,
                    chromaWidth,
                    chromaHeight,
                    row,
                    column,
                    componentOffset = Short.SIZE_BYTES
                )
                deadbandChromaSample(payload, offset, neighborU, profile)
                deadbandChromaSample(payload, offset + Short.SIZE_BYTES, neighborV, profile)
            }
        }
    }

    private fun smoothLocalChromaSpikes(
        payload: ByteArray,
        width: Int,
        height: Int,
        profile: Npv10ExportEnhancementProfile
    ) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val chromaWidth = (safeWidth + 1) / 2
        val chromaHeight = (safeHeight + 1) / 2
        if (chromaWidth * chromaHeight <= 1) return
        val original = currentRowScheduler().snapshot(payload)
        val chromaBase = safeWidth * safeHeight * Short.SIZE_BYTES
        val strictVisibleCleanExport = isStrictVisibleCleanExportProfile(profile)
        currentRowScheduler().forEachRow(0, chromaHeight) { row ->
            for (column in 0 until chromaWidth) {
                val pairOffset = chromaBase + (row * chromaWidth + column) * Short.SIZE_BYTES * 2
                val neighborU = chromaNeighborAverage(original, chromaBase, chromaWidth, chromaHeight, row, column, componentOffset = 0)
                val neighborV = chromaNeighborAverage(original, chromaBase, chromaWidth, chromaHeight, row, column, componentOffset = Short.SIZE_BYTES)
                smoothChromaComponent(original, payload, pairOffset, neighborU, strictVisibleCleanExport)
                smoothChromaComponent(
                    original,
                    payload,
                    pairOffset + Short.SIZE_BYTES,
                    neighborV,
                    strictVisibleCleanExport
                )
            }
        }
    }

    private fun chromaNeighborAverage(
        payload: ByteArray,
        chromaBase: Int,
        chromaWidth: Int,
        chromaHeight: Int,
        row: Int,
        column: Int,
        componentOffset: Int
    ): Int {
        var total = 0
        var count = 0
        fun add(neighborRow: Int, neighborColumn: Int) {
            if (neighborRow !in 0 until chromaHeight || neighborColumn !in 0 until chromaWidth) return
            val offset = chromaBase + (neighborRow * chromaWidth + neighborColumn) * Short.SIZE_BYTES * 2 + componentOffset
            total += readP010Sample(payload, offset)
            count += 1
        }
        add(row - 1, column)
        add(row + 1, column)
        add(row, column - 1)
        add(row, column + 1)
        return if (count == 0) NEUTRAL_CHROMA else total / count
    }

    private fun smoothChromaComponent(
        original: ByteArray,
        output: ByteArray,
        offset: Int,
        neighborAverage: Int,
        strictNormalSdrExport: Boolean
    ) {
        val current = readP010Sample(original, offset)
        val currentDelta = current - NEUTRAL_CHROMA
        val neighborDelta = neighborAverage - NEUTRAL_CHROMA
        val spikeThreshold = if (strictNormalSdrExport) {
            STRICT_LOCAL_CHROMA_SPIKE_THRESHOLD
        } else {
            LOCAL_CHROMA_SPIKE_THRESHOLD
        }
        val neighborNeutralWindow = if (strictNormalSdrExport) {
            STRICT_LOCAL_CHROMA_NEIGHBOR_NEUTRAL_WINDOW
        } else {
            LOCAL_CHROMA_NEIGHBOR_NEUTRAL_WINDOW
        }
        val isolatedSpike = kotlin.math.abs(currentDelta) >= spikeThreshold &&
            kotlin.math.abs(current - neighborAverage) >= spikeThreshold &&
            kotlin.math.abs(neighborDelta) <= neighborNeutralWindow
        if (isolatedSpike) {
            val retain = if (strictNormalSdrExport) {
                STRICT_LOCAL_CHROMA_SPIKE_RETAIN
            } else {
                LOCAL_CHROMA_SPIKE_RETAIN
            }
            val smoothed = NEUTRAL_CHROMA + (currentDelta * retain).toInt()
            writeP010Sample(output, offset, smoothed)
        }
    }

    /**
     * SNCE v2 HVC per-frame chroma nudge — byte-for-byte port of the GPU export shader's final chroma op
     * (neo_p010_export_enhance.comp): saturation scales each chroma delta about neutral 512, warmth biases
     * Cb (U) down and Cr (V) up (a warm-tilt WB residual). Luma is never touched. fp32 with `+0.5` round
     * then clamp, matching the shader's `uint(clamp(512 + Δ + 0.5, 0, 1023))` exactly. This is the ONLY
     * thing that puts the SNCE saturation/warmth into CPU-developed export pixels, so a hybrid clip's
     * CPU frames and GPU-accelerated frames stay colour-identical. Strict no-op at saturation=1/warmth=0.
     */
    private fun applySnceChromaNudge(
        payload: ByteArray,
        width: Int,
        height: Int,
        saturation: Float,
        warmth: Float
    ) {
        val snceSat = saturation.coerceIn(0.5f, 2.0f)
        val snceWarmth = warmth.coerceIn(-0.2f, 0.2f)
        if (snceSat == 1.0f && snceWarmth == 0.0f) return
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val chromaBase = safeWidth * safeHeight * Short.SIZE_BYTES
        // Point-wise per U/V pair (matches `while (offset + Short.SIZE_BYTES < payload.size)` ⇒
        // endExclusive = size - Short.SIZE_BYTES), stride = one pair. Disjoint per band ⇒ byte-identical.
        currentRowScheduler().forEachStrided(
            chromaBase,
            payload.size - Short.SIZE_BYTES,
            Short.SIZE_BYTES * 2
        ) { offset ->
            val u = readP010Sample(payload, offset).toFloat()
            val v = readP010Sample(payload, offset + Short.SIZE_BYTES).toFloat()
            val snceU = (u - NEUTRAL_CHROMA) * snceSat - snceWarmth * 220f
            val snceV = (v - NEUTRAL_CHROMA) * snceSat + snceWarmth * 220f
            writeP010Sample(payload, offset, (NEUTRAL_CHROMA + snceU + 0.5f).coerceIn(0f, 1023f).toInt())
            writeP010Sample(payload, offset + Short.SIZE_BYTES, (NEUTRAL_CHROMA + snceV + 0.5f).coerceIn(0f, 1023f).toInt())
        }
    }

    private fun applyNormalSdrColorCalibration(
        payload: ByteArray,
        width: Int,
        height: Int,
        profile: Npv10ExportEnhancementProfile
    ) {
        val strength = profile.normalSdrCalibrationStrength.coerceIn(0.0, 1.0)
        if (strength <= 0.0) return
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val chromaBase = safeWidth * safeHeight * Short.SIZE_BYTES
        // Point-wise per U/V pair (matches `while (offset + Short.SIZE_BYTES < payload.size)` ⇒
        // endExclusive = size - Short.SIZE_BYTES), stride = one pair. Disjoint per band ⇒ byte-identical.
        currentRowScheduler().forEachStrided(
            chromaBase,
            payload.size - Short.SIZE_BYTES,
            Short.SIZE_BYTES * 2
        ) { offset ->
            val u = readP010Sample(payload, offset)
            val v = readP010Sample(payload, offset + Short.SIZE_BYTES)
            writeP010Sample(payload, offset, calibrateNormalSdrU(u, strength))
            writeP010Sample(payload, offset + Short.SIZE_BYTES, calibrateNormalSdrV(v, strength))
        }
    }

    private fun calibrateNormalSdrU(sample: Int, strength: Double): Int {
        val delta = sample - NEUTRAL_CHROMA
        val scale = if (delta >= 0) {
            NormalSdrVideoQualityPolicy.EXPORT_CHROMA_U_POSITIVE_SCALE
        } else {
            NormalSdrVideoQualityPolicy.EXPORT_CHROMA_U_NEGATIVE_SCALE
        }
        return (NEUTRAL_CHROMA + delta.toDouble() * blendScale(scale, strength * chromaCorrectionGate(delta)))
            .toInt()
            .coerceIn(0, 1023)
    }

    private fun calibrateNormalSdrV(sample: Int, strength: Double): Int {
        val delta = sample - NEUTRAL_CHROMA
        val scale = if (delta >= 0) {
            NormalSdrVideoQualityPolicy.EXPORT_CHROMA_V_POSITIVE_SCALE
        } else {
            NormalSdrVideoQualityPolicy.EXPORT_CHROMA_V_NEGATIVE_SCALE
        }
        return (NEUTRAL_CHROMA + delta.toDouble() * blendScale(scale, strength * chromaCorrectionGate(delta)))
            .toInt()
            .coerceIn(0, 1023)
    }

    private fun blendScale(targetScale: Double, strength: Double): Double =
        1.0 + (targetScale - 1.0) * strength

    private fun chromaCorrectionGate(delta: Int): Double {
        val t = ((kotlin.math.abs(delta).toDouble() / 512.0) - 0.06) / (0.30 - 0.06)
        val clamped = t.coerceIn(0.0, 1.0)
        return clamped * clamped * (3.0 - 2.0 * clamped)
    }

    private fun liftEdgeAwareLumaDetail(
        payload: ByteArray,
        width: Int,
        height: Int,
        profile: Npv10ExportEnhancementProfile
    ) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        if (safeWidth < 3 || safeHeight < 3) return
        val original = currentRowScheduler().snapshot(payload)
        val stride = safeWidth * Short.SIZE_BYTES

        currentRowScheduler().forEachRow(1, safeHeight - 1) { row ->
            for (column in 1 until safeWidth - 1) {
                val offset = (row * safeWidth + column) * Short.SIZE_BYTES
                val center = readP010Sample(original, offset)
                val c_val = center.toDouble()

                val n_val = readP010Sample(original, offset - stride).toDouble()
                val s_val = readP010Sample(original, offset + stride).toDouble()
                val w_val = readP010Sample(original, offset - Short.SIZE_BYTES).toDouble()
                val e_val = readP010Sample(original, offset + Short.SIZE_BYTES).toDouble()

                val nw_val = readP010Sample(original, offset - stride - Short.SIZE_BYTES).toDouble()
                val ne_val = readP010Sample(original, offset - stride + Short.SIZE_BYTES).toDouble()
                val sw_val = readP010Sample(original, offset + stride - Short.SIZE_BYTES).toDouble()
                val se_val = readP010Sample(original, offset + stride + Short.SIZE_BYTES).toDouble()

                val neighborAverage = (
                    c_val * 4.0 +
                    (n_val + s_val + w_val + e_val) * 2.0 +
                    (nw_val + ne_val + sw_val + se_val)
                ) / 16.0
                val edgeSignal = c_val - neighborAverage
                val edgeMagnitude = kotlin.math.abs(edgeSignal)

                val detailGain = when {
                    edgeMagnitude >= profile.strongEdgeThreshold -> profile.strongDetailGain
                    edgeMagnitude >= profile.mediumEdgeThreshold -> profile.mediumDetailGain
                    edgeMagnitude >= profile.fineTextureThreshold -> profile.fineTextureDetailGain
                    else -> 0.0
                }

                if (detailGain > 0.0) {
                    val requestedLift = (edgeSignal * detailGain).toInt()
                    val aliasSafeLift = requestedLift.coerceIn(
                        -profile.aliasSafeMaxDetailDelta,
                        profile.aliasSafeMaxDetailDelta
                    )
                    val lifted = (center + aliasSafeLift).coerceIn(0, 1023)
                    writeP010Sample(payload, offset, lifted)
                }
            }
        }
    }

    private fun isStrictNormalSdrExportProfile(profile: Npv10ExportEnhancementProfile): Boolean =
        profile.normalSdrCalibrationStrength >= NormalSdrVideoQualityPolicy.EXPORT_COLOR_CALIBRATION_STRENGTH &&
            profile.fineTextureDetailGain >= NormalSdrVideoQualityPolicy.EXPORT_FINE_TEXTURE_DETAIL_GAIN &&
            profile.mediumDetailGain >= NormalSdrVideoQualityPolicy.EXPORT_MEDIUM_DETAIL_GAIN &&
            profile.strongDetailGain >= NormalSdrVideoQualityPolicy.EXPORT_STRONG_DETAIL_GAIN

    private fun hasBoundedSdrDetailBudget(profile: Npv10ExportEnhancementProfile): Boolean =
        isStrictNormalSdrExportProfile(profile) &&
            profile.cumulativeDetailBudget in 1..
            NormalSdrVideoQualityPolicy.EXPORT_CUMULATIVE_DETAIL_BUDGET

    private fun isStrictVisibleCleanExportProfile(profile: Npv10ExportEnhancementProfile): Boolean =
        isStrictNormalSdrExportProfile(profile) ||
            (
                profile.temporalLumaBlend >= 0.999 &&
                    profile.temporalChromaBlend >= 0.999 &&
                    profile.ditherAmplitude <= 0.0 &&
                    profile.chromaSuppression <= 0.006 &&
                    profile.shadowChromaDeadband >= 36 &&
                    profile.aliasSafeMaxDetailDelta <= 220
                )

    private fun recoverApexMicroTextureLuma(
        payload: ByteArray,
        width: Int,
        height: Int,
        profile: Npv10ExportEnhancementProfile
    ) {
        val strictNormalSdrExport = isStrictNormalSdrExportProfile(profile)
        val detailAuthority = if (strictNormalSdrExport) {
            profile.fineTextureDetailGain
        } else {
            (profile.fineTextureDetailGain / 10.0).coerceIn(0.0, 1.0)
        }
        if (detailAuthority <= 0.0) return
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        if (safeWidth < 5 || safeHeight < 5) return
        val original = currentRowScheduler().snapshot(payload)

        fun sampleAt(row: Int, column: Int): Double {
            val offset = (row * safeWidth + column) * Short.SIZE_BYTES
            return readP010Sample(original, offset).toDouble()
        }

        currentRowScheduler().forEachRow(2, safeHeight - 2) { row ->
            for (column in 2 until safeWidth - 2) {
                val offset = (row * safeWidth + column) * Short.SIZE_BYTES
                val center = sampleAt(row, column)
                val x1n = sampleAt(row, column - 1)
                val x1p = sampleAt(row, column + 1)
                val y1n = sampleAt(row - 1, column)
                val y1p = sampleAt(row + 1, column)
                val d1a = sampleAt(row - 1, column - 1)
                val d1b = sampleAt(row - 1, column + 1)
                val d1c = sampleAt(row + 1, column - 1)
                val d1d = sampleAt(row + 1, column + 1)
                val x2n = sampleAt(row, column - 2)
                val x2p = sampleAt(row, column + 2)
                val y2n = sampleAt(row - 2, column)
                val y2p = sampleAt(row + 2, column)
                val axial1 = x1n + x1p + y1n + y1p
                val diag1 = d1a + d1b + d1c + d1d
                val axial2 = x2n + x2p + y2n + y2p
                val microLowPass = (center * 4.0 + axial1 * 1.0 + diag1 * 0.66 + axial2 * 0.36) / 12.08
                val microTexture = center - microLowPass
                val localMin = minOf(x1n, x1p, y1n, y1p, d1a, d1b, d1c, d1d, x2n, x2p, y2n, y2p)
                val localMax = maxOf(x1n, x1p, y1n, y1p, d1a, d1b, d1c, d1d, x2n, x2p, y2n, y2p)
                val localRange = (localMax - localMin).coerceAtLeast(1.0)
                val textureGate = smoothStep(
                    profile.fineTextureThreshold.toDouble(),
                    (profile.mediumEdgeThreshold * 2).toDouble(),
                    kotlin.math.abs(microTexture)
                )
                val shadowGate = smoothStep(10.0, 64.0, center)
                val highlightGuard = 1.0 - smoothStep(920.0, 1006.0, center)
                val microGain = if (strictNormalSdrExport) detailAuthority else 2.0 + 8.0 * detailAuthority
                val microLimit = if (strictNormalSdrExport) {
                    max(10.0, localRange * detailAuthority)
                } else {
                    max(3.0, localRange * (0.18 + 0.24 * detailAuthority))
                }
                val limitedMicro = (microTexture * microGain * textureGate * shadowGate * highlightGuard)
                    .coerceIn(-microLimit, microLimit)
                val recovered = (readP010Sample(payload, offset).toDouble() + limitedMicro)
                    .toInt()
                    .coerceIn(0, 1023)
                writeP010Sample(payload, offset, recovered)
            }
        }
    }

    private fun applyApexLocalContrastLuma(
        payload: ByteArray,
        width: Int,
        height: Int,
        profile: Npv10ExportEnhancementProfile
    ) {
        val strictNormalSdrExport = isStrictNormalSdrExportProfile(profile)
        val detailAuthority = if (strictNormalSdrExport) {
            profile.strongDetailGain
        } else {
            (profile.strongDetailGain / 10.0).coerceIn(0.0, 1.0)
        }
        if (detailAuthority <= 0.0) return
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        if (safeWidth < 3 || safeHeight < 3) return
        val original = currentRowScheduler().snapshot(payload)
        val stride = safeWidth * Short.SIZE_BYTES

        currentRowScheduler().forEachRow(1, safeHeight - 1) { row ->
            for (column in 1 until safeWidth - 1) {
                val offset = (row * safeWidth + column) * Short.SIZE_BYTES
                val center = readP010Sample(original, offset).toDouble()
                val north = readP010Sample(original, offset - stride).toDouble()
                val south = readP010Sample(original, offset + stride).toDouble()
                val west = readP010Sample(original, offset - Short.SIZE_BYTES).toDouble()
                val east = readP010Sample(original, offset + Short.SIZE_BYTES).toDouble()
                val northwest = readP010Sample(original, offset - stride - Short.SIZE_BYTES).toDouble()
                val northeast = readP010Sample(original, offset - stride + Short.SIZE_BYTES).toDouble()
                val southwest = readP010Sample(original, offset + stride - Short.SIZE_BYTES).toDouble()
                val southeast = readP010Sample(original, offset + stride + Short.SIZE_BYTES).toDouble()

                val localMean = (
                    center * 4.0 +
                    (north + south + west + east) * 1.5 +
                    (northwest + northeast + southwest + southeast) * 0.75
                ) / 13.0
                val localContrast = center - localMean
                val localMin = minOf(north, south, west, east, northwest, northeast, southwest, southeast)
                val localMax = maxOf(north, south, west, east, northwest, northeast, southwest, southeast)
                val localRange = (localMax - localMin).coerceAtLeast(1.0)
                val contrastGate = smoothStep(4.0, 36.0, kotlin.math.abs(localContrast)) *
                    smoothStep(8.0, 96.0, localRange)
                val shadowGate = smoothStep(12.0, 80.0, center)
                val highlightGuard = 1.0 - smoothStep(900.0, 1010.0, center)
                val contrastGain = if (strictNormalSdrExport) profile.strongDetailGain else 1.0 + 3.0 * detailAuthority
                val contrastLimit = if (strictNormalSdrExport) {
                    max(12.0, localRange * 0.46)
                } else {
                    max(3.0, localRange * 0.16)
                }
                val limitedContrast = (localContrast * contrastGain * contrastGate * shadowGate * highlightGuard)
                    .coerceIn(-contrastLimit, contrastLimit)
                val contrasted = (readP010Sample(payload, offset).toDouble() + limitedContrast)
                    .toInt()
                    .coerceIn(0, 1023)
                writeP010Sample(payload, offset, contrasted)
            }
        }
    }

    private fun applyApexShadowNoiseFloorLuma(
        payload: ByteArray,
        width: Int,
        height: Int,
        profile: Npv10ExportEnhancementProfile
    ) {
        val strictVisibleCleanExport = isStrictVisibleCleanExportProfile(profile)
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        if (safeWidth < 3 || safeHeight < 3) return
        val original = currentRowScheduler().snapshot(payload)
        val stride = safeWidth * Short.SIZE_BYTES

        currentRowScheduler().forEachRow(1, safeHeight - 1) { row ->
            for (column in 1 until safeWidth - 1) {
                val offset = (row * safeWidth + column) * Short.SIZE_BYTES
                val center = readP010Sample(original, offset).toDouble()
                val north = readP010Sample(original, offset - stride).toDouble()
                val south = readP010Sample(original, offset + stride).toDouble()
                val west = readP010Sample(original, offset - Short.SIZE_BYTES).toDouble()
                val east = readP010Sample(original, offset + Short.SIZE_BYTES).toDouble()
                val northwest = readP010Sample(original, offset - stride - Short.SIZE_BYTES).toDouble()
                val northeast = readP010Sample(original, offset - stride + Short.SIZE_BYTES).toDouble()
                val southwest = readP010Sample(original, offset + stride - Short.SIZE_BYTES).toDouble()
                val southeast = readP010Sample(original, offset + stride + Short.SIZE_BYTES).toDouble()

                val localMean = (
                    center * 4.0 +
                    (north + south + west + east) * 1.5 +
                    (northwest + northeast + southwest + southeast) * 0.75
                ) / 13.0
                val localMin = minOf(north, south, west, east, northwest, northeast, southwest, southeast)
                val localMax = maxOf(north, south, west, east, northwest, northeast, southwest, southeast)
                val localRange = (localMax - localMin).coerceAtLeast(1.0)
                val legacyShadowNoiseGate = (1.0 - smoothStep(96.0, 360.0, center)) *
                    (1.0 - smoothStep(80.0, 260.0, localRange))
                val strictTexturelessGrainGate = (1.0 - smoothStep(128.0, 560.0, center)) *
                    (1.0 - smoothStep(42.0, 210.0, localRange)) *
                    smoothStep(2.0, 34.0, kotlin.math.abs(center - localMean))
                val strictShadowNoiseGate = max(
                    (1.0 - smoothStep(128.0, 520.0, center)) *
                        (1.0 - smoothStep(96.0, 340.0, localRange)),
                    strictTexturelessGrainGate
                )
                val shadowNoiseGate = if (strictVisibleCleanExport) {
                    strictShadowNoiseGate
                } else {
                    legacyShadowNoiseGate
                }
                val noiseFloorWeight = if (strictVisibleCleanExport) 1.0 else
                    (profile.chromaSuppression * 0.50).coerceIn(0.0, 1.0)
                val stabilized = blend(
                    readP010Sample(payload, offset),
                    localMean.toInt().coerceIn(0, 1023),
                    shadowNoiseGate * noiseFloorWeight
                )
                writeP010Sample(payload, offset, stabilized)
            }
        }
    }

    private fun applyApexHighlightTextureLuma(
        payload: ByteArray,
        width: Int,
        height: Int,
        profile: Npv10ExportEnhancementProfile
    ) {
        val strictNormalSdrExport = isStrictNormalSdrExportProfile(profile)
        val detailAuthority = if (strictNormalSdrExport) {
            profile.mediumDetailGain
        } else {
            (profile.mediumDetailGain / 10.0).coerceIn(0.0, 1.0)
        }
        if (detailAuthority <= 0.0) return
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        if (safeWidth < 3 || safeHeight < 3) return
        val original = currentRowScheduler().snapshot(payload)
        val stride = safeWidth * Short.SIZE_BYTES

        currentRowScheduler().forEachRow(1, safeHeight - 1) { row ->
            for (column in 1 until safeWidth - 1) {
                val offset = (row * safeWidth + column) * Short.SIZE_BYTES
                val center = readP010Sample(original, offset).toDouble()
                val north = readP010Sample(original, offset - stride).toDouble()
                val south = readP010Sample(original, offset + stride).toDouble()
                val west = readP010Sample(original, offset - Short.SIZE_BYTES).toDouble()
                val east = readP010Sample(original, offset + Short.SIZE_BYTES).toDouble()
                val northwest = readP010Sample(original, offset - stride - Short.SIZE_BYTES).toDouble()
                val northeast = readP010Sample(original, offset - stride + Short.SIZE_BYTES).toDouble()
                val southwest = readP010Sample(original, offset + stride - Short.SIZE_BYTES).toDouble()
                val southeast = readP010Sample(original, offset + stride + Short.SIZE_BYTES).toDouble()

                val localMean = (
                    center * 4.0 +
                    (north + south + west + east) * 1.5 +
                    (northwest + northeast + southwest + southeast) * 0.75
                ) / 13.0
                val highlightDetail = center - localMean
                val localMin = minOf(north, south, west, east, northwest, northeast, southwest, southeast)
                val localMax = maxOf(north, south, west, east, northwest, northeast, southwest, southeast)
                val localRange = (localMax - localMin).coerceAtLeast(1.0)
                val highlightTextureGate = smoothStep(600.0, 880.0, center) *
                    (1.0 - smoothStep(980.0, 1023.0, center))
                val textureGate = smoothStep(4.0, 32.0, kotlin.math.abs(highlightDetail)) *
                    smoothStep(10.0, 96.0, localRange)
                val highlightGain = if (strictNormalSdrExport) profile.mediumDetailGain else 1.0 + 4.0 * detailAuthority
                val highlightLimit = if (strictNormalSdrExport) {
                    max(16.0, localRange * 0.58)
                } else {
                    max(4.0, localRange * 0.18)
                }
                val current = readP010Sample(payload, offset).toDouble()
                val headroomGuard = if (highlightDetail > 0.0) {
                    smoothStep(0.0, 32.0, 1023.0 - current)
                } else {
                    1.0
                }
                val limitedHighlight = (highlightDetail * highlightGain * highlightTextureGate * textureGate * headroomGuard)
                    .coerceIn(-highlightLimit, highlightLimit)
                val highlighted = (current + limitedHighlight)
                    .toInt()
                    .coerceIn(0, 1023)
                writeP010Sample(payload, offset, highlighted)
            }
        }
    }

    private fun applyApexEdgeCoherenceLuma(
        payload: ByteArray,
        width: Int,
        height: Int,
        profile: Npv10ExportEnhancementProfile
    ) {
        val strictNormalSdrExport = isStrictNormalSdrExportProfile(profile)
        val detailAuthority = if (strictNormalSdrExport) {
            profile.strongDetailGain
        } else {
            (profile.strongDetailGain / 10.0).coerceIn(0.0, 1.0)
        }
        if (detailAuthority <= 0.0) return
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        if (safeWidth < 3 || safeHeight < 3) return
        val original = currentRowScheduler().snapshot(payload)
        val stride = safeWidth * Short.SIZE_BYTES

        currentRowScheduler().forEachRow(1, safeHeight - 1) { row ->
            for (column in 1 until safeWidth - 1) {
                val offset = (row * safeWidth + column) * Short.SIZE_BYTES
                val center = readP010Sample(original, offset).toDouble()
                val north = readP010Sample(original, offset - stride).toDouble()
                val south = readP010Sample(original, offset + stride).toDouble()
                val west = readP010Sample(original, offset - Short.SIZE_BYTES).toDouble()
                val east = readP010Sample(original, offset + Short.SIZE_BYTES).toDouble()
                val northwest = readP010Sample(original, offset - stride - Short.SIZE_BYTES).toDouble()
                val northeast = readP010Sample(original, offset - stride + Short.SIZE_BYTES).toDouble()
                val southwest = readP010Sample(original, offset + stride - Short.SIZE_BYTES).toDouble()
                val southeast = readP010Sample(original, offset + stride + Short.SIZE_BYTES).toDouble()

                val localMean = (
                    center * 4.0 +
                    (north + south + west + east) * 1.5 +
                    (northwest + northeast + southwest + southeast) * 0.75
                ) / 13.0
                val gx = (east - west) + (northeast + southeast - northwest - southwest) * 0.5
                val gy = (south - north) + (southwest + southeast - northwest - northeast) * 0.5
                val localMin = minOf(center, north, south, west, east, northwest, northeast, southwest, southeast)
                val localMax = maxOf(center, north, south, west, east, northwest, northeast, southwest, southeast)
                val localRange = (localMax - localMin).coerceAtLeast(1.0)
                val detail = center - localMean
                val edgeCoherenceGate = smoothStep(18.0, 96.0, kotlin.math.abs(gx) + kotlin.math.abs(gy)) *
                    smoothStep(10.0, 96.0, localRange)
                val haloRisk = smoothStep(localRange * 0.52, localRange, kotlin.math.abs(detail))
                val edgeGain = if (strictNormalSdrExport) profile.strongDetailGain else 1.0 + 4.0 * detailAuthority
                val edgeLimit = if (strictNormalSdrExport) {
                    max(20.0, localRange * 0.62)
                } else {
                    max(4.0, localRange * 0.20)
                }
                val current = readP010Sample(payload, offset).toDouble()
                val coherentLift = (detail * edgeGain * edgeCoherenceGate * (1.0 - haloRisk * 0.48))
                    .coerceIn(-edgeLimit, edgeLimit)
                val candidate = current + coherentLift
                val haloSafe = candidate.coerceIn(
                    max(localMin - edgeLimit, 0.0),
                    minOf(localMax + edgeLimit, 1023.0)
                )
                writeP010Sample(payload, offset, haloSafe.toInt().coerceIn(0, 1023))
            }
        }
    }

    private fun applyApexTonalDensityLuma(
        payload: ByteArray,
        width: Int,
        height: Int,
        profile: Npv10ExportEnhancementProfile
    ) {
        val strictNormalSdrExport = isStrictNormalSdrExportProfile(profile)
        val detailAuthority = if (strictNormalSdrExport) {
            profile.mediumDetailGain
        } else {
            (profile.mediumDetailGain / 10.0).coerceIn(0.0, 1.0)
        }
        if (detailAuthority <= 0.0) return
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        if (safeWidth < 3 || safeHeight < 3) return
        val original = currentRowScheduler().snapshot(payload)
        val stride = safeWidth * Short.SIZE_BYTES

        currentRowScheduler().forEachRow(1, safeHeight - 1) { row ->
            for (column in 1 until safeWidth - 1) {
                val offset = (row * safeWidth + column) * Short.SIZE_BYTES
                val center = readP010Sample(original, offset).toDouble()
                val north = readP010Sample(original, offset - stride).toDouble()
                val south = readP010Sample(original, offset + stride).toDouble()
                val west = readP010Sample(original, offset - Short.SIZE_BYTES).toDouble()
                val east = readP010Sample(original, offset + Short.SIZE_BYTES).toDouble()
                val northwest = readP010Sample(original, offset - stride - Short.SIZE_BYTES).toDouble()
                val northeast = readP010Sample(original, offset - stride + Short.SIZE_BYTES).toDouble()
                val southwest = readP010Sample(original, offset + stride - Short.SIZE_BYTES).toDouble()
                val southeast = readP010Sample(original, offset + stride + Short.SIZE_BYTES).toDouble()

                val localMin = minOf(center, north, south, west, east, northwest, northeast, southwest, southeast)
                val localMax = maxOf(center, north, south, west, east, northwest, northeast, southwest, southeast)
                val localRange = (localMax - localMin).coerceAtLeast(1.0)
                val neighborMean = (north + south + west + east + northwest + northeast + southwest + southeast) / 8.0
                val isolatedBrightDetail = (center - neighborMean).coerceAtLeast(0.0)
                val isolatedDetailGuard = 1.0 - smoothStep(6.0, 18.0, isolatedBrightDetail)
                val midtoneGate = smoothStep(160.0, 360.0, center) *
                    (1.0 - smoothStep(760.0, 980.0, center))
                val flatMidtoneGate = (1.0 - smoothStep(96.0, 260.0, localRange)) *
                    midtoneGate
                val current = readP010Sample(payload, offset).toDouble()
                val normalized = ((current - 512.0) / 512.0).coerceIn(-1.0, 1.0)
                val densityGain = if (strictNormalSdrExport) profile.mediumDetailGain else 1.0 + 3.0 * detailAuthority
                val densityDelta = normalized * (1.0 - kotlin.math.abs(normalized)) * densityGain
                val positiveDensityGuard = if (densityDelta > 0.0) isolatedDetailGuard else 1.0
                val densityLimit = if (strictNormalSdrExport) {
                    max(10.0, localRange * 0.10)
                } else {
                    max(2.0, localRange * 0.06)
                }
                val candidate = current + (densityDelta * flatMidtoneGate * positiveDensityGuard * 24.0)
                val densitySafe = candidate.coerceIn(current - densityLimit, current + densityLimit)
                writeP010Sample(payload, offset, densitySafe.toInt().coerceIn(0, 1023))
            }
        }
    }

    private fun applyApexLongDistanceDetailLuma(
        payload: ByteArray,
        width: Int,
        height: Int,
        profile: Npv10ExportEnhancementProfile
    ) {
        val strictNormalSdrExport = isStrictNormalSdrExportProfile(profile)
        val detailAuthority = if (strictNormalSdrExport) {
            profile.fineTextureDetailGain
        } else {
            (profile.fineTextureDetailGain / 10.0).coerceIn(0.0, 1.0)
        }
        if (detailAuthority <= 0.0) return
        if (!strictNormalSdrExport) return
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        if (safeWidth < 5 || safeHeight < 5) return
        val original = currentRowScheduler().snapshot(payload)

        fun sampleAt(row: Int, column: Int): Double {
            val offset = (row * safeWidth + column) * Short.SIZE_BYTES
            return readP010Sample(original, offset).toDouble()
        }

        currentRowScheduler().forEachRow(2, safeHeight - 2) { row ->
            for (column in 2 until safeWidth - 2) {
                val offset = (row * safeWidth + column) * Short.SIZE_BYTES
                val center = sampleAt(row, column)
                val x1n = sampleAt(row, column - 1)
                val x1p = sampleAt(row, column + 1)
                val y1n = sampleAt(row - 1, column)
                val y1p = sampleAt(row + 1, column)
                val d1a = sampleAt(row - 1, column - 1)
                val d1b = sampleAt(row - 1, column + 1)
                val d1c = sampleAt(row + 1, column - 1)
                val d1d = sampleAt(row + 1, column + 1)
                val x2n = sampleAt(row, column - 2)
                val x2p = sampleAt(row, column + 2)
                val y2n = sampleAt(row - 2, column)
                val y2p = sampleAt(row + 2, column)
                val hasWideRing = row >= 3 && column >= 3 && row + 3 < safeHeight && column + 3 < safeWidth
                val x3n = if (hasWideRing) sampleAt(row, column - 3) else x2n
                val x3p = if (hasWideRing) sampleAt(row, column + 3) else x2p
                val y3n = if (hasWideRing) sampleAt(row - 3, column) else y2n
                val y3p = if (hasWideRing) sampleAt(row + 3, column) else y2p

                val axial1 = x1n + x1p + y1n + y1p
                val diagonal1 = d1a + d1b + d1c + d1d
                val axial2 = x2n + x2p + y2n + y2p
                val axial3 = x3n + x3p + y3n + y3p
                val nearMean = (center * 4.0 + axial1 * 1.0 + diagonal1 * 0.75) / 11.0
                val farMean = (center * 4.0 + axial1 * 1.0 + diagonal1 * 0.66 + axial2 * 0.50) / 12.64
                val longDistanceDetail = (center - farMean) + (nearMean - farMean)
                val wideMean = if (hasWideRing) {
                    (center * 4.0 + axial1 * 0.82 + diagonal1 * 0.56 + axial2 * 0.44 + axial3 * 0.30) / 13.92
                } else {
                    farMean
                }
                val longDistanceStructure = blendDouble(
                    longDistanceDetail,
                    (center - wideMean) + (nearMean - wideMean),
                    if (hasWideRing) 0.42 else 0.18
                )
                val singlePixelNoise = kotlin.math.abs(center - nearMean)
                val localMin = minOf(center, x1n, x1p, y1n, y1p, d1a, d1b, d1c, d1d, x2n, x2p, y2n, y2p, x3n, x3p, y3n, y3p)
                val localMax = maxOf(center, x1n, x1p, y1n, y1p, d1a, d1b, d1c, d1d, x2n, x2p, y2n, y2p, x3n, x3p, y3n, y3p)
                val localRange = (localMax - localMin).coerceAtLeast(1.0)
                val textureGate = smoothStep(0.35, 2.2, kotlin.math.abs(longDistanceStructure)) *
                    (1.0 - smoothStep(56.0, 144.0, localRange))
                val midtoneGate = smoothStep(64.0, 180.0, center) *
                    (1.0 - smoothStep(900.0, 1016.0, center))
                val wideAgreement = if (hasWideRing) {
                    1.0 - smoothStep(2.0, 18.0, kotlin.math.abs(farMean - wideMean))
                } else {
                    0.38
                }
                val noiseReject = (
                    1.0 - smoothStep(18.0, 42.0, singlePixelNoise) * (0.62 - wideAgreement * 0.22)
                    ).coerceIn(0.34 + wideAgreement * 0.24, 1.0)
                val longDistanceGain = if (strictNormalSdrExport) profile.fineTextureDetailGain else 1.0 + 4.0 * detailAuthority
                val detailLimit = if (strictNormalSdrExport) {
                    max(18.0, localRange * 0.72)
                } else {
                    max(2.0, localRange * 0.10)
                }
                val current = readP010Sample(payload, offset).toDouble()
                val detailLift = (longDistanceStructure * longDistanceGain * textureGate * midtoneGate * noiseReject)
                    .coerceIn(-detailLimit, detailLimit)
                val candidate = current + detailLift
                val safeDetail = candidate.coerceIn(
                    max(localMin - detailLimit, current - detailLimit),
                    minOf(localMax + detailLimit, current + detailLimit, 1023.0)
                )
                writeP010Sample(payload, offset, safeDetail.toInt().coerceIn(0, 1023))
            }
        }
    }

    private fun applyChromaSharpeningCompensation(
        originalLuma: ByteArray,
        payload: ByteArray,
        width: Int,
        height: Int
    ) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val chromaWidth = (safeWidth + 1) / 2
        val chromaHeight = (safeHeight + 1) / 2
        if (chromaWidth <= 0 || chromaHeight <= 0) return

        val chromaBase = safeWidth * safeHeight * Short.SIZE_BYTES
        val lumaStride = safeWidth * Short.SIZE_BYTES

        currentRowScheduler().forEachRow(0, chromaHeight) { row ->
            val lumaRow0 = row * 2
            val lumaRow1 = minOf(lumaRow0 + 1, safeHeight - 1)
            for (column in 0 until chromaWidth) {
                val lumaCol0 = column * 2
                val lumaCol1 = minOf(lumaCol0 + 1, safeWidth - 1)

                // Read original luma 2x2 block
                val orig00 = readP010Sample(originalLuma, lumaRow0 * lumaStride + lumaCol0 * Short.SIZE_BYTES).toDouble()
                val orig01 = readP010Sample(originalLuma, lumaRow0 * lumaStride + lumaCol1 * Short.SIZE_BYTES).toDouble()
                val orig10 = readP010Sample(originalLuma, lumaRow1 * lumaStride + lumaCol0 * Short.SIZE_BYTES).toDouble()
                val orig11 = readP010Sample(originalLuma, lumaRow1 * lumaStride + lumaCol1 * Short.SIZE_BYTES).toDouble()
                val origMean = (orig00 + orig01 + orig10 + orig11) / 4.0

                // Read sharpened luma 2x2 block
                val new00 = readP010Sample(payload, lumaRow0 * lumaStride + lumaCol0 * Short.SIZE_BYTES).toDouble()
                val new01 = readP010Sample(payload, lumaRow0 * lumaStride + lumaCol1 * Short.SIZE_BYTES).toDouble()
                val new10 = readP010Sample(payload, lumaRow1 * lumaStride + lumaCol0 * Short.SIZE_BYTES).toDouble()
                val new11 = readP010Sample(payload, lumaRow1 * lumaStride + lumaCol1 * Short.SIZE_BYTES).toDouble()
                val newMean = (new00 + new01 + new10 + new11) / 4.0

                if (origMean > 0.0 && newMean != origMean) {
                    val ratio = newMean / origMean

                    val uvOffset = chromaBase + (row * chromaWidth + column) * Short.SIZE_BYTES * 2
                    val u = readP010Sample(payload, uvOffset)
                    val v = readP010Sample(payload, uvOffset + Short.SIZE_BYTES)

                    val uDelta = u - NEUTRAL_CHROMA
                    val vDelta = v - NEUTRAL_CHROMA

                    val uCompensated = NEUTRAL_CHROMA + (uDelta * ratio)
                    val vCompensated = NEUTRAL_CHROMA + (vDelta * ratio)

                    writeP010Sample(payload, uvOffset, uCompensated.toInt().coerceIn(0, 1023))
                    writeP010Sample(payload, uvOffset + Short.SIZE_BYTES, vCompensated.toInt().coerceIn(0, 1023))
                }
            }
        }
    }

    private fun applyBlueNoiseDither(
        payload: ByteArray,
        width: Int,
        height: Int,
        profile: Npv10ExportEnhancementProfile
    ) {
        val amplitude = profile.ditherAmplitude.coerceIn(0.0, 1.0)
        if (amplitude <= 0.0) return
        val safeWidth = width.coerceAtLeast(1)
        val lumaSamples = safeWidth * height.coerceAtLeast(1)
        val lumaBytes = lumaSamples * Short.SIZE_BYTES
        // Position-based blue-noise (rank from x,y) — NOT error diffusion, so each luma sample is independent
        // and row-band-parallel byte-identically. offset is always 2·sampleIndex ⇒ sampleIndex = offset/2.
        currentRowScheduler().forEachStrided(0, lumaBytes, Short.SIZE_BYTES) { offset ->
            val sampleIndex = offset / Short.SIZE_BYTES
            val current = readP010Sample(payload, offset)
            val dither = blueNoiseDitherDelta(sampleIndex, safeWidth, amplitude)
            writeP010Sample(payload, offset, (current + dither).coerceIn(0, 1023))
        }
    }

    internal fun blueNoiseDitherDelta(sampleIndex: Int, width: Int, amplitude: Double): Int {
        val safeWidth = width.coerceAtLeast(1)
        val x = sampleIndex % safeWidth
        val y = sampleIndex / safeWidth
        val rank = BLUE_NOISE_8X8[((y and 7) shl 3) + (x and 7)]
        val centeredBlueNoise = (rank - 31.5) / 31.5
        val blueNoise = centeredBlueNoise * amplitude.coerceIn(0.0, 1.0) * 2.0
        return when {
            blueNoise >= 0.33 -> 1
            blueNoise <= -0.33 -> -1
            else -> 0
        }
    }

    private fun enforceCumulativeDetailBudget(
        payload: ByteArray,
        baseline: ByteArray,
        width: Int,
        height: Int,
        budget: Int
    ) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val safeBudget = budget.coerceIn(
            0,
            NormalSdrVideoQualityPolicy.EXPORT_CUMULATIVE_DETAIL_BUDGET
        )
        currentRowScheduler().forEachRow(0, safeHeight) { row ->
            for (column in 0 until safeWidth) {
                val offset = (row * safeWidth + column) * Short.SIZE_BYTES
                val source = readP010Sample(baseline, offset)
                var bounded = readP010Sample(payload, offset).coerceIn(
                    (source - safeBudget).coerceAtLeast(0),
                    (source + safeBudget).coerceAtMost(1023)
                )
                if (row in 1 until safeHeight - 1 && column in 1 until safeWidth - 1) {
                    // Avoid a per-pixel IntArray while preserving the exact top/bottom/left/right
                    // sample set and integer mean used by the original quality guard.
                    val top = readP010Sample(
                        baseline,
                        offset - safeWidth * Short.SIZE_BYTES
                    )
                    val bottom = readP010Sample(
                        baseline,
                        offset + safeWidth * Short.SIZE_BYTES
                    )
                    val left = readP010Sample(baseline, offset - Short.SIZE_BYTES)
                    val right = readP010Sample(baseline, offset + Short.SIZE_BYTES)
                    val neighborMin = minOf(minOf(top, bottom), minOf(left, right))
                    val neighborMax = maxOf(maxOf(top, bottom), maxOf(left, right))
                    val neighborMean = (top + bottom + left + right) / 4
                    val isolatedImpulse = neighborMax - neighborMin <= 12 &&
                        kotlin.math.abs(source - neighborMean) >= 24
                    if (isolatedImpulse) {
                        val originalDeviation = kotlin.math.abs(source - neighborMean)
                        bounded = bounded.coerceIn(
                            (neighborMean - originalDeviation).coerceAtLeast(0),
                            (neighborMean + originalDeviation).coerceAtMost(1023)
                        )
                    }
                }
                writeP010Sample(payload, offset, bounded)
            }
        }
    }

    private fun suppressChromaSample(
        sample: Int,
        neighborAverage: Int,
        profile: Npv10ExportEnhancementProfile
    ): Int {
        val clampedSample = sample.coerceIn(0, 1023)
        val clampedNeighbor = neighborAverage.coerceIn(0, 1023)
        val delta = clampedSample - NEUTRAL_CHROMA
        val neighborDelta = clampedNeighbor - NEUTRAL_CHROMA
        val locallySupportedColor = locallySupportedChroma(
            sample = clampedSample,
            neighbor = clampedNeighbor,
            delta = delta,
            neighborDelta = neighborDelta
        )
        val strictNormalSdrExport = isStrictNormalSdrExportProfile(profile)
        val retain = if (locallySupportedColor) {
            max(profile.chromaSuppression, COHERENT_CHROMA_RETAIN_FLOOR)
        } else if (strictNormalSdrExport) {
            minOf(profile.chromaSuppression, 0.0005)
        } else {
            profile.chromaSuppression
        }
        val target = if (locallySupportedColor) clampedNeighbor else NEUTRAL_CHROMA
        return (target + (clampedSample - target) * retain)
            .toInt()
            .coerceIn(0, 1023)
    }

    private fun sameChromaDirection(a: Int, b: Int): Boolean =
        (a < 0 && b < 0) || (a > 0 && b > 0)

    private fun locallySupportedChroma(
        sample: Int,
        neighbor: Int,
        delta: Int = sample - NEUTRAL_CHROMA,
        neighborDelta: Int = neighbor - NEUTRAL_CHROMA
    ): Boolean =
        kotlin.math.abs(delta) >= LOCAL_CHROMA_SIGNAL_MIN_DELTA &&
            kotlin.math.abs(neighborDelta) >= LOCAL_CHROMA_SIGNAL_MIN_DELTA &&
            sameChromaDirection(delta, neighborDelta) &&
            kotlin.math.abs(sample - neighbor) <= COHERENT_CHROMA_SUPPORT_WINDOW

    private fun deadbandChromaSample(
        payload: ByteArray,
        offset: Int,
        neighborAverage: Int,
        profile: Npv10ExportEnhancementProfile
    ) {
        val sample = readP010Sample(payload, offset)
        val threshold = profile.shadowChromaDeadband.coerceAtLeast(0)
        if (threshold <= 0) return
        val delta = sample - NEUTRAL_CHROMA
        val magnitude = kotlin.math.abs(delta)
        if (magnitude <= threshold) {
            val retain = smoothstep(0.0, threshold.toDouble(), magnitude.toDouble())
            val neighbor = neighborAverage.coerceIn(0, 1023)
            val target = if (locallySupportedChroma(sample, neighbor, delta)) {
                neighbor
            } else {
                NEUTRAL_CHROMA
            }
            val blended = (target + (sample - target) * retain)
                .toInt()
                .coerceIn(0, 1023)
            writeP010Sample(payload, offset, blended)
        }
    }

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        if (edge0 == edge1) return if (value >= edge1) 1.0 else 0.0
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun blend(current: Int, prior: Int, priorWeight: Double): Int =
        (current.toDouble() * (1.0 - priorWeight) + prior.toDouble() * priorWeight)
            .toInt()
            .coerceIn(0, 1023)

    private fun blendDouble(current: Double, prior: Double, priorWeight: Double): Double =
        current * (1.0 - priorWeight) + prior * priorWeight

    private fun chromaPairOffsetForLumaSample(width: Int, height: Int, sampleIndex: Int): Int {
        val row = sampleIndex / width
        val column = sampleIndex % width
        val chromaWidth = (width + 1) / 2
        val chromaRow = row.coerceIn(0, height - 1) / 2
        val chromaColumn = column.coerceIn(0, width - 1) / 2
        return width * height * Short.SIZE_BYTES +
            (chromaRow * chromaWidth + chromaColumn) * Short.SIZE_BYTES * 2
    }

    private fun rgbFrameDifference(
        y: Int,
        u: Int,
        v: Int,
        previousY: Int,
        previousU: Int,
        previousV: Int
    ): Double {
        val current = yuvToRgb(y, u, v)
        val previous = yuvToRgb(previousY, previousU, previousV)
        return (
            kotlin.math.abs(current[0] - previous[0]) +
                kotlin.math.abs(current[1] - previous[1]) +
                kotlin.math.abs(current[2] - previous[2])
            ) / 3.0
    }

    private fun smoothStep(edge0: Double, edge1: Double, value: Double): Double {
        val t = ((value - edge0) / (edge1 - edge0).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun yuvToRgb(y: Int, u: Int, v: Int): DoubleArray {
        val yf = y.toDouble() / 1023.0
        val uf = (u - NEUTRAL_CHROMA).toDouble() / 512.0
        val vf = (v - NEUTRAL_CHROMA).toDouble() / 512.0
        return doubleArrayOf(
            (yf + 1.5748 * vf).coerceIn(0.0, 1.0),
            (yf - 0.1873 * uf - 0.4681 * vf).coerceIn(0.0, 1.0),
            (yf + 1.8556 * uf).coerceIn(0.0, 1.0)
        )
    }

    /**
     * P4 Phase A — apply the live ColorScience preview grade LUT to the exported P010 so an exported
     * NPV10 MOV matches the WYSIWYG preview. The LUT is a display-referred R'G'B' cube (size [lutSize],
     * packed idx=(ri+gi*N+bi*N*N)*3, R fastest — identical to ColorScience.getPreviewLutFloatArray and
     * the live GPU upload). Decode/encode use the same full-range Rec.709 convention as [yuvToRgb], and
     * 4:2:0 is handled by grading each luma in a 2x2 block with its shared chroma and writing one graded
     * chroma pair per block. An identity LUT round-trips within +/-1 code (decode/encode are exact
     * inverses). `internal` so [Npv10ExportGradeParityTest] can exercise the pixel math directly.
     */
    internal fun applyPreviewGradeLut(
        payload: ByteArray,
        width: Int,
        height: Int,
        lut: FloatArray,
        lutSize: Int
    ) {
        if (lutSize < 2 || lut.size < lutSize * lutSize * lutSize * 3) return
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val chromaWidth = (safeWidth + 1) / 2
        val chromaHeight = (safeHeight + 1) / 2
        val chromaBase = safeWidth * safeHeight * Short.SIZE_BYTES
        for (cy in 0 until chromaHeight) {
            for (cx in 0 until chromaWidth) {
                val chromaOffset = chromaBase + (cy * chromaWidth + cx) * Short.SIZE_BYTES * 2
                if (chromaOffset + Short.SIZE_BYTES >= payload.size) continue
                val u = readP010Sample(payload, chromaOffset)
                val v = readP010Sample(payload, chromaOffset + Short.SIZE_BYTES)
                var accR = 0.0
                var accG = 0.0
                var accB = 0.0
                var count = 0
                for (dy in 0..1) {
                    val y = cy * 2 + dy
                    if (y >= safeHeight) continue
                    for (dx in 0..1) {
                        val x = cx * 2 + dx
                        if (x >= safeWidth) continue
                        val lumaOffset = (y * safeWidth + x) * Short.SIZE_BYTES
                        val rgb = yuvToRgb(readP010Sample(payload, lumaOffset), u, v)
                        val graded = sampleGradeLutTrilinear(lut, lutSize, rgb[0], rgb[1], rgb[2])
                        val gradedY = 0.2126 * graded[0] + 0.7152 * graded[1] + 0.0722 * graded[2]
                        writeP010Sample(payload, lumaOffset, Math.round(gradedY * 1023.0).toInt().coerceIn(0, 1023))
                        accR += graded[0]
                        accG += graded[1]
                        accB += graded[2]
                        count += 1
                    }
                }
                if (count > 0) {
                    val gR = accR / count
                    val gG = accG / count
                    val gB = accB / count
                    val blockY = 0.2126 * gR + 0.7152 * gG + 0.0722 * gB
                    val cb = (gB - blockY) / 1.8556
                    val cr = (gR - blockY) / 1.5748
                    writeP010Sample(payload, chromaOffset, Math.round(cb * 512.0 + NEUTRAL_CHROMA).toInt().coerceIn(0, 1023))
                    writeP010Sample(payload, chromaOffset + Short.SIZE_BYTES, Math.round(cr * 512.0 + NEUTRAL_CHROMA).toInt().coerceIn(0, 1023))
                }
            }
        }
    }

    private fun sampleGradeLutTrilinear(lut: FloatArray, n: Int, r: Double, g: Double, b: Double): DoubleArray {
        val maxIdx = n - 1
        val rf = r.coerceIn(0.0, 1.0) * maxIdx
        val gf = g.coerceIn(0.0, 1.0) * maxIdx
        val bf = b.coerceIn(0.0, 1.0) * maxIdx
        val r0 = rf.toInt().coerceIn(0, maxIdx)
        val g0 = gf.toInt().coerceIn(0, maxIdx)
        val b0 = bf.toInt().coerceIn(0, maxIdx)
        val r1 = (r0 + 1).coerceAtMost(maxIdx)
        val g1 = (g0 + 1).coerceAtMost(maxIdx)
        val b1 = (b0 + 1).coerceAtMost(maxIdx)
        val dr = rf - r0
        val dg = gf - g0
        val db = bf - b0
        val out = DoubleArray(3)
        for (c in 0..2) {
            fun texel(ri: Int, gi: Int, bi: Int): Double = lut[((ri + gi * n + bi * n * n) * 3) + c].toDouble()
            val c00 = texel(r0, g0, b0) + (texel(r1, g0, b0) - texel(r0, g0, b0)) * dr
            val c10 = texel(r0, g1, b0) + (texel(r1, g1, b0) - texel(r0, g1, b0)) * dr
            val c01 = texel(r0, g0, b1) + (texel(r1, g0, b1) - texel(r0, g0, b1)) * dr
            val c11 = texel(r0, g1, b1) + (texel(r1, g1, b1) - texel(r0, g1, b1)) * dr
            val c0 = c00 + (c10 - c00) * dg
            val c1 = c01 + (c11 - c01) * dg
            out[c] = (c0 + (c1 - c0) * db).coerceIn(0.0, 1.0)
        }
        return out
    }

    private fun readP010Sample(payload: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 1 < payload.size) { "P010 payload is truncated" }
        val value = (payload[offset].toInt() and 0xFF) or
            ((payload[offset + 1].toInt() and 0xFF) shl 8)
        return (value ushr P010_VALID_BITS_SHIFT).coerceIn(0, 1023)
    }

    private fun writeP010Sample(payload: ByteArray, offset: Int, sample: Int) {
        val value = sample.coerceIn(0, 1023) shl P010_VALID_BITS_SHIFT
        payload[offset] = (value and 0xFF).toByte()
        payload[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

}

private fun VideoNoiseMetrics.plus(other: VideoNoiseMetrics): VideoNoiseMetrics =
    VideoNoiseMetrics(
        chromaNoise = chromaNoise + other.chromaNoise,
        lumaNoise = lumaNoise + other.lumaNoise,
        temporalInstability = temporalInstability + other.temporalInstability,
        frameToFrameRgbVariance = frameToFrameRgbVariance + other.frameToFrameRgbVariance
    )

private fun VideoNoiseMetrics.dividedBy(count: Int): VideoNoiseMetrics {
    if (count <= 0) return VideoNoiseMetrics.EMPTY
    return VideoNoiseMetrics(
        chromaNoise = chromaNoise / count,
        lumaNoise = lumaNoise / count,
        temporalInstability = temporalInstability / count,
        frameToFrameRgbVariance = frameToFrameRgbVariance / count
    ).clamped()
}
