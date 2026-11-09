package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NdrNivukxDeliveryEngine
import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.niv99.Niv99OfflineConversionPolicy
import com.neoproc.neoproccamx.niv99.Niv99RawConversionEvidenceAccumulator
import com.neoproc.neoproccamx.niv99.Niv99RawConversionOptions
import com.neoproc.neoproccamx.niv99.Niv99RawConversionEvidence
import com.neoproc.neoproccamx.niv99.Niv99RawProcessedFrame
import com.neoproc.neoproccamx.niv99.Niv99RawConversionProcessor
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.pipeline.RawExposureNormalizationPolicy
import com.neoproc.neoproccamx.pipeline.RawNeoLogDevelop
import java.nio.ByteBuffer

/**
 * RawToNeoLogMovConverter — offline conversion of a recorded 12-bit uncompressed RAW clip to a
 * 10-bit NeoLog .MOV.
 *
 * Composes the verified cores end-to-end:
 *   NPRAW pixel stream ([com.neoproc.neoproccamx.pipeline.NprawFrameCodec])
 *     + develop.meta sidecar ([com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar])
 *     → single-frame develop ([RawNeoLogDevelop])
 *     → 10-bit pack ([RawTenBitPacker]: 4:4:4 r210 master or v210 4:2:2 compat)
 *     → QuickTime MOV ([QuickTimeMovFileWriter]).
 *
 * The conversion LOGIC is I/O-free and unit-testable: [developAndPack] converts one frame and
 * [convert] drives an INJECTED frame source + sample sink, so the whole pipeline runs with fakes on
 * the JVM. The concrete pieces layered on top — reading `stream.npraw`, muxing via
 * QuickTimeMovFileWriter, MediaStore publish, and the Settings clip-list + thumbnails — are thin
 * device-integration wiring that reuse existing machinery and are validated on-device.
 *
 * SINGLE-FRAME ONLY (patent-safe): every frame is developed in isolation — no multi-frame merge,
 * temporal accumulation, exposure fusion, or wavelet denoise.
 */
class RawToNeoLogMovConverter {

    /** Final color delivery selected after the shared sensor-linear ACEScg develop seam. */
    enum class DeliveryProfile(
        val fileSuffix: String,
        val displayName: String,
        internal val ndrMode: NdrNivukxDeliveryEngine.Mode?
    ) {
        NEOLOG("neolog", "NeoLog", null),
        NDR10_PLUS("ndr10_plus", "NDR10+", NdrNivukxDeliveryEngine.Mode.NDR10_PLUS),
        NIVUKX_VISION("nivukx_vision", "NIVUKX Vision", NdrNivukxDeliveryEngine.Mode.NIVUKX_VISION)
    }

    /** Output container flavour. Every path retains the 10-bit NeoLog develop signal. */
    enum class OutputFormat(
        val movMime: String,
        val packerFormat: RawTenBitPacker.Format?,
        val memoryReferenceFormat: RawTenBitPacker.Format,
        val fileSuffix: String
    ) {
        /** 4:4:4 10-bit RGB master — most faithful, no chroma subsampling ('r210'). */
        MASTER_444("video/r210", RawTenBitPacker.Format.R210_444, RawTenBitPacker.Format.R210_444, "444"),

        /** v210 4:2:2 10-bit — universally decodable in Resolve/Premiere/FCP, ~⅓ smaller. */
        COMPAT_V210("video/v210", RawTenBitPacker.Format.V210_422, RawTenBitPacker.Format.V210_422, "v210"),

        /** Hardware HEVC Main10 from canonical P010 4:2:0; encoder output is verified before publish. */
        HEVC_MAIN10_420("video/hevc", null, RawTenBitPacker.Format.R210_444, "hevc_main10_420")
    }

    data class Options(
        val variant: NeoLogCurve.Variant = NeoLogCurve.Variant.BASE,
        val exposureStops: Float = RawExposureNormalizationPolicy.DEFAULT_EXPOSURE_STOPS,
        val format: OutputFormat = OutputFormat.MASTER_444,
        val deliveryProfile: DeliveryProfile = DeliveryProfile.NEOLOG,
        val niv99Enabled: Boolean = true,
        val niv99RawOptions: Niv99RawConversionOptions = Niv99RawConversionOptions.HIGH_QUALITY
    )

    data class ConvertResult(
        val framesConverted: Int,
        val bytesWritten: Long,
        val niv99Evidence: Niv99RawConversionEvidence? = null
    )

    private data class FirstFrameResolution(
        val useGpu: Boolean,
        val packedSample: ByteArray
    )

    private data class TimedPackedSample(
        val payload: ByteArray,
        val sourceTimestampNs: Long
    )

    /** Develop + pack ONE frame's raw samples into a 10-bit MOV sample payload (CPU). Pure + testable. */
    fun developAndPack(rawSamples: IntArray, meta: RawDevelopMetadata, options: Options): ByteArray {
        val prepared = createNiv99Processor(meta, options)?.process(
            source = rawSamples,
            frameNumber = 0L,
            sensorTimestampNs = 0L
        )?.samples ?: rawSamples
        return developPreparedAndPack(prepared, meta, options)
    }

    /** The RAW array supplied here is already the source-immutable NIV99 working copy. */
    private fun developPreparedAndPack(
        rawSamples: IntArray,
        meta: RawDevelopMetadata,
        options: Options
    ): ByteArray {
        requireCpuDevelopCapacity(meta, options)
        val acescg = RawNeoLogDevelop.developToAcescg(rawSamples, meta)
        return packDevelopedAcescg(acescg, meta, options)
    }

    // ── GPU develop (demosaic on GPU, NeoLog finish on CPU) + first-frame parity gate ────────────────
    // GPU and CPU differ ONLY in the demosaic (GPU edge-directed vs CPU bilinear), so we validate the GPU
    // path once on the clip's first frame against the CPU reference; if it deviates (or the accelerator is
    // unavailable / a dispatch fails) we develop the whole clip on the CPU (no-downgrade). Per-frame we
    // still fall back to CPU on any transient GPU failure.

    /** GPU demosaic → CPU NeoLog finish → pack; null when GPU is unavailable or the dispatch fails. */
    private fun developPreparedAndPackGpu(
        rawSamples: IntArray,
        meta: RawDevelopMetadata,
        options: Options
    ): ByteArray? {
        val acescg = RawDevelopGpuAccelerator.developToAcescg(rawSamples, meta, meta.width, meta.height)
            ?: return null
        return packDevelopedAcescg(acescg, meta, options)
    }

    /** Develop one frame on the chosen path; GPU falls back to CPU on any per-frame failure. */
    private fun developAndPackResolved(
        rawSamples: IntArray,
        meta: RawDevelopMetadata,
        options: Options,
        useGpu: Boolean
    ): ByteArray {
        if (useGpu) developPreparedAndPackGpu(rawSamples, meta, options)?.let { return it }
        return developPreparedAndPack(rawSamples, meta, options)
    }

    /**
     * Develop + pack ONE frame on the concurrent hybrid path ([convertStreamParallel]). The clip's
     * GPU-vs-CPU decision is resolved once on the first frame (parity gate) and is UNIFORM
     * for the whole clip: when GPU is chosen, EVERY frame's demosaic runs on the GPU, serialized by the
     * single [gpuPermit] (the native bridge allows one dispatch at a time). Crucially the permit is
     * ACQUIRED (blocking) and held ONLY around the GPU dispatch, then released before the linear-ACEScg
     * plane is finished (NeoLog encode + quantize) and packed on the CPU (both pure) — so the next frame's
     * GPU demosaic overlaps this frame's CPU finish + other workers' decode. Because the demosaic
     * algorithm is uniform across the clip, the output is deterministic and BYTE-IDENTICAL to
     * [convertStream] (which makes the same per-clip decision); we do NOT let permit-losers fall to the
     * CPU bilinear demosaic, which would mix two demosaic algorithms frame-to-frame (non-reproducible +
     * possible edge shimmer). A per-frame GPU dispatch/readback failure still falls back to the byte-parity
     * full CPU develop for that one frame — the exact same no-downgrade error path the sequential path has.
     */
    private fun developAndPackHybrid(
        rawSamples: IntArray,
        meta: RawDevelopMetadata,
        options: Options,
        gpuPermit: java.util.concurrent.Semaphore?,
        useGpu: Boolean
    ): ByteArray {
        if (useGpu && gpuPermit != null) {
            gpuPermit.acquire()
            val acescg = try {
                RawDevelopGpuAccelerator.developToAcescg(rawSamples, meta, meta.width, meta.height)
            } finally {
                gpuPermit.release()
            }
            if (acescg != null) {
                return packDevelopedAcescg(acescg, meta, options)
            }
        }
        return developPreparedAndPack(rawSamples, meta, options)
    }

    /**
     * Decide the develop path for a whole clip from a representative frame: GPU only if the accelerator is
     * available AND its develop is parity-close to the CPU reference on this frame. Cheap when the GPU is
     * unavailable (returns false before any CPU reference develop).
     */
    private fun resolveGpuPath(
        sampleFrame: IntArray,
        meta: RawDevelopMetadata,
        options: Options
    ): FirstFrameResolution {
        if (!RawDevelopGpuAccelerator.isAvailable()) {
            NeoLogger.i(TAG, "RAW->LOG GPU demosaic UNAVAILABLE (accelerator off or Vulkan bridge unsupported here) -> CPU bilinear develop")
            return FirstFrameResolution(
                useGpu = false,
                packedSample = developPreparedAndPack(sampleFrame, meta, options)
            )
        }
        val w = meta.width
        val h = meta.height
        val parityPeak = RawDevelopMemoryPolicy.gpuFullParityPeakBytes(
            w,
            h,
            options.format.memoryReferenceFormat
        )
        if (!RawDevelopMemoryPolicy.fitsNow(parityPeak)) {
            NeoLogger.i(
                TAG,
                "RAW->LOG GPU demosaic parity skipped: needs ~${parityPeak / (1024 * 1024)} MiB " +
                    "Java heap, available ~${RawDevelopMemoryPolicy.availableJavaHeapBytes() / (1024 * 1024)} MiB -> CPU reference develop"
            )
            return FirstFrameResolution(
                useGpu = false,
                packedSample = developPreparedAndPack(sampleFrame, meta, options)
            )
        }
        var gpuAcescg = RawDevelopGpuAccelerator.developToAcescg(sampleFrame, meta, w, h)
        if (gpuAcescg == null) {
            NeoLogger.i(TAG, "RAW->LOG GPU demosaic dispatch/readback FAILED on first frame -> CPU bilinear develop")
            return FirstFrameResolution(
                useGpu = false,
                packedSample = developPreparedAndPack(sampleFrame, meta, options)
            )
        }
        val gpuFrame = RawNeoLogDevelop.finishFromAcescg(gpuAcescg, w, h, options.variant, options.exposureStops, 10)
        var cpuAcescg: FloatArray? = RawNeoLogDevelop.developToAcescg(sampleFrame, meta)
        val cpuFrame = RawNeoLogDevelop.finishFromAcescg(
            requireNotNull(cpuAcescg),
            w,
            h,
            options.variant,
            options.exposureStops,
            10
        )
        val accepted = developsMatchWithinTolerance(gpuFrame.rgb, cpuFrame.rgb)
        logRawGpuParity(gpuFrame.rgb, cpuFrame.rgb, accepted)
        val selected = if (accepted) {
            cpuAcescg = null
            requireNotNull(gpuAcescg)
        } else {
            gpuAcescg = null
            requireNotNull(cpuAcescg)
        }
        return FirstFrameResolution(
            useGpu = accepted,
            // finishFromAcescg() above already ran the exact elite-detail pass used by the direct packer.
            // Reusing this completed first frame removes a second full demosaic/develop without applying
            // detail twice or changing one output code.
            packedSample = packDevelopedAcescg(
                selected,
                meta,
                options,
                detailAlreadyApplied = true
            )
        )
    }

    /** First-frame observability for the RAW->LOG GPU-vs-CPU decision (comparison only; never alters pixels). */
    private fun logRawGpuParity(gpu: ShortArray, cpu: ShortArray, accepted: Boolean) {
        if (gpu.size != cpu.size || gpu.isEmpty()) return
        var sumAbs = 0.0
        var maxAbs = 0
        for (i in gpu.indices) {
            val d = kotlin.math.abs((gpu[i].toInt() and 0xFFFF) - (cpu[i].toInt() and 0xFFFF))
            sumAbs += d
            if (d > maxAbs) maxAbs = d
        }
        val mean = sumAbs / gpu.size
        NeoLogger.i(
            TAG,
            "RAW->LOG GPU demosaic parity: meanAbs=$mean maxAbs=$maxAbs " +
                "(tol mean<=$GPU_PARITY_MEAN_TOLERANCE max<=$GPU_PARITY_MAX_TOLERANCE) -> " +
                (if (accepted) "GPU ACCEPTED (edge-directed demosaic engaged for clip)" else "REJECTED -> CPU bilinear")
        )
    }

    /**
     * GPU-vs-CPU develop parity on 10-bit codes (0..1023). Exact equality is NOT expected (edge-directed
     * vs bilinear demosaic), but a CORRECT GPU develop stays perceptually identical — so accept only when
     * the mean absolute code difference is tiny and no pixel diverges wildly (a broken transform / wrong
     * colour space blows both far past tolerance → CPU fallback).
     */
    private fun developsMatchWithinTolerance(gpu: ShortArray, cpu: ShortArray): Boolean {
        if (gpu.size != cpu.size || gpu.isEmpty()) return false
        var sumAbs = 0.0
        var maxAbs = 0
        for (i in gpu.indices) {
            val d = kotlin.math.abs((gpu[i].toInt() and 0xFFFF) - (cpu[i].toInt() and 0xFFFF))
            sumAbs += d.toDouble()
            if (d > maxAbs) maxAbs = d
        }
        return (sumAbs / gpu.size) <= GPU_PARITY_MEAN_TOLERANCE && maxAbs <= GPU_PARITY_MAX_TOLERANCE
    }

    /**
     * Drive the conversion over an injected frame source and sample sink. I/O-free: the caller wires
     * [frames] to NprawFrameCodec-decoded sensor samples and [sink] to the MOV muxer / writer. Returns
     * per-clip counts so the caller can report progress and verify frame integrity.
     */
    fun convert(
        frames: Iterator<IntArray>,
        meta: RawDevelopMetadata,
        options: Options,
        sink: (sample: ByteArray, index: Int) -> Unit
    ): ConvertResult {
        val niv99 = createNiv99Processor(meta, options)
        val frameDurationNs = 1_000_000_000L / meta.fps.coerceAtLeast(1)
        var index = 0
        var bytes = 0L
        while (frames.hasNext()) {
            val raw = frames.next()
            val prepared = niv99?.process(
                source = raw,
                frameNumber = index.toLong(),
                sensorTimestampNs = index.toLong() * frameDurationNs
            )?.samples ?: raw
            val sample = developPreparedAndPack(prepared, meta, options)
            sink(sample, index)
            bytes += sample.size.toLong()
            index++
        }
        return ConvertResult(
            framesConverted = index,
            bytesWritten = bytes,
            niv99Evidence = niv99?.evidence()
        )
    }

    /**
     * Convert an entire NPRAW pixel stream (`stream.npraw`) to developed 10-bit MOV samples, STREAMING
     * frame-by-frame via [com.neoproc.neoproccamx.pipeline.NprawFrameCodec.readFrame] (never loads the
     * whole clip into memory). The caller wires [input] to the recorded stream and [sink] to the MOV
     * muxer. This is the on-disk entry the device-integration layer drives.
     */
    fun convertStream(
        input: java.io.InputStream,
        meta: RawDevelopMetadata,
        options: Options,
        sink: (sample: ByteArray, index: Int) -> Unit
    ): ConvertResult = convertStreamTimed(input, meta, options) { sample, index, _ ->
        sink(sample, index)
    }

    /**
     * Timestamp-preserving form used by the production MOV coordinator. [sourceTimestampNs] is the exact
     * sensor timestamp carried by each NPRAW packet; the coordinator normalizes it to a zero-based MOV
     * timeline without replacing variable frame cadence with an invented constant cadence.
     */
    internal fun convertStreamTimed(
        input: java.io.InputStream,
        meta: RawDevelopMetadata,
        options: Options,
        sink: (sample: ByteArray, index: Int, sourceTimestampNs: Long) -> Unit
    ): ConvertResult {
        val niv99 = createNiv99Processor(meta, options)
        // Read + decide on the first frame. The clip's GPU-vs-CPU decision is resolved once (parity gate);
        // unavailable / failed parity → CPU (no-downgrade). Deciding eagerly (vs the old lazy first-iteration
        // decision — same frame, same result) lets the GPU path pipeline frame N+1's demosaic with frame N's
        // CPU finish (see [convertStreamGpuOverlapped]); the CPU path stays a plain sequential loop.
        val firstBytes = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.readRawFrameBytes(input)
            ?: return ConvertResult(
                framesConverted = 0,
                bytesWritten = 0L,
                niv99Evidence = niv99?.evidence()
            )
        val firstFrame = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.decodeFrame(firstBytes)
        val firstPrepared = niv99?.process(
            source = firstFrame.samples,
            frameNumber = 0L,
            sensorTimestampNs = firstFrame.timestamp
        )?.samples ?: firstFrame.samples
        val firstResolution = resolveGpuPath(firstPrepared, meta, options)
        val useGpu = firstResolution.useGpu
        // Overlap only when GPU is the clip's path AND the extra resident ACEScg plane fits the heap budget;
        // otherwise fall back to the byte-identical sequential develop (no-downgrade, never OOM).
        if (useGpu && RawDevelopPipelineOverlapPolicy.shouldOverlap(meta.width, meta.height)) {
            return convertStreamGpuOverlapped(
                firstResolution.packedSample,
                firstFrame.timestamp,
                input,
                meta,
                options,
                niv99,
                sink
            )
        }
        // Sequential develop (CPU, or GPU without overlap) — byte-identical to the prior behaviour: develop
        // the already-decoded first frame, then stream the rest with the same uniform per-clip decision.
        var index = 0
        var bytes = 0L
        run {
            sink(firstResolution.packedSample, index, firstFrame.timestamp)
            bytes += firstResolution.packedSample.size.toLong()
            index++
        }
        while (true) {
            val frame = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.readFrame(input) ?: break
            val prepared = niv99?.process(
                source = frame.samples,
                frameNumber = index.toLong(),
                sensorTimestampNs = frame.timestamp
            )?.samples ?: frame.samples
            val sample = developAndPackResolved(prepared, meta, options, useGpu)
            sink(sample, index, frame.timestamp)
            bytes += sample.size.toLong()
            index++
        }
        return ConvertResult(
            framesConverted = index,
            bytesWritten = bytes,
            niv99Evidence = niv99?.evidence()
        )
    }

    /**
     * Bounded-memory production route for RAW -> hardware HEVC Main10 conversion.
     *
     * The decoded NPRAW array is transferred to NIV99, the Vulkan ACEScg result stays in direct/native
     * storage, and the final P010 frame is written into one reusable direct encoder buffer. The disk-backed
     * RAW master is never modified. A sampled first-frame CPU oracle still rejects an invalid GPU colour
     * transform, while avoiding the former full-frame CPU+GPU parity allocation that could exceed the
     * Android Java heap before frame one.
     *
     * [sink] must consume the supplied buffer synchronously. MediaCodec's byte-buffer input contract copies
     * the payload before returning, so reuse on the next frame is safe.
     */
    internal fun convertStreamDirectP010Timed(
        input: java.io.InputStream,
        meta: RawDevelopMetadata,
        options: Options,
        telemetry: RawConversionStageTelemetry,
        onFrame: ((framesDone: Int) -> Unit)? = null,
        sink: (sample: ByteBuffer, index: Int, sourceTimestampNs: Long) -> Unit
    ): ConvertResult {
        require(options.format == OutputFormat.HEVC_MAIN10_420) {
            "Direct P010 conversion requires HEVC_MAIN10_420"
        }
        val niv99 = createNiv99Processor(meta, options)
        val niv99Evidence = niv99?.let { Niv99RawConversionEvidenceAccumulator(it.plan) }
        val p010Workspace = RawDevelopDirectP010Workspace()
        val expectedBytes = sampleSize(meta, options)
        val gpuAdmissionStarted = System.nanoTime()
        val gpuPipelineReady = RawDevelopGpuAccelerator.awaitRawConversionPipelineReady()
        telemetry.addCopyWait((System.nanoTime() - gpuAdmissionStarted).coerceAtLeast(0L))
        var gpuDecision: Boolean? = if (gpuPipelineReady) null else false
        if (!gpuPipelineReady) {
            NeoLogger.i(
                TAG,
                "RAW GPU conversion pipeline unavailable after bounded admission; " +
                    "strict full-quality CPU route selected"
            )
        }
        var index = 0
        var bytes = 0L

        while (true) {
            val readStarted = System.nanoTime()
            val frameBytes = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.readRawFrameBytes(input)
            telemetry.addCopyWait((System.nanoTime() - readStarted).coerceAtLeast(0L))
            if (frameBytes == null) break

            val decodeStarted = System.nanoTime()
            val decoded = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.decodeFrame(frameBytes)
            telemetry.addCpu((System.nanoTime() - decodeStarted).coerceAtLeast(0L))

            // Frame zero uses the established Vulkan-demosaic + CPU-tail path as a bounded quality
            // oracle. It is paid once per clip; every later accepted frame stays GPU-native through
            // detail, profile delivery and P010 packing. This prevents a fast but numerically wrong
            // shader from becoming the clip-wide path.
            var firstFrameReference: RawDevelopP010SampleOracle? = null
            var cpuFallbackPrepared: Niv99RawProcessedFrame? = null
            if (gpuDecision == null) {
                val niv99OracleStarted = System.nanoTime()
                // Keep the decoded source immutable for the GPU attempt. The CPU copy is the one-time
                // quality oracle and is also retained as frame-zero fallback if native validation fails.
                cpuFallbackPrepared = niv99?.process(
                    source = decoded.samples,
                    frameNumber = index.toLong(),
                    sensorTimestampNs = decoded.timestamp
                )
                val referenceSamples = cpuFallbackPrepared?.samples ?: decoded.samples
                var referenceCpuNanos =
                    (System.nanoTime() - niv99OracleStarted).coerceAtLeast(0L)
                val referenceStarted = System.nanoTime()
                val referenceInvocation = RawDevelopGpuAccelerator.withDevelopedAcescgDirect(
                    rawSamples = referenceSamples,
                    meta = meta,
                    width = meta.width,
                    height = meta.height
                ) reference@{ acescg ->
                    val parityStarted = System.nanoTime()
                    val demosaicParity = RawDevelopSampledParityPolicy.evaluate(
                        rawSamples = referenceSamples,
                        meta = meta,
                        gpuAcescg = acescg,
                        variant = options.variant,
                        exposureStops = options.exposureStops,
                        meanTolerance = GPU_PARITY_MEAN_TOLERANCE,
                        maximumTolerance = GPU_PARITY_MAX_TOLERANCE
                    )
                    referenceCpuNanos = saturatedAdd(
                        referenceCpuNanos,
                        (System.nanoTime() - parityStarted).coerceAtLeast(0L)
                    )
                    logRawGpuSampledParity(demosaicParity)
                    if (!demosaicParity.accepted) return@reference false
                    val oracleStarted = System.nanoTime()
                    firstFrameReference = RawDevelopP010SampledParityPolicy.buildReferenceFromAcescg(
                        acescg = acescg,
                        width = meta.width,
                        height = meta.height,
                        variant = options.variant,
                        deliveryProfile = options.deliveryProfile,
                        exposureStops = options.exposureStops
                    )
                    referenceCpuNanos = saturatedAdd(
                        referenceCpuNanos,
                        (System.nanoTime() - oracleStarted).coerceAtLeast(0L)
                    )
                    firstFrameReference != null
                }
                telemetry.addCpu(saturatedAdd(referenceCpuNanos, referenceInvocation?.cpuNanos ?: 0L))
                if (referenceInvocation != null) {
                    telemetry.addGpu(referenceInvocation.gpuNanos)
                    telemetry.addCopyWait(referenceInvocation.copyWaitNanos)
                } else {
                    telemetry.addCopyWait(
                        (System.nanoTime() - referenceStarted - referenceCpuNanos).coerceAtLeast(0L)
                    )
                }
                if (referenceInvocation?.value != true || firstFrameReference == null) {
                    gpuDecision = false
                    NeoLogger.i(
                        TAG,
                        "RAW GPU P010 reference unavailable/rejected; strict CPU reference selected"
                    )
                }
            }

            var callbackCpuNanos = 0L
            var callbackEncoderNanos = 0L
            val gpuAttemptStarted = System.nanoTime()
            val directInvocation = if (gpuDecision != false) {
                RawDevelopGpuAccelerator.withDevelopedP010Direct(
                    rawSamples = decoded.samples,
                    meta = meta,
                    width = meta.width,
                    height = meta.height,
                    variant = options.variant,
                    deliveryProfile = options.deliveryProfile,
                    exposureStops = options.exposureStops,
                    niv99Plan = niv99?.plan,
                    niv99Options = options.niv99RawOptions,
                    frameNumber = index.toLong(),
                    sensorTimestampNs = decoded.timestamp
                ) direct@{ gpuP010 ->
                    if (gpuDecision == null) {
                        val parityStarted = System.nanoTime()
                        val parity = RawDevelopP010SampledParityPolicy.evaluate(
                            reference = requireNotNull(firstFrameReference),
                            candidate = gpuP010,
                            meanTolerance = GPU_P010_PARITY_MEAN_TOLERANCE,
                            maximumTolerance = GPU_P010_PARITY_MAX_TOLERANCE
                        )
                        callbackCpuNanos += (System.nanoTime() - parityStarted).coerceAtLeast(0L)
                        logRawGpuP010Parity(parity)
                        if (!parity.accepted) return@direct false
                    }
                    val encodeStarted = System.nanoTime()
                    sink(gpuP010, index, decoded.timestamp)
                    callbackEncoderNanos += (System.nanoTime() - encodeStarted).coerceAtLeast(0L)
                    true
                }
            } else {
                null
            }
            val gpuAttemptNanos = (System.nanoTime() - gpuAttemptStarted).coerceAtLeast(0L)
            val directResult = directInvocation?.value
            telemetry.addCpu(saturatedAdd(callbackCpuNanos, directInvocation?.cpuNanos ?: 0L))
            telemetry.addEncoder(callbackEncoderNanos)
            if (directInvocation != null) {
                telemetry.addGpu(directInvocation.gpuNanos)
                telemetry.addCopyWait(directInvocation.copyWaitNanos)
            } else if (gpuDecision != false) {
                // A failed native attempt cannot expose phase timings. Attribute its residual wall time to
                // copy/wait instead of falsely claiming it was GPU compute.
                telemetry.addCopyWait(
                    (gpuAttemptNanos - callbackCpuNanos - callbackEncoderNanos).coerceAtLeast(0L)
                )
            }

            val deliveredByGpu = directResult == true
            if (deliveredByGpu && niv99Evidence != null) {
                niv99Evidence.addGpu(
                    checkNotNull(directInvocation.niv99Evidence) {
                        "GPU NIV99 delivered a frame without backend evidence"
                    }
                )
            }
            if (gpuDecision == null) {
                gpuDecision = deliveredByGpu
                NeoLogger.i(
                    TAG,
                    if (deliveredByGpu) {
                        "RAW GPU final-P010 parity accepted; complete Vulkan conversion route engaged"
                    } else {
                        "RAW GPU final-P010 parity unavailable/rejected; strict reference route selected"
                    }
                )
            }
            if (!deliveredByGpu) {
                if (gpuDecision == true) {
                    // A transient native failure permanently hands the remaining clip to the proven CPU
                    // backend. This avoids repeated native failures and gives CPU temporal history a new,
                    // explicit reset followed by contiguous frames.
                    gpuDecision = false
                    NeoLogger.i(
                        TAG,
                        "RAW GPU final-P010 dispatch failed; strict CPU route selected for remaining frames"
                    )
                }
                val cpuStarted = System.nanoTime()
                val fallbackPrepared = cpuFallbackPrepared ?: niv99?.processOwned(
                    source = decoded.samples,
                    frameNumber = index.toLong(),
                    sensorTimestampNs = decoded.timestamp
                )
                fallbackPrepared?.evidence?.let { niv99Evidence?.addCpu(it) }
                val sample = developPreparedAndPack(
                    fallbackPrepared?.samples ?: decoded.samples,
                    meta,
                    options
                )
                telemetry.addCpu((System.nanoTime() - cpuStarted).coerceAtLeast(0L))
                p010Workspace.use(meta.width, meta.height) { p010 ->
                    val copyStarted = System.nanoTime()
                    p010.put(sample)
                    p010.flip()
                    telemetry.addCopyWait((System.nanoTime() - copyStarted).coerceAtLeast(0L))
                    val encodeStarted = System.nanoTime()
                    sink(p010, index, decoded.timestamp)
                    telemetry.addEncoder((System.nanoTime() - encodeStarted).coerceAtLeast(0L))
                }
            }
            bytes = Math.addExact(bytes, expectedBytes.toLong())
            index++
            telemetry.frameCompleted()
            onFrame?.invoke(index)
        }
        return ConvertResult(
            framesConverted = index,
            bytesWritten = bytes,
            niv99Evidence = niv99Evidence?.evidence()
        )
    }

    // ── Single-worker CPU+GPU overlap (flagship full-sensor / 4K RAW) ─────────────────────────────────
    // Stage-2 work item for [convertStreamGpuOverlapped], handed from the GPU-demosaic producer thread to
    // the CPU-finish consumer thread in strict frame order.
    private sealed interface RawStage2Item

    /** GPU produced the LINEAR ACEScg plane; stage 2 runs the CPU finish (elite detail + NeoLog encode) + pack. */
    private class RawStage2Acescg(val plane: FloatArray) : RawStage2Item

    /** Per-frame GPU fallback already produced the final packed 10-bit sample on the CPU; stage 2 just sinks it. */
    private class RawStage2Packed(val sample: ByteArray) : RawStage2Item

    private class TimedRawStage2Item(
        val item: RawStage2Item,
        val sourceTimestampNs: Long
    )

    /**
     * GPU single-worker OVERLAP variant of the sequential develop — engaged by [convertStream] when the
     * clip's chosen path is the GPU demosaic AND [RawDevelopPipelineOverlapPolicy] is ON (typically the
     * flagship full-sensor / 4K RAW, where [ConversionParallelismPolicy] floors the worker count to 1 and
     * the develop would otherwise run GPU-demosaic and CPU-finish strictly one-after-another).
     *
     * A single background thread (stage 1) streams + decodes each frame and runs its GPU demosaic; the
     * calling thread (stage 2) runs the CPU NeoLog finish + 10-bit pack and delivers samples to [sink] in
     * strict frame order (see [runInOrderTwoStage]). So frame N+1's GPU demosaic overlaps frame N's CPU
     * finish — both lanes stay busy. Output is BYTE-IDENTICAL to the sequential [convertStream] GPU path:
     * the demosaic algorithm is uniform across the clip, the finish tail is the same pure function, and
     * delivery order is preserved — only the wall-clock changes (no-downgrade). A per-frame GPU
     * dispatch/readback failure falls back to the byte-exact full CPU develop for THAT frame (the same
     * no-downgrade error path the sequential develop has). The rendezvous hand-off keeps at most two ACEScg
     * planes resident (one produced while one is consumed), the minimum for overlap. [firstPackedSample]
     * is the parity-gated frame-zero result, so it is neither re-read nor developed a second time.
     */
    private fun convertStreamGpuOverlapped(
        firstPackedSample: ByteArray,
        firstSourceTimestampNs: Long,
        input: java.io.InputStream,
        meta: RawDevelopMetadata,
        options: Options,
        niv99: Niv99RawConversionProcessor?,
        sink: (sample: ByteArray, index: Int, sourceTimestampNs: Long) -> Unit
    ): ConvertResult {
        val w = meta.width
        val h = meta.height
        var index = 1
        var bytes = firstPackedSample.size.toLong()
        var nextSourceFrameNumber = 1L
        sink(firstPackedSample, 0, firstSourceTimestampNs)
        // Only the single producer thread touches this; the first frame is already decoded, later frames are
        // streamed + decoded on that same thread — so its GPU demosaic overlaps the consumer's CPU finish.
        runInOrderTwoStage<TimedRawStage2Item>(
            stage1 = stage1@{
                val frameBytes = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.readRawFrameBytes(input)
                    ?: return@stage1 null
                val decoded = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.decodeFrame(frameBytes)
                val frameNumber = nextSourceFrameNumber++
                val prepared = niv99?.process(
                    source = decoded.samples,
                    frameNumber = frameNumber,
                    sensorTimestampNs = decoded.timestamp
                )?.samples ?: decoded.samples
                // Uniform per-clip GPU demosaic; a per-frame dispatch failure develops that frame on the CPU.
                val plane = RawDevelopGpuAccelerator.developToAcescg(prepared, meta, w, h)
                TimedRawStage2Item(
                    item = if (plane != null) {
                        RawStage2Acescg(plane)
                    } else {
                        RawStage2Packed(developPreparedAndPack(prepared, meta, options))
                    },
                    sourceTimestampNs = decoded.timestamp
                )
            },
            stage2 = { timed ->
                val sample = when (val item = timed.item) {
                    is RawStage2Acescg -> packDevelopedAcescg(item.plane, meta, options)
                    is RawStage2Packed -> item.sample
                }
                sink(sample, index, timed.sourceTimestampNs)
                bytes += sample.size.toLong()
                index++
            }
        )
        return ConvertResult(
            framesConverted = index,
            bytesWritten = bytes,
            niv99Evidence = niv99?.evidence()
        )
    }

    /**
     * PARALLEL (CPU+GPU hybrid) variant of [convertStream]: one thread reads NPRAW frame bytes
     * sequentially (stream I/O is serial), and the expensive per-frame decode + develop + 10-bit pack is
     * fanned out across [parallelism] worker threads. When the GPU develop path is the clip's chosen
     * decision, each frame's demosaic runs on the GPU serialized by a single permit while its NeoLog
     * finish + pack (CPU) and other workers' decode overlap the next frame's GPU demosaic (see
     * [developAndPackHybrid]); otherwise every frame develops fully on the CPU. Developed samples are
     * handed to [sink] STRICTLY IN FRAME ORDER via an order-preserving in-flight window.
     *
     * Output is BYTE-IDENTICAL to [convertStream]: both make the SAME per-clip GPU-vs-CPU decision and use
     * a UNIFORM demosaic algorithm across the clip, so only the wall-clock changes (no-downgrade — every
     * frame is developed with the exact same single-frame math, just concurrently). [onFrame] fires once
     * per frame delivered to [sink], in order, carrying the running count.
     *
     * The in-flight window is bounded to `parallelism + 2` frames so peak resident developed buffers stay
     * bounded regardless of clip length (cinema frames are large). [developAndPack]/[finishFromAcescg]/
     * [RawTenBitPacker.pack] are pure functions of their inputs, so concurrent invocation is safe.
     */
    fun convertStreamParallel(
        input: java.io.InputStream,
        meta: RawDevelopMetadata,
        options: Options,
        parallelism: Int,
        onFrame: ((framesDone: Int) -> Unit)? = null,
        sink: (sample: ByteArray, index: Int) -> Unit
    ): ConvertResult = convertStreamParallelTimed(
        input = input,
        meta = meta,
        options = options,
        parallelism = parallelism,
        onFrame = onFrame
    ) { sample, index, _ ->
        sink(sample, index)
    }

    internal fun convertStreamParallelTimed(
        input: java.io.InputStream,
        meta: RawDevelopMetadata,
        options: Options,
        parallelism: Int,
        onFrame: ((framesDone: Int) -> Unit)? = null,
        sink: (sample: ByteArray, index: Int, sourceTimestampNs: Long) -> Unit
    ): ConvertResult {
        val workers = parallelism.coerceAtLeast(1)
        if (workers == 1) {
            // Single worker: the sequential path is already optimal and avoids all thread overhead.
            return convertStreamTimed(input, meta, options) { sample, index, sourceTimestampNs ->
                sink(sample, index, sourceTimestampNs)
                onFrame?.invoke(index + 1)
            }
        }

        // Resolve GPU-vs-CPU from the actual chronological frame zero before workers are started. The
        // previous lazy race let whichever worker happened to run first choose the clip-wide algorithm,
        // making the parity decision scheduling-dependent and potentially different from convertStream().
        val firstBytes = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.readRawFrameBytes(input)
            ?: return ConvertResult(
                framesConverted = 0,
                bytesWritten = 0L,
                niv99Evidence = null
            )
        val firstFrame = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.decodeFrame(firstBytes)
        val niv99 = createNiv99Processor(meta, options)
        val firstPrepared = niv99?.process(
            source = firstFrame.samples,
            frameNumber = 0L,
            sensorTimestampNs = firstFrame.timestamp
        )?.samples ?: firstFrame.samples
        val firstResolution = resolveGpuPath(firstPrepared, meta, options)
        sink(firstResolution.packedSample, 0, firstFrame.timestamp)
        onFrame?.invoke(1)

        val pool = java.util.concurrent.Executors.newFixedThreadPool(workers) { runnable ->
            Thread(runnable, "raw-develop").apply { isDaemon = true }
        }
        val inFlight = ArrayDeque<java.util.concurrent.Future<TimedPackedSample>>()
        // The memory-derived worker window remains authoritative; DMC can make it narrower for a
        // conversion that starts under measured thermal pressure without changing any pixel operation.
        val maxInFlight = minOf(
            workers + 2,
            DmcConversionTuningRegistry.current().inFlightLimit
        ).coerceAtLeast(1)
        var bytes = firstResolution.packedSample.size.toLong()
        var writeIndex = 1
        // CPU+GPU HYBRID (pipelined, uniform demosaic): a single GPU permit serializes the GPU demosaic
        // to one in-flight dispatch (the native Vulkan bridge allows only one; the permit also keeps the
        // whole pool from piling onto that native mutex and stalling the live pipeline). The permit is
        // released the instant the GPU dispatch returns, so this frame's NeoLog finish + 10-bit pack (CPU)
        // and other workers' NPRAW decode overlap the NEXT frame's GPU demosaic — GPU and CPU stay busy
        // together. When GPU is the clip's chosen path, EVERY frame's demosaic uses it (uniform algorithm
        // ⇒ deterministic + byte-identical to convertStream); we deliberately do NOT hand permit-losers to
        // the CPU bilinear demosaic, which would mix two algorithms per clip. Null permit (GPU unavailable
        // or parity-rejected) ⇒ pure multi-core CPU develop, unchanged.
        val gpuPermit: java.util.concurrent.Semaphore? =
            if (firstResolution.useGpu) java.util.concurrent.Semaphore(1) else null
        try {
            while (true) {
                val frameBytes = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.readRawFrameBytes(input)
                    ?: break
                // Decode and run the order-sensitive bounded NIV99 history on this serial reader lane.
                // Expensive demosaic/develop/packing remains parallel below.
                val decoded = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.decodeFrame(frameBytes)
                val sourceFrameIndex = writeIndex + inFlight.size
                val prepared = niv99?.process(
                    source = decoded.samples,
                    frameNumber = sourceFrameIndex.toLong(),
                    sensorTimestampNs = decoded.timestamp
                )?.samples ?: decoded.samples
                inFlight.addLast(
                    pool.submit(
                        java.util.concurrent.Callable {
                            TimedPackedSample(
                                payload = developAndPackHybrid(
                                    prepared,
                                    meta,
                                    options,
                                    gpuPermit,
                                    firstResolution.useGpu
                                ),
                                sourceTimestampNs = decoded.timestamp
                            )
                        }
                    )
                )
                if (inFlight.size >= maxInFlight) {
                    val sample = awaitNext(inFlight.removeFirst())
                    sink(sample.payload, writeIndex, sample.sourceTimestampNs)
                    bytes += sample.payload.size.toLong()
                    writeIndex++
                    onFrame?.invoke(writeIndex)
                }
            }
            while (inFlight.isNotEmpty()) {
                val sample = awaitNext(inFlight.removeFirst())
                sink(sample.payload, writeIndex, sample.sourceTimestampNs)
                bytes += sample.payload.size.toLong()
                writeIndex++
                onFrame?.invoke(writeIndex)
            }
        } catch (t: Throwable) {
            inFlight.forEach { it.cancel(true) }
            throw t
        } finally {
            pool.shutdownNow()
        }
        return ConvertResult(
            framesConverted = writeIndex,
            bytesWritten = bytes,
            niv99Evidence = niv99?.evidence()
        )
    }

    private fun createNiv99Processor(
        meta: RawDevelopMetadata,
        options: Options
    ): Niv99RawConversionProcessor? {
        if (!options.niv99Enabled) return null
        val plan = Niv99OfflineConversionPolicy.forRawDelivery(options.deliveryProfile.name)
        plan.requireFrameContract(
            sourceWidth = meta.width,
            sourceHeight = meta.height,
            outputWidth = meta.width,
            outputHeight = meta.height,
            sourceFps = meta.fps.coerceAtLeast(1),
            outputFps = meta.fps.coerceAtLeast(1),
            outputBitDepth = 10
        )
        return Niv99RawConversionProcessor(
            metadata = meta,
            plan = plan,
            options = options.niv99RawOptions
        )
    }

    /** Await one develop future, unwrapping ExecutionException so the caller sees the real failure. */
    private fun <T> awaitNext(future: java.util.concurrent.Future<T>): T =
        try {
            future.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }

    /** Expected packed byte size per frame for [options] — for muxer buffer sizing / preflight. */
    fun sampleSize(meta: RawDevelopMetadata, options: Options): Int = when (options.format) {
        OutputFormat.HEVC_MAIN10_420 ->
            Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(meta.width, meta.height).toInt()
        else -> RawTenBitPacker.payloadSize(
            requireNotNull(options.format.packerFormat),
            meta.width,
            meta.height
        )
    }

    private fun packDevelopedAcescg(
        acescg: FloatArray,
        meta: RawDevelopMetadata,
        options: Options,
        detailAlreadyApplied: Boolean = false
    ): ByteArray {
        val ndrMode = options.deliveryProfile.ndrMode
        if (ndrMode != null) {
            return when (options.format) {
                OutputFormat.HEVC_MAIN10_420 -> RawAcescgTenBitPacker.packNdrP010420(
                    acescg = acescg,
                    width = meta.width,
                    height = meta.height,
                    mode = ndrMode,
                    exposureStops = options.exposureStops,
                    detailAlreadyApplied = detailAlreadyApplied
                )
                OutputFormat.MASTER_444,
                OutputFormat.COMPAT_V210 -> RawAcescgTenBitPacker.packNdrUncompressed(
                    acescg = acescg,
                    width = meta.width,
                    height = meta.height,
                    mode = ndrMode,
                    format = requireNotNull(options.format.packerFormat),
                    exposureStops = options.exposureStops,
                    detailAlreadyApplied = detailAlreadyApplied
                )
            }
        }
        return when (options.format) {
            OutputFormat.HEVC_MAIN10_420 -> RawAcescgTenBitPacker.packP010420(
                acescg = acescg,
                width = meta.width,
                height = meta.height,
                variant = options.variant,
                exposureStops = options.exposureStops,
                detailAlreadyApplied = detailAlreadyApplied
            )
            OutputFormat.MASTER_444,
            OutputFormat.COMPAT_V210 -> RawAcescgTenBitPacker.pack(
                acescg = acescg,
                width = meta.width,
                height = meta.height,
                variant = options.variant,
                exposureStops = options.exposureStops,
                outputBitDepth = 10,
                format = requireNotNull(options.format.packerFormat),
                detailAlreadyApplied = detailAlreadyApplied
            )
        }
    }

    private fun packDevelopedAcescgDirect(
        acescg: java.nio.FloatBuffer,
        output: ByteBuffer,
        meta: RawDevelopMetadata,
        options: Options
    ): ByteBuffer {
        val ndrMode = options.deliveryProfile.ndrMode
        return if (ndrMode != null) {
            RawAcescgTenBitPacker.packNdrP010420Direct(
                acescg = acescg,
                width = meta.width,
                height = meta.height,
                mode = ndrMode,
                output = output,
                exposureStops = options.exposureStops
            )
        } else {
            RawAcescgTenBitPacker.packP010420Direct(
                acescg = acescg,
                width = meta.width,
                height = meta.height,
                variant = options.variant,
                output = output,
                exposureStops = options.exposureStops
            )
        }
    }

    private fun logRawGpuSampledParity(evidence: RawDevelopSampledParityEvidence) {
        NeoLogger.i(
            TAG,
            "RAW->LOG bounded GPU parity: sampledPixels=${evidence.sampledPixels} " +
                "finiteGpuSamples=${evidence.finiteGpuSamples} " +
                "meanAbs=${evidence.meanAbsoluteCodeDifference} " +
                "maxAbs=${evidence.maximumAbsoluteCodeDifference} " +
                "(tol mean<=$GPU_PARITY_MEAN_TOLERANCE max<=$GPU_PARITY_MAX_TOLERANCE) -> " +
                if (evidence.accepted) "GPU ACCEPTED" else "REJECTED"
        )
    }

    private fun logRawGpuP010Parity(evidence: RawDevelopP010ParityEvidence) {
        NeoLogger.i(
            TAG,
            "RAW GPU final-P010 parity: sampledComponents=${evidence.sampledComponents} " +
                "meanAbs=${evidence.meanAbsoluteCodeDifference} " +
                "maxAbs=${evidence.maximumAbsoluteCodeDifference} " +
                "(tol mean<=$GPU_P010_PARITY_MEAN_TOLERANCE " +
                "max<=$GPU_P010_PARITY_MAX_TOLERANCE) -> " +
                if (evidence.accepted) "GPU ACCEPTED" else "REJECTED"
        )
    }

    private fun saturatedAdd(a: Long, b: Long): Long =
        if (b <= 0L) a else if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

    private fun requireCpuDevelopCapacity(meta: RawDevelopMetadata, options: Options) {
        val requiredBytes = RawDevelopMemoryPolicy.cpuDirectPackPeakBytes(
            width = meta.width,
            height = meta.height,
            format = options.format.memoryReferenceFormat
        )
        val availableBytes = RawDevelopMemoryPolicy.availableJavaHeapBytes()
        if (requiredBytes > availableBytes) {
            throw IllegalStateException(
                "Insufficient memory for ${meta.width}x${meta.height} RAW develop: " +
                    "requires about ${requiredBytes / (1024 * 1024)} MiB, " +
                    "available ${availableBytes / (1024 * 1024)} MiB"
            )
        }
    }

    private companion object {
        private const val TAG = "RawToNeoLogConvert"

        // GPU-vs-CPU develop parity tolerances on 10-bit codes (0..1023). Tuned to accept demosaic-
        // algorithm differences (GPU edge-directed vs CPU bilinear) but reject a broken transform /
        // wrong colour space (which blows both far past tolerance → CPU fallback).
        const val GPU_PARITY_MEAN_TOLERANCE = 8.0
        const val GPU_PARITY_MAX_TOLERANCE = 96
        const val GPU_P010_PARITY_MEAN_TOLERANCE = 2.0
        const val GPU_P010_PARITY_MAX_TOLERANCE = 16
    }
}
