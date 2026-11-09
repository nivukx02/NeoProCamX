package com.neoproc.neoproccamx.recording

import android.graphics.ImageFormat
import android.media.Image
import android.os.Build
import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import com.neoproc.neoproccamx.rendering.GpuImageEnhancementUniforms
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

object Npv10FramePacker {
    private const val NEUTRAL_CHROMA_8_BIT = 128
    private val nativeLibraryAvailable: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            System.loadLibrary("neoproc_vulkan")
            true
        }.getOrDefault(false)
    }
    private val niv99NativeProcessorAvailable: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            nativeLibraryAvailable &&
            runCatching { nativePrepareNiv99P010Processor() }.getOrDefault(false)
    }

    enum class P010CopyPath {
        NATIVE_DIRECT,
        DIRECT_BUFFER,
        FAILED
    }

    data class P010CopyResult(
        val bytesCopied: Int,
        val path: P010CopyPath
    )

    data class P010PackedResult(
        val bytesCopied: Int,
        val path: P010CopyPath,
        val crc32: Int?
    )

    internal fun isNiv99P010NativeProcessingAvailable(): Boolean =
        niv99NativeProcessorAvailable

    /** Prewarms the shared row executor before Camera2 begins delivering owned P010 Images. */
    internal fun prepareP010PackingExecutor(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            nativeLibraryAvailable &&
            runCatching { nativePrepareP010PackingExecutor() }.getOrDefault(false)

    /** Touch each existing helper's bounded lossless scratch before accepting a 12MP image. */
    internal fun prepareP010LosslessChunkExecutor(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && nativeLibraryAvailable &&
            runCatching { nativePrepareP010LosslessChunkExecutor() }.getOrDefault(false)

    /** Native deterministic oracle; never called by the production frame hot path. */
    internal fun verifyNiv99P010BlockReuseParityForTest(): Long =
        if (!isNiv99P010NativeProcessingAvailable()) {
            NATIVE_CRC_FAILURE
        } else {
            runCatching { nativeVerifyNiv99P010BlockReuseParity() }
                .getOrDefault(NATIVE_CRC_FAILURE)
        }

    /** Repeatedly joins and recreates the fixed worker pool, returning its final live count. */
    internal fun exerciseNiv99P010ProcessorLifecycleForTest(cycles: Int): Int =
        if (cycles <= 0 || !isNiv99P010NativeProcessingAvailable()) {
            -1
        } else {
            runCatching { nativeExerciseNiv99P010ProcessorLifecycle(cycles) }.getOrDefault(-1)
        }

    /**
     * Fused ten-bit NIV99 spatial processing and packed-10 emission. There is deliberately no Java
     * or untreated-pixel fallback: callers requiring NIV99 must fail the recording when this returns
     * a negative value. The unsigned low 32 bits of a successful return are the packet CRC32.
     */
    internal fun processNiv99P010ImageToPackedTenBitBufferWithCrc(
        image: Image,
        target: ByteBuffer,
        encoding: Npv10Niv99ColorEncoding,
        redCorrection: Float,
        greenCorrection: Float,
        blueCorrection: Float,
        spatialDenoise: Float,
        chromaDenoise: Float,
        sharpeningCeiling: Float,
        confidence: Float
    ): Long {
        if (!isNiv99P010NativeProcessingAvailable() ||
            image.format != ImageFormat.YCBCR_P010 ||
            image.planes.size < 2 ||
            !target.isDirect ||
            !encoding.limitedRange
        ) {
            return NATIVE_CRC_FAILURE
        }
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            image.width,
            image.height,
            Npv10PayloadFormat.P010_LE
        )
        val packedBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes,
            Npv10PacketCodec.PACKED_P010_10_LE_V1
        ).toInt()
        if (target.capacity() < packedBytes) return NATIVE_CRC_FAILURE
        val yPlane = image.planes[0]
        val uvPlane = image.planes[1]
        // Pass the framework-owned direct planes with explicit offsets. duplicate()/slice() create
        // wrapper objects on every frame and are unnecessary when native validates the offset and
        // remaining capacity before reading.
        val yBuffer = yPlane.buffer
        val uvBuffer = uvPlane.buffer
        if (!yBuffer.isDirect || !uvBuffer.isDirect) return NATIVE_CRC_FAILURE
        val yBufferOffset = yBuffer.position()
        val uvBufferOffset = uvBuffer.position()
        target.clear()
        val crc = runCatching {
            nativeProcessNiv99P010ImagePlanesToPackedTenBitWithCrc(
                yBuffer = yBuffer,
                uvBuffer = uvBuffer,
                yBufferOffset = yBufferOffset,
                uvBufferOffset = uvBufferOffset,
                width = image.width,
                height = image.height,
                yRowStride = yPlane.rowStride,
                uvRowStride = uvPlane.rowStride,
                yPixelStride = yPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES),
                uvPixelStride = uvPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES * 2),
                targetBuffer = target,
                redCorrection = redCorrection,
                greenCorrection = greenCorrection,
                blueCorrection = blueCorrection,
                spatialDenoise = spatialDenoise,
                chromaDenoise = chromaDenoise,
                sharpeningCeiling = sharpeningCeiling,
                confidence = confidence,
                matrixCode = encoding.matrix.nativeCode,
                transferCode = encoding.transfer.nativeCode
            )
        }.getOrDefault(NATIVE_CRC_FAILURE)
        if (crc !in 0L..UNSIGNED_INT_MAX) {
            target.clear()
            return NATIVE_CRC_FAILURE
        }
        target.position(packedBytes)
        target.flip()
        return crc
    }

    /**
     * Zero-copy Camera2-to-DMC NIV99 path. The framework-owned P010 planes remain valid for the
     * complete synchronous native call, and native writes packed ten-bit output directly into the
     * pre-mapped master-file payload. A negative result is a hard failure; untreated pixels are
     * never substituted for a NIV99-required frame.
     */
    internal fun processNiv99P010ImageToDirectMappedTenBitWithCrc(
        image: Image,
        directMappedCommit: Npv10DirectMappedCommitEngine,
        frameIndex: Long,
        encoding: Npv10Niv99ColorEncoding,
        redCorrection: Float,
        greenCorrection: Float,
        blueCorrection: Float,
        spatialDenoise: Float,
        chromaDenoise: Float,
        sharpeningCeiling: Float,
        confidence: Float
    ): Npv10DirectMappedPreparedPayload? {
        if (!isNiv99P010NativeProcessingAvailable() ||
            image.format != ImageFormat.YCBCR_P010 ||
            image.planes.size < 2 || frameIndex < 0L || !encoding.limitedRange
        ) {
            return null
        }
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            image.width,
            image.height,
            Npv10PayloadFormat.P010_LE
        )
        val packedBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes,
            Npv10PacketCodec.PACKED_P010_10_LE_V1
        )
        if (packedBytes != directMappedCommit.payloadBytes.toLong()) {
            return null
        }
        val yPlane = image.planes[0]
        val uvPlane = image.planes[1]
        val yBuffer = yPlane.buffer
        val uvBuffer = uvPlane.buffer
        if (!yBuffer.isDirect || !uvBuffer.isDirect) return null
        val token = runCatching {
            nativeProcessNiv99P010ImagePlanesToDirectMappedTenBitWithCrc(
                yBuffer = yBuffer,
                uvBuffer = uvBuffer,
                yBufferOffset = yBuffer.position(),
                uvBufferOffset = uvBuffer.position(),
                width = image.width,
                height = image.height,
                yRowStride = yPlane.rowStride,
                uvRowStride = uvPlane.rowStride,
                yPixelStride = yPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES),
                uvPixelStride = uvPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES * 2),
                directMappedCommitHandle = directMappedCommit.processingHandle(),
                frameIndex = frameIndex,
                redCorrection = redCorrection,
                greenCorrection = greenCorrection,
                blueCorrection = blueCorrection,
                spatialDenoise = spatialDenoise,
                chromaDenoise = chromaDenoise,
                sharpeningCeiling = sharpeningCeiling,
                confidence = confidence,
                matrixCode = encoding.matrix.nativeCode,
                transferCode = encoding.transfer.nativeCode
            )
        }.getOrDefault(NATIVE_CRC_FAILURE)
        return decodeDirectMappedPreparedPayload(token, directMappedCommit)
    }

    /**
     * Processes a tightly packed canonical P010 copy after its Camera2 Image has been closed.
     * [canonicalP010] contains full-width 16-bit Y rows followed by full-width interleaved UV rows;
     * the same direct buffer is intentionally passed twice with explicit, validated plane offsets.
     */
    internal fun processNiv99CanonicalP010ToPackedTenBitBufferWithCrc(
        canonicalP010: ByteBuffer,
        width: Int,
        height: Int,
        target: ByteBuffer,
        encoding: Npv10Niv99ColorEncoding,
        redCorrection: Float,
        greenCorrection: Float,
        blueCorrection: Float,
        spatialDenoise: Float,
        chromaDenoise: Float,
        sharpeningCeiling: Float,
        confidence: Float
    ): Long {
        if (!isNiv99P010NativeProcessingAvailable() ||
            width <= 0 || height <= 0 ||
            !canonicalP010.isDirect || !target.isDirect ||
            canonicalP010 === target || !encoding.limitedRange
        ) {
            return NATIVE_CRC_FAILURE
        }
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            width,
            height,
            Npv10PayloadFormat.P010_LE
        )
        val packedBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes,
            Npv10PacketCodec.PACKED_P010_10_LE_V1
        )
        val yPlaneBytes = width.toLong() * height.toLong() * Short.SIZE_BYTES
        if (decodedBytes !in 1L..Int.MAX_VALUE.toLong() ||
            packedBytes !in 1L..Int.MAX_VALUE.toLong() ||
            yPlaneBytes !in 1L until decodedBytes ||
            canonicalP010.capacity().toLong() < decodedBytes ||
            target.capacity().toLong() < packedBytes
        ) {
            return NATIVE_CRC_FAILURE
        }

        target.clear()
        val crc = runCatching {
            nativeProcessNiv99P010ImagePlanesToPackedTenBitWithCrc(
                yBuffer = canonicalP010,
                uvBuffer = canonicalP010,
                yBufferOffset = 0,
                uvBufferOffset = yPlaneBytes.toInt(),
                width = width,
                height = height,
                yRowStride = Math.multiplyExact(width, Short.SIZE_BYTES),
                uvRowStride = Math.multiplyExact(width, Short.SIZE_BYTES),
                yPixelStride = Short.SIZE_BYTES,
                uvPixelStride = Short.SIZE_BYTES * 2,
                targetBuffer = target,
                redCorrection = redCorrection,
                greenCorrection = greenCorrection,
                blueCorrection = blueCorrection,
                spatialDenoise = spatialDenoise,
                chromaDenoise = chromaDenoise,
                sharpeningCeiling = sharpeningCeiling,
                confidence = confidence,
                matrixCode = encoding.matrix.nativeCode,
                transferCode = encoding.transfer.nativeCode
            )
        }.getOrDefault(NATIVE_CRC_FAILURE)
        if (crc !in 0L..UNSIGNED_INT_MAX) {
            target.clear()
            return NATIVE_CRC_FAILURE
        }
        target.position(packedBytes.toInt())
        target.flip()
        return crc
    }

    /**
     * Full-frame NIV99 conversion kernel for encoder-ready P010. This reuses the prewarmed bounded
     * native row executor and writes directly into [target], avoiding the monolithic export Vulkan
     * shader and its host-visible staging copies. The source and temporal predecessor are read-only;
     * the returned token reports successful completion and whether motion-safe temporal fusion ran.
     */
    internal fun processNiv99CanonicalP010ToP010(
        canonicalP010: ByteBuffer,
        previousCanonicalP010: ByteBuffer?,
        width: Int,
        height: Int,
        target: ByteBuffer,
        encoding: Npv10Niv99ColorEncoding,
        uniforms: GpuImageEnhancementUniforms,
        residualWhiteBalanceAllowed: Boolean
    ): Long {
        if (!isNiv99P010NativeProcessingAvailable() ||
            width <= 0 || height <= 0 ||
            !canonicalP010.isDirect || !target.isDirect ||
            canonicalP010 === target || previousCanonicalP010 === target ||
            previousCanonicalP010?.isDirect == false || !encoding.limitedRange
        ) {
            return NATIVE_CRC_FAILURE
        }
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            width,
            height,
            Npv10PayloadFormat.P010_LE
        )
        val yPlaneBytes = width.toLong() * height.toLong() * Short.SIZE_BYTES
        if (decodedBytes !in 1L..Int.MAX_VALUE.toLong() ||
            yPlaneBytes !in 1L until decodedBytes ||
            canonicalP010.remaining().toLong() < decodedBytes ||
            target.remaining().toLong() < decodedBytes ||
            previousCanonicalP010 != null && previousCanonicalP010.remaining().toLong() < decodedBytes
        ) {
            return NATIVE_CRC_FAILURE
        }

        val warmth = if (residualWhiteBalanceAllowed) {
            uniforms.warmth.coerceIn(-MAXIMUM_RESIDUAL_WARMTH, MAXIMUM_RESIDUAL_WARMTH)
        } else {
            0f
        }
        val redCorrection = exp((warmth * RESIDUAL_WARMTH_GAIN_SCALE).toDouble()).toFloat()
        val blueCorrection = exp((-warmth * RESIDUAL_WARMTH_GAIN_SCALE).toDouble()).toFloat()
        val sharpeningCeiling = uniforms.sharpeningCeiling
            .takeIf { it.isFinite() && it > 0f }
            ?.let { minOf(uniforms.sharpening, it) }
            ?: uniforms.sharpening
        target.position(0)
        target.limit(decodedBytes.toInt())
        val token = runCatching {
            nativeProcessNiv99CanonicalP010ToP010(
                currentBuffer = canonicalP010,
                previousBuffer = previousCanonicalP010,
                outputBuffer = target,
                currentOffset = canonicalP010.position(),
                previousOffset = previousCanonicalP010?.position() ?: 0,
                width = width,
                height = height,
                redCorrection = redCorrection,
                greenCorrection = 1f,
                blueCorrection = blueCorrection,
                saturation = uniforms.saturation,
                spatialDenoise = uniforms.spatialDenoise,
                chromaDenoise = uniforms.chromaDenoise,
                sharpeningCeiling = sharpeningCeiling,
                temporalLumaStrength = uniforms.temporalLumaBlend,
                temporalChromaStrength = uniforms.temporalChromaBlend,
                shotNoiseCoefficient = uniforms.sensorShotNoiseCoefficient,
                readNoiseCoefficient = uniforms.sensorReadNoiseCoefficient,
                confidence = 1f,
                matrixCode = encoding.matrix.nativeCode,
                transferCode = encoding.transfer.nativeCode
            )
        }.getOrDefault(NATIVE_CRC_FAILURE)
        if (token < 0L) {
            target.position(0)
            target.limit(decodedBytes.toInt())
            return NATIVE_CRC_FAILURE
        }
        target.position(0)
        target.limit(decodedBytes.toInt())
        return token
    }

    internal fun niv99P010TemporalUsed(token: Long): Boolean =
        token >= 0L && token and NIV99_P010_TEMPORAL_USED_FLAG != 0L

    /**
     * DMC variant of [processNiv99CanonicalP010ToPackedTenBitBufferWithCrc]. The native processor
     * resolves the already-mapped slot by handle and frame index, then emits packed ten-bit bytes and
     * CRC directly at the final master-file payload offset. No Java packet buffer is materialized.
     */
    internal fun processNiv99CanonicalP010ToDirectMappedTenBitWithCrc(
        canonicalP010: ByteBuffer,
        width: Int,
        height: Int,
        directMappedCommit: Npv10DirectMappedCommitEngine,
        frameIndex: Long,
        encoding: Npv10Niv99ColorEncoding,
        redCorrection: Float,
        greenCorrection: Float,
        blueCorrection: Float,
        spatialDenoise: Float,
        chromaDenoise: Float,
        sharpeningCeiling: Float,
        confidence: Float
    ): Npv10DirectMappedPreparedPayload? {
        if (!isNiv99P010NativeProcessingAvailable() ||
            width <= 0 || height <= 0 || frameIndex < 0L ||
            !canonicalP010.isDirect || !encoding.limitedRange
        ) {
            return null
        }
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            width,
            height,
            Npv10PayloadFormat.P010_LE
        )
        val packedBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes,
            Npv10PacketCodec.PACKED_P010_10_LE_V1
        )
        val yPlaneBytes = width.toLong() * height.toLong() * Short.SIZE_BYTES
        if (decodedBytes !in 1L..Int.MAX_VALUE.toLong() ||
            packedBytes != directMappedCommit.payloadBytes.toLong() ||
            yPlaneBytes !in 1L until decodedBytes ||
            canonicalP010.capacity().toLong() < decodedBytes
        ) {
            return null
        }
        val token = runCatching {
            nativeProcessNiv99P010ImagePlanesToDirectMappedTenBitWithCrc(
                yBuffer = canonicalP010,
                uvBuffer = canonicalP010,
                yBufferOffset = 0,
                uvBufferOffset = yPlaneBytes.toInt(),
                width = width,
                height = height,
                yRowStride = Math.multiplyExact(width, Short.SIZE_BYTES),
                uvRowStride = Math.multiplyExact(width, Short.SIZE_BYTES),
                yPixelStride = Short.SIZE_BYTES,
                uvPixelStride = Short.SIZE_BYTES * 2,
                directMappedCommitHandle = directMappedCommit.processingHandle(),
                frameIndex = frameIndex,
                redCorrection = redCorrection,
                greenCorrection = greenCorrection,
                blueCorrection = blueCorrection,
                spatialDenoise = spatialDenoise,
                chromaDenoise = chromaDenoise,
                sharpeningCeiling = sharpeningCeiling,
                confidence = confidence,
                matrixCode = encoding.matrix.nativeCode,
                transferCode = encoding.transfer.nativeCode
            )
        }.getOrDefault(NATIVE_CRC_FAILURE)
        return decodeDirectMappedPreparedPayload(token, directMappedCommit)
    }

    /**
     * Allocation-free production copy used before a deferred NIV99 metadata join. Unlike the
     * compatibility API below, this method has no Kotlin buffer views and no result object.
     */
    internal fun copyP010ImageIntoCanonicalDirectBuffer(
        image: Image,
        target: ByteBuffer
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            image.format != ImageFormat.YCBCR_P010 ||
            !target.isDirect || !nativeLibraryAvailable
        ) {
            return 0
        }
        val planes = image.planes
        if (planes.size < 2) return 0
        val payloadBytes = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(image.width, image.height, Npv10PayloadFormat.P010_LE)
        if (payloadBytes !in 1L..Int.MAX_VALUE.toLong() ||
            target.capacity().toLong() < payloadBytes
        ) {
            return 0
        }
        val yPlane = planes[0]
        val uvPlane = planes[1]
        val yBuffer = yPlane.buffer
        val uvBuffer = uvPlane.buffer
        if (!yBuffer.isDirect || !uvBuffer.isDirect) return 0

        target.clear()
        val copied = runCatching {
            nativeCopyP010ImagePlanes(
                yBuffer = yBuffer,
                uvBuffer = uvBuffer,
                yBufferOffset = yBuffer.position(),
                uvBufferOffset = uvBuffer.position(),
                width = image.width,
                height = image.height,
                yRowStride = yPlane.rowStride,
                uvRowStride = uvPlane.rowStride,
                yPixelStride = yPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES),
                uvPixelStride = uvPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES * 2),
                target = target
            )
        }.getOrDefault(false)
        if (!copied) {
            target.clear()
            return 0
        }
        target.position(payloadBytes.toInt())
        target.flip()
        return payloadBytes.toInt()
    }

    fun pack(
        frame: RawYuvFrame,
        payloadFormat: Npv10PayloadFormat = Npv10PayloadFormat.YUV420P10_LE
    ): ByteArray =
        when (payloadFormat) {
            Npv10PayloadFormat.P010_LE -> packP010(frame)
            Npv10PayloadFormat.RAW12_NPV10_PACKED -> packRaw12Frame(frame)
            Npv10PayloadFormat.RAW14_NPV10_PACKED -> packRaw14Frame(frame)
            Npv10PayloadFormat.YUV420P10_LE,
            Npv10PayloadFormat.YUV420_8BIT_EXPANDED -> packPlanar(frame)
        }

    fun packRaw12ToNpv10P010Words(rawSamples: IntArray): ByteArray {
        val output = ByteArray(rawSamples.size * Short.SIZE_BYTES)
        rawSamples.forEachIndexed { index, sample ->
            val raw = sample.coerceIn(0, RAW12_MAX)
            val word = ((raw ushr 2) shl P010_VALID_BITS_SHIFT) or
                ((raw and 0x03) shl 4) or
                crc4Raw12(raw)
            writeLittleEndian16(output, index * Short.SIZE_BYTES, word)
        }
        return output
    }

    fun unpackRaw12FromNpv10P010Words(payload: ByteArray): IntArray {
        require(payload.size % Short.SIZE_BYTES == 0) { "RAW12 NPV10 payload must be 16-bit aligned" }
        return IntArray(payload.size / Short.SIZE_BYTES) { index ->
            val word = readLittleEndian16(payload, index * Short.SIZE_BYTES)
            ((word ushr P010_VALID_BITS_SHIFT) shl 2) or ((word ushr 4) and 0x03)
        }
    }

    fun packRaw14ToNpv10P010Words(rawSamples: IntArray, frameParitySeed: Int = 0): ByteArray {
        val output = ByteArray(rawSamples.size * Short.SIZE_BYTES)
        rawSamples.forEachIndexed { index, sample ->
            val raw = sample.coerceIn(0, RAW14_MAX)
            val parity = (frameParitySeed + index) and 0x03
            val word = ((raw ushr 4) shl P010_VALID_BITS_SHIFT) or
                ((raw and 0x0F) shl 2) or
                parity
            writeLittleEndian16(output, index * Short.SIZE_BYTES, word)
        }
        return output
    }

    fun unpackRaw14FromNpv10P010Words(payload: ByteArray): IntArray {
        require(payload.size % Short.SIZE_BYTES == 0) { "RAW14 NPV10 payload must be 16-bit aligned" }
        return IntArray(payload.size / Short.SIZE_BYTES) { index ->
            val word = readLittleEndian16(payload, index * Short.SIZE_BYTES)
            ((word ushr P010_VALID_BITS_SHIFT) shl 4) or ((word ushr 2) and 0x0F)
        }
    }

    fun crc4Raw12(sample: Int): Int {
        val raw = sample and RAW12_MAX
        return (raw xor (raw ushr 4) xor (raw ushr 8)) and 0x0F
    }

    fun packIntoDirectP010(
        frame: RawYuvFrame,
        target: ByteBuffer = ByteBuffer.allocateDirect(
            Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(frame.width, frame.height).toInt()
        ).order(ByteOrder.nativeOrder())
    ): ByteBuffer {
        val payloadBytes = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(frame.width, frame.height)
            .toInt()
        target.clear()
        val packed =
            nativeLibraryAvailable &&
                runCatching {
                    nativePackP010Neon(
                        frame.yBytes,
                        frame.uBytes,
                        frame.vBytes,
                        frame.width,
                        frame.height,
                        frame.yRowStride,
                        frame.uRowStride,
                        frame.vRowStride,
                        frame.yPixelStride,
                        frame.uPixelStride,
                        frame.vPixelStride,
                        frame.bitDepth,
                        target
                    )
                }.getOrDefault(false)
        if (packed) {
            target.position(payloadBytes)
        } else {
            target.clear()
            target.put(pack(frame, Npv10PayloadFormat.P010_LE))
        }
        target.flip()
        return target
    }

    fun copyP010ImageIntoDirectBuffer(
        image: Image,
        target: ByteBuffer
    ): Int = copyP010ImageIntoDirectBufferWithResult(image, target).bytesCopied

    /**
     * Losslessly removes the six mandatory zero padding bits from Camera2 P010 while reading the
     * image planes. This fuses the former full-frame copy followed by packed-10 encoding, eliminating
     * one 37.7 MiB intermediate write and reread for each 4096x3072 frame. The packet layout remains
     * byte-identical to [Npv10PacketCodec.PACKED_P010_10_LE_V1].
     */
    fun packP010ImageToPackedTenBitBufferWithResult(
        image: Image,
        target: ByteBuffer
    ): P010CopyResult =
        packP010ImageToPackedTenBitBufferWithCrcResult(image, target).let { result ->
            P010CopyResult(result.bytesCopied, result.path)
        }

    /**
     * Direct Camera2 plane -> packed-10 packet with a cache-hot CRC when the standard P010 layout
     * is available. The native fast path consumes 32 samples per iteration and combines row CRCs in
     * canonical Y-then-UV order. Unusual vendor layouts retain the exact existing packet fallback;
     * callers must calculate CRC when [P010PackedResult.crc32] is null.
     */
    fun packP010ImageToPackedTenBitBufferWithCrcResult(
        image: Image,
        target: ByteBuffer
    ): P010PackedResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            image.format != ImageFormat.YCBCR_P010 ||
            image.planes.size < 2
        ) {
            return P010PackedResult(0, P010CopyPath.FAILED, crc32 = null)
        }
        val decodedBytes = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(image.width, image.height, Npv10PayloadFormat.P010_LE)
        val packedBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes,
            Npv10PacketCodec.PACKED_P010_10_LE_V1
        ).toInt()
        if (target.capacity() < packedBytes || !target.isDirect || !nativeLibraryAvailable) {
            return P010PackedResult(0, P010CopyPath.FAILED, crc32 = null)
        }
        val yPlane = image.planes[0]
        val uvPlane = image.planes[1]
        val yBuffer = yPlane.buffer.duplicate().slice()
        val uvBuffer = uvPlane.buffer.duplicate().slice()
        if (!yBuffer.isDirect || !uvBuffer.isDirect) {
            return P010PackedResult(0, P010CopyPath.FAILED, crc32 = null)
        }
        target.clear()
        val nativeCrc = runCatching {
            nativePackP010ImagePlanesToPackedTenBitWithCrc(
                yBuffer = yBuffer,
                uvBuffer = uvBuffer,
                width = image.width,
                height = image.height,
                yRowStride = yPlane.rowStride,
                uvRowStride = uvPlane.rowStride,
                yPixelStride = yPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES),
                uvPixelStride = uvPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES * 2),
                targetBuffer = target
            )
        }.getOrDefault(NATIVE_CRC_FAILURE)
        if (nativeCrc in 0L..UNSIGNED_INT_MAX) {
            target.position(packedBytes)
            target.flip()
            return P010PackedResult(
                bytesCopied = packedBytes,
                path = P010CopyPath.NATIVE_DIRECT,
                crc32 = nativeCrc.toInt()
            )
        }

        target.clear()
        val packedWithoutCrc = runCatching {
            nativePackP010ImagePlanesToPackedTenBit(
                yBuffer = yBuffer,
                uvBuffer = uvBuffer,
                width = image.width,
                height = image.height,
                yRowStride = yPlane.rowStride,
                uvRowStride = uvPlane.rowStride,
                yPixelStride = yPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES),
                uvPixelStride = uvPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES * 2),
                targetBuffer = target
            )
        }.getOrDefault(false)
        if (!packedWithoutCrc) {
            target.clear()
            return P010PackedResult(0, P010CopyPath.FAILED, crc32 = null)
        }
        target.position(packedBytes)
        target.flip()
        return P010PackedResult(
            bytesCopied = packedBytes,
            path = P010CopyPath.NATIVE_DIRECT,
            crc32 = null
        )
    }

    /**
     * Lossless recording hot path: packs the framework-owned P010 planes straight into the already
     * claimed DMC payload and calculates its CRC in the same native traversal. No filtered pixels,
     * Java packet buffer, full-frame copy, or per-frame ByteBuffer view is introduced.
     */
    internal fun packP010ImageToDirectMappedTenBitWithCrc(
        image: Image,
        directMappedCommit: Npv10DirectMappedCommitEngine,
        frameIndex: Long,
        onPlanesCopied: Runnable? = null
    ): Npv10DirectMappedPreparedPayload? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !nativeLibraryAvailable ||
            image.format != ImageFormat.YCBCR_P010 ||
            image.planes.size < 2 ||
            frameIndex < 0L
        ) {
            return null
        }
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            image.width,
            image.height,
            Npv10PayloadFormat.P010_LE
        )
        val packedBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes,
            Npv10PacketCodec.PACKED_P010_10_LE_V1
        )
        if (packedBytes != directMappedCommit.payloadBytes.toLong()) {
            return null
        }
        val yPlane = image.planes[0]
        val uvPlane = image.planes[1]
        val yBuffer = yPlane.buffer
        val uvBuffer = uvPlane.buffer
        if (!yBuffer.isDirect || !uvBuffer.isDirect) return null
        val token = runCatching {
            nativePackP010ImagePlanesToDirectMappedTenBitWithCrc(
                yBuffer = yBuffer,
                uvBuffer = uvBuffer,
                yBufferOffset = yBuffer.position(),
                uvBufferOffset = uvBuffer.position(),
                width = image.width,
                height = image.height,
                yRowStride = yPlane.rowStride,
                uvRowStride = uvPlane.rowStride,
                yPixelStride = yPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES),
                uvPixelStride = uvPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES * 2),
                directMappedCommitHandle = directMappedCommit.processingHandle(),
                frameIndex = frameIndex,
                onPlanesCopied = onPlanesCopied
            )
        }.getOrElse { failure ->
            // The live ownership callback must never silently lose a failed Image.close().
            // Native code has already retired its PROCESSING claim on a callback exception.
            if (onPlanesCopied != null) throw failure
            NATIVE_CRC_FAILURE
        }
        return decodeDirectMappedPreparedPayload(token, directMappedCommit)
    }

    private fun decodeDirectMappedPreparedPayload(
        token: Long,
        directMappedCommit: Npv10DirectMappedCommitEngine
    ): Npv10DirectMappedPreparedPayload? {
        if (token < 0L) return null
        val encodedBytes = (token ushr Int.SIZE_BITS).toInt()
        if (encodedBytes !in 1..directMappedCommit.payloadCapacityBytes) return null
        return Npv10DirectMappedPreparedPayload(
            encodedBytes = encodedBytes,
            crc32 = token.toInt()
        )
    }

    fun copyP010ImageIntoDirectBufferWithResult(
        image: Image,
        target: ByteBuffer
    ): P010CopyResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            image.format != ImageFormat.YCBCR_P010 ||
            image.planes.size < 2
        ) {
            return P010CopyResult(0, P010CopyPath.FAILED)
        }
        val payloadBytes = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(image.width, image.height)
            .toInt()
        if (target.capacity() < payloadBytes) {
            return P010CopyResult(0, P010CopyPath.FAILED)
        }
        val yPlane = image.planes[0]
        val uvPlane = image.planes[1]
        val vPlane = image.planes.getOrNull(2)
        val yBuffer = yPlane.buffer.duplicate().slice()
        val uvBuffer = uvPlane.buffer.duplicate().slice()
        val vBuffer = vPlane?.buffer?.duplicate()?.slice()
        target.clear()
        val yPixelStride = yPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES)
        val uvPixelStride = uvPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES * 2)
        val nativeCopied =
            nativeLibraryAvailable &&
                runCatching {
                    nativeCopyP010ImagePlanes(
                        yBuffer,
                        uvBuffer,
                        0,
                        0,
                        image.width,
                        image.height,
                        yPlane.rowStride,
                        uvPlane.rowStride,
                        yPixelStride,
                        uvPixelStride,
                        target
                    )
                }.getOrDefault(false)
        if (nativeCopied) {
            target.position(payloadBytes)
            target.flip()
            return P010CopyResult(payloadBytes, P010CopyPath.NATIVE_DIRECT)
        }

        if (vPlane != null && vBuffer != null) {
            target.clear()
            val threePlaneCopiedBytes = copyP010ThreePlaneBuffersDirect(
                yBuffer = yBuffer,
                uBuffer = uvBuffer,
                vBuffer = vBuffer,
                width = image.width,
                height = image.height,
                yRowStride = yPlane.rowStride,
                uRowStride = uvPlane.rowStride,
                vRowStride = vPlane.rowStride,
                yPixelStride = yPixelStride,
                uPixelStride = uvPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES),
                vPixelStride = vPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES),
                target = target
            )
            if (threePlaneCopiedBytes > 0) {
                target.flip()
                return P010CopyResult(threePlaneCopiedBytes, P010CopyPath.DIRECT_BUFFER)
            }
        }

        target.clear()
        val directCopiedBytes = copyP010PlaneBuffersDirect(
            yBuffer = yBuffer,
            uvBuffer = uvBuffer,
            width = image.width,
            height = image.height,
            yRowStride = yPlane.rowStride,
            uvRowStride = uvPlane.rowStride,
            yPixelStride = yPixelStride,
            uvPixelStride = uvPixelStride,
            target = target
        )
        if (directCopiedBytes <= 0) {
            target.clear()
            return P010CopyResult(0, P010CopyPath.FAILED)
        }
        target.flip()
        return P010CopyResult(directCopiedBytes, P010CopyPath.DIRECT_BUFFER)
    }

    internal fun copyP010PlaneBuffersDirect(
        yBuffer: ByteBuffer,
        uvBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        yPixelStride: Int,
        uvPixelStride: Int,
        target: ByteBuffer
    ): Int {
        if (width <= 0 || height <= 0 ||
            yRowStride <= 0 || uvRowStride <= 0 ||
            yPixelStride < Short.SIZE_BYTES ||
            uvPixelStride < Short.SIZE_BYTES * 2
        ) {
            return 0
        }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val yPayloadRowBytes = width * Short.SIZE_BYTES
        val uvPayloadRowBytes = chromaWidth * Short.SIZE_BYTES * 2
        val payloadBytes = yPayloadRowBytes * height + uvPayloadRowBytes * chromaHeight
        if (target.capacity() < payloadBytes) return 0

        target.clear()
        val yCopied = copyPlaneGroupsDirect(
            source = yBuffer,
            target = target,
            groupsPerRow = width,
            rows = height,
            sourceRowStride = yRowStride,
            sourceGroupStride = yPixelStride,
            bytesPerGroup = Short.SIZE_BYTES
        )
        val uvCopied = yCopied && copyPlaneGroupsDirect(
            source = uvBuffer,
            target = target,
            groupsPerRow = chromaWidth,
            rows = chromaHeight,
            sourceRowStride = uvRowStride,
            sourceGroupStride = uvPixelStride,
            bytesPerGroup = Short.SIZE_BYTES * 2
        )
        if (!uvCopied || target.position() != payloadBytes) {
            target.clear()
            return 0
        }
        return payloadBytes
    }

    internal fun copyP010ThreePlaneBuffersDirect(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uRowStride: Int,
        vRowStride: Int,
        yPixelStride: Int,
        uPixelStride: Int,
        vPixelStride: Int,
        target: ByteBuffer
    ): Int {
        if (width <= 0 || height <= 0 ||
            yRowStride <= 0 || uRowStride <= 0 || vRowStride <= 0 ||
            yPixelStride < Short.SIZE_BYTES ||
            uPixelStride < Short.SIZE_BYTES ||
            vPixelStride < Short.SIZE_BYTES
        ) {
            return 0
        }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val payloadBytes = width * height * Short.SIZE_BYTES +
            chromaWidth * chromaHeight * Short.SIZE_BYTES * 2
        if (target.capacity() < payloadBytes) return 0

        target.clear()
        val yCopied = copyPlaneGroupsDirect(
            source = yBuffer,
            target = target,
            groupsPerRow = width,
            rows = height,
            sourceRowStride = yRowStride,
            sourceGroupStride = yPixelStride,
            bytesPerGroup = Short.SIZE_BYTES
        )
        val chromaCopied = yCopied && copyP010ChromaViewsDirect(
            uSource = uBuffer,
            vSource = vBuffer,
            target = target,
            groupsPerRow = chromaWidth,
            rows = chromaHeight,
            uRowStride = uRowStride,
            vRowStride = vRowStride,
            uGroupStride = uPixelStride,
            vGroupStride = vPixelStride
        )
        if (!chromaCopied || target.position() != payloadBytes) {
            target.clear()
            return 0
        }
        return payloadBytes
    }

    private fun copyPlaneGroupsDirect(
        source: ByteBuffer,
        target: ByteBuffer,
        groupsPerRow: Int,
        rows: Int,
        sourceRowStride: Int,
        sourceGroupStride: Int,
        bytesPerGroup: Int
    ): Boolean {
        if (groupsPerRow <= 0 || rows <= 0) return false
        val requiredSourceBytes =
            (rows - 1L) * sourceRowStride +
                (groupsPerRow - 1L) * sourceGroupStride +
                bytesPerGroup
        if (requiredSourceBytes > source.limit().toLong()) return false

        val payloadRowBytes = groupsPerRow * bytesPerGroup
        if (sourceGroupStride == bytesPerGroup) {
            if (sourceRowStride == payloadRowBytes) {
                val contiguous = source.duplicate()
                contiguous.position(0)
                contiguous.limit(payloadRowBytes * rows)
                target.put(contiguous)
                return true
            }
            repeat(rows) { row ->
                val rowStart = row * sourceRowStride
                val rowSlice = source.duplicate()
                rowSlice.position(rowStart)
                rowSlice.limit(rowStart + payloadRowBytes)
                target.put(rowSlice)
            }
            return true
        }

        repeat(rows) { row ->
            repeat(groupsPerRow) { group ->
                val sourceOffset = row * sourceRowStride + group * sourceGroupStride
                repeat(bytesPerGroup) { byteOffset ->
                    target.put(source.get(sourceOffset + byteOffset))
                }
            }
        }
        return true
    }

    private fun copyP010ChromaViewsDirect(
        uSource: ByteBuffer,
        vSource: ByteBuffer,
        target: ByteBuffer,
        groupsPerRow: Int,
        rows: Int,
        uRowStride: Int,
        vRowStride: Int,
        uGroupStride: Int,
        vGroupStride: Int
    ): Boolean {
        if (groupsPerRow <= 0 || rows <= 0) return false
        val requiredUBytes =
            (rows - 1L) * uRowStride +
                (groupsPerRow - 1L) * uGroupStride +
                Short.SIZE_BYTES
        val requiredVBytes =
            (rows - 1L) * vRowStride +
                (groupsPerRow - 1L) * vGroupStride +
                Short.SIZE_BYTES
        if (requiredUBytes > uSource.limit().toLong() || requiredVBytes > vSource.limit().toLong()) {
            return false
        }

        repeat(rows) { row ->
            repeat(groupsPerRow) { group ->
                val uOffset = row * uRowStride + group * uGroupStride
                val vOffset = row * vRowStride + group * vGroupStride
                target.put(uSource.get(uOffset))
                target.put(uSource.get(uOffset + 1))
                target.put(vSource.get(vOffset))
                target.put(vSource.get(vOffset + 1))
            }
        }
        return true
    }

    private fun packPlanar(frame: RawYuvFrame): ByteArray {
        val width = frame.width.coerceAtLeast(1)
        val height = frame.height.coerceAtLeast(1)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val output = ByteArray(Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(width, height).toInt())
        var offset = 0

        for (row in 0 until height) {
            for (column in 0 until width) {
                val sample = readPlaneSample(
                    bytes = frame.yBytes,
                    row = row,
                    column = column,
                    rowStride = frame.yRowStride,
                    pixelStride = frame.yPixelStride,
                    fallback = 0,
                    bitDepth = frame.bitDepth
                )
                writeTenBitSample(output, offset, sample, frame.bitDepth)
                offset += Short.SIZE_BYTES
            }
        }

        offset = packChromaPlane(
            output = output,
            offset = offset,
            bytes = frame.uBytes,
            width = chromaWidth,
            height = chromaHeight,
            rowStride = frame.uRowStride,
            pixelStride = frame.uPixelStride,
            bitDepth = frame.bitDepth
        )
        packChromaPlane(
            output = output,
            offset = offset,
            bytes = frame.vBytes,
            width = chromaWidth,
            height = chromaHeight,
            rowStride = frame.vRowStride,
            pixelStride = frame.vPixelStride,
            bitDepth = frame.bitDepth
        )
        return output
    }

    private fun packP010(frame: RawYuvFrame): ByteArray {
        val width = frame.width.coerceAtLeast(1)
        val height = frame.height.coerceAtLeast(1)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val output = ByteArray(Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(width, height).toInt())
        var offset = 0

        for (row in 0 until height) {
            for (column in 0 until width) {
                val sample = readPlaneSample(
                    bytes = frame.yBytes,
                    row = row,
                    column = column,
                    rowStride = frame.yRowStride,
                    pixelStride = frame.yPixelStride,
                    fallback = 0,
                    bitDepth = frame.bitDepth
                )
                writeP010Sample(output, offset, sample, frame.bitDepth)
                offset += Short.SIZE_BYTES
            }
        }

        for (row in 0 until chromaHeight) {
            for (column in 0 until chromaWidth) {
                val u = readPlaneSample(
                    bytes = frame.uBytes,
                    row = row,
                    column = column,
                    rowStride = frame.uRowStride,
                    pixelStride = frame.uPixelStride,
                    fallback = NEUTRAL_CHROMA_8_BIT,
                    bitDepth = frame.bitDepth
                )
                val v = readPlaneSample(
                    bytes = frame.vBytes,
                    row = row,
                    column = column,
                    rowStride = frame.vRowStride,
                    pixelStride = frame.vPixelStride,
                    fallback = NEUTRAL_CHROMA_8_BIT,
                    bitDepth = frame.bitDepth
                )
                writeP010Sample(output, offset, u, frame.bitDepth)
                writeP010Sample(output, offset + Short.SIZE_BYTES, v, frame.bitDepth)
                offset += Short.SIZE_BYTES * 2
            }
        }
        return output
    }

    private fun packRaw12Frame(frame: RawYuvFrame): ByteArray =
        packRaw12ToNpv10P010Words(readRawPlane(frame, RAW12_MAX))

    private fun packRaw14Frame(frame: RawYuvFrame): ByteArray =
        packRaw14ToNpv10P010Words(
            rawSamples = readRawPlane(frame, RAW14_MAX),
            frameParitySeed = ((frame.timestamp ushr 1) and 0x03).toInt()
        )

    private fun readRawPlane(frame: RawYuvFrame, maxValue: Int): IntArray {
        val width = frame.width.coerceAtLeast(1)
        val height = frame.height.coerceAtLeast(1)
        val samples = IntArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                samples[row * width + column] = readPlaneSample(
                    bytes = frame.yBytes,
                    row = row,
                    column = column,
                    rowStride = frame.yRowStride,
                    pixelStride = frame.yPixelStride,
                    fallback = 0,
                    bitDepth = frame.bitDepth,
                    maxValueOverride = maxValue
                ).coerceIn(0, maxValue)
            }
        }
        return samples
    }

    private fun packChromaPlane(
        output: ByteArray,
        offset: Int,
        bytes: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        bitDepth: Int
    ): Int {
        var cursor = offset
        for (row in 0 until height) {
            for (column in 0 until width) {
                val sample = readPlaneSample(
                    bytes = bytes,
                    row = row,
                    column = column,
                    rowStride = rowStride,
                    pixelStride = pixelStride,
                    fallback = NEUTRAL_CHROMA_8_BIT,
                    bitDepth = bitDepth
                )
                writeTenBitSample(output, cursor, sample, bitDepth)
                cursor += Short.SIZE_BYTES
            }
        }
        return cursor
    }

    private fun readPlaneSample(
        bytes: ByteArray,
        row: Int,
        column: Int,
        rowStride: Int,
        pixelStride: Int,
        fallback: Int,
        bitDepth: Int,
        maxValueOverride: Int = 1023
    ): Int {
        if (bytes.isEmpty() || rowStride <= 0 || pixelStride <= 0) return fallback
        val index = row * rowStride + column * pixelStride
        if (bitDepth >= 10) {
            val lo = bytes.getOrNull(index)?.toInt()?.and(0xFF) ?: return fallback
            val hi = bytes.getOrNull(index + 1)?.toInt()?.and(0xFF) ?: 0
            val packed = (hi shl 8) or lo
            return if (packed > 1023) {
                if (maxValueOverride > 1023) packed else packed ushr 6
            } else {
                packed
            }.coerceIn(0, maxValueOverride)
        }
        return bytes.getOrNull(index)?.toInt()?.and(0xFF) ?: fallback
    }

    private fun writeTenBitSample(output: ByteArray, offset: Int, sample: Int, bitDepth: Int) {
        writeLittleEndian16(output, offset, normalizeTenBitSample(sample, bitDepth))
    }

    private fun writeP010Sample(output: ByteArray, offset: Int, sample: Int, bitDepth: Int) {
        writeLittleEndian16(output, offset, normalizeTenBitSample(sample, bitDepth) shl P010_VALID_BITS_SHIFT)
    }

    private fun normalizeTenBitSample(sample: Int, bitDepth: Int): Int {
        val tenBit = if (bitDepth >= 10) {
            sample.coerceIn(0, 1023)
        } else {
            (sample.coerceIn(0, 255) shl 2).coerceIn(0, 1023)
        }
        return tenBit
    }

    private fun writeLittleEndian16(output: ByteArray, offset: Int, value: Int) {
        output[offset] = (value and 0xFF).toByte()
        output[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun readLittleEndian16(input: ByteArray, offset: Int): Int =
        (input[offset].toInt() and 0xFF) or
            ((input[offset + 1].toInt() and 0xFF) shl 8)

    @JvmStatic
    private external fun nativePackP010Neon(
        yBytes: ByteArray,
        uBytes: ByteArray,
        vBytes: ByteArray,
        width: Int,
        height: Int,
        yRowStride: Int,
        uRowStride: Int,
        vRowStride: Int,
        yPixelStride: Int,
        uPixelStride: Int,
        vPixelStride: Int,
        bitDepth: Int,
        target: ByteBuffer
    ): Boolean

    @JvmStatic
    private external fun nativeCopyP010ImagePlanes(
        yBuffer: ByteBuffer,
        uvBuffer: ByteBuffer,
        yBufferOffset: Int,
        uvBufferOffset: Int,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        yPixelStride: Int,
        uvPixelStride: Int,
        target: ByteBuffer
    ): Boolean

    @JvmStatic
    private external fun nativePackP010ImagePlanesToPackedTenBit(
        yBuffer: ByteBuffer,
        uvBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        yPixelStride: Int,
        uvPixelStride: Int,
        targetBuffer: ByteBuffer
    ): Boolean

    @JvmStatic
    private external fun nativePackP010ImagePlanesToPackedTenBitWithCrc(
        yBuffer: ByteBuffer,
        uvBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        yPixelStride: Int,
        uvPixelStride: Int,
        targetBuffer: ByteBuffer
    ): Long

    @JvmStatic
    private external fun nativePackP010ImagePlanesToDirectMappedTenBitWithCrc(
        yBuffer: ByteBuffer,
        uvBuffer: ByteBuffer,
        yBufferOffset: Int,
        uvBufferOffset: Int,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        yPixelStride: Int,
        uvPixelStride: Int,
        directMappedCommitHandle: Long,
        frameIndex: Long,
        onPlanesCopied: Runnable?
    ): Long

    @JvmStatic
    private external fun nativePrepareP010PackingExecutor(): Boolean

    @JvmStatic
    private external fun nativePrepareP010LosslessChunkExecutor(): Boolean

    @JvmStatic
    private external fun nativePrepareNiv99P010Processor(): Boolean

    @JvmStatic
    private external fun nativeVerifyNiv99P010BlockReuseParity(): Long

    @JvmStatic
    private external fun nativeExerciseNiv99P010ProcessorLifecycle(cycles: Int): Int

    @JvmStatic
    private external fun nativeProcessNiv99P010ImagePlanesToPackedTenBitWithCrc(
        yBuffer: ByteBuffer,
        uvBuffer: ByteBuffer,
        yBufferOffset: Int,
        uvBufferOffset: Int,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        yPixelStride: Int,
        uvPixelStride: Int,
        targetBuffer: ByteBuffer,
        redCorrection: Float,
        greenCorrection: Float,
        blueCorrection: Float,
        spatialDenoise: Float,
        chromaDenoise: Float,
        sharpeningCeiling: Float,
        confidence: Float,
        matrixCode: Int,
        transferCode: Int
    ): Long

    @JvmStatic
    private external fun nativeProcessNiv99P010ImagePlanesToDirectMappedTenBitWithCrc(
        yBuffer: ByteBuffer,
        uvBuffer: ByteBuffer,
        yBufferOffset: Int,
        uvBufferOffset: Int,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        yPixelStride: Int,
        uvPixelStride: Int,
        directMappedCommitHandle: Long,
        frameIndex: Long,
        redCorrection: Float,
        greenCorrection: Float,
        blueCorrection: Float,
        spatialDenoise: Float,
        chromaDenoise: Float,
        sharpeningCeiling: Float,
        confidence: Float,
        matrixCode: Int,
        transferCode: Int
    ): Long

    @JvmStatic
    private external fun nativeProcessNiv99CanonicalP010ToP010(
        currentBuffer: ByteBuffer,
        previousBuffer: ByteBuffer?,
        outputBuffer: ByteBuffer,
        currentOffset: Int,
        previousOffset: Int,
        width: Int,
        height: Int,
        redCorrection: Float,
        greenCorrection: Float,
        blueCorrection: Float,
        saturation: Float,
        spatialDenoise: Float,
        chromaDenoise: Float,
        sharpeningCeiling: Float,
        temporalLumaStrength: Float,
        temporalChromaStrength: Float,
        shotNoiseCoefficient: Float,
        readNoiseCoefficient: Float,
        confidence: Float,
        matrixCode: Int,
        transferCode: Int
    ): Long

    private const val P010_VALID_BITS_SHIFT = 6
    private const val RAW12_MAX = 0x0FFF
    private const val RAW14_MAX = 0x3FFF
    private const val NATIVE_CRC_FAILURE = -1L
    private const val UNSIGNED_INT_MAX = 0xFFFF_FFFFL
    private const val NIV99_P010_TEMPORAL_USED_FLAG = 1L shl 32
    private const val MAXIMUM_RESIDUAL_WARMTH = 0.2f
    private const val RESIDUAL_WARMTH_GAIN_SCALE = 0.45f
}
