package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NdrNivukxDeliveryEngine
import com.neoproc.neoproccamx.niv99.Niv99OfflineConversionPolicy
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.recording.RecordDevelopP010ColorIngress.OutputTransfer
import com.neoproc.neoproccamx.pipeline.SceneRenderProfile
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.rendering.GpuActiveTransport
import com.neoproc.neoproccamx.rendering.GpuImageEnhancementUniforms
import com.neoproc.neoproccamx.rendering.GpuSignalFamily
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorSpace
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorTransfer
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.VideoDevelopColorMetadata
import com.neoproc.neoproccamx.snce.SnceIlluminantEstimator
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  RECORD-THEN-DEVELOP — Phase 2 OFFLINE DEVELOP CONVERTER
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Turns a flat LIGHT record intermediate (`.mov`, decoded through a [RecordDevelopP010FrameReader]) into a
 * graded, uncompressed 10-bit master, applying the elite [VideoDevelopColorPipeline] offline. It mirrors
 * [Npv10MovConverter]'s proven skeleton:
 *
 *   • two-tier entry: [convert] = [convertWithAudit] + certificate sidecar;
 *   • CPU-parallel develop with a coroutine-`Semaphore` in-flight window and a writer-side reorder buffer
 *     so uncompressed samples are muxed STRICTLY in frame order regardless of worker completion order;
 *   • fail-safe muxing: any failure `abort()`s the writer (no `moov`) so a partial master is never left
 *     playable; the private intermediate is SHA-256-fingerprinted before/after to prove it was read-only.
 *
 * NO-DOWNGRADE: it never touches the live record path and is unreachable from the UI until the Phase-5
 * cards. With [RecordDevelopConversionOptions] strengths at 0 the develop is a pipeline passthrough, so the
 * output is the decoded intermediate re-encoded within ≤±1 code (transfer functions are exact inverses).
 *
 * SEAM: the real HEVC→P010 decode ([RecordDevelopMediaCodecP010Reader]) is DEVICE-ONLY and injected via
 * [readerFactory]; the pure-JVM tests inject [InMemoryP010FrameReader], so the entire loop is testable.
 *
 * Phase 7 — OPTIONAL CPU+GPU HYBRID: the per-pixel colour develop can be offloaded to a GPU lane behind the
 * injectable [RecordDevelopGpuAccelerator] seam (single permit, first-frame parity gate, UNIFORM algorithm
 * per clip, per-frame CPU fallback — mirrors [RawToNeoLogMovConverter]). NO-DOWNGRADE: the default
 * `gpuAcceleratorFactory` defaults to null for JVM tests. Production injects a native Vulkan accelerator;
 * the first-frame parity gate accepts it only when the GPU codes match the CPU reference. The CPU still does
 * serial decode, Phase-4 enhance fallback, packing, and in-order muxing.
 */
internal object RecordDevelopMovConverter {

    private const val TAG = "RecordDevelopConvert"
    const val OUTPUT_EXTENSION = "mov"

    /**
     * Reader/worker/writer channel depth. The resident-frame semaphore below still caps this by the
     * measured heap budget, so raising the overlap window improves converter throughput when memory allows
     * without changing any developed pixel values.
     */
    private const val PIPELINE_CHANNEL_CAPACITY = 4

    /** Row-level parallelism is only for full-resolution offline develops; tiny tests stay single-threaded. */
    private const val ROW_PARALLEL_MIN_PIXELS = 2_000_000L
    private const val ROW_PARALLEL_MIN_ROWS = 128
    private const val ROW_PARALLEL_CHUNK_ROWS = 48
    private const val ROW_PARALLEL_MAX_WORKERS = 8

    // Phase 7 GPU-vs-CPU develop parity tolerances live in the reversible, live-tunable
    // [RecordDevelopParityGatePolicy] (meanAbs / maxAbs / p99.9). The GPU reproduces the SAME develop math
    // (not a different algorithm like RAW's demosaic), so only float32-vs-float64 rounding should differ:
    // a broken transform / wrong colour space blows the mean into the hundreds → byte-exact CPU fallback for
    // the whole clip. Device measurement (vivo, SDR develop) showed meanAbs≈0.4 with a lone maxAbs≈35 once
    // the deterministic mismatches (NDR truncate + hoisted CAM16 constants) are fixed. The p99.9 guard now
    // additionally catches a STRUCTURED regional error that a lone-max/global-mean bound would hide.
    private const val P010_VALID_BITS_SHIFT = 6
    private const val V210_MAX10 = 1023
    private const val BT709_KR = 0.2126
    private const val BT709_KB = 0.0722
    private const val V210_KR = 0.2627
    private const val V210_KB = 0.0593
    private const val V210_KG = 1.0 - V210_KR - V210_KB
    private const val V210_LUMA_MIN = 64.0
    private const val V210_LUMA_MAX = 940.0
    private const val V210_CHROMA_MIN = 64.0
    private const val V210_CHROMA_MAX = 960.0
    private const val V210_CHROMA_ZERO = 512.0

    /** Names the sibling `.mov` output for a develop, under [outputDirectory]. */
    fun defaultOutputFile(master: File, outputDirectory: File): File {
        outputDirectory.mkdirs()
        return File(outputDirectory, "${master.nameWithoutExtension}.$OUTPUT_EXTENSION")
    }

    /**
     * Estimate frame count from the MP4 sample table when it is available. This is only for UI progress;
     * the converter still streams the decoder as source of truth.
     */
    fun estimateFrameCount(master: File): Int =
        Mp4SampleCountEstimator.estimateVideoSampleCount(master).coerceAtLeast(0)

    /**
     * Full develop: [convertWithAudit], inspect the finalized MOV, then atomically publish an
     * inspector-authoritative certificate next to [output]. Returns the declared pipeline audit only
     * after the artifact gate succeeds.
     */
    fun convert(
        master: File,
        output: File,
        sidecar: VideoDevelopColorMetadata?,
        options: RecordDevelopConversionOptions = RecordDevelopConversionOptions(),
        sourceKind: RecordDevelopMasterKind,
        readerFactory: (File) -> RecordDevelopP010FrameReader,
        onFrame: ((framesDone: Int) -> Unit)? = null,
        gpuAcceleratorFactory: ((RecordDevelopGpuContext) -> RecordDevelopGpuAccelerator?)? = null,
        expectedFrameCount: Long? = null
    ): RecordDevelopConversionAudit {
        return try {
            val audit = convertWithAudit(
                master,
                output,
                sidecar,
                options,
                sourceKind,
                readerFactory,
                onFrame,
                gpuAcceleratorFactory,
                expectedFrameCount = expectedFrameCount
            )
            RecordDevelopMovPublicationGate.publishFinalized(
                output = output,
                certificateFile = certificateSidecarFile(output),
                expectation = publicationExpectation(
                    audit = audit,
                    rotationDegrees = sidecar?.orientationHintDegrees ?: 0
                ),
                declaredPipelineAuditText = audit.toCertificateText(),
                additionalStaleCertificates = listOf(
                    File("${output.absolutePath}.elite-conversion-certificate.txt")
                )
            )
            audit
        } catch (failure: Throwable) {
            cleanupConversionArtifacts(
                output,
                MovOutputOwnership.OWNED_REGULAR_FILE,
                failure
            )
            throw failure
        }
    }

    /**
     * Core develop. Decodes [master] frame-by-frame through [readerFactory], develops each frame through the
     * elite pipeline built from [sidecar] (+ [options]), packs it to the chosen uncompressed 10-bit format,
     * and muxes an in-order QuickTime `.mov`. Throws (after `abort()`) on an empty stream or bad geometry.
     *
     * Phase 7: pass [gpuAcceleratorFactory] to offload the per-pixel colour develop to a GPU lane (see
     * [RecordDevelopGpuAccelerator]). A null factory (the default) runs the byte-exact CPU develop.
     */
    fun convertWithAudit(
        master: File,
        output: File,
        sidecar: VideoDevelopColorMetadata?,
        options: RecordDevelopConversionOptions = RecordDevelopConversionOptions(),
        sourceKind: RecordDevelopMasterKind,
        readerFactory: (File) -> RecordDevelopP010FrameReader,
        onFrame: ((framesDone: Int) -> Unit)? = null,
        gpuAcceleratorFactory: ((RecordDevelopGpuContext) -> RecordDevelopGpuAccelerator?)? = null,
        outputOwnership: MovOutputOwnership = MovOutputOwnership.OWNED_REGULAR_FILE,
        expectedFrameCount: Long? = null
    ): RecordDevelopConversionAudit {
        val sourceMaster = master.canonicalFile
        require(conversionArtifacts(output).none { it.canonicalFile == sourceMaster }) {
            "Record-develop output or certificate must not overwrite the private source master"
        }
        val outputParent = output.absoluteFile.parentFile
            ?: error("Record-develop output has no parent directory")
        check(outputParent.isDirectory || outputParent.mkdirs()) {
            "Unable to create record-develop output directory ${outputParent.absolutePath}"
        }
        if (outputOwnership.converterMayDeleteOutput) {
            deleteConversionArtifactsStrict(output)
        } else {
            require(output.exists()) {
                "Borrowed record-develop destination must exist before conversion: ${output.absolutePath}"
            }
        }

        // Tamper-evidence: fingerprint the private intermediate BEFORE (proven read-only AFTER).
        val masterBytesBefore = master.length()
        val masterShaBefore = sha256Hex(master)

        val deliveryFormat = options.resolvedDeliveryFormat()
        val effectiveOutputFormat = deliveryFormat.uncompressedFormat ?: options.outputFormat
        val plan = buildDevelopPlan(sidecar, options, sourceKind)
        val niv99Plan = Niv99OfflineConversionPolicy.recordDevelop(
            isNdr = plan.target == DevelopTarget.NDR_PQ_BT2020
        )
        val delivery = resolveColorDelivery(plan.target, deliveryFormat, plan.ndrGenuineHdr)

        var frameCount = 0L
        var workerCount = 1
        var gpuAccelerated = false
        var gpuEnhanceFrames = 0L
        var enhanceProcessingPath = "CPU_P010_ENHANCE"
        var presentationDurationUs = 0L
        var advancedEncoderControlsAccepted: Boolean? = null
        var eliteQualityAudit: EliteConversionQualityContract.Audit? = null
        var niv99PixelEvidence: Niv99OfflineP010Evidence? = null

        try {
            readerFactory(master).use { reader ->
            val fmt = reader.format
            require(fmt.width > 0 && fmt.height > 0) {
                "Record-develop source has invalid geometry ${fmt.width}x${fmt.height}"
            }
            require(fmt.storageBitDepth == 10) {
                "Record-develop expects 10-bit P010, got ${fmt.storageBitDepth}-bit"
            }
            niv99Plan.requireFrameContract(
                sourceWidth = fmt.width,
                sourceHeight = fmt.height,
                outputWidth = fmt.width,
                outputHeight = fmt.height,
                sourceFps = fmt.fps,
                outputFps = fmt.fps,
                outputBitDepth = 10
            )
            val declaredFrameCount = expectedFrameCount
                ?: estimateFrameCount(master).toLong().takeIf { it > 0L }
            val niv99PixelProcessor = if (plan.enhanceEnabled) {
                Niv99OfflineP010PixelProcessor(
                    plan = niv99Plan,
                    width = fmt.width,
                    height = fmt.height,
                    fps = fmt.fps,
                    expectedFrames = declaredFrameCount
                )
            } else {
                null
            }
            if (deliveryFormat.compressed) {
                // Reject an impossible strict HEVC delivery before creating an output writer or
                // launching decode/develop workers. R210/V210 remain explicit caller-selected paths;
                // this check never substitutes their transport for a rejected HEVC request.
                RecordDevelopHevcMovEncoder.requireStrictCapability(
                    width = fmt.width,
                    height = fmt.height,
                    fps = fmt.fps
                )
            }

            val writer = QuickTimeMovFileWriter(
                outputPath = output.absolutePath,
                containerLabel = "NeoProCamX Develop ${sourceKind.name}",
                preallocationPlan = RecordingPreallocationPolicy.offlineConversionOutput(
                    output = output,
                    estimatedPayloadBytes = expectedUncompressedPayloadBytes(
                        format = effectiveOutputFormat,
                        width = fmt.width,
                        height = fmt.height,
                        frameCount = expectedFrameCount
                            ?: estimateFrameCount(master).toLong().takeIf { it > 0L }
                    )
                )
            )
            try {
                writer.setOrientationHint(sidecar?.orientationHintDegrees ?: 0)
                val result = if (deliveryFormat.compressed) {
                    runHybridHevcDevelop(
                        reader = reader,
                        writer = writer,
                        fmt = fmt,
                        plan = plan,
                        delivery = delivery,
                        deliveryFormat = deliveryFormat,
                        options = options,
                        niv99PixelProcessor = niv99PixelProcessor,
                        onFrame = onFrame,
                        gpuAcceleratorFactory = gpuAcceleratorFactory
                    )
                } else {
                    val track = writer.addVideoTrack(
                        QuickTimeVideoTrackConfig(
                            mime = effectiveOutputFormat.movMime,
                            width = fmt.width,
                            height = fmt.height,
                            fps = fmt.fps,
                            codecConfig = ByteArray(0),
                            colorConfig = delivery.colorConfig
                        )
                    )
                    runHybridDevelop(
                        reader,
                        writer,
                        track,
                        fmt,
                        plan,
                        delivery,
                        options.copy(outputFormat = effectiveOutputFormat),
                        niv99PixelProcessor,
                        onFrame,
                        gpuAcceleratorFactory
                    )
                }
                require(result.frameCount > 0L) { "Cannot develop an empty record master (0 frames)" }
                niv99PixelEvidence = niv99PixelProcessor?.requireComplete(result.frameCount)
                frameCount = result.frameCount
                workerCount = result.workerCount
                gpuAccelerated = result.gpuAccelerated
                gpuEnhanceFrames = result.gpuEnhanceFrames
                enhanceProcessingPath = result.enhanceProcessingPath
                presentationDurationUs = result.presentationDurationUs
                advancedEncoderControlsAccepted = result.advancedEncoderControlsAccepted
                eliteQualityAudit = EliteConversionQualityEvidenceFactory.enforceRecordDevelop(
                    sourceKind = sourceKind,
                    target = plan.target,
                    sourceBitDepth = fmt.storageBitDepth,
                    width = fmt.width,
                    height = fmt.height,
                    fps = fmt.fps,
                    frameCount = result.frameCount,
                    deliveryFormat = deliveryFormat,
                    containerColor = delivery.colorConfig,
                    sourceSidecar = sidecar,
                    minimumBitrateBps = result.minimumBitrateBps,
                    configuredBitrateBps = result.configuredBitrateBps,
                    policyTargetBitrateBps = result.policyTargetBitrateBps,
                    advertisedBitrateCeilingBps = result.advertisedBitrateCeilingBps,
                    encoderCapabilityBound = result.encoderCapabilityBound,
                    advancedEncoderControlsAccepted = result.advancedEncoderControlsAccepted
                )
                writer.close()
            } catch (t: Throwable) {
                runCatching { writer.abort() }
                    .exceptionOrNull()
                    ?.let(t::addSuppressed)
                throw t
            }
        }

        val masterUnchanged =
            masterBytesBefore == master.length() && masterShaBefore == sha256Hex(master)
        check(masterUnchanged) {
            "Record-develop source master changed while conversion was running"
        }

            return RecordDevelopConversionAudit(
            outputPath = output.absolutePath,
            outputBytes = output.length(),
            frameCount = frameCount,
            presentationDurationUs = presentationDurationUs,
            sourceKind = sourceKind,
            width = plan.width.takeIf { it > 0 } ?: 0,
            height = plan.height.takeIf { it > 0 } ?: 0,
            fps = plan.fps,
            developTarget = plan.target,
            outputFormat = effectiveOutputFormat,
            deliveryFormat = deliveryFormat,
            colorPrimaries = delivery.colorConfig.colorPrimaries,
            transferCharacteristics = delivery.colorConfig.transferCharacteristics,
            matrixCoefficients = delivery.colorConfig.matrixCoefficients,
            fullRange = delivery.colorConfig.fullRange,
            hdrMasteringAttached = delivery.colorConfig.masteringDisplay != null,
            sidecarPresent = sidecar != null,
            snceAppearanceStrength = plan.options.snceAppearanceStrength,
            filmPrintStrength = plan.options.filmPrintStrength,
            acesToneMap = plan.options.acesToneMap,
            workerCount = workerCount,
            inputMasterHashUnchanged = masterUnchanged,
            niv99Applied = plan.enhanceEnabled,
            niv99Route = niv99Plan.route.name,
            niv99FullFrame = niv99Plan.fullFrame,
            niv99PixelEvidence = niv99PixelEvidence?.metadata().orEmpty(),
            enhanceApplied = plan.enhanceEnabled,
            // Label derives from the ACTUAL profile object the worker applies (below), not from target —
            // one source of truth, so the audit can never misreport the profile that ran.
            enhanceProfile = plan.enhanceProfileLabel,
            temporalDenoise = plan.enhanceTemporal,
            ndrGenuineHdr = plan.ndrGenuineHdr,
            gpuEnhanceFrames = gpuEnhanceFrames,
            enhanceProcessingPath = enhanceProcessingPath,
            requireGpuDevelop = options.requireGpuDevelop,
            advancedEncoderControlsAccepted = advancedEncoderControlsAccepted,
            gpuAccelerated = gpuAccelerated,
            eliteQualityAudit = requireNotNull(eliteQualityAudit) {
                "Record-develop completed without elite conversion quality evidence"
            }
            )
        } catch (failure: Throwable) {
            cleanupConversionArtifacts(output, outputOwnership, failure)
            throw failure
        }
    }

    private fun expectedUncompressedPayloadBytes(
        format: RecordDevelopOutputFormat,
        width: Int,
        height: Int,
        frameCount: Long?
    ): Long? {
        val count = frameCount?.takeIf { it > 0L } ?: return null
        return Math.multiplyExact(
            RawTenBitPacker.payloadSize(format.packerFormat, width, height).toLong(),
            count
        )
    }

    // ── sidecar → pipeline plan ──────────────────────────────────────────────────────────────────────

    private class DevelopPlan(
        val pipeline: VideoDevelopColorPipeline,
        val options: VideoDevelopColorOptions,
        val target: DevelopTarget,
        val decodeSpace: DevelopColorSpace,
        val decodeTransfer: DevelopColorTransfer,
        /** NDR PQ nit anchor for the legacy SDR-fit ODT output (SDR + genuine-HDR NDR ignore it). */
        val diffuseWhiteNits: Float,
        /** Phase-6 (R6): NDR delivered as genuine HDR via the canonical engine (vs the legacy 203-anchor). */
        val ndrGenuineHdr: Boolean,
        /** Phase-4 pre-develop denoise/detail profile (colour-clean); null ⇒ enhance stage disabled. */
        val enhanceProfile: Npv10ExportEnhancementProfile?,
        /** Audit label for [enhanceProfile] ("NONE" when disabled) — derived from the profile, not target. */
        val enhanceProfileLabel: String,
        /** Feed each frame its predecessor's SOURCE P010 for the temporal-coherence lane. */
        val enhanceTemporal: Boolean,
        /** Capture-time detail intent, used by per-frame conversion quality analysis. */
        val videoDetailProfile: VideoDetailProfile,
        /** Bounded scene-analyzer controls consumed by the actual P010 enhancement stage. */
        val highEndAiTuning: RecordDevelopHighEndAiTuning,
        /** Sidecar working-space RGB→XYZ (or null ⇒ pipeline default) — surfaced to the GPU develop context. */
        val workingRgbToXyz: FloatArray?,
        /** Scene adapting luminance the pipeline was built with — surfaced to the GPU develop context. */
        val sceneLuminanceLA: Float,
        var width: Int = 0,
        var height: Int = 0,
        var fps: Int = 0
    ) {
        val enhanceEnabled: Boolean get() = enhanceProfile != null
    }

    /**
     * Builds the one-per-clip develop pipeline from the colour sidecar (absent ⇒ neutral SDR/Rec.709
     * defaults, a faithful develop of a flat intermediate). The delivery target is derived from the
     * recorded transfer/space, so an HLG-coherent NDR intermediate is delivered as PQ BT.2020 and the
     * creative film LUT is suppressed (the pipeline forces this too for NDR).
     */
    private fun buildDevelopPlan(
        sidecar: VideoDevelopColorMetadata?,
        options: RecordDevelopConversionOptions,
        sourceKind: RecordDevelopMasterKind
    ): DevelopPlan {
        val target = deriveTarget(sidecar, sourceKind)
        val isNdr = target == DevelopTarget.NDR_PQ_BT2020
        // R6: genuine-HDR NDR bypasses the ACES SDR-fit RRT+ODT (which clamped the delivery to display [0,1]
        // and flat-anchored it to 203 nits) and instead tone-maps in the canonical NDR PQ delivery. The legacy
        // path keeps the ACES ODT. Either way NDR runs Stage-A SNCE appearance and suppresses the film LUT.
        val ndrGenuineHdr = isNdr && options.ndrDelivery == RecordDevelopNdrDelivery.GENUINE_HDR
        val acesToneMap = isNdr && !ndrGenuineHdr
        val filmStrength = if (isNdr) 0f else options.filmPrintStrength
        val colorOptions = VideoDevelopColorOptions(
            target = target,
            snceAppearanceStrength = options.snceAppearanceStrength,
            filmPrintStrength = filmStrength,
            acesToneMap = acesToneMap,
            semanticClass = options.semanticClass,
            clampOutput = true,
            warmSkinRedTameStrength = if (isNdr) 0f else NdrWarmSkinRedTameFlag.effectiveStrength.toFloat()
        )
        val workingRgbToXyz = sidecar?.workingRgbToXyz?.takeIf { it.size == 9 }
            ?: if (sourceKind == RecordDevelopMasterKind.NDR) {
                SnceIlluminantEstimator.REC2020_TO_XYZ.copyOf()
            } else {
                null
            }
        val sceneLa = sidecar
            ?.adaptingLuminance
            ?.takeIf { it > 0f }
            ?: VideoDevelopColorPipeline.DEFAULT_SCENE_LA
        // Resolve the intermediate decode transfer up-front so the SNCE white-normalisation lever can anchor
        // Stage A on the correct diffuse-white point (shared source of truth with the PQ encode).
        val decodeTransferResolved = sidecar?.colorTransfer ?: if (sourceKind == RecordDevelopMasterKind.NDR) {
            DevelopColorTransfer.HLG
        } else {
            DevelopColorTransfer.SDR
        }
        // NDR genuine-HDR white-normalisation (reversible, default OFF): run CAM16 at diffuse white ≈1.0 so
        // it stops over-boosting reds. Byte-identical when OFF (scale = 1f). See RecordDevelopWhiteNormalizeFlag.
        val snceWhiteNormalizeScale =
            if (ndrGenuineHdr && RecordDevelopWhiteNormalizeFlag.enabled) {
                (1.0 / RecordDevelopP010ColorIngress.diffuseWhiteSceneFor(decodeTransferResolved)).toFloat()
            } else {
                1f
            }
        val pipeline = VideoDevelopColorPipeline(
            options = colorOptions,
            workingRgbToXyz = workingRgbToXyz,
            viewingConditions = null, // Phase-2a: D65; CCT/tint → white-point wiring is Phase-2b (R4).
            sceneLuminanceLA = sceneLa,
            snceWhiteNormalizeScale = snceWhiteNormalizeScale
        )
        val videoDetailProfile = VideoDetailProfile.fromPreference(sidecar?.detailProfileTag)
        val enhanceProfile = if (options.enhance.enabled) enhanceProfileFor(target) else null
        return DevelopPlan(
            pipeline = pipeline,
            options = colorOptions,
            target = target,
            decodeSpace = sidecar?.colorSpace ?: if (sourceKind == RecordDevelopMasterKind.NDR) {
                DevelopColorSpace.REC2020
            } else {
                DevelopColorSpace.REC709
            },
            decodeTransfer = decodeTransferResolved,
            diffuseWhiteNits = options.ndrDiffuseWhiteNits,
            ndrGenuineHdr = ndrGenuineHdr,
            enhanceProfile = enhanceProfile,
            enhanceProfileLabel = enhanceProfile?.let { enhanceProfileLabel(it) } ?: "NONE",
            enhanceTemporal = options.enhance.enabled && options.enhance.temporalDenoise,
            videoDetailProfile = videoDetailProfile,
            highEndAiTuning = options.highEndAiTuning,
            workingRgbToXyz = workingRgbToXyz,
            sceneLuminanceLA = sceneLa
        )
    }

    /**
     * Picks the pre-develop denoise/detail profile for a target. Both are COLOUR-CLEAN — their
     * `normalSdrCalibrationStrength` is 0, so [Npv10ExportEnhancementPolicy]'s SDR colour-calibration lane is
     * a no-op — and the converter always invokes the enhancer with the SNCE nudge at the identity and no
     * grade LUT, so the entire colour look is left to [VideoDevelopColorPipeline] (no double-grade).
     */
    private val CINEMA_NDR_NATURAL_ENHANCE_PROFILE = Npv10ExportEnhancementProfile.NDR10_PLUS.copy(
        chromaSuppression = 0.008,
        shadowChromaDeadband = 48,
        fineTextureThreshold = 14,
        mediumEdgeThreshold = 32,
        strongEdgeThreshold = 98,
        fineTextureDetailGain = 1.12,
        mediumDetailGain = 1.22,
        strongDetailGain = 1.32,
        aliasSafeMaxDetailDelta = 88
    )

    private val CINEMA_SDR_NATURAL_ENHANCE_PROFILE = Npv10ExportEnhancementProfile.DEFAULT.copy(
        chromaSuppression = 0.010,
        shadowChromaDeadband = 44,
        fineTextureThreshold = 16,
        mediumEdgeThreshold = 34,
        strongEdgeThreshold = 96,
        fineTextureDetailGain = 1.16,
        mediumDetailGain = 1.24,
        strongDetailGain = 1.36,
        aliasSafeMaxDetailDelta = 96
    )

    // LIVE-CINEMA PARITY variants (default-ON RecordDevelopLiveParityFlag): all three detail gains = 0 so
    // liftEdgeAwareLumaDetail and every Apex luma-sharpen lane early-out (they guard on detailGain/10 <= 0),
    // killing the over-sharpen + grain amplification. Temporal luma/chroma blend is capped to the live 0.35
    // (was 1.0 full-replace), and the live 1-LSB dither is enabled. The actual sharpen comes from the live
    // bilateral + halo-guarded unsharp applied per frame by enhanceLiveCinemaLumaP010 (see enhanceP010ForDevelop).
    // Chroma-NR (chromaSuppression) is left strong = clean. OFF => byte-identical CINEMA_*_NATURAL.
    private val CINEMA_NDR_LIVE_PARITY_ENHANCE_PROFILE = CINEMA_NDR_NATURAL_ENHANCE_PROFILE.copy(
        fineTextureDetailGain = 0.0,
        mediumDetailGain = 0.0,
        strongDetailGain = 0.0,
        temporalLumaBlend = 0.35,
        temporalChromaBlend = 0.35,
        ditherAmplitude = 1.0
    )

    private val CINEMA_SDR_LIVE_PARITY_ENHANCE_PROFILE = CINEMA_SDR_NATURAL_ENHANCE_PROFILE.copy(
        fineTextureDetailGain = 0.0,
        mediumDetailGain = 0.0,
        strongDetailGain = 0.0,
        temporalLumaBlend = 0.35,
        temporalChromaBlend = 0.35,
        ditherAmplitude = 1.0
    )

    private fun enhanceProfileFor(target: DevelopTarget): Npv10ExportEnhancementProfile {
        val parity = RecordDevelopLiveParityFlag.enabled
        return when (target) {
            DevelopTarget.NDR_PQ_BT2020 ->
                if (parity) CINEMA_NDR_LIVE_PARITY_ENHANCE_PROFILE else CINEMA_NDR_NATURAL_ENHANCE_PROFILE
            DevelopTarget.SDR_REC709, DevelopTarget.SDR_REC2020 ->
                if (parity) CINEMA_SDR_LIVE_PARITY_ENHANCE_PROFILE else CINEMA_SDR_NATURAL_ENHANCE_PROFILE
        }
    }

    /**
     * Audit label for the ACTUAL profile object [enhanceProfileFor] returned (structural `==` against the
     * known [Npv10ExportEnhancementProfile] presets). Deriving the label from the profile — not from target —
     * makes the audit and the applied profile a single source of truth: they cannot diverge.
     */
    private fun enhanceProfileLabel(profile: Npv10ExportEnhancementProfile): String = when (profile) {
        CINEMA_NDR_LIVE_PARITY_ENHANCE_PROFILE -> "CINEMA_NDR_LIVE_PARITY"
        CINEMA_SDR_LIVE_PARITY_ENHANCE_PROFILE -> "CINEMA_SDR_LIVE_PARITY"
        CINEMA_NDR_NATURAL_ENHANCE_PROFILE -> "CINEMA_NDR_NATURAL"
        CINEMA_SDR_NATURAL_ENHANCE_PROFILE -> "CINEMA_SDR_NATURAL"
        Npv10ExportEnhancementProfile.NDR10_PLUS -> "NDR10_PLUS"
        Npv10ExportEnhancementProfile.DEFAULT -> "DEFAULT"
        else -> "CUSTOM"
    }

    private fun deriveTarget(
        sidecar: VideoDevelopColorMetadata?,
        sourceKind: RecordDevelopMasterKind
    ): DevelopTarget = when {
        sidecar == null && sourceKind == RecordDevelopMasterKind.NDR -> DevelopTarget.NDR_PQ_BT2020
        sidecar == null -> DevelopTarget.SDR_REC709
        sidecar.colorTransfer == DevelopColorTransfer.PQ ||
            sidecar.colorTransfer == DevelopColorTransfer.HLG -> DevelopTarget.NDR_PQ_BT2020
        sidecar.colorSpace == DevelopColorSpace.REC2020 -> DevelopTarget.SDR_REC2020
        else -> DevelopTarget.SDR_REC709
    }

    // ── delivery colour tag + egress transfer ────────────────────────────────────────────────────────

    private class ColorDelivery(
        val colorConfig: QuickTimeColorAtomConfig,
        val outputTransfer: OutputTransfer,
        /** True ⇒ widen linear Rec.709 develop output to Rec.2020 before packing (v210 + SDR-709 only). */
        val widenToRec2020: Boolean,
        /** R6: encode NDR via the canonical [OutputTransfer]-bypassing genuine-HDR PQ delivery. */
        val ndrGenuineHdr: Boolean = false
    )

    /**
     * Transfer-encodes developed Float32 RGB without quantizing it. YCbCr deliveries consume these
     * continuous signals and perform their only integer rounding at the final P010/v210 component boundary.
     * R210 deliberately keeps its established direct RGB-code path and never uses this encoder.
     */
    private class ContinuousOutputEncoder(
        decodeTransfer: DevelopColorTransfer,
        private val outputTransfer: OutputTransfer,
        diffuseWhiteNits: Float,
        genuineNdr: Boolean
    ) {
        private val pqScale = diffuseWhiteNits.toDouble() / RecordDevelopP010ColorIngress.PQ_PEAK_NITS
        private val ndrScale = if (genuineNdr) {
            1.0 / RecordDevelopP010ColorIngress.diffuseWhiteSceneFor(decodeTransfer)
        } else {
            1.0
        }
        private val ndrDelivery = if (genuineNdr) {
            NdrNivukxDeliveryEngine.prepare(NdrNivukxDeliveryEngine.Mode.NDR10_PLUS)
        } else {
            null
        }
        private val ndrScratch = DoubleArray(3)
        private val warmSkinRedTame = NdrWarmSkinRedTameFlag.effectiveStrength

        fun encodeInto(r: Float, g: Float, b: Float, out: DoubleArray, offset: Int) {
            val preparedNdr = ndrDelivery
            if (preparedNdr != null) {
                NdrNivukxDeliveryEngine.deliverPqInto(
                    rLinear = r.toDouble() * ndrScale,
                    gLinear = g.toDouble() * ndrScale,
                    bLinear = b.toDouble() * ndrScale,
                    delivery = preparedNdr,
                    warmSkinRedTame = warmSkinRedTame,
                    out = out,
                    offset = offset,
                    scratch = ndrScratch
                )
                return
            }
            when (outputTransfer) {
                OutputTransfer.SDR_REC709_OETF -> {
                    out[offset] = RecordDevelopP010ColorIngress.linearToRec709(r.toDouble())
                    out[offset + 1] = RecordDevelopP010ColorIngress.linearToRec709(g.toDouble())
                    out[offset + 2] = RecordDevelopP010ColorIngress.linearToRec709(b.toDouble())
                }
                OutputTransfer.PQ_ST2084 -> {
                    out[offset] = RecordDevelopP010ColorIngress.pqOetf(r.toDouble() * pqScale)
                    out[offset + 1] = RecordDevelopP010ColorIngress.pqOetf(g.toDouble() * pqScale)
                    out[offset + 2] = RecordDevelopP010ColorIngress.pqOetf(b.toDouble() * pqScale)
                }
            }
        }
    }

    /**
     * Resolves the QuickTime `colr` nclx tag + egress transfer for a target/format combination so pixels and
     * tags always agree. r210 stores RGB verbatim (full range, no baked matrix); v210 bakes BT.2020-NCL
     * limited-range Y′CbCr, so the SDR-709 case is gamut-widened to BT.2020 to keep the file self-consistent.
     * HEVC uses limited-range P010: Rec.709 for SDR-709 delivery, BT.2020 for SDR-2020/NDR.
     * The container colr tag is ST.2084 for NDR regardless of the delivery mode; only the egress tone-map
     * differs (genuine canonical PQ vs the legacy 203-anchor), so tags and pixels stay coherent either way.
     */
    private fun resolveColorDelivery(
        target: DevelopTarget,
        deliveryFormat: RecordDevelopDeliveryFormat,
        ndrGenuineHdr: Boolean
    ): ColorDelivery {
        val isR210 = deliveryFormat == RecordDevelopDeliveryFormat.R210_444_UNCOMPRESSED_MOV
        val isHevc = deliveryFormat.compressed
        val fullRange = isR210 // r210 = full-range RGB codes; YUV deliveries are limited-range.
        return when (target) {
            DevelopTarget.SDR_REC709 ->
                if (isR210) {
                    ColorDelivery(
                        QuickTimeColorAtomConfig(
                            colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT709,
                            transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                            matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT709,
                            fullRange = true
                        ),
                        OutputTransfer.SDR_REC709_OETF,
                        widenToRec2020 = false
                    )
                } else if (isHevc) {
                    ColorDelivery(
                        QuickTimeColorAtomConfig(
                            colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT709,
                            transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                            matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT709,
                            fullRange = false
                        ),
                        OutputTransfer.SDR_REC709_OETF,
                        widenToRec2020 = false
                    )
                } else {
                    ColorDelivery(
                        QuickTimeColorAtomConfig(
                            colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                            transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                            matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                            fullRange = false
                        ),
                        OutputTransfer.SDR_REC709_OETF,
                        widenToRec2020 = true
                    )
                }
            DevelopTarget.SDR_REC2020 -> ColorDelivery(
                QuickTimeColorAtomConfig(
                    colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                    transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                    matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                    fullRange = fullRange
                ),
                OutputTransfer.SDR_REC709_OETF,
                widenToRec2020 = false
            )
            DevelopTarget.NDR_PQ_BT2020 -> ColorDelivery(
                QuickTimeColorAtomConfig(
                    colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                    transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                    matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                    fullRange = fullRange,
                    masteringDisplay = HdrStaticMetadataPolicy.resolveMasteringDisplay(
                        HdrStaticMetadataPolicy.StaticMetadataMode.HDR10,
                        HdrStaticMetadataPolicy.SceneMetadata.DEFAULT.copy(
                            masteringPeakNits = NdrNivukxDeliveryEngine.targetPeakNits(
                                NdrNivukxDeliveryEngine.Mode.NDR10_PLUS
                            )
                        )
                    )
                ),
                OutputTransfer.PQ_ST2084, // used only by the legacy 203-anchor path; genuine-HDR bypasses it.
                widenToRec2020 = false,
                ndrGenuineHdr = ndrGenuineHdr
            )
        }
    }

    // ── CPU+GPU hybrid develop with in-order muxing (mirrors Npv10MovConverter.runParallelCpuExport +
    //    RawToNeoLogMovConverter's permit/parity hybrid) ─────────────────────────────────────────────────

    private class DecodedDevelopFrame(
        val index: Long,
        val presentationTimeUs: Long,
        val p010: ByteArray,
        val width: Int,
        val height: Int,
        /** Predecessor frame's SOURCE P010 for the temporal lane; null for frame 0 / temporal-off. */
        val previousP010: ByteArray?,
        /** Immutable NQIS decision produced once by the ordered decode stage. */
        val qualityInstruction: NeoQualityInstruction? = null
    )
    private class PackedDevelopFrame(val index: Long, val presentationTimeUs: Long, val payload: ByteArray)
    private class DevelopLoopResult(
        val frameCount: Long,
        val workerCount: Int,
        val gpuAccelerated: Boolean,
        val gpuEnhanceFrames: Long,
        val enhanceProcessingPath: String,
        val presentationDurationUs: Long,
        val minimumBitrateBps: Long? = null,
        val configuredBitrateBps: Long? = null,
        val policyTargetBitrateBps: Long? = null,
        val advertisedBitrateCeilingBps: Long? = null,
        val encoderCapabilityBound: Boolean? = null,
        val advancedEncoderControlsAccepted: Boolean? = null
    )

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun runHybridDevelop(
        reader: RecordDevelopP010FrameReader,
        writer: QuickTimeMovFileWriter,
        track: Int,
        fmt: RecordDevelopDecodedFormat,
        plan: DevelopPlan,
        delivery: ColorDelivery,
        options: RecordDevelopConversionOptions,
        niv99PixelProcessor: Niv99OfflineP010PixelProcessor?,
        onFrame: ((framesDone: Int) -> Unit)?,
        gpuAcceleratorFactory: ((RecordDevelopGpuContext) -> RecordDevelopGpuAccelerator?)?
    ): DevelopLoopResult {
        plan.width = fmt.width
        plan.height = fmt.height
        plan.fps = fmt.fps
        val frameDurationUs = max(1L, 1_000_000L / fmt.fps.coerceAtLeast(1))
        val presentationTimeline = RecordDevelopPresentationTimeline(frameDurationUs)
        fun resolvedPresentationEndUs(): Long =
            presentationTimeline.endPresentationTimeUs(fmt.sourceDurationUs)

        // Phase 7 — resolve the optional GPU develop lane ONCE, up front. It is engaged only when the master
        // flag is on, a factory is injected, and its accelerator reports available; otherwise `accelerator`
        // is null, `gpuPermit` is null, and every worker takes the byte-exact CPU branch (no-downgrade). The
        // single permit serializes GPU dispatch to one in-flight call (the native bridge allows one) and is
        // released the instant the dispatch returns, so this frame's CPU pack/mux and the next frame's GPU
        // develop overlap. The GPU-vs-CPU choice is resolved once by a first-frame parity gate and is UNIFORM
        // for the whole clip — never mixing two algorithms frame-to-frame (RAW-hybrid lesson: mixing is
        // non-reproducible + risks temporal shimmer).
        val acceleratorCandidate = resolveGpuAccelerator(
            fmt = fmt,
            plan = plan,
            delivery = delivery,
            options = options,
            gpuAcceleratorFactory = gpuAcceleratorFactory
        )
        val memoryBudget = RecordDevelopPipelineOverlapPolicy.effectiveMemoryBudget(
            requestedBudgetBytes = options.memoryBudgetBytes,
            maxMemoryBytes = Runtime.getRuntime().maxMemory()
        )
        val temporalReserveBytes = if (plan.enhanceTemporal) {
            Math.multiplyExact(Math.multiplyExact(fmt.width.toLong(), fmt.height.toLong()), 3L)
        } else {
            0L
        }
        val cpuResidentBytes = estimateResidentBytesPerFrame(
            width = fmt.width,
            height = fmt.height,
            enhanceEnabled = plan.enhanceEnabled,
            outputFormat = options.outputFormat,
            gpuDevelopAvailable = false
        )
        val acceleratedResidentBytes = estimateResidentBytesPerFrame(
            width = fmt.width,
            height = fmt.height,
            enhanceEnabled = plan.enhanceEnabled,
            outputFormat = options.outputFormat,
            gpuDevelopAvailable = acceleratorCandidate != null
        )
        val accelerator = admitGpuDevelopWithinMemoryBudget(
            candidate = acceleratorCandidate,
            options = options,
            residentBytesPerFrame = acceleratedResidentBytes,
            fixedReserveBytes = temporalReserveBytes,
            memoryBudgetBytes = memoryBudget,
            deliveryLabel = options.resolvedDeliveryFormat().auditLabel
        )
        val gpuPermit: Semaphore? = if (accelerator != null) Semaphore(permits = 1) else null
        // -1 = unresolved, 0 = CPU, 1 = GPU. Resolved by the first worker to reach the gate (thread-safe).
        val gpuDecision = java.util.concurrent.atomic.AtomicInteger(-1)
        // Count frames whose WRITTEN codes actually came from the GPU (not the decision). This keeps the audit
        // honest: if the clip commits to GPU but every dispatch then transiently fails and falls back to CPU,
        // the output is 100% CPU and `gpuAccelerated` reports false — provenance never over-claims.
        val gpuFramesDeveloped = java.util.concurrent.atomic.AtomicLong(0L)
        val enhanceGpuUniforms = plan.enhanceProfile?.let { buildEnhanceGpuUniforms(it, plan.target) }
        val usesDedicatedLiveParityEnhance =
            plan.enhanceProfile == CINEMA_NDR_LIVE_PARITY_ENHANCE_PROFILE ||
                plan.enhanceProfile == CINEMA_SDR_LIVE_PARITY_ENHANCE_PROFILE
        val enhanceGpuPermit: Semaphore? =
            if (!usesDedicatedLiveParityEnhance &&
                enhanceGpuUniforms != null &&
                Npv10ExportGpuAccelerator.isAccelerationAvailable()
            ) {
                Semaphore(permits = 1)
            } else {
                null
            }
        val enhanceGpuDecision = java.util.concurrent.atomic.AtomicInteger(-1)
        val enhanceGpuFrames = java.util.concurrent.atomic.AtomicLong(0L)
        val enhanceCpuFallbackFrames = java.util.concurrent.atomic.AtomicLong(0L)
        // Live-parity luma enhance on the GPU (single permit; native mutex serialises the shared engine).
        // Engaged only when the flag is on AND the optional native pipeline built on this device; a
        // first-frame per-operating-point parity gate then commits GPU-or-CPU uniformly for the whole clip.
        val parityEnhancePermit: Semaphore? =
            if (plan.enhanceProfile != null &&
                RecordDevelopParityEnhanceGpuFlag.enabled &&
                RecordDevelopParityEnhanceGpuBridge.isAvailable()
            ) {
                Semaphore(permits = 1)
            } else {
                null
            }
        val parityEnhanceDecision = java.util.concurrent.atomic.AtomicInteger(-1)

        val residentBytesPerFrame = if (accelerator != null) acceleratedResidentBytes else cpuResidentBytes
        // The temporal predecessor remains live outside the per-frame permit window. Reserve it first,
        // then fail closed if the effective hard budget cannot hold one complete resident frame. Silently
        // coercing this value up to one frame would make the advertised budget meaningless.
        val pipelineBudgetFrames = RecordDevelopPipelineOverlapPolicy.frameCapacity(
            residentBytesPerFrame = residentBytesPerFrame,
            fixedReserveBytes = temporalReserveBytes,
            memoryBudgetBytes = memoryBudget
        )
        val pipelineMemoryBudget = memoryBudget - temporalReserveBytes
        val workers = ConversionParallelismPolicy.workerCount(
            frameBytes = residentBytesPerFrame,
            memoryBudgetBytes = pipelineMemoryBudget,
            pipelineDepth = PIPELINE_CHANNEL_CAPACITY,
            residentCopiesPerFrame = 1
        )
        // Bound read-ahead so the writer's reorder buffer can never grow unbounded when one worker straggles
        // on the head-of-line frame — AND so the simultaneously-resident frames can never exceed the memory
        // budget. `workerCount()` derives `workers` from the budget, but the (workers + depth) read-ahead must
        // ALSO be byte-capped: at full sensor a single r210 + enhance-ON frame is a large fraction of the
        // maxMemory/3 budget, so `workers` floors to 1 while `workers + depth` (= 3) would still overrun the
        // heap. Capping the in-flight permits to `budget / perFrame` restores the resident ≤ budget invariant.
        // Output is byte-identical regardless of the window (proven by the cross-worker determinism test), so
        // this bounds peak memory without changing a single output byte.
        // Concurrent CPU+GPU hybrid ([RecordDevelopHybridCpuLaneFlag], default OFF): when enabled AND the
        // heap can hold the ~1.5-frame hybrid working set (one CHEAP GPU frame + one CPU frame), run a
        // second CPU develop lane beside the GPU so both engines develop different frames at once. The guard
        // authorises raising the resident cap to 2; a gpuPermit(1) + cpuLanePermit(1) keep at most one CPU
        // develop resident. Output stays byte-identical to single-lane (writer reorders by index; CPU frames
        // are the golden reference, GPU frames the parity-accepted GPU output). Throughput lever only.
        val hybridConcurrency = RecordDevelopHybridCpuLanePolicy.developConcurrency(
            gpuAvailable = accelerator != null,
            residentBytesPerFrame = residentBytesPerFrame,
            // The hybrid guard must obey the same caller/effective budget as the permit window. Passing
            // the process maximum here previously allowed the CPU lane to engage outside that contract.
            maxMemoryBytes = pipelineMemoryBudget
        )
        val hybridEnabled = hybridConcurrency > 1 && pipelineBudgetFrames >= 2
        val cpuLanePermit: Semaphore? = if (hybridEnabled) Semaphore(permits = 1) else null
        val developWorkers = if (hybridEnabled) maxOf(workers, 2) else workers
        val maxResidentFrames = if (hybridEnabled) {
            2
        } else {
            // Pipeline overlap: fit as many frames as the REAL heap safely allows (guarded), so decode ∥
            // develop ∥ mux overlap instead of serialising at 1 frame. Default (flag OFF) = conservative.
            RecordDevelopPipelineOverlapPolicy.maxResidentFrames(
                residentBytesPerFrame = residentBytesPerFrame,
                // A/B rollback must restore a genuinely serial one-frame window. The enabled policy
                // may expand only to what pipelineBudgetFrames and the heap guard can safely hold.
                conservativeMaxFrames = 1,
                maxMemoryBytes = Runtime.getRuntime().maxMemory(),
                memoryBudgetBytes = pipelineMemoryBudget
            )
        }
        val reorderWindow = minOf(developWorkers + PIPELINE_CHANNEL_CAPACITY, maxResidentFrames)
        val inFlightPermits = Semaphore(permits = reorderWindow)
        // Row parallelism is sized for ONE concurrent CPU develop (cpuLanePermit caps CPU develops at 1), so
        // the CPU lane keeps full row fan-out even in the hybrid.
        val rowParallelism = resolveRowParallelism(workers, fmt.width, fmt.height)
        val rowExecutor = if (rowParallelism > 1) {
            Executors.newFixedThreadPool(rowParallelism) { task ->
                Thread(task, "recdev-row").apply { isDaemon = true }
            }
        } else {
            null
        }
        // Fan the heavy single-worker enhance passes across the same row-executor (byte-identical, disjoint
        // rows). Engages only in the single frame-worker / row-parallel regime; otherwise SEQUENTIAL keeps
        // the exact original behaviour. Reversible via `debug.record_enhance_parallel`.
        val enhanceRowScheduler = if (
            RecordDevelopEnhanceParallelismPolicy.enabled && rowExecutor != null && rowParallelism > 1
        ) {
            EnhanceRowScheduler.parallel(rowExecutor, rowParallelism)
        } else {
            EnhanceRowScheduler.SEQUENTIAL
        }
        NeoLogger.i(
            TAG,
            "Record-develop pipeline scheduling workers=$developWorkers reorderWindow=$reorderWindow " +
                "rowParallelism=$rowParallelism residentBytesPerFrame=$residentBytesPerFrame " +
                "memoryBudget=$memoryBudget outputFormat=${options.outputFormat} " +
                "hybridCpuLane=$hybridEnabled cpuShareBps=${RecordDevelopHybridCpuLanePolicy.cpuShareBps}"
        )

        var writtenFrames = 0L
        val readerDispatcher = newFixedThreadPoolContext(1, "recdev-reader")
        // The guarded hybrid may raise a one-worker CPU schedule to two develop lanes. Size the
        // dispatcher to the lanes we actually launch; otherwise both "concurrent" CPU/GPU workers
        // time-slice on one thread and the hybrid cannot overlap.
        val workerDispatcher = newFixedThreadPoolContext(developWorkers, "recdev-worker")
        val writerDispatcher = newFixedThreadPoolContext(1, "recdev-writer")
        try {
            runBlocking {
                val rawFrames = Channel<DecodedDevelopFrame>(capacity = PIPELINE_CHANNEL_CAPACITY)
                val packedFrames = Channel<PackedDevelopFrame>(capacity = PIPELINE_CHANNEL_CAPACITY)

                // READER: decode is inherently serial; the develop is stateless so nothing else runs here.
                // The temporal lane needs each frame's predecessor SOURCE P010; the serial reader is the one
                // place with the natural ordering, so it hands the prior frame's bytes forward. Using the
                // SOURCE (never an enhanced/developed result) keeps every worker's output a pure function of
                // its inputs ⇒ byte-identical regardless of which worker or when.
                val readerJob = launch(readerDispatcher) {
                    var priorSource: ByteArray? = null
                    while (true) {
                        // Reserve capacity before decode: readNextFrame itself materializes a full P010
                        // frame, so acquiring afterwards exceeded the advertised resident window by one.
                        inFlightPermits.acquire()
                        val frame = try {
                            reader.readNextFrame()
                        } catch (failure: Throwable) {
                            inFlightPermits.release()
                            throw failure
                        }
                        if (frame == null) {
                            inFlightPermits.release()
                            break
                        }
                        try {
                            val temporalPrior = if (plan.enhanceTemporal) priorSource else null
                            rawFrames.send(DecodedDevelopFrame(
                                index = frame.index,
                                presentationTimeUs = frame.presentationTimeUs,
                                p010 = frame.p010,
                                width = frame.width,
                                height = frame.height,
                                previousP010 = temporalPrior,
                                qualityInstruction = buildDevelopQualityInstruction(
                                    frameIndex = frame.index,
                                    p010 = frame.p010,
                                    previousP010 = temporalPrior,
                                    width = frame.width,
                                    height = frame.height,
                                    plan = plan
                                )
                            ))
                            priorSource = if (plan.enhanceTemporal) frame.p010 else null
                        } catch (failure: Throwable) {
                            inFlightPermits.release()
                            throw failure
                        }
                    }
                    rawFrames.close()
                }

                // WORKERS: the full per-pixel develop + pack. The pipeline holds no per-frame state, so a
                // shared instance is safe to fan out (its own contract). Byte-identical regardless of worker
                // count (pure function of source + sidecar + options), so only wall-clock changes.
                val workerJobs = (0 until developWorkers).map {
                    launch(workerDispatcher) {
                        for (raw in rawFrames) {
                            try {
                                // Phase-4: denoise/sharpen/temporal in the P010 code domain BEFORE the colour
                                // develop. enhanceP010 copies its input (source stays pristine ⇒ master read-only
                                // + valid temporal prior for the successor). Colour lanes are neutralised
                                // (calibration-free profile + identity SNCE nudge + no LUT) ⇒ no double-grade.
                                val niv99StartedNs = System.nanoTime()
                                val source = enhanceP010ForDevelop(
                                    raw = raw,
                                    profile = plan.enhanceProfile,
                                    niv99PixelProcessor = niv99PixelProcessor,
                                    detailProfile = plan.videoDetailProfile,
                                    highEndAiTuning = plan.highEndAiTuning,
                                    enhanceGpuUniforms = enhanceGpuUniforms,
                                    enhanceGpuPermit = enhanceGpuPermit,
                                    enhanceGpuDecision = enhanceGpuDecision,
                                    enhanceGpuFrames = enhanceGpuFrames,
                                    enhanceCpuFallbackFrames = enhanceCpuFallbackFrames,
                                    parityEnhancePermit = parityEnhancePermit,
                                    parityEnhanceDecision = parityEnhanceDecision,
                                    rowScheduler = enhanceRowScheduler
                                )
                                niv99PixelProcessor?.recordParityGatedFrame(
                                    frameIndex = raw.index,
                                    sensorTimestampNs = raw.presentationTimeUs.coerceAtLeast(0L) * 1_000L,
                                    output = source,
                                    temporalHistoryUsed = raw.previousP010 != null,
                                    residualWhiteBalanceApplied = false,
                                    processingTimeNs =
                                        (System.nanoTime() - niv99StartedNs).coerceAtLeast(0L)
                                )
                                // Phase 7: when GPU is the clip's chosen (parity-accepted) path, develop this
                                // frame's enhanced source on the GPU under the single permit — released before the
                                // CPU pack, so GPU(N+1) overlaps CPU-pack/mux(N). A null GPU result (per-frame
                                // dispatch failure) falls back to the byte-exact CPU develop for THIS frame only.
                                // When the clip decided CPU (or GPU is off), the CPU branch is unchanged from
                                // Phase 2–6 ⇒ byte-identical output.
                                // Hybrid: route this frame to the CPU lane (byte-exact) when the concurrent lane
                                // is active and the deterministic share selects it; the cpuLanePermit(1) bounds
                                // resident CPU develops to one so the heap holds ≤ ~1.5 frames. GPU frames run
                                // concurrently under gpuPermit(1). Both lanes feed the reorder-by-index writer.
                                val routeCpu = cpuLanePermit != null &&
                                    RecordDevelopHybridCpuLanePolicy.routeToCpuLane(raw.index)
                                suspend fun cpuDevelopPacked(): ByteArray {
                                    cpuLanePermit?.acquire()
                                    return try {
                                        developFrameToPackedPayload(
                                            source, raw.width, raw.height, plan, delivery,
                                            options.outputFormat, rowExecutor
                                        )
                                    } finally {
                                        cpuLanePermit?.release()
                                    }
                                }
                                val payload = if (!routeCpu && gpuPermit != null && accelerator != null &&
                                    decideGpu(
                                        gpuDecision, accelerator, source, raw.width, raw.height, plan, delivery,
                                        requireGpuDevelop = options.requireGpuDevelop,
                                        gpuPackedProvider = {
                                            accelerator.developFrameToPackedPayload(
                                                source, raw.width, raw.height, options.outputFormat
                                            )
                                        },
                                        cpuPackedFromCodes = { codes ->
                                            packDevelopedCodes(codes, raw.width, raw.height, options.outputFormat)
                                        },
                                        packedCategory = "PACK_${options.outputFormat.name}"
                                    )
                                ) {
                                    gpuPermit.acquire()
                                    val gpu = try {
                                        accelerator.developFrameToPackedPayload(
                                            source,
                                            raw.width,
                                            raw.height,
                                            options.outputFormat
                                        )
                                    } finally {
                                        gpuPermit.release()
                                    }
                                    if (gpu != null) {
                                        gpuFramesDeveloped.incrementAndGet()
                                        gpu
                                    } else if (options.requireGpuDevelop) {
                                        error("Record-develop strict GPU dispatch returned null")
                                    } else {
                                        cpuDevelopPacked()
                                    }
                                } else {
                                    cpuDevelopPacked()
                                }
                                packedFrames.send(PackedDevelopFrame(raw.index, raw.presentationTimeUs, payload))
                            } catch (failure: Throwable) {
                                // Reader transferred this frame's permit to the worker only after send
                                // succeeded. If develop or the worker→writer send fails, return that permit
                                // before propagating so the reader cannot remain capacity-blocked.
                                inFlightPermits.release()
                                throw failure
                            }
                        }
                    }
                }
                launch(workerDispatcher) {
                    workerJobs.joinAll()
                    packedFrames.close()
                }

                // WRITER: uncompressed samples must be muxed in frame order; hold out-of-order frames until
                // the next expected index arrives. Each write releases one permit (1:1 with reader acquire).
                val writerJob = launch(writerDispatcher) {
                    val pending = HashMap<Long, PackedDevelopFrame>()
                    var nextIndex = 0L
                    for (packed in packedFrames) {
                        pending[packed.index] = packed
                        while (true) {
                            val ready = pending.remove(nextIndex) ?: break
                            try {
                                val presentationUs = presentationTimeline.normalize(
                                    ready.index,
                                    ready.presentationTimeUs
                                )
                                writer.writeSample(
                                    trackIndex = track,
                                    sample = QuickTimeSampleBuffer(
                                        bytes = ready.payload,
                                        presentationTimeUs = presentationUs,
                                        flags = 1 // every uncompressed sample is a sync frame
                                    )
                                )
                                writtenFrames += 1L
                                onFrame?.invoke(writtenFrames.toInt())
                                nextIndex += 1L
                            } finally {
                                // The writer owns the permit after the worker send. Release it even when
                                // muxing or a progress callback fails so cancellation cannot strand capacity.
                                inFlightPermits.release()
                            }
                        }
                    }
                    check(pending.isEmpty()) {
                        "Record-develop reorder queue ended with ${pending.size} missing earlier frame(s)"
                    }
                    if (writtenFrames > 0L) {
                        writer.setTrackEndPresentationTimeUs(
                            track,
                            resolvedPresentationEndUs()
                        )
                    }
                }
                joinAll(readerJob, writerJob)
            }
        } finally {
            readerDispatcher.close()
            workerDispatcher.close()
            writerDispatcher.close()
            rowExecutor?.shutdown()
            rowExecutor?.awaitTermination(5, TimeUnit.SECONDS)
            // The accelerator is built fresh per clip; free its device resources (no-op for the CPU path).
            runCatching { accelerator?.close() }
        }
        return DevelopLoopResult(
            frameCount = writtenFrames,
            workerCount = developWorkers,
            gpuAccelerated = gpuFramesDeveloped.get() > 0L,
            gpuEnhanceFrames = enhanceGpuFrames.get(),
            enhanceProcessingPath = resolveEnhanceProcessingPath(
                gpuFrames = enhanceGpuFrames.get(),
                cpuFallbackFrames = enhanceCpuFallbackFrames.get(),
                enhanceEnabled = plan.enhanceEnabled,
                gpuAvailable = enhanceGpuPermit != null || parityEnhancePermit != null
            ),
            presentationDurationUs = if (writtenFrames > 0L) {
                resolvedPresentationEndUs()
            } else {
                0L
            }
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun runHybridHevcDevelop(
        reader: RecordDevelopP010FrameReader,
        writer: QuickTimeMovFileWriter,
        fmt: RecordDevelopDecodedFormat,
        plan: DevelopPlan,
        delivery: ColorDelivery,
        deliveryFormat: RecordDevelopDeliveryFormat,
        options: RecordDevelopConversionOptions,
        niv99PixelProcessor: Niv99OfflineP010PixelProcessor?,
        onFrame: ((framesDone: Int) -> Unit)?,
        gpuAcceleratorFactory: ((RecordDevelopGpuContext) -> RecordDevelopGpuAccelerator?)?
    ): DevelopLoopResult {
        plan.width = fmt.width
        plan.height = fmt.height
        plan.fps = fmt.fps
        val frameDurationUs = max(1L, 1_000_000L / fmt.fps.coerceAtLeast(1))
        val presentationTimeline = RecordDevelopPresentationTimeline(frameDurationUs)
        fun resolvedPresentationEndUs(): Long =
            presentationTimeline.endPresentationTimeUs(fmt.sourceDurationUs)

        val acceleratorCandidate = resolveGpuAccelerator(
            fmt = fmt,
            plan = plan,
            delivery = delivery,
            options = options,
            gpuAcceleratorFactory = gpuAcceleratorFactory
        )
        val memoryBudget = RecordDevelopPipelineOverlapPolicy.effectiveMemoryBudget(
            requestedBudgetBytes = options.memoryBudgetBytes,
            maxMemoryBytes = Runtime.getRuntime().maxMemory()
        )
        val temporalReserveBytes = if (plan.enhanceTemporal) {
            Math.multiplyExact(Math.multiplyExact(fmt.width.toLong(), fmt.height.toLong()), 3L)
        } else {
            0L
        }
        val cpuResidentBytes = estimateResidentBytesPerFrameForHevc(
            width = fmt.width,
            height = fmt.height,
            enhanceEnabled = plan.enhanceEnabled,
            gpuDevelopAvailable = false
        )
        val acceleratedResidentBytes = estimateResidentBytesPerFrameForHevc(
            width = fmt.width,
            height = fmt.height,
            enhanceEnabled = plan.enhanceEnabled,
            gpuDevelopAvailable = acceleratorCandidate != null
        )
        val accelerator = admitGpuDevelopWithinMemoryBudget(
            candidate = acceleratorCandidate,
            options = options,
            residentBytesPerFrame = acceleratedResidentBytes,
            fixedReserveBytes = temporalReserveBytes,
            memoryBudgetBytes = memoryBudget,
            deliveryLabel = deliveryFormat.auditLabel
        )
        val gpuPermit: Semaphore? = if (accelerator != null) Semaphore(permits = 1) else null
        val gpuDecision = java.util.concurrent.atomic.AtomicInteger(-1)
        val gpuFramesDeveloped = java.util.concurrent.atomic.AtomicLong(0L)
        val hevcUsesBt709Matrix =
            delivery.colorConfig.matrixCoefficients == QuickTimeColorAtomPolicy.MATRIX_BT709
        val enhanceGpuUniforms = plan.enhanceProfile?.let { buildEnhanceGpuUniforms(it, plan.target) }
        val usesDedicatedLiveParityEnhance =
            plan.enhanceProfile == CINEMA_NDR_LIVE_PARITY_ENHANCE_PROFILE ||
                plan.enhanceProfile == CINEMA_SDR_LIVE_PARITY_ENHANCE_PROFILE
        val enhanceGpuPermit: Semaphore? =
            if (!usesDedicatedLiveParityEnhance &&
                enhanceGpuUniforms != null &&
                Npv10ExportGpuAccelerator.isAccelerationAvailable()
            ) {
                Semaphore(permits = 1)
            } else {
                null
            }
        val enhanceGpuDecision = java.util.concurrent.atomic.AtomicInteger(-1)
        val enhanceGpuFrames = java.util.concurrent.atomic.AtomicLong(0L)
        val enhanceCpuFallbackFrames = java.util.concurrent.atomic.AtomicLong(0L)
        // Live-parity luma enhance on the GPU (single permit; native mutex serialises the shared engine).
        // Engaged only when the flag is on AND the optional native pipeline built on this device; a
        // first-frame per-operating-point parity gate then commits GPU-or-CPU uniformly for the whole clip.
        val parityEnhancePermit: Semaphore? =
            if (plan.enhanceProfile != null &&
                RecordDevelopParityEnhanceGpuFlag.enabled &&
                RecordDevelopParityEnhanceGpuBridge.isAvailable()
            ) {
                Semaphore(permits = 1)
            } else {
                null
            }
        val parityEnhanceDecision = java.util.concurrent.atomic.AtomicInteger(-1)

        val residentBytesPerFrame = if (accelerator != null) acceleratedResidentBytes else cpuResidentBytes
        val pipelineBudgetFrames = RecordDevelopPipelineOverlapPolicy.frameCapacity(
            residentBytesPerFrame = residentBytesPerFrame,
            fixedReserveBytes = temporalReserveBytes,
            memoryBudgetBytes = memoryBudget
        )
        val pipelineMemoryBudget = memoryBudget - temporalReserveBytes
        val workers = ConversionParallelismPolicy.workerCount(
            frameBytes = residentBytesPerFrame,
            memoryBudgetBytes = pipelineMemoryBudget,
            pipelineDepth = PIPELINE_CHANNEL_CAPACITY,
            residentCopiesPerFrame = 1
        )
        // Concurrent CPU+GPU hybrid ([RecordDevelopHybridCpuLaneFlag], default OFF) — see the uncompressed
        // path for the full rationale. Guard-authorised ⇒ resident cap 2 (one CHEAP GPU frame + one CPU
        // frame); gpuPermit(1) + cpuLanePermit(1) keep at most one CPU develop resident. Byte-identical.
        val hybridConcurrency = RecordDevelopHybridCpuLanePolicy.developConcurrency(
            gpuAvailable = accelerator != null,
            residentBytesPerFrame = residentBytesPerFrame,
            maxMemoryBytes = pipelineMemoryBudget
        )
        val hybridEnabled = hybridConcurrency > 1 && pipelineBudgetFrames >= 2
        val cpuLanePermit: Semaphore? = if (hybridEnabled) Semaphore(permits = 1) else null
        val developWorkers = if (hybridEnabled) maxOf(workers, 2) else workers
        val maxResidentFrames = if (hybridEnabled) {
            2
        } else {
            // Pipeline overlap: fit as many frames as the REAL heap safely allows (guarded), so decode ∥
            // develop ∥ mux overlap instead of serialising at 1 frame. Default (flag OFF) = conservative.
            RecordDevelopPipelineOverlapPolicy.maxResidentFrames(
                residentBytesPerFrame = residentBytesPerFrame,
                conservativeMaxFrames = 1,
                maxMemoryBytes = Runtime.getRuntime().maxMemory(),
                memoryBudgetBytes = pipelineMemoryBudget
            )
        }
        val reorderWindow = minOf(developWorkers + PIPELINE_CHANNEL_CAPACITY, maxResidentFrames)
        val inFlightPermits = Semaphore(permits = reorderWindow)
        val rowParallelism = resolveRowParallelism(workers, fmt.width, fmt.height)
        val rowExecutor = if (rowParallelism > 1) {
            Executors.newFixedThreadPool(rowParallelism) { task ->
                Thread(task, "recdev-hevc-row").apply { isDaemon = true }
            }
        } else {
            null
        }
        val enhanceRowScheduler = if (
            RecordDevelopEnhanceParallelismPolicy.enabled && rowExecutor != null && rowParallelism > 1
        ) {
            EnhanceRowScheduler.parallel(rowExecutor, rowParallelism)
        } else {
            EnhanceRowScheduler.SEQUENTIAL
        }
        NeoLogger.i(
            TAG,
            "Record-develop HEVC scheduling workers=$developWorkers reorderWindow=$reorderWindow " +
                "rowParallelism=$rowParallelism residentBytesPerFrame=$residentBytesPerFrame " +
                "memoryBudget=$memoryBudget delivery=${deliveryFormat.auditLabel} " +
                "hybridCpuLane=$hybridEnabled cpuShareBps=${RecordDevelopHybridCpuLanePolicy.cpuShareBps}"
        )

        var writtenFrames = 0L
        var encoder: RecordDevelopHevcMovEncoder? = null
        val readerDispatcher = newFixedThreadPoolContext(1, "recdev-hevc-reader")
        val workerDispatcher = newFixedThreadPoolContext(developWorkers, "recdev-hevc-worker")
        val writerDispatcher = newFixedThreadPoolContext(1, "recdev-hevc-writer")
        try {
            runBlocking {
                val rawFrames = Channel<DecodedDevelopFrame>(capacity = PIPELINE_CHANNEL_CAPACITY)
                val developedFrames = Channel<PackedDevelopFrame>(capacity = PIPELINE_CHANNEL_CAPACITY)

                val readerJob = launch(readerDispatcher) {
                    var priorSource: ByteArray? = null
                    while (true) {
                        inFlightPermits.acquire()
                        val frame = try {
                            reader.readNextFrame()
                        } catch (failure: Throwable) {
                            inFlightPermits.release()
                            throw failure
                        }
                        if (frame == null) {
                            inFlightPermits.release()
                            break
                        }
                        try {
                            val temporalPrior = if (plan.enhanceTemporal) priorSource else null
                            rawFrames.send(DecodedDevelopFrame(
                                index = frame.index,
                                presentationTimeUs = frame.presentationTimeUs,
                                p010 = frame.p010,
                                width = frame.width,
                                height = frame.height,
                                previousP010 = temporalPrior,
                                qualityInstruction = buildDevelopQualityInstruction(
                                    frameIndex = frame.index,
                                    p010 = frame.p010,
                                    previousP010 = temporalPrior,
                                    width = frame.width,
                                    height = frame.height,
                                    plan = plan
                                )
                            ))
                            priorSource = if (plan.enhanceTemporal) frame.p010 else null
                        } catch (failure: Throwable) {
                            inFlightPermits.release()
                            throw failure
                        }
                    }
                    rawFrames.close()
                }

                val workerJobs = (0 until developWorkers).map {
                    launch(workerDispatcher) {
                        for (raw in rawFrames) {
                            try {
                                val niv99StartedNs = System.nanoTime()
                                val source = enhanceP010ForDevelop(
                                    raw = raw,
                                    profile = plan.enhanceProfile,
                                    niv99PixelProcessor = niv99PixelProcessor,
                                    detailProfile = plan.videoDetailProfile,
                                    highEndAiTuning = plan.highEndAiTuning,
                                    enhanceGpuUniforms = enhanceGpuUniforms,
                                    enhanceGpuPermit = enhanceGpuPermit,
                                    enhanceGpuDecision = enhanceGpuDecision,
                                    enhanceGpuFrames = enhanceGpuFrames,
                                    enhanceCpuFallbackFrames = enhanceCpuFallbackFrames,
                                    parityEnhancePermit = parityEnhancePermit,
                                    parityEnhanceDecision = parityEnhanceDecision,
                                    rowScheduler = enhanceRowScheduler
                                )
                                niv99PixelProcessor?.recordParityGatedFrame(
                                    frameIndex = raw.index,
                                    sensorTimestampNs = raw.presentationTimeUs.coerceAtLeast(0L) * 1_000L,
                                    output = source,
                                    temporalHistoryUsed = raw.previousP010 != null,
                                    residualWhiteBalanceApplied = false,
                                    processingTimeNs =
                                        (System.nanoTime() - niv99StartedNs).coerceAtLeast(0L)
                                )
                                // Hybrid: concurrent CPU lane for ~cpuShare of frames (byte-exact), bounded by
                                // cpuLanePermit(1); GPU frames run concurrently under gpuPermit(1). See the
                                // uncompressed path for the full rationale.
                                val routeCpu = cpuLanePermit != null &&
                                    RecordDevelopHybridCpuLanePolicy.routeToCpuLane(raw.index)
                                suspend fun cpuDevelopP010(): ByteArray {
                                    cpuLanePermit?.acquire()
                                    return try {
                                        developFrameToP010420Payload(
                                            source, raw.width, raw.height, plan, delivery, rowExecutor
                                        )
                                    } finally {
                                        cpuLanePermit?.release()
                                    }
                                }
                                val payload = if (!routeCpu && gpuPermit != null && accelerator != null &&
                                    decideGpu(
                                        gpuDecision, accelerator, source, raw.width, raw.height, plan, delivery,
                                        requireGpuDevelop = options.requireGpuDevelop,
                                        gpuPackedProvider = {
                                            accelerator.developFrameToP010420Payload(
                                                source, raw.width, raw.height, hevcUsesBt709Matrix
                                            )
                                        },
                                        cpuPackedFromCodes = { codes ->
                                            developedCodesToP010420Payload(codes, raw.width, raw.height, delivery)
                                        },
                                        packedCategory = "PACK_P010_420"
                                    )
                                ) {
                                    gpuPermit.acquire()
                                    val gpuPayload = try {
                                        accelerator.developFrameToP010420Payload(
                                            source,
                                            raw.width,
                                            raw.height,
                                            hevcUsesBt709Matrix
                                        )
                                    } finally {
                                        gpuPermit.release()
                                    }
                                    if (gpuPayload != null) {
                                        gpuFramesDeveloped.incrementAndGet()
                                        gpuPayload
                                    } else if (options.requireGpuDevelop) {
                                        error("Record-develop strict GPU dispatch returned null")
                                    } else {
                                        cpuDevelopP010()
                                    }
                                } else {
                                    cpuDevelopP010()
                                }
                                developedFrames.send(PackedDevelopFrame(raw.index, raw.presentationTimeUs, payload))
                            } catch (failure: Throwable) {
                                inFlightPermits.release()
                                throw failure
                            }
                        }
                    }
                }
                launch(workerDispatcher) {
                    workerJobs.joinAll()
                    developedFrames.close()
                }

                val writerJob = launch(writerDispatcher) {
                    val hevc = RecordDevelopHevcMovEncoder(
                        writer = writer,
                        width = fmt.width,
                        height = fmt.height,
                        fps = fmt.fps,
                        colorConfig = delivery.colorConfig,
                        deliveryFormat = deliveryFormat
                    )
                    encoder = hevc
                    check(hevc.advancedQualityControlsAccepted) {
                        "Record-develop HEVC publication requires encoder acceptance of every elite quality control"
                    }
                    val pending = HashMap<Long, PackedDevelopFrame>()
                    var nextIndex = 0L
                    for (developed in developedFrames) {
                        pending[developed.index] = developed
                        while (true) {
                            val ready = pending.remove(nextIndex) ?: break
                            try {
                                val presentationUs = presentationTimeline.normalize(
                                    ready.index,
                                    ready.presentationTimeUs
                                )
                                hevc.encodeFrame(ready.payload, presentationUs)
                                writtenFrames += 1L
                                onFrame?.invoke(writtenFrames.toInt())
                                nextIndex += 1L
                            } finally {
                                inFlightPermits.release()
                            }
                        }
                    }
                    check(pending.isEmpty()) {
                        "Record-develop HEVC reorder queue ended with ${pending.size} missing earlier frame(s)"
                    }
                    val encodedFrames = hevc.finish(resolvedPresentationEndUs())
                    check(encodedFrames == writtenFrames) {
                        "HEVC record-develop encoded frame mismatch encoded=$encodedFrames submitted=$writtenFrames"
                    }
                }
                joinAll(readerJob, writerJob)
            }
        } finally {
            readerDispatcher.close()
            workerDispatcher.close()
            writerDispatcher.close()
            rowExecutor?.shutdown()
            rowExecutor?.awaitTermination(5, TimeUnit.SECONDS)
            runCatching { encoder?.close() }
            runCatching { accelerator?.close() }
        }

        return DevelopLoopResult(
            frameCount = writtenFrames,
            workerCount = developWorkers,
            gpuAccelerated = gpuFramesDeveloped.get() > 0L,
            gpuEnhanceFrames = enhanceGpuFrames.get(),
            enhanceProcessingPath = resolveEnhanceProcessingPath(
                gpuFrames = enhanceGpuFrames.get(),
                cpuFallbackFrames = enhanceCpuFallbackFrames.get(),
                enhanceEnabled = plan.enhanceEnabled,
                gpuAvailable = enhanceGpuPermit != null || parityEnhancePermit != null
            ),
            presentationDurationUs = resolvedPresentationEndUs(),
            minimumBitrateBps = requireNotNull(encoder).minimumViableBitrateBps,
            configuredBitrateBps = requireNotNull(encoder).configuredBitrateBps,
            policyTargetBitrateBps = requireNotNull(encoder).policyTargetBitrateBps,
            advertisedBitrateCeilingBps = requireNotNull(encoder).advertisedBitrateCeilingBps,
            encoderCapabilityBound = requireNotNull(encoder).capabilityBound,
            advancedEncoderControlsAccepted = requireNotNull(encoder).advancedQualityControlsAccepted
        )
    }

    /** Resolves the optional accelerator once; strict mode rejects every unavailable state. */
    private fun resolveGpuAccelerator(
        fmt: RecordDevelopDecodedFormat,
        plan: DevelopPlan,
        delivery: ColorDelivery,
        options: RecordDevelopConversionOptions,
        gpuAcceleratorFactory: ((RecordDevelopGpuContext) -> RecordDevelopGpuAccelerator?)?
    ): RecordDevelopGpuAccelerator? {
        if (!RecordDevelopGpuAccelerationFlag.enabled) {
            check(!options.requireGpuDevelop) {
                "Record-develop strict GPU requested but GPU acceleration is disabled"
            }
            return null
        }
        if (gpuAcceleratorFactory == null) {
            check(!options.requireGpuDevelop) {
                "Record-develop strict GPU requested but no accelerator factory was provided"
            }
            return null
        }
        val candidate = gpuAcceleratorFactory(buildGpuContext(fmt, plan, delivery))
        if (candidate == null || !candidate.isAvailable()) {
            runCatching { candidate?.close() }
            check(!options.requireGpuDevelop) {
                "Record-develop strict GPU requested but no available colour-develop accelerator exists"
            }
            return null
        }
        return candidate
    }

    /**
     * The first-frame GPU parity gate holds two full RGB10 code planes at once: the native candidate and
     * the CPU reference. On smaller ART heaps that temporary validation peak can exceed the safe conversion
     * envelope even though the byte-exact serial CPU path fits. Admit the optional GPU lane only when its
     * complete peak fits; otherwise close it before decode and use the CPU reference for the whole clip.
     * Pixel math, bit depth, geometry, colour tags and output format are unchanged.
     */
    private fun admitGpuDevelopWithinMemoryBudget(
        candidate: RecordDevelopGpuAccelerator?,
        options: RecordDevelopConversionOptions,
        residentBytesPerFrame: Long,
        fixedReserveBytes: Long,
        memoryBudgetBytes: Long,
        deliveryLabel: String
    ): RecordDevelopGpuAccelerator? {
        if (candidate == null) return null
        if (
            RecordDevelopPipelineOverlapPolicy.canFitOneFrame(
                residentBytesPerFrame = residentBytesPerFrame,
                fixedReserveBytes = fixedReserveBytes,
                memoryBudgetBytes = memoryBudgetBytes
            )
        ) {
            return candidate
        }

        val reason =
            "Record-develop GPU parity working set $residentBytesPerFrame plus fixed reserve " +
                "$fixedReserveBytes exceeds memory budget $memoryBudgetBytes for $deliveryLabel"
        runCatching { candidate.close() }
        check(!options.requireGpuDevelop) { reason }
        NeoLogger.i(TAG, "$reason; selecting the byte-exact CPU reference path before decode")
        return null
    }

    /** Flattens the per-clip develop plan + delivery into the public [RecordDevelopGpuContext] seam once. */
    private fun buildGpuContext(
        fmt: RecordDevelopDecodedFormat,
        plan: DevelopPlan,
        delivery: ColorDelivery
    ): RecordDevelopGpuContext = RecordDevelopGpuContext(
        format = fmt,
        target = plan.target,
        decodeSpace = plan.decodeSpace,
        decodeTransfer = plan.decodeTransfer,
        outputTransfer = delivery.outputTransfer,
        snceAppearanceStrength = plan.options.snceAppearanceStrength,
        filmPrintStrength = plan.options.filmPrintStrength,
        warmSkinRedTameStrength = if (delivery.ndrGenuineHdr) {
            NdrWarmSkinRedTameFlag.effectiveStrength.toFloat()
        } else {
            plan.options.warmSkinRedTameStrength
        },
        acesToneMap = plan.options.acesToneMap,
        semanticClass = plan.options.semanticClass,
        clampOutput = plan.options.clampOutput,
        ndrGenuineHdr = delivery.ndrGenuineHdr,
        diffuseWhiteNits = plan.diffuseWhiteNits,
        widenToRec2020 = delivery.widenToRec2020,
        workingRgbToXyz = plan.workingRgbToXyz,
        sceneLuminanceLA = plan.sceneLuminanceLA
    )

    private fun buildEnhanceGpuUniforms(
        profile: Npv10ExportEnhancementProfile,
        target: DevelopTarget
    ): GpuImageEnhancementUniforms {
        val signalFamily = if (target == DevelopTarget.NDR_PQ_BT2020) GpuSignalFamily.HDR else GpuSignalFamily.SDR
        val activeTransport = when (target) {
            DevelopTarget.SDR_REC709 -> GpuActiveTransport.REC709_SDR
            DevelopTarget.SDR_REC2020 -> GpuActiveTransport.BT2020_SDR
            DevelopTarget.NDR_PQ_BT2020 -> GpuActiveTransport.BT2100_PQ_MAIN10
        }
        val colorSpaceName = when (target) {
            DevelopTarget.SDR_REC709 -> "Rec.709"
            DevelopTarget.SDR_REC2020,
            DevelopTarget.NDR_PQ_BT2020 -> "BT.2020"
        }
        return GpuImageEnhancementUniforms.fromSceneProfile(
            profile = SceneRenderProfile(
                temporalBlend = profile.temporalLumaBlend.toFloat(),
                spatialDenoise = 0f,
                chromaDenoise = 1f - profile.chromaSuppression.toFloat(),
                shadowBoost = 0f,
                sharpening = profile.strongDetailGain.toFloat(),
                warmth = 0f,
                contrast = 1f,
                saturation = 1f,
                highlightCompression = 0f,
                logStrength = 0f,
                ditherStrength = 0f,
                videoHdrFusionStrength = if (signalFamily == GpuSignalFamily.HDR) 0.82f else 0f
            ),
            signalFamily = signalFamily,
            colorSpaceName = colorSpaceName,
            colorScienceName = "RecordDevelop",
            activeTransport = activeTransport,
            exportProfile = profile
        )
    }

    /**
     * Produce the NQIS decision on the ordered decode lane while the previous frame is still naturally
     * available. Pixel enhancement remains on the CPU/GPU worker; this only moves lightweight analysis ahead
     * of it and guarantees every processing lane consumes the same bounded controls.
     */
    private fun buildDevelopQualityInstruction(
        frameIndex: Long,
        p010: ByteArray,
        previousP010: ByteArray?,
        width: Int,
        height: Int,
        plan: DevelopPlan
    ): NeoQualityInstruction? {
        val family = when (plan.enhanceProfile) {
            CINEMA_NDR_LIVE_PARITY_ENHANCE_PROFILE -> GpuSignalFamily.HDR
            CINEMA_SDR_LIVE_PARITY_ENHANCE_PROFILE -> GpuSignalFamily.SDR
            else -> return null
        }
        val tuning = plan.highEndAiTuning
        return NeoQualityInstructionStream.analyzeP010(
            frameIndex = frameIndex,
            payload = p010,
            previousP010Payload = previousP010,
            width = width,
            height = height,
            signalFamily = family,
            detailProfile = plan.videoDetailProfile,
            sourceSceneAnalysisEnabled = tuning.sourceSceneAnalysisEnabled,
            sharpnessScale = if (tuning.enabled) tuning.sharpnessScale else 1f,
            denoiseScale = if (tuning.enabled) tuning.denoiseScale else 1f
        )
    }

    private suspend fun enhanceP010ForDevelop(
        raw: DecodedDevelopFrame,
        profile: Npv10ExportEnhancementProfile?,
        niv99PixelProcessor: Niv99OfflineP010PixelProcessor?,
        detailProfile: VideoDetailProfile,
        highEndAiTuning: RecordDevelopHighEndAiTuning,
        enhanceGpuUniforms: GpuImageEnhancementUniforms?,
        enhanceGpuPermit: Semaphore?,
        enhanceGpuDecision: java.util.concurrent.atomic.AtomicInteger,
        enhanceGpuFrames: java.util.concurrent.atomic.AtomicLong,
        enhanceCpuFallbackFrames: java.util.concurrent.atomic.AtomicLong,
        parityEnhancePermit: Semaphore?,
        parityEnhanceDecision: java.util.concurrent.atomic.AtomicInteger,
        rowScheduler: EnhanceRowScheduler = EnhanceRowScheduler.SEQUENTIAL
    ): ByteArray {
        if (profile == null) return raw.p010
        val niv99 = checkNotNull(niv99PixelProcessor) {
            "Enabled record-develop enhancement requires the NIV99 P010 pixel processor"
        }
        // LIVE-CINEMA parity (default-ON): the resolved profile has detail gains = 0 (lift/Apex disabled),
        // and the sharpen/denoise is supplied by the live bilateral + halo-guarded unsharp. Both SDR and NDR
        // use ConversionFrameQualityPolicy: each decoded frame's luma/chroma noise and temporal instability
        // select more denoise and less sharpening as noise rises, while the persisted Natural/Focused/Extreme
        // profile selects the intended detail range. The edge-aware bilateral preserves real edges.
        // null => the legacy over-sharpened path (byte-identical to before).
        fun qualityFor(family: GpuSignalFamily): ConversionFrameQualityPolicy.Plan {
            val precomputed = raw.qualityInstruction
            if (precomputed != null &&
                precomputed.signalFamily == family &&
                precomputed.detailProfile == detailProfile
            ) {
                return precomputed.quality
            }
            // Defensive compatibility for injected/test readers that construct a frame without NQIS.
            return NeoQualityInstructionStream.analyzeP010(
                frameIndex = raw.index,
                payload = raw.p010,
                previousP010Payload = raw.previousP010,
                width = raw.width,
                height = raw.height,
                signalFamily = family,
                detailProfile = detailProfile,
                sourceSceneAnalysisEnabled = highEndAiTuning.sourceSceneAnalysisEnabled,
                sharpnessScale = if (highEndAiTuning.enabled) highEndAiTuning.sharpnessScale else 1f,
                denoiseScale = if (highEndAiTuning.enabled) highEndAiTuning.denoiseScale else 1f
            ).quality
        }
        val liveParityOp: Pair<Double, Double>? = when (profile) {
            CINEMA_NDR_LIVE_PARITY_ENHANCE_PROFILE -> {
                val quality = qualityFor(GpuSignalFamily.HDR)
                quality.sharpen.toDouble() to quality.denoise.toDouble()
            }
            CINEMA_SDR_LIVE_PARITY_ENHANCE_PROFILE -> {
                val quality = qualityFor(GpuSignalFamily.SDR)
                quality.sharpen.toDouble() to quality.denoise.toDouble()
            }
            else -> null
        }
        fun cpu(): ByteArray {
            enhanceCpuFallbackFrames.incrementAndGet()
            return niv99.applyCpu(
                frameIndex = raw.index,
                sensorTimestampNs = raw.presentationTimeUs.coerceAtLeast(0L) * 1_000L,
                sourceP010 = raw.p010,
                previousSourceP010 = raw.previousP010,
                profile = profile,
                liveCinemaSharpness = liveParityOp?.first,
                liveCinemaDenoise = liveParityOp?.second,
                gradeLut = null,
                gradeLutSize = 0,
                saturation = 1f,
                warmth = 0f,
                rowScheduler = rowScheduler
            )
        }
        // LIVE parity: run the bilateral + halo-guarded unsharp LUMA pass on the GPU when the (optional)
        // native parity pipeline is available AND a per-operating-point first-frame parity gate accepts it;
        // otherwise on the CPU. Either way the colour-lane enhanceP010 (identity luma under the gain-zeroed
        // parity profile — chroma-NR / temporal / dither only) runs on the CPU over the resulting luma, so
        // the develop stays byte-identical regardless of where the luma pass ran. A divergent/unavailable/
        // timed-out GPU pass yields the byte-exact CPU pass ⇒ worst case == today's approved parity look.
        if (liveParityOp != null) {
            val (uSharp, uDenoise) = liveParityOp
            val gpuLuma: ByteArray? =
                if (RecordDevelopParityEnhanceGpuFlag.enabled &&
                    parityEnhancePermit != null &&
                    RecordDevelopParityEnhanceGpuBridge.isAvailable() &&
                    decideParityEnhanceGpu(
                        parityEnhanceDecision,
                        raw,
                        profile,
                        detailProfile,
                        parityEnhancePermit
                    )
                ) {
                    parityEnhancePermit.acquire()
                    val enhanced = try {
                        RecordDevelopParityEnhanceGpuBridge.enhanceLumaP010(
                            payload = raw.p010,
                            width = raw.width,
                            height = raw.height,
                            uSharpness = uSharp,
                            uDenoise = uDenoise
                        )
                    } finally {
                        parityEnhancePermit.release()
                    }
                    if (enhanced != null && enhanced.size == raw.p010.size) enhanced else null
                } else {
                    null
                }
            val lumaSource = if (gpuLuma != null) {
                enhanceGpuFrames.incrementAndGet()
                gpuLuma
            } else {
                enhanceCpuFallbackFrames.incrementAndGet()
                Npv10ExportEnhancementPolicy.enhanceLiveCinemaLumaP010(
                    payload = raw.p010,
                    width = raw.width,
                    height = raw.height,
                    uSharpness = uSharp,
                    uDenoise = uDenoise,
                    rowScheduler = rowScheduler
                )
            }
            val cpuEnhanced = niv99.applyCpu(
                frameIndex = raw.index,
                sensorTimestampNs = raw.presentationTimeUs.coerceAtLeast(0L) * 1_000L,
                sourceP010 = raw.p010,
                previousSourceP010 = raw.previousP010,
                profile = profile,
                liveCinemaSharpness = null,
                liveCinemaDenoise = null,
                gradeLut = null,
                gradeLutSize = 0,
                saturation = 1f,
                warmth = 0f,
                rowScheduler = rowScheduler,
                preprocessedLuma = lumaSource
            )
            // OPTIONAL (dormant [RecordDevelopEnhanceLaneGpuFlag], default OFF): also run the chroma enhance
            // lane (temporal/chroma-NR/deadband/spike/dither) on the GPU and accept it ONLY when it matches
            // the CPU reference within the develop parity tolerance — else keep the byte-exact CPU result.
            // This is the on-device A/B path: zero downgrade (a divergent/unavailable/timed-out GPU lane
            // yields the exact CPU output). It currently runs both lanes per frame to VERIFY the GPU lane on
            // device; a decide-once clip verdict (to skip the CPU pass for true offload) is the follow-up
            // once the residual is confirmed within tolerance.
            if (RecordDevelopEnhanceLaneGpuFlag.enabled && RecordDevelopEnhanceLaneGpuBridge.isAvailable()) {
                val gpuEnhanced = RecordDevelopEnhanceLaneGpuBridge.enhanceLaneP010(
                    payload = lumaSource,
                    previousP010Payload = raw.previousP010,
                    width = raw.width,
                    height = raw.height,
                    uniforms = Npv10ExportEnhancementPolicy.enhanceLaneGpuUniforms(profile)
                )
                if (gpuEnhanced != null && gpuEnhanced.size == cpuEnhanced.size &&
                    enhanceLanePayloadsMatch(gpuEnhanced, cpuEnhanced, raw.width, profile)
                ) {
                    enhanceGpuFrames.incrementAndGet()
                    return gpuEnhanced
                }
                enhanceCpuFallbackFrames.incrementAndGet()
            }
            return cpuEnhanced
        }
        // B3 no-downgrade temporal-parity fix: the clip-level GPU/CPU decision (decideGpuEnhance) probes a
        // frame whose temporal lane is INERT (previousP010 == null), so it validates only the spatial/detail/
        // chroma lanes. The native GPU temporal lane uses a different adaptive gate + weight cap than the CPU
        // reference and can diverge past tolerance on motion. Route every frame whose temporal lane is
        // genuinely active (a prior source frame exists) straight to the reference-quality CPU enhance — this
        // GUARANTEES the develop output never diverges from the CPU elite reference, and is strictly LESS work
        // than running the GPU lane and re-verifying it against CPU. Frames with no temporal prior (frame 0 /
        // non-temporal clips) keep the GPU path the decision gate already validated, so GPU-accelerated
        // develop is fully retained wherever it is proven correct.
        if (raw.previousP010 != null) return cpu()
        if (enhanceGpuUniforms == null || enhanceGpuPermit == null) return cpu()
        if (!decideGpuEnhance(
                decision = enhanceGpuDecision,
                raw = raw,
                profile = profile,
                uniforms = enhanceGpuUniforms,
                permit = enhanceGpuPermit
            )
        ) {
            return cpu()
        }
        enhanceGpuPermit.acquire()
        val gpu = try {
            Npv10ExportGpuAccelerator.enhanceP010(
                payload = raw.p010,
                previousP010Payload = raw.previousP010,
                width = raw.width,
                height = raw.height,
                uniforms = enhanceGpuUniforms
            )
        } finally {
            enhanceGpuPermit.release()
        }
        val payload = gpu?.payload
        return if (payload != null && payload.size == raw.p010.size) {
            enhanceGpuFrames.incrementAndGet()
            payload
        } else {
            cpu()
        }
    }

    private suspend fun decideGpuEnhance(
        decision: java.util.concurrent.atomic.AtomicInteger,
        raw: DecodedDevelopFrame,
        profile: Npv10ExportEnhancementProfile,
        uniforms: GpuImageEnhancementUniforms,
        permit: Semaphore
    ): Boolean {
        val current = decision.get()
        if (current >= 0) return current == 1
        synchronized(decision) {
            if (decision.get() >= 0) return decision.get() == 1
        }
        permit.acquire()
        val gpu = try {
            Npv10ExportGpuAccelerator.enhanceP010(
                payload = raw.p010,
                previousP010Payload = raw.previousP010,
                width = raw.width,
                height = raw.height,
                uniforms = uniforms
            )?.payload
        } finally {
            permit.release()
        }
        val accepted = if (gpu != null && gpu.size == raw.p010.size) {
            val cpu = Npv10ExportEnhancementPolicy.enhanceP010(
                payload = raw.p010,
                width = raw.width,
                height = raw.height,
                previousP010Payload = raw.previousP010,
                profile = profile,
                gradeLut = null,
                gradeLutSize = 0,
                saturation = 1f,
                warmth = 0f
            )
            p010PayloadsMatchWithinTolerance(gpu, cpu)
        } else {
            false
        }
        synchronized(decision) {
            if (decision.get() < 0) decision.set(if (accepted) 1 else 0)
            return decision.get() == 1
        }
    }

    private fun p010PayloadsMatchWithinTolerance(gpu: ByteArray, cpu: ByteArray): Boolean {
        if (gpu.size != cpu.size || gpu.size < Short.SIZE_BYTES) return false
        val samples = gpu.size / Short.SIZE_BYTES
        val stride = max(1, samples / 65_536)
        var count = 0
        var sumAbs = 0.0
        var maxAbs = 0
        var sample = 0
        while (sample < samples) {
            val offset = sample * Short.SIZE_BYTES
            val g = readP010Code(gpu, offset)
            val c = readP010Code(cpu, offset)
            val d = abs(g - c)
            sumAbs += d.toDouble()
            if (d > maxAbs) maxAbs = d
            count += 1
            sample += stride
        }
        if (count == 0) return false
        return (sumAbs / count) <= RecordDevelopParityGatePolicy.meanTolerance &&
            maxAbs <= RecordDevelopParityGatePolicy.maxTolerance
    }

    /**
     * Resolve the clip's parity-enhance GPU-vs-CPU decision EXACTLY ONCE (the first worker to reach the gate
     * probes; the rest read the committed 0/1). The GPU luma pass is float32 while the CPU reference is
     * float64, so it is accepted only when it matches the CPU [Npv10ExportEnhancementPolicy.enhanceLiveCinemaLumaP010]
     * within [RecordDevelopParityGatePolicy] (meanAbs / maxAbs) on the LUMA plane. SDR and NDR both resolve
     * (sharpen,denoise) per frame over a profile-specific bounded range, so the gate validates both clean and
     * noisy extremes. The kernel is continuous between them. Once committed, every frame uses one implementation.
     */
    private suspend fun decideParityEnhanceGpu(
        decision: java.util.concurrent.atomic.AtomicInteger,
        raw: DecodedDevelopFrame,
        profile: Npv10ExportEnhancementProfile?,
        detailProfile: VideoDetailProfile,
        permit: Semaphore
    ): Boolean {
        val current = decision.get()
        if (current >= 0) return current == 1
        synchronized(decision) {
            if (decision.get() >= 0) return decision.get() == 1
        }
        // Probe OUTSIDE the lock (Semaphore.acquire is suspending). Two workers may both probe on the very
        // first frame — harmless: the permit serialises the GPU calls and both derive the SAME accept/reject,
        // then commit the same 0/1 idempotently (mirrors decideGpuEnhance).
        var accepted = RecordDevelopParityEnhanceGpuBridge.isAvailable()
        if (accepted) {
            for ((uSharp, uDenoise) in parityEnhanceProbePoints(profile, detailProfile)) {
                permit.acquire()
                val gpu = try {
                    RecordDevelopParityEnhanceGpuBridge.enhanceLumaP010(
                        payload = raw.p010,
                        width = raw.width,
                        height = raw.height,
                        uSharpness = uSharp,
                        uDenoise = uDenoise
                    )
                } finally {
                    permit.release()
                }
                if (gpu == null || gpu.size != raw.p010.size) {
                    accepted = false
                    break
                }
                val cpu = Npv10ExportEnhancementPolicy.enhanceLiveCinemaLumaP010(
                    payload = raw.p010,
                    width = raw.width,
                    height = raw.height,
                    uSharpness = uSharp,
                    uDenoise = uDenoise
                )
                if (!lumaP010MatchesWithinTolerance(gpu, cpu, raw.width, raw.height)) {
                    accepted = false
                    break
                }
            }
        }
        synchronized(decision) {
            if (decision.get() < 0) decision.set(if (accepted) 1 else 0)
            return decision.get() == 1
        }
    }

    /**
     * Probe the clean and maximally noisy ends of the content-aware range used by this detail profile.
     */
    private fun parityEnhanceProbePoints(
        profile: Npv10ExportEnhancementProfile?,
        detailProfile: VideoDetailProfile
    ): List<Pair<Double, Double>> {
        val family = if (profile == CINEMA_NDR_LIVE_PARITY_ENHANCE_PROFILE) {
            GpuSignalFamily.HDR
        } else {
            GpuSignalFamily.SDR
        }
        val clean = ConversionFrameQualityPolicy.resolve(
            family,
            detailProfile,
            com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics.EMPTY
        )
        val noisy = ConversionFrameQualityPolicy.resolve(
            family,
            detailProfile,
            com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics(1f, 1f, 1f, 1f)
        )
        return listOf(
            clean.sharpen.toDouble() to clean.denoise.toDouble(),
            noisy.sharpen.toDouble() to noisy.denoise.toDouble()
        ).distinct()
    }

    /**
     * GPU-vs-CPU parity on the P010 LUMA plane only (the parity pass never touches chroma, and averaging over
     * the zero-diff chroma would falsely loosen the tolerance). Mean over the whole plane + an EXHAUSTIVE max
     * scan (NO subsampling) so a localized halo/ring at a hard edge cannot slip between sample points. Runs
     * once per clip per probe point, so the full scan cost is negligible.
     */
    private fun lumaP010MatchesWithinTolerance(
        gpu: ByteArray,
        cpu: ByteArray,
        width: Int,
        height: Int
    ): Boolean {
        val lumaSamples = width.toLong() * height.toLong()
        val lumaBytes = lumaSamples * Short.SIZE_BYTES
        if (gpu.size != cpu.size || lumaBytes <= 0L || gpu.size.toLong() < lumaBytes) return false
        var sumAbs = 0.0
        var maxAbs = 0
        var sample = 0L
        while (sample < lumaSamples) {
            val offset = (sample * Short.SIZE_BYTES).toInt()
            val d = abs(readP010Code(gpu, offset) - readP010Code(cpu, offset))
            sumAbs += d.toDouble()
            if (d > maxAbs) maxAbs = d
            sample += 1L
        }
        return (sumAbs / lumaSamples.toDouble()) <= RecordDevelopParityGatePolicy.meanTolerance &&
            maxAbs <= RecordDevelopParityGatePolicy.maxTolerance
    }

    private fun resolveEnhanceProcessingPath(
        gpuFrames: Long,
        cpuFallbackFrames: Long,
        enhanceEnabled: Boolean,
        gpuAvailable: Boolean
    ): String = when {
        !enhanceEnabled -> "P010_ENHANCE_DISABLED"
        gpuFrames > 0L && cpuFallbackFrames == 0L -> "GPU_P010_ENHANCE"
        gpuFrames > 0L -> "MIXED_GPU_CPU_P010_ENHANCE"
        gpuAvailable -> "CPU_P010_ENHANCE_GPU_REJECTED_OR_FAILED"
        else -> "CPU_P010_ENHANCE"
    }

    /**
     * Resolve the clip's GPU-vs-CPU decision EXACTLY ONCE across the worker threads (the first worker to
     * reach an unresolved gate runs the parity probe; the rest read the committed answer). Uniform per clip:
     * once decided, every frame follows the same path — the develop never mixes GPU and CPU algorithms.
     */
    private fun decideGpu(
        decision: java.util.concurrent.atomic.AtomicInteger,
        accelerator: RecordDevelopGpuAccelerator,
        source: ByteArray,
        w: Int,
        h: Int,
        plan: DevelopPlan,
        delivery: ColorDelivery,
        requireGpuDevelop: Boolean,
        // OPTIONAL packed-payload parity probe (Step 5): when the diagnostics flag is ON, the first-frame
        // gate also logs the GPU-vs-CPU PACKED byte divergence for this clip's output format. Diagnostic
        // only — it never changes the accept/reject verdict, so it can never introduce a CPU fallback.
        gpuPackedProvider: (() -> ByteArray?)? = null,
        cpuPackedFromCodes: ((ShortArray) -> ByteArray)? = null,
        packedCategory: String? = null
    ): Boolean {
        val current = decision.get()
        if (current >= 0) {
            check(current == 1 || !requireGpuDevelop) {
                "Record-develop GPU path was rejected by the strict parity gate"
            }
            return current == 1
        }
        synchronized(decision) {
            if (decision.get() < 0) {
                decision.set(
                    if (resolveGpuPath(
                            accelerator, source, w, h, plan, delivery,
                            gpuPackedProvider, cpuPackedFromCodes, packedCategory
                        )
                    ) 1 else 0
                )
            }
            val accepted = decision.get() == 1
            check(accepted || !requireGpuDevelop) {
                "Record-develop GPU path was rejected by the strict parity gate"
            }
            return accepted
        }
    }

    /**
     * Decide the develop path from a representative (already-enhanced) frame: GPU only if its develop is
     * parity-close to the CPU reference on this frame. A broken transform / wrong colour space blows past
     * tolerance → CPU for the whole clip. A null GPU result (dispatch failure) → CPU. Cheap: one extra CPU
     * develop of one frame, once per clip.
     */
    private fun resolveGpuPath(
        accelerator: RecordDevelopGpuAccelerator,
        source: ByteArray,
        w: Int,
        h: Int,
        plan: DevelopPlan,
        delivery: ColorDelivery,
        gpuPackedProvider: (() -> ByteArray?)? = null,
        cpuPackedFromCodes: ((ShortArray) -> ByteArray)? = null,
        packedCategory: String? = null
    ): Boolean {
        val gpu = accelerator.developFrameToCodes(source, w, h)
        if (gpu == null) {
            NeoLogger.w(
                TAG,
                "Record-develop GPU parity rejected: native dispatch returned null " +
                    "target=${plan.target} transfer=${plan.decodeTransfer} film=${plan.options.filmPrintStrength}"
            )
            return false
        }
        val cpu = developFrameToCodes(source, w, h, plan, delivery)
        val category = if (plan.target == DevelopTarget.NDR_PQ_BT2020) "NDR_PQ" else "SDR_COLOR"
        if (RecordDevelopParityDiagnosticsFlag.enabled && w > 0 && h > 0) {
            val probeX = minOf(264, w - 1)
            val probeY = minOf(80, h - 1)
            val probePixel = probeY * w + probeX
            val probeCode = probePixel * 3
            val sourceY = RecordDevelopP010ColorIngress.readLumaCode(source, w, h, probeX, probeY)
            val sourceCb = RecordDevelopP010ColorIngress.readChromaCode(source, w, h, probeX, probeY, 0)
            val sourceCr = RecordDevelopP010ColorIngress.readChromaCode(source, w, h, probeX, probeY, 1)
            NeoLogger.i(
                TAG,
                "parity[$category] probe=($probeX,$probeY) src=($sourceY,$sourceCb,$sourceCr) " +
                    "gpu=(${gpu[probeCode].toInt() and 0xFFFF},${gpu[probeCode + 1].toInt() and 0xFFFF}," +
                    "${gpu[probeCode + 2].toInt() and 0xFFFF}) " +
                    "cpu=(${cpu[probeCode].toInt() and 0xFFFF},${cpu[probeCode + 1].toInt() and 0xFFFF}," +
                    "${cpu[probeCode + 2].toInt() and 0xFFFF}) " +
                    "snce=${plan.options.snceAppearanceStrength} film=${plan.options.filmPrintStrength} " +
                    "decode=${plan.decodeSpace}/${plan.decodeTransfer} widen=${delivery.widenToRec2020}"
            )
        }
        val accepted = developsMatchWithinTolerance(gpu, cpu, category, w)
        // Step 5: packed-payload parity diagnostic (harness only; never affects the verdict).
        if (RecordDevelopParityDiagnosticsFlag.enabled &&
            gpuPackedProvider != null && cpuPackedFromCodes != null && packedCategory != null
        ) {
            logPackedPayloadParity(gpuPackedProvider, cpuPackedFromCodes, cpu, packedCategory)
        }
        return accepted
    }

    /**
     * Log the GPU-vs-CPU PACKED-payload byte divergence for the first frame (Step 5). r210 tracks the
     * per-code residual; v210 / P010-4:2:0 surface packer-level trunc/round/subsample/matrix drift that the
     * pre-pack code compare cannot represent. Diagnostic only — wrapped so a probe failure never affects the
     * develop or the GPU verdict.
     */
    private fun logPackedPayloadParity(
        gpuPackedProvider: () -> ByteArray?,
        cpuPackedFromCodes: (ShortArray) -> ByteArray,
        cpuCodes: ShortArray,
        category: String
    ) {
        val gpuPacked = runCatching { gpuPackedProvider() }.getOrNull()
        if (gpuPacked == null) {
            NeoLogger.w(TAG, "parity[$category] packed: GPU packed payload unavailable (CPU pack path)")
            return
        }
        val cpuPacked = runCatching { cpuPackedFromCodes(cpuCodes) }.getOrNull() ?: return
        val stats = RecordDevelopParityDiagnostics.comparePackedBytes(gpuPacked, cpuPacked, category)
        if (stats == null) {
            NeoLogger.w(
                TAG,
                "parity[$category] packed size mismatch gpu=${gpuPacked.size} cpu=${cpuPacked.size}"
            )
            return
        }
        NeoLogger.i(TAG, RecordDevelopParityDiagnostics.formatPacked(stats))
    }

    /**
     * GPU-vs-CPU develop parity on the interleaved 10-bit codes (0..1023). The GPU reproduces the SAME
     * develop math, so only float32-vs-float64 rounding should differ — accept only when meanAbs, maxAbs AND
     * p99.9 are all within the live-tunable [RecordDevelopParityGatePolicy] (a wrong transform blows the mean
     * far past tolerance → CPU fallback; a structured regional error is caught by p99.9). The full residual
     * distribution is logged on rejection, and on every probe when [RecordDevelopParityDiagnosticsFlag] is ON
     * (the on-device A/B harness). Comparison only — never alters produced pixels.
     */
    private fun developsMatchWithinTolerance(
        gpu: ShortArray,
        cpu: ShortArray,
        category: String,
        width: Int
    ): Boolean {
        val stats = RecordDevelopParityDiagnostics.compareCodes(gpu, cpu, category, width)
        if (stats == null) {
            NeoLogger.w(TAG, "Record-develop GPU parity rejected: size mismatch gpu=${gpu.size} cpu=${cpu.size}")
            return false
        }
        val meanTol = RecordDevelopParityGatePolicy.meanTolerance
        val maxTol = RecordDevelopParityGatePolicy.maxTolerance
        val p999Tol = RecordDevelopParityGatePolicy.p999Tolerance
        val accepted = stats.meanAbs <= meanTol && stats.maxAbs <= maxTol && stats.p999 <= p999Tol
        if (!accepted) {
            NeoLogger.w(
                TAG,
                "Record-develop GPU parity rejected: ${RecordDevelopParityDiagnostics.format(stats)} " +
                    "meanTol=$meanTol maxTol=$maxTol p99.9Tol=$p999Tol"
            )
        } else if (RecordDevelopParityDiagnosticsFlag.enabled) {
            NeoLogger.i(
                TAG,
                "Record-develop GPU parity accepted: ${RecordDevelopParityDiagnostics.format(stats)} " +
                    "meanTol=$meanTol maxTol=$maxTol p99.9Tol=$p999Tol"
            )
        }
        return accepted
    }

    /** Decode a P010 payload to its interleaved 10-bit code stream (value >> 6), for the parity gate. */
    private fun p010ToCodes(p010: ByteArray): ShortArray {
        val count = p010.size / 2
        val codes = ShortArray(count)
        var byteIndex = 0
        var i = 0
        while (i < count) {
            val value = (p010[byteIndex].toInt() and 0xFF) or ((p010[byteIndex + 1].toInt() and 0xFF) shl 8)
            codes[i] = ((value ushr 6) and 0x3FF).toShort()
            byteIndex += 2
            i += 1
        }
        return codes
    }

    /**
     * GPU-vs-CPU parity for the chroma enhance lane on the full P010 code stream, reusing the develop gate
     * ([RecordDevelopParityGatePolicy] mean/max/p99.9). The GPU lane reproduces the SAME chroma math so only
     * float32-vs-float64 rounding should differ; a divergence past tolerance ⇒ CPU fallback (no downgrade).
     */
    private fun enhanceLanePayloadsMatch(
        gpu: ByteArray,
        cpu: ByteArray,
        width: Int,
        profile: Npv10ExportEnhancementProfile
    ): Boolean {
        val category = if (profile == CINEMA_NDR_LIVE_PARITY_ENHANCE_PROFILE) "ENH_NDR" else "ENH_SDR"
        return developsMatchWithinTolerance(p010ToCodes(gpu), p010ToCodes(cpu), category, width)
    }

    private fun packDevelopedCodes(
        codes: ShortArray,
        width: Int,
        height: Int,
        outputFormat: RecordDevelopOutputFormat
    ): ByteArray = when (outputFormat) {
        RecordDevelopOutputFormat.R210_444 -> RawTenBitPacker.packR210(codes, width, height)
        RecordDevelopOutputFormat.V210_422 -> RawTenBitPacker.packV210(codes, width, height)
    }

    /**
     * CPU develop + pack without materialising a full-frame RGB-code [ShortArray]. This preserves the exact
     * row math of [developFrameToCodes] and [RawTenBitPacker], but keeps peak 4K r210 memory roughly one
     * full RGB-code frame lower.
     */
    private fun developFrameToPackedPayload(
        p010: ByteArray,
        w: Int,
        h: Int,
        plan: DevelopPlan,
        delivery: ColorDelivery,
        outputFormat: RecordDevelopOutputFormat,
        rowExecutor: ExecutorService? = null
    ): ByteArray {
        val packerFormat = outputFormat.packerFormat
        val stride = RawTenBitPacker.rowBytes(packerFormat, w)
        val payload = ByteArray(stride * h)
        if (rowExecutor != null && h >= ROW_PARALLEL_MIN_ROWS) {
            val workers = resolveTaskCount(h, ROW_PARALLEL_CHUNK_ROWS)
            val futures = rowExecutor.invokeAll(
                (0 until workers).map { worker ->
                    Callable {
                        val startRow = worker * h / workers
                        val endRow = (worker + 1) * h / workers
                        developRowsToPackedPayload(
                            p010 = p010,
                            w = w,
                            h = h,
                            startRow = startRow,
                            endRow = endRow,
                            plan = plan,
                            delivery = delivery,
                            outputFormat = outputFormat,
                            payload = payload,
                            stride = stride
                        )
                    }
                }
            )
            futures.forEach { it.get() }
            return payload
        }
        developRowsToPackedPayload(
            p010 = p010,
            w = w,
            h = h,
            startRow = 0,
            endRow = h,
            plan = plan,
            delivery = delivery,
            outputFormat = outputFormat,
            payload = payload,
            stride = stride
        )
        return payload
    }

    private fun developRowsToPackedPayload(
        p010: ByteArray,
        w: Int,
        h: Int,
        startRow: Int,
        endRow: Int,
        plan: DevelopPlan,
        delivery: ColorDelivery,
        outputFormat: RecordDevelopOutputFormat,
        payload: ByteArray,
        stride: Int
    ) {
        val rowLinear = FloatArray(w * 3)
        val rowCodes = if (outputFormat == RecordDevelopOutputFormat.R210_444) ShortArray(w * 3) else null
        val rowContinuous = if (outputFormat == RecordDevelopOutputFormat.V210_422) DoubleArray(w * 3) else null
        val luma = if (outputFormat == RecordDevelopOutputFormat.V210_422) IntArray(w) else null
        val cb = if (outputFormat == RecordDevelopOutputFormat.V210_422) IntArray(w) else null
        val cr = if (outputFormat == RecordDevelopOutputFormat.V210_422) IntArray(w) else null
        val widened = FloatArray(3)
        val encoded = IntArray(3)
        val continuousEncoder = if (outputFormat == RecordDevelopOutputFormat.V210_422) {
            ContinuousOutputEncoder(
                decodeTransfer = plan.decodeTransfer,
                outputTransfer = delivery.outputTransfer,
                diffuseWhiteNits = plan.diffuseWhiteNits,
                genuineNdr = delivery.ndrGenuineHdr
            )
        } else {
            null
        }

        for (y in startRow until endRow) {
            var i = 0
            for (x in 0 until w) {
                val yc = RecordDevelopP010ColorIngress.readLumaCode(p010, w, h, x, y)
                val cbCode = RecordDevelopP010ColorIngress.readChromaCode(p010, w, h, x, y, 0)
                val crCode = RecordDevelopP010ColorIngress.readChromaCode(p010, w, h, x, y, 1)
                RecordDevelopP010ColorIngress.decodeToLinearRgbInto(
                    yc,
                    cbCode,
                    crCode,
                    plan.decodeSpace,
                    plan.decodeTransfer,
                    rowLinear,
                    i
                )
                i += 3
            }

            val developed = plan.pipeline.develop(rowLinear)
            var j = 0
            for (x in 0 until w) {
                var r = developed[j]
                var g = developed[j + 1]
                var b = developed[j + 2]
                if (delivery.widenToRec2020) {
                    RecordDevelopP010ColorIngress.rec709LinearToRec2020Into(r, g, b, widened)
                    r = widened[0]
                    g = widened[1]
                    b = widened[2]
                }
                if (outputFormat == RecordDevelopOutputFormat.V210_422) {
                    requireNotNull(continuousEncoder).encodeInto(
                        r,
                        g,
                        b,
                        requireNotNull(rowContinuous),
                        j
                    )
                } else {
                    val enc = if (delivery.ndrGenuineHdr) {
                        RecordDevelopP010ColorIngress.encodeNdrNivukxPqCodes(
                            r,
                            g,
                            b,
                            plan.decodeTransfer,
                            warmSkinRedTame = NdrWarmSkinRedTameFlag.effectiveStrength
                        )
                    } else {
                        RecordDevelopP010ColorIngress.encodeLinearRgbInto(
                            r,
                            g,
                            b,
                            delivery.outputTransfer,
                            plan.diffuseWhiteNits,
                            encoded
                        )
                    }
                    val r210Codes = requireNotNull(rowCodes)
                    r210Codes[j] = enc[0].toShort()
                    r210Codes[j + 1] = enc[1].toShort()
                    r210Codes[j + 2] = enc[2].toShort()
                }
                j += 3
            }

            when (outputFormat) {
                RecordDevelopOutputFormat.R210_444 -> packR210Row(
                    requireNotNull(rowCodes),
                    payload,
                    y * stride,
                    w
                )
                RecordDevelopOutputFormat.V210_422 -> packContinuousV210Row(
                    rowSignals = requireNotNull(rowContinuous),
                    rgbOffset = 0,
                    output = payload,
                    rowOffset = y * stride,
                    width = w,
                    luma = luma ?: error("v210 luma buffer missing"),
                    cb = cb ?: error("v210 cb buffer missing"),
                    cr = cr ?: error("v210 cr buffer missing")
                )
            }
        }
    }

    private fun packR210Row(rowCodes: ShortArray, output: ByteArray, rowOffset: Int, width: Int) {
        var o = rowOffset
        for (x in 0 until width) {
            val i = x * 3
            val r = clamp10(rowCodes[i].toInt())
            val g = clamp10(rowCodes[i + 1].toInt())
            val b = clamp10(rowCodes[i + 2].toInt())
            val word = (r shl 20) or (g shl 10) or b
            output[o] = (word ushr 24).toByte()
            output[o + 1] = (word ushr 16).toByte()
            output[o + 2] = (word ushr 8).toByte()
            output[o + 3] = word.toByte()
            o += 4
        }
    }

    private fun packContinuousV210Row(
        rowSignals: DoubleArray,
        rgbOffset: Int,
        output: ByteArray,
        rowOffset: Int,
        width: Int,
        luma: IntArray,
        cb: IntArray,
        cr: IntArray
    ) {
        for (x in 0 until width) {
            val i = rgbOffset + x * 3
            val rf = rowSignals[i]
            val gf = rowSignals[i + 1]
            val bf = rowSignals[i + 2]
            val yp = V210_KR * rf + V210_KG * gf + V210_KB * bf
            luma[x] = quantizeLimitedLuma(yp)
        }
        for (x in 0 until width step 2) {
            val sampleCount = if (x + 1 < width) 2 else 1
            var cbSum = 0.0
            var crSum = 0.0
            for (sample in 0 until sampleCount) {
                val i = rgbOffset + (x + sample) * 3
                val rf = rowSignals[i]
                val gf = rowSignals[i + 1]
                val bf = rowSignals[i + 2]
                val yp = V210_KR * rf + V210_KG * gf + V210_KB * bf
                cbSum += (bf - yp) / (2.0 * (1.0 - V210_KB))
                crSum += (rf - yp) / (2.0 * (1.0 - V210_KR))
            }
            cb[x] = quantizeLimitedChroma(cbSum / sampleCount)
            cr[x] = quantizeLimitedChroma(crSum / sampleCount)
        }
        var o = rowOffset
        var x = 0
        while (x < width) {
            val y0 = luma[x]
            val y1 = luma.getOrElse(x + 1) { y0 }
            val y2 = luma.getOrElse(x + 2) { y1 }
            val y3 = luma.getOrElse(x + 3) { y2 }
            val y4 = luma.getOrElse(x + 4) { y3 }
            val y5 = luma.getOrElse(x + 5) { y4 }
            val cb0 = cb[x]
            val cr0 = cr[x]
            val cb2 = cb.getOrElse(x + 2) { cb0 }
            val cr2 = cr.getOrElse(x + 2) { cr0 }
            val cb4 = cb.getOrElse(x + 4) { cb2 }
            val cr4 = cr.getOrElse(x + 4) { cr2 }
            writeWordLe(output, o, (cr0 shl 20) or (y0 shl 10) or cb0)
            o += 4
            writeWordLe(output, o, (y2 shl 20) or (cb2 shl 10) or y1)
            o += 4
            writeWordLe(output, o, (cb4 shl 20) or (y3 shl 10) or cr2)
            o += 4
            writeWordLe(output, o, (y5 shl 20) or (cr4 shl 10) or y4)
            o += 4
            x += 6
        }
    }

    /** Deterministic unit-test seam over the production continuous v210 row packer. */
    internal fun packContinuousV210ForValidation(
        transferEncodedRgb: DoubleArray,
        width: Int,
        height: Int
    ): ByteArray {
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        require(transferEncodedRgb.size >= width * height * 3) {
            "continuous RGB buffer too small for ${width}x$height"
        }
        val stride = RawTenBitPacker.rowBytes(RawTenBitPacker.Format.V210_422, width)
        val output = ByteArray(stride * height)
        val luma = IntArray(width)
        val cb = IntArray(width)
        val cr = IntArray(width)
        for (y in 0 until height) {
            packContinuousV210Row(
                rowSignals = transferEncodedRgb,
                rgbOffset = y * width * 3,
                output = output,
                rowOffset = y * stride,
                width = width,
                luma = luma,
                cb = cb,
                cr = cr
            )
        }
        return output
    }

    private data class YcbcrMatrix(val kr: Double, val kb: Double) {
        val kg: Double = 1.0 - kr - kb
    }

    private fun matrixFor(delivery: ColorDelivery): YcbcrMatrix =
        if (delivery.colorConfig.matrixCoefficients == QuickTimeColorAtomPolicy.MATRIX_BT709) {
            YcbcrMatrix(BT709_KR, BT709_KB)
        } else {
            YcbcrMatrix(V210_KR, V210_KB)
        }

    private fun developFrameToP010420Payload(
        p010: ByteArray,
        w: Int,
        h: Int,
        plan: DevelopPlan,
        delivery: ColorDelivery,
        rowExecutor: ExecutorService? = null
    ): ByteArray {
        val payload = ByteArray(Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(w, h).toInt())
        if (rowExecutor != null && h >= ROW_PARALLEL_MIN_ROWS) {
            val pairCount = (h + 1) / 2
            val workers = resolveTaskCount(pairCount, max(1, ROW_PARALLEL_CHUNK_ROWS / 2))
            val futures = rowExecutor.invokeAll(
                (0 until workers).map { worker ->
                    Callable {
                        val startPair = worker * pairCount / workers
                        val endPair = (worker + 1) * pairCount / workers
                        developRowPairsToP010420Payload(
                            p010 = p010,
                            w = w,
                            h = h,
                            startPair = startPair,
                            endPair = endPair,
                            plan = plan,
                            delivery = delivery,
                            payload = payload
                        )
                    }
                }
            )
            futures.forEach { it.get() }
            return payload
        }
        developRowPairsToP010420Payload(
            p010 = p010,
            w = w,
            h = h,
            startPair = 0,
            endPair = (h + 1) / 2,
            plan = plan,
            delivery = delivery,
            payload = payload
        )
        return payload
    }

    private fun developRowPairsToP010420Payload(
        p010: ByteArray,
        w: Int,
        h: Int,
        startPair: Int,
        endPair: Int,
        plan: DevelopPlan,
        delivery: ColorDelivery,
        payload: ByteArray
    ) {
        val row0Linear = FloatArray(w * 3)
        val row1Linear = FloatArray(w * 3)
        val row0Signals = DoubleArray(w * 3)
        val row1Signals = DoubleArray(w * 3)
        val matrix = matrixFor(delivery)
        val yPlaneBytes = w * h * Short.SIZE_BYTES
        val chromaWidth = (w + 1) / 2
        val widened = FloatArray(3)
        val continuousEncoder = ContinuousOutputEncoder(
            decodeTransfer = plan.decodeTransfer,
            outputTransfer = delivery.outputTransfer,
            diffuseWhiteNits = plan.diffuseWhiteNits,
            genuineNdr = delivery.ndrGenuineHdr
        )
        for (pair in startPair until endPair) {
            val y0 = pair * 2
            if (y0 >= h) break
            val y1 = (y0 + 1).coerceAtMost(h - 1)
            fillDevelopedRgbRowSignals(
                p010 = p010,
                w = w,
                h = h,
                y = y0,
                plan = plan,
                delivery = delivery,
                rowLinear = row0Linear,
                rowSignals = row0Signals,
                widened = widened,
                encoder = continuousEncoder
            )
            if (y1 != y0) {
                fillDevelopedRgbRowSignals(
                    p010 = p010,
                    w = w,
                    h = h,
                    y = y1,
                    plan = plan,
                    delivery = delivery,
                    rowLinear = row1Linear,
                    rowSignals = row1Signals,
                    widened = widened,
                    encoder = continuousEncoder
                )
            }
            writeContinuousP010RowPair(
                row0 = row0Signals,
                row0Offset = 0,
                row1 = row1Signals,
                row1Offset = 0,
                rowCount = if (y1 == y0) 1 else 2,
                payload = payload,
                width = w,
                y0 = y0,
                y1 = y1,
                chromaRow = pair,
                yPlaneBytes = yPlaneBytes,
                chromaWidth = chromaWidth,
                matrix = matrix
            )
        }
    }

    private fun fillDevelopedRgbRowSignals(
        p010: ByteArray,
        w: Int,
        h: Int,
        y: Int,
        plan: DevelopPlan,
        delivery: ColorDelivery,
        rowLinear: FloatArray,
        rowSignals: DoubleArray,
        widened: FloatArray,
        encoder: ContinuousOutputEncoder
    ) {
        var i = 0
        for (x in 0 until w) {
            val yc = RecordDevelopP010ColorIngress.readLumaCode(p010, w, h, x, y)
            val cbCode = RecordDevelopP010ColorIngress.readChromaCode(p010, w, h, x, y, 0)
            val crCode = RecordDevelopP010ColorIngress.readChromaCode(p010, w, h, x, y, 1)
            RecordDevelopP010ColorIngress.decodeToLinearRgbInto(
                yc,
                cbCode,
                crCode,
                plan.decodeSpace,
                plan.decodeTransfer,
                rowLinear,
                i
            )
            i += 3
        }
        val developed = plan.pipeline.develop(rowLinear)
        var j = 0
        for (x in 0 until w) {
            var r = developed[j]
            var g = developed[j + 1]
            var b = developed[j + 2]
            if (delivery.widenToRec2020) {
                RecordDevelopP010ColorIngress.rec709LinearToRec2020Into(r, g, b, widened)
                r = widened[0]
                g = widened[1]
                b = widened[2]
            }
            encoder.encodeInto(r, g, b, rowSignals, j)
            j += 3
        }
    }

    private fun writeContinuousP010RowPair(
        row0: DoubleArray,
        row0Offset: Int,
        row1: DoubleArray,
        row1Offset: Int,
        rowCount: Int,
        payload: ByteArray,
        width: Int,
        y0: Int,
        y1: Int,
        chromaRow: Int,
        yPlaneBytes: Int,
        chromaWidth: Int,
        matrix: YcbcrMatrix
    ) {
        writeContinuousLumaRow(row0, row0Offset, payload, y0 * width * Short.SIZE_BYTES, width, matrix)
        if (rowCount == 2) {
            writeContinuousLumaRow(row1, row1Offset, payload, y1 * width * Short.SIZE_BYTES, width, matrix)
        }
        var x = 0
        while (x < width) {
            val columnCount = if (x + 1 < width) 2 else 1
            var cbSum = 0.0
            var crSum = 0.0
            var sampleCount = 0
            for (rowIndex in 0 until rowCount) {
                val row = if (rowIndex == 0) row0 else row1
                val base = if (rowIndex == 0) row0Offset else row1Offset
                for (column in 0 until columnCount) {
                    val i = base + (x + column) * 3
                    val r = row[i]
                    val g = row[i + 1]
                    val b = row[i + 2]
                    val yp = matrix.kr * r + matrix.kg * g + matrix.kb * b
                    cbSum += (b - yp) / (2.0 * (1.0 - matrix.kb))
                    crSum += (r - yp) / (2.0 * (1.0 - matrix.kr))
                    sampleCount++
                }
            }
            val uvOffset = yPlaneBytes +
                (chromaRow * chromaWidth + (x / 2)) * 2 * Short.SIZE_BYTES
            writeP010Word(payload, uvOffset, quantizeLimitedChroma(cbSum / sampleCount))
            writeP010Word(
                payload,
                uvOffset + Short.SIZE_BYTES,
                quantizeLimitedChroma(crSum / sampleCount)
            )
            x += 2
        }
    }

    private fun writeContinuousLumaRow(
        rowSignals: DoubleArray,
        rgbOffset: Int,
        payload: ByteArray,
        rowByteOffset: Int,
        width: Int,
        matrix: YcbcrMatrix
    ) {
        var outputOffset = rowByteOffset
        for (x in 0 until width) {
            val i = rgbOffset + x * 3
            val yp = matrix.kr * rowSignals[i] +
                matrix.kg * rowSignals[i + 1] +
                matrix.kb * rowSignals[i + 2]
            writeP010Word(payload, outputOffset, quantizeLimitedLuma(yp))
            outputOffset += Short.SIZE_BYTES
        }
    }

    /** Deterministic unit-test seam over the production continuous P010 row-pair packer. */
    internal fun packContinuousP010ForValidation(
        transferEncodedRgb: DoubleArray,
        width: Int,
        height: Int,
        useBt709Matrix: Boolean
    ): ByteArray {
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        require(transferEncodedRgb.size >= width * height * 3) {
            "continuous RGB buffer too small for ${width}x$height"
        }
        val payload = ByteArray(Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(width, height).toInt())
        val matrix = if (useBt709Matrix) {
            YcbcrMatrix(BT709_KR, BT709_KB)
        } else {
            YcbcrMatrix(V210_KR, V210_KB)
        }
        val yPlaneBytes = width * height * Short.SIZE_BYTES
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        for (pair in 0 until chromaHeight) {
            val y0 = pair * 2
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            writeContinuousP010RowPair(
                row0 = transferEncodedRgb,
                row0Offset = y0 * width * 3,
                row1 = transferEncodedRgb,
                row1Offset = y1 * width * 3,
                rowCount = if (y1 == y0) 1 else 2,
                payload = payload,
                width = width,
                y0 = y0,
                y1 = y1,
                chromaRow = pair,
                yPlaneBytes = yPlaneBytes,
                chromaWidth = chromaWidth,
                matrix = matrix
            )
        }
        return payload
    }

    private fun developedCodesToP010420Payload(
        codes: ShortArray,
        w: Int,
        h: Int,
        delivery: ColorDelivery
    ): ByteArray {
        require(codes.size >= w * h * 3) {
            "Developed RGB code buffer too small size=${codes.size} expected=${w * h * 3}"
        }
        val payload = ByteArray(Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(w, h).toInt())
        val matrix = matrixFor(delivery)
        val yPlaneBytes = w * h * Short.SIZE_BYTES
        val chromaWidth = (w + 1) / 2
        for (y in 0 until h) {
            val rowOffset = y * w * 3
            for (x in 0 until w) {
                writeP010Word(
                    payload,
                    (y * w + x) * Short.SIZE_BYTES,
                    lumaFromRgb(codes, rowOffset + x * 3, matrix)
                )
            }
        }
        var y = 0
        while (y < h) {
            val y1 = (y + 1).coerceAtMost(h - 1)
            var x = 0
            while (x < w) {
                val x1 = (x + 1).coerceAtMost(w - 1)
                val c00 = chromaFromRgb(codes, y * w + x, matrix)
                val c01 = chromaFromRgb(codes, y * w + x1, matrix)
                val c10 = chromaFromRgb(codes, y1 * w + x, matrix)
                val c11 = chromaFromRgb(codes, y1 * w + x1, matrix)
                val cb = ((c00.first + c01.first + c10.first + c11.first) / 4.0).roundToInt()
                val cr = ((c00.second + c01.second + c10.second + c11.second) / 4.0).roundToInt()
                val uvOffset = yPlaneBytes + ((y / 2) * chromaWidth + (x / 2)) * 2 * Short.SIZE_BYTES
                writeP010Word(payload, uvOffset, cb)
                writeP010Word(payload, uvOffset + Short.SIZE_BYTES, cr)
                x += 2
            }
            y += 2
        }
        return payload
    }

    private fun writeLumaRowP010(
        rowCodes: ShortArray,
        payload: ByteArray,
        rowByteOffset: Int,
        width: Int,
        matrix: YcbcrMatrix
    ) {
        var offset = rowByteOffset
        for (x in 0 until width) {
            writeP010Word(payload, offset, lumaFromRgb(rowCodes, x * 3, matrix))
            offset += Short.SIZE_BYTES
        }
    }

    private fun lumaFromRgb(codes: ShortArray, rgbOffset: Int, matrix: YcbcrMatrix): Int {
        val rf = clamp10(codes[rgbOffset].toInt()) / V210_MAX10.toDouble()
        val gf = clamp10(codes[rgbOffset + 1].toInt()) / V210_MAX10.toDouble()
        val bf = clamp10(codes[rgbOffset + 2].toInt()) / V210_MAX10.toDouble()
        val yp = matrix.kr * rf + matrix.kg * gf + matrix.kb * bf
        return clamp10((yp * (V210_LUMA_MAX - V210_LUMA_MIN) + V210_LUMA_MIN).roundToInt())
    }

    private fun chromaFromRgb(codes: ShortArray, pixelIndex: Int, matrix: YcbcrMatrix): Pair<Int, Int> {
        val i = pixelIndex * 3
        val rf = clamp10(codes[i].toInt()) / V210_MAX10.toDouble()
        val gf = clamp10(codes[i + 1].toInt()) / V210_MAX10.toDouble()
        val bf = clamp10(codes[i + 2].toInt()) / V210_MAX10.toDouble()
        val yp = matrix.kr * rf + matrix.kg * gf + matrix.kb * bf
        val cbv = (bf - yp) / (2.0 * (1.0 - matrix.kb))
        val crv = (rf - yp) / (2.0 * (1.0 - matrix.kr))
        val cb = clamp10((cbv * (V210_CHROMA_MAX - V210_CHROMA_MIN) + V210_CHROMA_ZERO).roundToInt())
        val cr = clamp10((crv * (V210_CHROMA_MAX - V210_CHROMA_MIN) + V210_CHROMA_ZERO).roundToInt())
        return cb to cr
    }

    private fun writeP010Word(output: ByteArray, offset: Int, code: Int) {
        val word = clamp10(code) shl P010_VALID_BITS_SHIFT
        output[offset] = word.toByte()
        output[offset + 1] = (word ushr 8).toByte()
    }

    /**
     * Row-band CPU parallelism for a SINGLE frame's develop + enhance. It engages ONLY in the single
     * frame-worker regime ([workerCount] == 1) — i.e. the flagship full-sensor develop where the memory
     * budget floors the converter to one frame in flight. When multiple frame-workers run (smaller
     * resolutions) the frames already saturate the cores, so per-frame row parallelism stays 1 to avoid
     * oversubscription. Gating on the ACTUAL worker count (not the read-ahead window) fixes the mid-heap
     * trap where `workerCount` floored to 1 yet the reorder window was > 1, leaving the develop fully
     * single-threaded. Row parallelism never changes output bytes (disjoint rows), so this only affects
     * wall-clock.
     */
    private fun resolveRowParallelism(workerCount: Int, width: Int, height: Int): Int {
        if (workerCount > 1) return 1
        val pixels = width.toLong() * height.toLong()
        if (pixels < ROW_PARALLEL_MIN_PIXELS) return 1
        return Runtime.getRuntime().availableProcessors()
            .coerceAtLeast(1)
            .coerceAtMost(ROW_PARALLEL_MAX_WORKERS)
    }

    private fun resolveTaskCount(rows: Int, rowsPerTask: Int): Int =
        max(1, (rows + rowsPerTask - 1) / rowsPerTask)

    /**
     * Conservative ART-heap peak for one uncompressed develop frame, excluding the temporal predecessor
     * that the scheduler reserves separately.
     *
     * This is deliberately phase-aware. The live-parity enhancement peak owns the source, luma result,
     * colour-enhance result, immutable detail baseline, and pass snapshot at the same time (five P010
     * planes), while the later pack phase owns the source, enhanced result, retained scheduler snapshot,
     * packed sample, and row workers. Adding every phase together would reject frames that safely fit;
     * counting only the pack phase would under-report the real enhancement peak.
     */
    internal fun estimateResidentBytesPerFrame(
        width: Int,
        height: Int,
        enhanceEnabled: Boolean,
        outputFormat: RecordDevelopOutputFormat,
        gpuDevelopAvailable: Boolean,
        packedParityDiagnosticsEnabled: Boolean = RecordDevelopParityDiagnosticsFlag.enabled
    ): Long {
        val pixels = width.toLong() * height.toLong()
        val p010Bytes = pixels * 3L
        val packedBytes = RawTenBitPacker.payloadSize(outputFormat.packerFormat, width, height).toLong()
        val outputRowScratchBytes = if (outputFormat == RecordDevelopOutputFormat.V210_422) {
            3L * Double.SIZE_BYTES + 3L * Int.SIZE_BYTES
        } else {
            3L * Short.SIZE_BYTES
        }
        // Each active row task owns both the decoded and developed RGB FloatArray rows. At full resolution
        // the single frame worker fans out over at most ROW_PARALLEL_MAX_WORKERS tasks.
        val rowScratchCopies = conservativeRowScratchCopies(width, height)
        val rowScratchBytes = width.toLong() *
            (6L * Float.SIZE_BYTES + outputRowScratchBytes) * rowScratchCopies

        val enhancementPeakBytes = if (enhanceEnabled) {
            // source + live-parity luma + colour output + immutable baseline + reusable pass snapshot
            p010Bytes * 5L
        } else {
            p010Bytes
        }
        val retainedForDevelopBytes = if (enhanceEnabled) {
            // source + enhanced output + reusable pass snapshot
            p010Bytes * 3L
        } else {
            p010Bytes
        }
        val packingPeakBytes = retainedForDevelopBytes + packedBytes + rowScratchBytes

        // The first-frame gate simultaneously owns BOTH full RGB10 planes (GPU candidate + CPU reference).
        // Diagnostics additionally retain both packed comparisons; an interface implementation using the
        // default packer may also materialise one more RGB10 plane while producing its packed candidate.
        val gpuParityPeakBytes = if (gpuDevelopAvailable) {
            val twoRgb10Planes = pixels * 3L * Short.SIZE_BYTES * 2L
            val diagnosticBytes = if (packedParityDiagnosticsEnabled) {
                packedBytes * 2L + pixels * 3L * Short.SIZE_BYTES
            } else {
                0L
            }
            retainedForDevelopBytes + twoRgb10Planes + diagnosticBytes + rowScratchBytes
        } else {
            0L
        }
        return maxOf(enhancementPeakBytes, packingPeakBytes, gpuParityPeakBytes)
    }

    /** Phase-aware sibling of [estimateResidentBytesPerFrame] for Main10 P010 encoder input. */
    internal fun estimateResidentBytesPerFrameForHevc(
        width: Int,
        height: Int,
        enhanceEnabled: Boolean,
        gpuDevelopAvailable: Boolean,
        packedParityDiagnosticsEnabled: Boolean = RecordDevelopParityDiagnosticsFlag.enabled
    ): Long {
        val pixels = width.toLong() * height.toLong()
        val p010Bytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(width, height)
        // Each row-pair task owns two decoded rows, two developed rows, and two continuous-signal rows.
        val rowScratchBytes = width.toLong() *
            (12L * Float.SIZE_BYTES + 6L * Double.SIZE_BYTES) *
            conservativeRowScratchCopies(width, height)
        val enhancementPeakBytes = if (enhanceEnabled) p010Bytes * 5L else p010Bytes
        val retainedForDevelopBytes = if (enhanceEnabled) p010Bytes * 3L else p010Bytes
        val packingPeakBytes = retainedForDevelopBytes + p010Bytes + rowScratchBytes
        val gpuParityPeakBytes = if (gpuDevelopAvailable) {
            val twoRgb10Planes = pixels * 3L * Short.SIZE_BYTES * 2L
            val diagnosticBytes = if (packedParityDiagnosticsEnabled) {
                // Two P010 comparison payloads plus a possible RGB10 plane from the interface default packer.
                p010Bytes * 2L + pixels * 3L * Short.SIZE_BYTES
            } else {
                0L
            }
            retainedForDevelopBytes + twoRgb10Planes + diagnosticBytes + rowScratchBytes
        } else {
            0L
        }
        return maxOf(enhancementPeakBytes, packingPeakBytes, gpuParityPeakBytes)
    }

    private fun conservativeRowScratchCopies(width: Int, height: Int): Long {
        val pixels = width.toLong() * height.toLong()
        return if (pixels >= ROW_PARALLEL_MIN_PIXELS && height >= ROW_PARALLEL_MIN_ROWS) {
            ROW_PARALLEL_MAX_WORKERS.toLong()
        } else {
            1L
        }
    }

    private fun writeWordLe(output: ByteArray, offset: Int, word: Int) {
        output[offset] = word.toByte()
        output[offset + 1] = (word ushr 8).toByte()
        output[offset + 2] = (word ushr 16).toByte()
        output[offset + 3] = (word ushr 24).toByte()
    }

    private fun quantizeLimitedLuma(signal: Double): Int = clamp10(
        (signal * (V210_LUMA_MAX - V210_LUMA_MIN) + V210_LUMA_MIN).roundToInt()
    )

    private fun quantizeLimitedChroma(signal: Double): Int = clamp10(
        (signal * (V210_CHROMA_MAX - V210_CHROMA_MIN) + V210_CHROMA_ZERO).roundToInt()
    )

    private fun clamp10(value: Int): Int = value.coerceIn(0, V210_MAX10)

    private fun readP010Code(payload: ByteArray, offset: Int): Int {
        if (offset + 1 >= payload.size) return 0
        val word = (payload[offset].toInt() and 0xFF) or ((payload[offset + 1].toInt() and 0xFF) shl 8)
        return (word ushr P010_VALID_BITS_SHIFT).coerceIn(0, V210_MAX10)
    }

    /**
     * Develops ONE decoded P010 frame ([p010] — already denoise/detail-enhanced when the Phase-4 stage is on)
     * into an interleaved 10-bit RGB code array (row-major, R fastest), ready for [RawTenBitPacker].
     * Row-batched through [VideoDevelopColorPipeline.develop] so only a single row of floats is resident at a
     * time (never a full-frame float image). Pure — safe to call concurrently across workers on different
     * frames.
     */
    private fun developFrameToCodes(
        p010: ByteArray,
        w: Int,
        h: Int,
        plan: DevelopPlan,
        delivery: ColorDelivery
    ): ShortArray {
        val codes = ShortArray(w * h * 3)
        val rowLinear = FloatArray(w * 3)
        val widened = FloatArray(3)
        val encoded = IntArray(3)
        for (y in 0 until h) {
            // Decode this row: P010 Y′CbCr → linear working RGB.
            var i = 0
            for (x in 0 until w) {
                val yc = RecordDevelopP010ColorIngress.readLumaCode(p010, w, h, x, y)
                val cb = RecordDevelopP010ColorIngress.readChromaCode(p010, w, h, x, y, 0)
                val cr = RecordDevelopP010ColorIngress.readChromaCode(p010, w, h, x, y, 1)
                RecordDevelopP010ColorIngress.decodeToLinearRgbInto(
                    yc,
                    cb,
                    cr,
                    plan.decodeSpace,
                    plan.decodeTransfer,
                    rowLinear,
                    i
                )
                i += 3
            }
            // Develop the whole row (pipeline is pure; identity options ⇒ passthrough).
            val developed = plan.pipeline.develop(rowLinear)
            // Encode: optional gamut widen → OETF → 10-bit codes.
            var o = y * w * 3
            var j = 0
            for (x in 0 until w) {
                var r = developed[j]
                var g = developed[j + 1]
                var b = developed[j + 2]
                if (delivery.widenToRec2020) {
                    RecordDevelopP010ColorIngress.rec709LinearToRec2020Into(r, g, b, widened)
                    r = widened[0]; g = widened[1]; b = widened[2]
                }
                // R6: genuine-HDR NDR tone-maps + PQ-encodes through the canonical NDR delivery engine (203-nit
                // diffuse white + C1 shoulder to the master peak). Every other target uses the SDR/legacy-PQ
                // OETF with the flat nit anchor. The develop RGB is already BT.2020 for NDR (no widen).
                val enc = if (delivery.ndrGenuineHdr) {
                    RecordDevelopP010ColorIngress.encodeNdrNivukxPqCodes(r, g, b, plan.decodeTransfer, warmSkinRedTame = NdrWarmSkinRedTameFlag.effectiveStrength)
                } else {
                    RecordDevelopP010ColorIngress.encodeLinearRgbInto(
                        r, g, b, delivery.outputTransfer, plan.diffuseWhiteNits, encoded
                    )
                }
                codes[o] = enc[0].toShort(); codes[o + 1] = enc[1].toShort(); codes[o + 2] = enc[2].toShort()
                o += 3
                j += 3
            }
        }
        return codes
    }

    // ── certificate + tamper-evidence helpers ──────────────────────────────────────────────────────────

    private fun conversionArtifacts(output: File): List<File> = listOf(
        output,
        certificateSidecarFile(output),
        File("${output.absolutePath}.elite-conversion-certificate.txt")
    ).distinctBy { it.absoluteFile.normalize().path }

    private fun movSampleEntry(deliveryFormat: RecordDevelopDeliveryFormat): String =
        when (deliveryFormat) {
            RecordDevelopDeliveryFormat.R210_444_UNCOMPRESSED_MOV -> "r210"
            RecordDevelopDeliveryFormat.V210_422_UNCOMPRESSED_MOV -> "v210"
            RecordDevelopDeliveryFormat.HEVC_MAIN10_420_MOV,
            RecordDevelopDeliveryFormat.HEVC_MAIN10_422_MOV -> "hvc1"
        }

    /** One expectation builder shared by staged and direct-MediaStore publication paths. */
    internal fun publicationExpectation(
        audit: RecordDevelopConversionAudit,
        rotationDegrees: Int
    ): RecordDevelopMovPublicationExpectation = RecordDevelopMovPublicationExpectation(
        sampleEntry = movSampleEntry(audit.deliveryFormat),
        width = audit.width,
        height = audit.height,
        rotationDegrees = rotationDegrees,
        color = QuickTimeMovNclxExpectation(
            colorPrimaries = audit.colorPrimaries,
            transferCharacteristics = audit.transferCharacteristics,
            matrixCoefficients = audit.matrixCoefficients,
            fullRange = audit.fullRange
        ),
        frameCount = audit.frameCount,
        expectedDurationUs = audit.presentationDurationUs,
        expectedAverageFps = null,
        // The configured bitrate/floor/codec ceiling are enforced before encoding and retained in the
        // declared audit. The measured payload rate remains authoritative certificate evidence, but it
        // cannot be treated as a minimum for short or low-entropy clips: even CBR encoders may emit a
        // smaller losslessly adequate access unit sequence. Frame/PTS/Main10/colour integrity remain
        // mandatory at this publication boundary and in the LQ-031 decode oracle.
        minimumMeasuredVideoBitrateBps = null
    )

    private fun deleteConversionArtifactsStrict(output: File) {
        conversionArtifacts(output).forEach { artifact ->
            if (artifact.exists()) {
                check(artifact.delete() && !artifact.exists()) {
                    "Failed to remove stale conversion artifact ${artifact.absolutePath}"
                }
            }
        }
    }

    private fun cleanupConversionArtifacts(
        output: File,
        outputOwnership: MovOutputOwnership,
        failure: Throwable
    ) {
        if (!outputOwnership.converterMayDeleteOutput) return
        conversionArtifacts(output).forEach { artifact ->
            if (artifact.exists() && (!artifact.delete() || artifact.exists())) {
                failure.addSuppressed(
                    IllegalStateException(
                        "Failed to remove partial conversion artifact ${artifact.absolutePath}"
                    )
                )
            }
        }
    }

    internal fun certificateSidecarFile(output: File): File =
        File(output.parentFile, "${output.name}.certificate.txt")

    private fun sha256Hex(file: File): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }
}
