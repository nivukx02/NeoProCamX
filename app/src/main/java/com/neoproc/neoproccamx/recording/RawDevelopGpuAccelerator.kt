package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NdrToneOperator
import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.niv99.Niv99OfflineConversionPlan
import com.neoproc.neoproccamx.niv99.Niv99OfflinePixelDomain
import com.neoproc.neoproccamx.niv99.Niv99RawConversionOptions
import com.neoproc.neoproccamx.niv99.Niv99RawFrameEvidence
import com.neoproc.neoproccamx.pipeline.RawDevelopEliteDetailFlag
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.pipeline.RawExposureNormalizationPolicy
import com.neoproc.neoproccamx.rendering.VulkanBridge
import com.neoproc.neoproccamx.rendering.RawDevelopMappedNiv99Config
import com.neoproc.neoproccamx.rendering.XYZ_D50_TO_ACESCG
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.locks.LockSupport

internal data class RawDevelopGpuDirectResult<T : Any>(
    val value: T,
    val cpuNanos: Long,
    val gpuNanos: Long,
    val copyWaitNanos: Long,
    val niv99Evidence: Niv99RawFrameEvidence? = null
)

internal data class RawDevelopP010TransferPlan(
    val deliveryMode: Int,
    val detailEnabled: Boolean,
    val packet: FloatArray
)

/** Single Kotlin -> GLSL colour packet; no native-side profile constants can silently drift. */
internal object RawDevelopP010TransferPolicy {
    const val FLOAT_COUNT = 20

    fun create(
        variant: NeoLogCurve.Variant,
        deliveryProfile: RawToNeoLogMovConverter.DeliveryProfile,
        exposureStops: Float
    ): RawDevelopP010TransferPlan {
        require(exposureStops.isFinite()) { "RAW exposure stops must be finite" }
        val log = NeoLogCurve.v5Parameters(variant)
        val ndr = when (deliveryProfile) {
            RawToNeoLogMovConverter.DeliveryProfile.NEOLOG -> NdrToneOperator.ndr10Plus()
            RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS -> NdrToneOperator.ndr10Plus()
            RawToNeoLogMovConverter.DeliveryProfile.NIVUKX_VISION -> NdrToneOperator.nivukxVision()
        }
        val deliveryMode = when (deliveryProfile) {
            RawToNeoLogMovConverter.DeliveryProfile.NEOLOG -> 0
            RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS -> 1
            RawToNeoLogMovConverter.DeliveryProfile.NIVUKX_VISION -> 2
        }
        val exposure = RawExposureNormalizationPolicy.exposureScale(exposureStops)
        val sceneExposure = if (deliveryMode == 0) {
            exposure
        } else {
            (exposure.toDouble() / NdrToneOperator.SENSOR_DIFFUSE_SCENE).toFloat()
        }
        val packet = floatArrayOf(
            sceneExposure,
            log.toeCut,
            log.toeHeight,
            log.logDensity,
            log.logScale,
            log.c1,
            log.c3,
            log.c4,
            log.c5,
            log.xMax,
            ndr.signalGamma.toFloat(),
            ndr.toeWidth.toFloat(),
            ndr.toeAmount.toFloat(),
            ndr.peakNits.toFloat(),
            ndr.highlightDesat.toFloat(),
            ndr.shadowMidtoneLift.toFloat(),
            ndr.midtoneContrast.toFloat(),
            ndr.chromaBoost.toFloat(),
            ndr.skyChromaBonus.toFloat(),
            0f
        )
        check(packet.size == FLOAT_COUNT && packet.all { it.isFinite() }) {
            "RAW GPU P010 transfer packet is invalid"
        }
        return RawDevelopP010TransferPlan(
            deliveryMode = deliveryMode,
            detailEnabled = RawDevelopEliteDetailFlag.enabled,
            packet = packet
        )
    }
}

/**
 * RawDevelopGpuAccelerator — GPU (Vulkan compute) demosaic → sensor→ACEScg for the OFFLINE RAW→NeoLog
 * converter. It uploads a CPU sensor mosaic, runs the same `demosaic_vulkan.comp` used by the live
 * cinema-debayer (WB-before-demosaic + edge-directed green + CP11 sensor→XYZ→ACEScg), and reads the
 * FULL-RES ACEScg plane back to the CPU; the NeoLog encode + 10-bit pack finish on the CPU via
 * [com.neoproc.neoproccamx.pipeline.RawNeoLogDevelop.finishFromAcescg]. So the GPU path differs from the
 * full CPU develop ONLY in the demosaic — exactly what the converter's first-frame parity gate checks.
 *
 * GATING (no-downgrade): [developToAcescg] returns null — and the caller uses the full CPU develop —
 * unless ALL of these hold:
 *   1. [developGpuEnabled] is true (DEFAULT FALSE — device-gated until on-device parity-verified), and
 *   2. the `neoproc_vulkan` native library loaded, and
 *   3. a live [VulkanBridge] with the demosaic pipeline is [attach]ed, and
 *   4. the native dispatch + readback succeeds for this frame.
 * Any failure path returns null, so a device that cannot run it falls back to the byte-exact CPU develop.
 */
internal object RawDevelopGpuAccelerator {

    private val workspace = RawDevelopGpuWorkspace()
    private val directInputWorkspace = RawDevelopGpuInputWorkspace()

    /**
     * Master gate, DEFAULT ON (opt-in hybrid). The GPU lane only ever REPLACES the demosaic; it is made
     * safe without prior device certification by two independent guards that keep it no-downgrade:
     *   • the converter's first-frame parity gate rejects the GPU develop for the whole clip unless it
     *     matches the CPU reference within tolerance (a broken transform / wrong colour space → CPU), and
     *   • [developToAcescg] returns null on any per-frame dispatch/readback failure → that frame develops
     *     on the byte-exact CPU path.
     * So on a device where the Vulkan demosaic is unavailable or wrong, RAW conversion transparently runs
     * the proven multi-core CPU develop. Flip OFF via [setDevelopGpuEnabled] to force pure-CPU develop.
     */
    @Volatile
    private var developGpuEnabled: Boolean = true

    @Volatile
    private var bridge: VulkanBridge? = null

    fun setDevelopGpuEnabled(enabled: Boolean) {
        developGpuEnabled = enabled
    }

    /** Attach the process-wide Vulkan bridge (whose demosaic pipeline is already built) for offline use. */
    fun attach(bridge: VulkanBridge?) {
        this.bridge = bridge
        if (bridge == null) workspace.clear()
    }

    /** True only when the GPU develop path is enabled AND a supported bridge is attached. */
    fun isAvailable(): Boolean {
        if (!developGpuEnabled) return false
        val b = bridge ?: return false
        return runCatching { b.isSupported() }.getOrDefault(false)
    }

    /**
     * Waits briefly for the asynchronously-built RAW conversion pipeline before frame zero chooses the
     * clip-wide backend. Without this admission gate, a conversion started immediately after bridge
     * creation can permanently select the CPU fallback a few milliseconds before Vulkan publishes its
     * completed demosaic, NIV99, detail, colour-delivery, and P010 pipelines.
     *
     * The wait is bounded and interruption-safe. Unsupported devices, a detached/closed bridge, pipeline
     * compilation failure, timeout, or thread cancellation all return false so callers retain the exact
     * full-quality CPU path; no quality/parity gate is bypassed.
     */
    fun awaitRawConversionPipelineReady(
        timeoutNanos: Long = RAW_CONVERSION_PIPELINE_READY_TIMEOUT_NANOS
    ): Boolean {
        if (timeoutNanos < 0L || !developGpuEnabled) return false
        val activeBridge = bridge ?: return false
        if (!runCatching { activeBridge.isSupported() }.getOrDefault(false)) return false

        val startedNanos = System.nanoTime()
        while (true) {
            if (bridge !== activeBridge || Thread.currentThread().isInterrupted) return false
            if (runCatching { activeBridge.isRawDemosaicPipelineReady() }.getOrDefault(false)) {
                return true
            }
            val elapsedNanos = (System.nanoTime() - startedNanos).coerceAtLeast(0L)
            val remainingNanos = timeoutNanos - elapsedNanos
            if (remainingNanos <= 0L) return false
            LockSupport.parkNanos(minOf(RAW_CONVERSION_PIPELINE_READY_POLL_NANOS, remainingNanos))
        }
    }

    /**
     * Demosaic [rawSamples] (row-major sensor mosaic, 0..whiteLevel) to a LINEAR ACEScg interleaved RGB
     * FloatArray of size width*height*3 (pre-exposure). Returns null when the GPU path is unavailable or
     * the native dispatch/readback fails — the caller then develops the frame on the CPU (no-downgrade).
     */
    fun developToAcescg(
        rawSamples: IntArray,
        meta: RawDevelopMetadata,
        width: Int,
        height: Int
    ): FloatArray? {
        if (!isAvailable() || width <= 0 || height <= 0) return null
        val pixelCount = try {
            Math.multiplyExact(width, height)
        } catch (_: ArithmeticException) {
            return null
        }
        if (rawSamples.size < pixelCount) return null
        // Account for the simultaneously-live input IntArray, returned FloatArray, retained JNI
        // mosaic/output buffers, native RGBA16F readback, detail scratch, and safety reserve. This is
        // deliberately conservative: failing the admission check selects the pixel-identical CPU path.
        val requiredBytes = RawDevelopMemoryPolicy.gpuDevelopHostPeakBytes(width, height)
        if (!RawDevelopMemoryPolicy.fitsNow(requiredBytes)) return null
        val activeBridge = bridge ?: return null
        return try {
            workspace.use(width, height) { buffers ->
                // Sensor mosaic → tightly-packed little-endian uint16 direct buffer (R16 upload source).
                // Both direct buffers are retained for this geometry. The workspace lock covers upload,
                // dispatch and copy-out, so preview/offline callers can never overwrite an in-flight frame.
                packRawSamples(activeBridge, rawSamples, buffers.mosaic, buffers.pixelCount)
                buffers.out.clear()
                val ok = activeBridge.developRawToAcescg(
                    mosaic = buffers.mosaic,
                    rowStrideBytes = Math.multiplyExact(width, Short.SIZE_BYTES),
                    width = width,
                    height = height,
                    bayerPattern = meta.bayerPatternValue,
                    whiteLevel = meta.whiteLevel,
                    blackLevel = meta.blackLevel,
                    whiteBalanceR = meta.whiteBalanceR,
                    whiteBalanceGr = meta.whiteBalanceGr,
                    whiteBalanceGb = meta.whiteBalanceGb,
                    whiteBalanceB = meta.whiteBalanceB,
                    sensorToXyz = meta.sensorToXyz,
                    // Same XYZ(D50)→ACEScg matrix the CPU develop uses, so GPU vs CPU differ ONLY in demosaic.
                    xyzToAcescg = XYZ_D50_TO_ACESCG,
                    out = buffers.out,
                    blackLevelRggb = meta.blackLevelRggb,
                    lensShadingGridRggb = meta.lensShadingGridRggb,
                    lensShadingCorrectionEnabled = meta.lensShadingCorrectionEnabled
                )
                if (!ok) return@use null
                // A new FloatArray is intentional: downstream CPU finish stages can overlap the next GPU
                // dispatch without aliasing the persistent native output buffer.
                val floats = FloatArray(Math.multiplyExact(buffers.pixelCount, 3))
                buffers.out.order(ByteOrder.nativeOrder()).asFloatBuffer().get(floats)
                floats
            }
        } catch (_: OutOfMemoryError) {
            // Direct-buffer allocation can fail outside the Java heap accounting. Release retained
            // references and let the converter take its full-quality CPU reference path.
            workspace.clear()
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Runs the same Vulkan RAW develop while the result stays in the reusable direct buffer. [consume]
     * executes under the workspace ownership lock and must consume the frame before returning. This is
     * the production 4K path: it removes the 144 MiB FloatArray mirror and lets the caller pack directly
     * into encoder input storage without changing the shader, colour matrix, precision, or pixels.
     */
    fun <T : Any> withDevelopedAcescgDirect(
        rawSamples: IntArray,
        meta: RawDevelopMetadata,
        width: Int,
        height: Int,
        consume: (FloatBuffer) -> T
    ): RawDevelopGpuDirectResult<T>? {
        if (!isAvailable() || width <= 0 || height <= 0) return null
        val pixelCount = try {
            Math.multiplyExact(width, height)
        } catch (_: ArithmeticException) {
            return null
        }
        if (rawSamples.size < pixelCount) return null
        if (!RawDevelopMemoryPolicy.fitsNow(
                RawDevelopMemoryPolicy.gpuMappedCallbackJavaHeapBytes(width, height)
            )
        ) {
            return null
        }
        val activeBridge = bridge ?: return null
        return try {
            directInputWorkspace.use(width, height) { input ->
                val inputStarted = System.nanoTime()
                packRawSamples(activeBridge, rawSamples, input.mosaic, input.pixelCount)
                val inputNanos = (System.nanoTime() - inputStarted).coerceAtLeast(0L)
                val mapped = runCatching {
                    activeBridge.withDevelopedRawToAcescgMapped(
                        mosaic = input.mosaic,
                        rowStrideBytes = Math.multiplyExact(width, Short.SIZE_BYTES),
                        width = width,
                        height = height,
                        bayerPattern = meta.bayerPatternValue,
                        whiteLevel = meta.whiteLevel,
                        blackLevel = meta.blackLevel,
                        whiteBalanceR = meta.whiteBalanceR,
                        whiteBalanceGr = meta.whiteBalanceGr,
                        whiteBalanceGb = meta.whiteBalanceGb,
                        whiteBalanceB = meta.whiteBalanceB,
                        sensorToXyz = meta.sensorToXyz,
                        xyzToAcescg = XYZ_D50_TO_ACESCG,
                        blackLevelRggb = meta.blackLevelRggb,
                        lensShadingGridRggb = meta.lensShadingGridRggb,
                        lensShadingCorrectionEnabled = meta.lensShadingCorrectionEnabled,
                        consume = consume
                    )
                }.getOrNull() ?: return@use null
                RawDevelopGpuDirectResult(
                    value = mapped.value,
                    cpuNanos = saturatedAdd(inputNanos, mapped.timing.hostExpandNanos),
                    gpuNanos = mapped.timing.gpuNanos,
                    copyWaitNanos = mapped.timing.copyWaitNanos
                )
            }
        } catch (_: OutOfMemoryError) {
            directInputWorkspace.clear()
            null
        }
    }

    /**
     * Converts RAW directly into mapped encoder-ready P010 with no full-frame CPU colour/packing pass.
     * Null preserves the existing full-quality fallback on unsupported devices or any native failure.
     */
    fun <T : Any> withDevelopedP010Direct(
        rawSamples: IntArray,
        meta: RawDevelopMetadata,
        width: Int,
        height: Int,
        variant: NeoLogCurve.Variant,
        deliveryProfile: RawToNeoLogMovConverter.DeliveryProfile,
        exposureStops: Float,
        niv99Plan: Niv99OfflineConversionPlan? = null,
        niv99Options: Niv99RawConversionOptions = Niv99RawConversionOptions.HIGH_QUALITY,
        frameNumber: Long = 0L,
        sensorTimestampNs: Long = 0L,
        consume: (ByteBuffer) -> T
    ): RawDevelopGpuDirectResult<T>? {
        if (!isAvailable() || width <= 0 || height <= 0 ||
            width % 2 != 0 || height % 2 != 0) {
            return null
        }
        val pixelCount = try {
            Math.multiplyExact(width, height)
        } catch (_: ArithmeticException) {
            return null
        }
        if (rawSamples.size < pixelCount || !RawDevelopMemoryPolicy.fitsNow(
                RawDevelopMemoryPolicy.gpuP010MappedCallbackJavaHeapBytes(width, height)
            )
        ) {
            return null
        }
        val activeBridge = bridge ?: return null
        val transferPlan = runCatching {
            RawDevelopP010TransferPolicy.create(variant, deliveryProfile, exposureStops)
        }.getOrNull() ?: return null
        val niv99Config = niv99Plan?.let { plan ->
            if (!plan.enabled || plan.pixelDomain != Niv99OfflinePixelDomain.SENSOR_CFA ||
                frameNumber < 0L) return null
            RawDevelopMappedNiv99Config(
                frameNumber = frameNumber,
                sensorTimestampNs = sensorTimestampNs,
                fps = meta.fps.coerceAtLeast(1),
                noiseS = meta.noiseS.takeIf { it.isFinite() && it >= 0f } ?: 0f,
                noiseO = meta.noiseO.takeIf { it.isFinite() && it >= 0f } ?: 0f,
                totalGain = (meta.analogGain * meta.digitalGain)
                    .takeIf { it.isFinite() && it > 0f }
                    ?.coerceIn(1f, 64f)
                    ?: 1f,
                dynamicRange = (meta.whiteLevel - meta.blackLevel).coerceAtLeast(1).toFloat(),
                spatialStrength = niv99Options.spatialStrength,
                defectSigmaThreshold = niv99Options.defectSigmaThreshold,
                spatialSigmaThreshold = niv99Options.spatialSigmaThreshold,
                temporalStrength = niv99Options.temporalStrength,
                temporalSigmaThreshold = niv99Options.temporalSigmaThreshold,
                sceneCutThreshold = niv99Options.sceneCutThreshold
            )
        }
        return try {
            directInputWorkspace.use(width, height) { input ->
                val inputStarted = System.nanoTime()
                packRawSamples(activeBridge, rawSamples, input.mosaic, input.pixelCount)
                val inputNanos = (System.nanoTime() - inputStarted).coerceAtLeast(0L)
                val mapped = runCatching {
                    activeBridge.withDevelopedRawToP010Mapped(
                        mosaic = input.mosaic,
                        rowStrideBytes = Math.multiplyExact(width, Short.SIZE_BYTES),
                        width = width,
                        height = height,
                        bayerPattern = meta.bayerPatternValue,
                        whiteLevel = meta.whiteLevel,
                        blackLevel = meta.blackLevel,
                        whiteBalanceR = meta.whiteBalanceR,
                        whiteBalanceGr = meta.whiteBalanceGr,
                        whiteBalanceGb = meta.whiteBalanceGb,
                        whiteBalanceB = meta.whiteBalanceB,
                        sensorToXyz = meta.sensorToXyz,
                        xyzToAcescg = XYZ_D50_TO_ACESCG,
                        deliveryMode = transferPlan.deliveryMode,
                        detailEnabled = transferPlan.detailEnabled,
                        transferPacket = transferPlan.packet,
                        niv99 = niv99Config,
                        blackLevelRggb = meta.blackLevelRggb,
                        lensShadingGridRggb = meta.lensShadingGridRggb,
                        lensShadingCorrectionEnabled = meta.lensShadingCorrectionEnabled,
                        consume = consume
                    )
                }.getOrNull() ?: return@use null
                val nativeNiv99 = mapped.timing.niv99
                if (niv99Plan != null && nativeNiv99 == null) return@use null
                RawDevelopGpuDirectResult(
                    value = mapped.value,
                    cpuNanos = saturatedAdd(inputNanos, mapped.timing.hostExpandNanos),
                    gpuNanos = mapped.timing.gpuNanos,
                    copyWaitNanos = mapped.timing.copyWaitNanos,
                    niv99Evidence = if (nativeNiv99 != null && niv99Plan != null) {
                        Niv99RawFrameEvidence(
                            route = niv99Plan.route,
                            sessionGeneration = 0L,
                            frameNumber = frameNumber,
                            sensorTimestampNs = sensorTimestampNs,
                            fullFrameSamples = pixelCount,
                            spatiallyFilteredSamples = nativeNiv99.spatiallyFilteredSamples,
                            temporallyFusedSamples = nativeNiv99.temporallyFusedSamples,
                            correctedDefects = nativeNiv99.correctedDefects,
                            historyUsed = nativeNiv99.historyUsed,
                            historyReset = nativeNiv99.historyReset,
                            sourceSamplesPreserved = true,
                            capturedWhiteBalancePreserved =
                                niv99Plan.capturedWhiteBalanceAuthoritative,
                            processingTimeNs = nativeNiv99.processingNanos
                        )
                    } else {
                        null
                    }
                )
            }
        } catch (_: OutOfMemoryError) {
            directInputWorkspace.clear()
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun saturatedAdd(a: Long, b: Long): Long =
        if (b <= 0L) a else if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

    private const val RAW_CONVERSION_PIPELINE_READY_TIMEOUT_NANOS = 8_000_000_000L
    private const val RAW_CONVERSION_PIPELINE_READY_POLL_NANOS = 2_000_000L

    private fun packRawSamples(
        activeBridge: VulkanBridge,
        source: IntArray,
        destination: ByteBuffer,
        sampleCount: Int
    ) {
        destination.clear()
        if (activeBridge.packRawSamplesToU16(source, destination, sampleCount)) return
        val mosaicShorts = destination.order(ByteOrder.nativeOrder()).asShortBuffer()
        for (i in 0 until sampleCount) {
            mosaicShorts.put(i, source[i].coerceIn(0, 0xFFFF).toShort())
        }
    }
}

/** Retains only the uint16 upload mosaic; the 144 MiB float result is native-mapped, never duplicated. */
internal class RawDevelopGpuInputWorkspace {
    internal data class Input(
        val width: Int,
        val height: Int,
        val pixelCount: Int,
        val mosaic: ByteBuffer
    )

    private var input: Input? = null

    @Volatile
    internal var allocationCount: Int = 0
        private set

    fun <T> use(width: Int, height: Int, operation: (Input) -> T): T = synchronized(this) {
        require(width > 0 && height > 0) { "RAW workspace geometry must be positive" }
        val pixels = Math.multiplyExact(width, height)
        val current = input
        val active = if (current != null && current.width == width && current.height == height) {
            current
        } else {
            Input(
                width = width,
                height = height,
                pixelCount = pixels,
                mosaic = ByteBuffer.allocateDirect(Math.multiplyExact(pixels, Short.SIZE_BYTES))
                    .order(ByteOrder.nativeOrder())
            ).also {
                input = it
                allocationCount += 1
            }
        }
        operation(active)
    }

    fun clear() = synchronized(this) {
        input = null
    }
}

/**
 * One bounded pair of direct JNI buffers for RAW offline develop. Reallocation happens only when the
 * sensor geometry changes; [use] serializes access because the Vulkan bridge owns one RAW output image.
 */
internal class RawDevelopGpuWorkspace {
    internal data class Buffers(
        val width: Int,
        val height: Int,
        val pixelCount: Int,
        val mosaic: ByteBuffer,
        val out: ByteBuffer
    )

    private var buffers: Buffers? = null

    @Volatile
    internal var allocationCount: Int = 0
        private set

    fun <T> use(width: Int, height: Int, operation: (Buffers) -> T): T = synchronized(this) {
        require(width > 0 && height > 0) { "RAW workspace geometry must be positive" }
        val pixels = Math.multiplyExact(width, height)
        val current = buffers
        val active = if (current != null && current.width == width && current.height == height) {
            current
        } else {
            Buffers(
                width = width,
                height = height,
                pixelCount = pixels,
                mosaic = ByteBuffer.allocateDirect(Math.multiplyExact(pixels, Short.SIZE_BYTES))
                    .order(ByteOrder.nativeOrder()),
                out = ByteBuffer.allocateDirect(
                    Math.multiplyExact(Math.multiplyExact(pixels, 3), Float.SIZE_BYTES)
                ).order(ByteOrder.nativeOrder())
            ).also {
                buffers = it
                allocationCount += 1
            }
        }
        operation(active)
    }

    fun clear() = synchronized(this) {
        buffers = null
    }
}
