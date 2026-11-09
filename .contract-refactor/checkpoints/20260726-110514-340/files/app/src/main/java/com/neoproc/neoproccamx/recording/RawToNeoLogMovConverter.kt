package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.pipeline.RawExposureNormalizationPolicy
import com.neoproc.neoproccamx.pipeline.RawNeoLogDevelop

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

    /** Output container flavour. Both are uncompressed 10-bit; MASTER_444 is bit-exact to the develop. */
    enum class OutputFormat(val movMime: String, val packerFormat: RawTenBitPacker.Format) {
        /** 4:4:4 10-bit RGB master — most faithful, no chroma subsampling ('r210'). */
        MASTER_444("video/r210", RawTenBitPacker.Format.R210_444),

        /** v210 4:2:2 10-bit — universally decodable in Resolve/Premiere/FCP, ~⅓ smaller. */
        COMPAT_V210("video/v210", RawTenBitPacker.Format.V210_422)
    }

    data class Options(
        val variant: NeoLogCurve.Variant = NeoLogCurve.Variant.BASE,
        val exposureStops: Float = RawExposureNormalizationPolicy.DEFAULT_EXPOSURE_STOPS,
        val format: OutputFormat = OutputFormat.MASTER_444
    )

    data class ConvertResult(val framesConverted: Int, val bytesWritten: Long)

    /** Develop + pack ONE frame's raw samples into a 10-bit MOV sample payload (CPU). Pure + testable. */
    fun developAndPack(rawSamples: IntArray, meta: RawDevelopMetadata, options: Options): ByteArray {
        val acescg = RawNeoLogDevelop.developToAcescg(rawSamples, meta)
        return RawAcescgTenBitPacker.pack(
            acescg = acescg,
            width = meta.width,
            height = meta.height,
            variant = options.variant,
            exposureStops = options.exposureStops,
            outputBitDepth = 10,
            format = options.format.packerFormat
        )
    }

    // ── GPU develop (demosaic on GPU, NeoLog finish on CPU) + first-frame parity gate ────────────────
    // GPU and CPU differ ONLY in the demosaic (GPU edge-directed vs CPU bilinear), so we validate the GPU
    // path once on the clip's first frame against the CPU reference; if it deviates (or the accelerator is
    // unavailable / a dispatch fails) we develop the whole clip on the CPU (no-downgrade). Per-frame we
    // still fall back to CPU on any transient GPU failure.

    /** GPU demosaic → CPU NeoLog finish → pack; null when GPU is unavailable or the dispatch fails. */
    private fun developAndPackGpu(rawSamples: IntArray, meta: RawDevelopMetadata, options: Options): ByteArray? {
        val acescg = RawDevelopGpuAccelerator.developToAcescg(rawSamples, meta, meta.width, meta.height)
            ?: return null
        return RawAcescgTenBitPacker.pack(
            acescg = acescg,
            width = meta.width,
            height = meta.height,
            variant = options.variant,
            exposureStops = options.exposureStops,
            outputBitDepth = 10,
            format = options.format.packerFormat
        )
    }

    /** Develop one frame on the chosen path; GPU falls back to CPU on any per-frame failure. */
    private fun developAndPackResolved(
        rawSamples: IntArray,
        meta: RawDevelopMetadata,
        options: Options,
        useGpu: Boolean
    ): ByteArray {
        if (useGpu) developAndPackGpu(rawSamples, meta, options)?.let { return it }
        return developAndPack(rawSamples, meta, options)
    }

    /**
     * Develop + pack ONE frame on the concurrent hybrid path ([convertStreamParallel]). The clip's
     * GPU-vs-CPU decision ([decideGpu]) is resolved once on the first frame (parity gate) and is UNIFORM
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
        gpuDecision: java.util.concurrent.atomic.AtomicInteger
    ): ByteArray {
        if (gpuPermit != null && decideGpu(gpuDecision, rawSamples, meta, options)) {
            gpuPermit.acquire()
            val acescg = try {
                RawDevelopGpuAccelerator.developToAcescg(rawSamples, meta, meta.width, meta.height)
            } finally {
                gpuPermit.release()
            }
            if (acescg != null) {
                return RawAcescgTenBitPacker.pack(
                    acescg = acescg,
                    width = meta.width,
                    height = meta.height,
                    variant = options.variant,
                    exposureStops = options.exposureStops,
                    outputBitDepth = 10,
                    format = options.format.packerFormat
                )
            }
        }
        return developAndPack(rawSamples, meta, options)
    }

    /**
     * Decide the develop path for a whole clip from a representative frame: GPU only if the accelerator is
     * available AND its develop is parity-close to the CPU reference on this frame. Cheap when the GPU is
     * unavailable (returns false before any CPU reference develop).
     */
    private fun resolveGpuPath(sampleFrame: IntArray, meta: RawDevelopMetadata, options: Options): Boolean {
        if (!RawDevelopGpuAccelerator.isAvailable()) {
            NeoLogger.i(TAG, "RAW->LOG GPU demosaic UNAVAILABLE (accelerator off or Vulkan bridge unsupported here) -> CPU bilinear develop")
            return false
        }
        val w = meta.width
        val h = meta.height
        val parityPeak = RawDevelopMemoryPolicy.gpuFullParityPeakBytes(w, h, options.format.packerFormat)
        if (!RawDevelopMemoryPolicy.fitsNow(parityPeak)) {
            NeoLogger.i(
                TAG,
                "RAW->LOG GPU demosaic parity skipped: needs ~${parityPeak / (1024 * 1024)} MiB " +
                    "Java heap, available ~${RawDevelopMemoryPolicy.availableJavaHeapBytes() / (1024 * 1024)} MiB -> CPU reference develop"
            )
            return false
        }
        val gpuAcescg = RawDevelopGpuAccelerator.developToAcescg(sampleFrame, meta, w, h)
        if (gpuAcescg == null) {
            NeoLogger.i(TAG, "RAW->LOG GPU demosaic dispatch/readback FAILED on first frame -> CPU bilinear develop")
            return false
        }
        val gpuFrame = RawNeoLogDevelop.finishFromAcescg(gpuAcescg, w, h, options.variant, options.exposureStops, 10)
        val cpuFrame = RawNeoLogDevelop.develop(sampleFrame, meta, options.variant, options.exposureStops, 10)
        val accepted = developsMatchWithinTolerance(gpuFrame.rgb, cpuFrame.rgb)
        logRawGpuParity(gpuFrame.rgb, cpuFrame.rgb, accepted)
        return accepted
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
        var index = 0
        var bytes = 0L
        while (frames.hasNext()) {
            val sample = developAndPack(frames.next(), meta, options)
            sink(sample, index)
            bytes += sample.size.toLong()
            index++
        }
        return ConvertResult(framesConverted = index, bytesWritten = bytes)
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
    ): ConvertResult {
        // Read + decide on the first frame. The clip's GPU-vs-CPU decision is resolved once (parity gate);
        // unavailable / failed parity → CPU (no-downgrade). Deciding eagerly (vs the old lazy first-iteration
        // decision — same frame, same result) lets the GPU path pipeline frame N+1's demosaic with frame N's
        // CPU finish (see [convertStreamGpuOverlapped]); the CPU path stays a plain sequential loop.
        val firstBytes = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.readRawFrameBytes(input)
            ?: return ConvertResult(framesConverted = 0, bytesWritten = 0L)
        val firstSamples = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.decodeFrame(firstBytes).samples
        val useGpu = resolveGpuPath(firstSamples, meta, options)
        // Overlap only when GPU is the clip's path AND the extra resident ACEScg plane fits the heap budget;
        // otherwise fall back to the byte-identical sequential develop (no-downgrade, never OOM).
        if (useGpu && RawDevelopPipelineOverlapPolicy.shouldOverlap(meta.width, meta.height)) {
            return convertStreamGpuOverlapped(firstSamples, input, meta, options, sink)
        }
        // Sequential develop (CPU, or GPU without overlap) — byte-identical to the prior behaviour: develop
        // the already-decoded first frame, then stream the rest with the same uniform per-clip decision.
        var index = 0
        var bytes = 0L
        run {
            val sample = developAndPackResolved(firstSamples, meta, options, useGpu)
            sink(sample, index)
            bytes += sample.size.toLong()
            index++
        }
        while (true) {
            val frame = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.readFrame(input) ?: break
            val sample = developAndPackResolved(frame.samples, meta, options, useGpu)
            sink(sample, index)
            bytes += sample.size.toLong()
            index++
        }
        return ConvertResult(framesConverted = index, bytesWritten = bytes)
    }

    // ── Single-worker CPU+GPU overlap (flagship full-sensor / 4K RAW) ─────────────────────────────────
    // Stage-2 work item for [convertStreamGpuOverlapped], handed from the GPU-demosaic producer thread to
    // the CPU-finish consumer thread in strict frame order.
    private sealed interface RawStage2Item

    /** GPU produced the LINEAR ACEScg plane; stage 2 runs the CPU finish (elite detail + NeoLog encode) + pack. */
    private class RawStage2Acescg(val plane: FloatArray) : RawStage2Item

    /** Per-frame GPU fallback already produced the final packed 10-bit sample on the CPU; stage 2 just sinks it. */
    private class RawStage2Packed(val sample: ByteArray) : RawStage2Item

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
     * planes resident (one produced while one is consumed), the minimum for overlap. [firstSamples] is the
     * already-decoded first frame's mosaic (read by [convertStream] for the parity gate) so it is not re-read.
     */
    private fun convertStreamGpuOverlapped(
        firstSamples: IntArray,
        input: java.io.InputStream,
        meta: RawDevelopMetadata,
        options: Options,
        sink: (sample: ByteArray, index: Int) -> Unit
    ): ConvertResult {
        val w = meta.width
        val h = meta.height
        var index = 0
        var bytes = 0L
        // Only the single producer thread touches this; the first frame is already decoded, later frames are
        // streamed + decoded on that same thread — so its GPU demosaic overlaps the consumer's CPU finish.
        var pendingSamples: IntArray? = firstSamples
        runInOrderTwoStage<RawStage2Item>(
            stage1 = stage1@{
                val samples = pendingSamples ?: run {
                    val frameBytes = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.readRawFrameBytes(input)
                        ?: return@stage1 null
                    com.neoproc.neoproccamx.pipeline.NprawFrameCodec.decodeFrame(frameBytes).samples
                }
                pendingSamples = null
                // Uniform per-clip GPU demosaic; a per-frame dispatch failure develops that frame on the CPU.
                val plane = RawDevelopGpuAccelerator.developToAcescg(samples, meta, w, h)
                if (plane != null) RawStage2Acescg(plane) else RawStage2Packed(developAndPack(samples, meta, options))
            },
            stage2 = { item ->
                val sample = when (item) {
                    is RawStage2Acescg -> RawAcescgTenBitPacker.pack(
                        acescg = item.plane,
                        width = w,
                        height = h,
                        variant = options.variant,
                        exposureStops = options.exposureStops,
                        outputBitDepth = 10,
                        format = options.format.packerFormat
                    )
                    is RawStage2Packed -> item.sample
                }
                sink(sample, index)
                bytes += sample.size.toLong()
                index++
            }
        )
        return ConvertResult(framesConverted = index, bytesWritten = bytes)
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
    ): ConvertResult {
        val workers = parallelism.coerceAtLeast(1)
        if (workers == 1) {
            // Single worker: the sequential path is already optimal and avoids all thread overhead.
            return convertStream(input, meta, options) { sample, index ->
                sink(sample, index)
                onFrame?.invoke(index + 1)
            }
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(workers) { runnable ->
            Thread(runnable, "raw-develop").apply { isDaemon = true }
        }
        val inFlight = ArrayDeque<java.util.concurrent.Future<ByteArray>>()
        val maxInFlight = workers + 2
        var bytes = 0L
        var writeIndex = 0
        // GPU-vs-CPU is decided ONCE for the clip (thread-safe) on the first frame a worker decodes.
        // -1 = unresolved, 0 = CPU, 1 = GPU.
        val gpuDecision = java.util.concurrent.atomic.AtomicInteger(-1)
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
            if (RawDevelopGpuAccelerator.isAvailable()) java.util.concurrent.Semaphore(1) else null
        try {
            while (true) {
                val frameBytes = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.readRawFrameBytes(input)
                    ?: break
                inFlight.addLast(
                    pool.submit(
                        java.util.concurrent.Callable {
                            val decoded = com.neoproc.neoproccamx.pipeline.NprawFrameCodec.decodeFrame(frameBytes)
                            developAndPackHybrid(decoded.samples, meta, options, gpuPermit, gpuDecision)
                        }
                    )
                )
                if (inFlight.size >= maxInFlight) {
                    val sample = awaitNext(inFlight.removeFirst())
                    sink(sample, writeIndex)
                    bytes += sample.size.toLong()
                    writeIndex++
                    onFrame?.invoke(writeIndex)
                }
            }
            while (inFlight.isNotEmpty()) {
                val sample = awaitNext(inFlight.removeFirst())
                sink(sample, writeIndex)
                bytes += sample.size.toLong()
                writeIndex++
                onFrame?.invoke(writeIndex)
            }
        } catch (t: Throwable) {
            inFlight.forEach { it.cancel(true) }
            throw t
        } finally {
            pool.shutdownNow()
        }
        return ConvertResult(framesConverted = writeIndex, bytesWritten = bytes)
    }

    /** Await one develop future, unwrapping ExecutionException so the caller sees the real failure. */
    private fun awaitNext(future: java.util.concurrent.Future<ByteArray>): ByteArray =
        try {
            future.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }

    /** Resolve the clip's GPU-vs-CPU decision exactly once across worker threads (first frame decides). */
    private fun decideGpu(
        decision: java.util.concurrent.atomic.AtomicInteger,
        sampleFrame: IntArray,
        meta: RawDevelopMetadata,
        options: Options
    ): Boolean {
        val current = decision.get()
        if (current >= 0) return current == 1
        synchronized(decision) {
            if (decision.get() < 0) {
                decision.set(if (resolveGpuPath(sampleFrame, meta, options)) 1 else 0)
            }
            return decision.get() == 1
        }
    }

    /** Expected packed byte size per frame for [options] — for muxer buffer sizing / preflight. */
    fun sampleSize(meta: RawDevelopMetadata, options: Options): Int =
        RawTenBitPacker.payloadSize(options.format.packerFormat, meta.width, meta.height)

    private companion object {
        private const val TAG = "RawToNeoLogConvert"

        // GPU-vs-CPU develop parity tolerances on 10-bit codes (0..1023). Tuned to accept demosaic-
        // algorithm differences (GPU edge-directed vs CPU bilinear) but reject a broken transform /
        // wrong colour space (which blows both far past tolerance → CPU fallback).
        const val GPU_PARITY_MEAN_TOLERANCE = 8.0
        const val GPU_PARITY_MAX_TOLERANCE = 96
    }
}
