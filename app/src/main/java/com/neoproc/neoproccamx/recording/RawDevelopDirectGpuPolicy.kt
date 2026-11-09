package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.pipeline.RawExposureNormalizationPolicy
import com.neoproc.neoproccamx.pipeline.RawNeoLogDevelop
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.min

internal data class RawDevelopSampledParityEvidence(
    val accepted: Boolean,
    val sampledPixels: Int,
    val meanAbsoluteCodeDifference: Double,
    val maximumAbsoluteCodeDifference: Int,
    val finiteGpuSamples: Int
)

internal data class RawDevelopP010ParityEvidence(
    val accepted: Boolean,
    val sampledComponents: Int,
    val meanAbsoluteCodeDifference: Double,
    val maximumAbsoluteCodeDifference: Int
)

internal data class RawDevelopP010SampleOracle(
    /** Interleaved byte offset, expected ten-bit code pairs. */
    val offsetAndCode: IntArray
) {
    val sampledComponents: Int
        get() = offsetAndCode.size / 2
}

/** Final-stage oracle comparing deterministic 10-bit P010 codes, not timing or synthetic workload. */
internal object RawDevelopP010SampledParityPolicy {
    private const val MAX_SAMPLED_COMPONENTS = 8_192

    /**
     * Builds an independent, bounded CPU oracle for the final GPU tail without repacking the full frame.
     *
     * Each selected 2x2 P010 macro-pixel is surrounded by a two-pixel ACEScg guard. That guard is the
     * complete dependency radius of the edge-aware denoise + two 3x3 detail filters, so the four centre
     * luma values and their shared chroma pair are identical to a full-frame CPU pack (apart from the
     * intentionally tolerated one-code NeoLog dither phase). Work remains constant as resolution grows.
     */
    fun buildReferenceFromAcescg(
        acescg: FloatBuffer,
        width: Int,
        height: Int,
        variant: NeoLogCurve.Variant,
        deliveryProfile: RawToNeoLogMovConverter.DeliveryProfile,
        exposureStops: Float
    ): RawDevelopP010SampleOracle? {
        if (width < MIN_ORACLE_EXTENT || height < MIN_ORACLE_EXTENT ||
            width % 2 != 0 || height % 2 != 0) {
            return null
        }
        val requiredComponents = Math.multiplyExact(Math.multiplyExact(width, height), 3)
        if (acescg.remaining() < requiredComponents) return null
        val source = acescg.slice()
        val blocks = stratifiedBlockOrigins(width, height)
        if (blocks.isEmpty()) return null
        val pairs = IntArray(Math.multiplyExact(blocks.size / 2, COMPONENTS_PER_BLOCK * 2))
        var pairOffset = 0
        var blockOffset = 0
        while (blockOffset < blocks.size) {
            val blockX = blocks[blockOffset]
            val blockY = blocks[blockOffset + 1]
            val patchX = blockX - DETAIL_RADIUS
            val patchY = blockY - DETAIL_RADIUS
            val patch = FloatArray(PATCH_WIDTH * PATCH_HEIGHT * 3)
            var destination = 0
            for (y in 0 until PATCH_HEIGHT) {
                val sourceRow = ((patchY + y) * width + patchX) * 3
                for (x in 0 until PATCH_WIDTH * 3) {
                    patch[destination++] = source.get(sourceRow + x)
                }
            }
            val packed = if (deliveryProfile.ndrMode != null) {
                RawAcescgTenBitPacker.packNdrP010420(
                    acescg = patch,
                    width = PATCH_WIDTH,
                    height = PATCH_HEIGHT,
                    mode = requireNotNull(deliveryProfile.ndrMode),
                    exposureStops = exposureStops
                )
            } else {
                RawAcescgTenBitPacker.packP010420(
                    acescg = patch,
                    width = PATCH_WIDTH,
                    height = PATCH_HEIGHT,
                    variant = variant,
                    exposureStops = exposureStops
                )
            }
            val patchBuffer = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN)
            for (dy in 0..1) {
                for (dx in 0..1) {
                    val globalPixel = (blockY + dy) * width + blockX + dx
                    val patchPixel = (DETAIL_RADIUS + dy) * PATCH_WIDTH + DETAIL_RADIUS + dx
                    pairs[pairOffset++] = globalPixel * Short.SIZE_BYTES
                    pairs[pairOffset++] = p010Code(patchBuffer, patchPixel * Short.SIZE_BYTES)
                }
            }
            val globalChroma = (blockY / 2) * (width / 2) + blockX / 2
            val patchChroma = (DETAIL_RADIUS / 2) * (PATCH_WIDTH / 2) + DETAIL_RADIUS / 2
            val globalChromaOffset = width * height * Short.SIZE_BYTES +
                globalChroma * 2 * Short.SIZE_BYTES
            val patchChromaOffset = PATCH_WIDTH * PATCH_HEIGHT * Short.SIZE_BYTES +
                patchChroma * 2 * Short.SIZE_BYTES
            pairs[pairOffset++] = globalChromaOffset
            pairs[pairOffset++] = p010Code(patchBuffer, patchChromaOffset)
            pairs[pairOffset++] = globalChromaOffset + Short.SIZE_BYTES
            pairs[pairOffset++] = p010Code(patchBuffer, patchChromaOffset + Short.SIZE_BYTES)
            blockOffset += 2
        }
        return RawDevelopP010SampleOracle(pairs)
    }

    fun evaluate(
        reference: RawDevelopP010SampleOracle,
        candidate: ByteBuffer,
        meanTolerance: Double,
        maximumTolerance: Int
    ): RawDevelopP010ParityEvidence {
        require(meanTolerance >= 0.0 && maximumTolerance >= 0) { "P010 tolerances must be non-negative" }
        val candidateView = candidate.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var sumAbsolute = 0.0
        var maximumAbsolute = 0
        var offset = 0
        while (offset < reference.offsetAndCode.size) {
            val byteOffset = reference.offsetAndCode[offset]
            require(byteOffset >= 0 && byteOffset + Short.SIZE_BYTES <= candidateView.capacity()) {
                "P010 sample offset $byteOffset exceeds candidate capacity ${candidateView.capacity()}"
            }
            val expected = reference.offsetAndCode[offset + 1]
            val actual = p010Code(candidateView, byteOffset)
            val difference = abs(expected - actual)
            sumAbsolute += difference.toDouble()
            if (difference > maximumAbsolute) maximumAbsolute = difference
            offset += 2
        }
        val samples = reference.sampledComponents
        val mean = if (samples == 0) Double.POSITIVE_INFINITY else sumAbsolute / samples
        return RawDevelopP010ParityEvidence(
            accepted = samples > 0 && mean <= meanTolerance && maximumAbsolute <= maximumTolerance,
            sampledComponents = samples,
            meanAbsoluteCodeDifference = mean,
            maximumAbsoluteCodeDifference = maximumAbsolute
        )
    }

    fun evaluate(
        reference: ByteBuffer,
        candidate: ByteBuffer,
        width: Int,
        height: Int,
        meanTolerance: Double,
        maximumTolerance: Int
    ): RawDevelopP010ParityEvidence {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "P010 parity geometry must be positive and even"
        }
        val bytes = Math.multiplyExact(Math.multiplyExact(width, height), 3)
        require(reference.capacity() >= bytes && candidate.capacity() >= bytes) {
            "P010 parity inputs are smaller than $bytes bytes"
        }
        val referenceView = reference.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val candidateView = candidate.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val componentCount = bytes / Short.SIZE_BYTES
        val samples = min(MAX_SAMPLED_COMPONENTS, componentCount)
        var sumAbsolute = 0.0
        var maximumAbsolute = 0
        for (sample in 0 until samples) {
            val component = if (samples == 1) {
                0
            } else {
                ((componentCount - 1).toLong() * sample / (samples - 1)).toInt()
            }
            val offset = component * Short.SIZE_BYTES
            val expected = (referenceView.getShort(offset).toInt() and 0xffff) ushr 6
            val actual = (candidateView.getShort(offset).toInt() and 0xffff) ushr 6
            val difference = abs(expected - actual)
            sumAbsolute += difference.toDouble()
            if (difference > maximumAbsolute) maximumAbsolute = difference
        }
        val mean = if (samples == 0) Double.POSITIVE_INFINITY else sumAbsolute / samples
        return RawDevelopP010ParityEvidence(
            accepted = samples > 0 && mean <= meanTolerance && maximumAbsolute <= maximumTolerance,
            sampledComponents = samples,
            meanAbsoluteCodeDifference = mean,
            maximumAbsoluteCodeDifference = maximumAbsolute
        )
    }

    internal fun stratifiedBlockOrigins(width: Int, height: Int): IntArray {
        require(width >= MIN_ORACLE_EXTENT && height >= MIN_ORACLE_EXTENT) {
            "P010 oracle geometry must be at least ${MIN_ORACLE_EXTENT}x$MIN_ORACLE_EXTENT"
        }
        require(width % 2 == 0 && height % 2 == 0) { "P010 oracle geometry must be even" }
        val minimumX = DETAIL_RADIUS
        val minimumY = DETAIL_RADIUS
        val maximumX = width - DETAIL_RADIUS - 2
        val maximumY = height - DETAIL_RADIUS - 2
        val availableColumns = ((maximumX - minimumX) / 2 + 1).coerceAtLeast(1)
        val availableRows = ((maximumY - minimumY) / 2 + 1).coerceAtLeast(1)
        val columns = min(ORACLE_GRID_COLUMNS, availableColumns)
        val rows = min((MAX_ORACLE_BLOCKS / columns).coerceAtLeast(1), availableRows)
        val result = IntArray(columns * rows * 2)
        var output = 0
        for (row in 0 until rows) {
            val yIndex = if (rows == 1) availableRows / 2 else
                ((availableRows - 1).toLong() * row / (rows - 1)).toInt()
            val y = minimumY + yIndex * 2
            for (column in 0 until columns) {
                val xIndex = if (columns == 1) availableColumns / 2 else
                    ((availableColumns - 1).toLong() * column / (columns - 1)).toInt()
                result[output++] = minimumX + xIndex * 2
                result[output++] = y
            }
        }
        return result
    }

    private fun p010Code(buffer: ByteBuffer, offset: Int): Int =
        (buffer.getShort(offset).toInt() and 0xffff) ushr 6

    private const val DETAIL_RADIUS = 2
    private const val PATCH_WIDTH = 6
    private const val PATCH_HEIGHT = 6
    private const val MIN_ORACLE_EXTENT = 8
    private const val ORACLE_GRID_COLUMNS = 16
    private const val MAX_ORACLE_BLOCKS = 128
    private const val COMPONENTS_PER_BLOCK = 6
}

/** Bounded stratified parity oracle for the offline RAW Vulkan demosaic. */
internal object RawDevelopSampledParityPolicy {
    private const val MAX_SAMPLE_PIXELS = 2_048
    private const val GRID_COLUMNS = 64

    fun evaluate(
        rawSamples: IntArray,
        meta: RawDevelopMetadata,
        gpuAcescg: FloatBuffer,
        variant: NeoLogCurve.Variant,
        exposureStops: Float,
        meanTolerance: Double,
        maximumTolerance: Int
    ): RawDevelopSampledParityEvidence {
        val coordinates = stratifiedCoordinates(meta.width, meta.height)
        val cpu = RawNeoLogDevelop.developGpuReferenceAcescgSamples(rawSamples, meta, coordinates)
        val gpu = gpuAcescg.slice()
        val exposureScale = RawExposureNormalizationPolicy.exposureScale(exposureStops)
        var sumAbsolute = 0.0
        var maximumAbsolute = 0
        var finite = 0
        var sourceOffset = 0
        var sample = 0
        while (sourceOffset < coordinates.size) {
            val x = coordinates[sourceOffset]
            val y = coordinates[sourceOffset + 1]
            val gpuOffset = (y * meta.width + x) * 3
            val cpuOffset = sample * 3
            for (channel in 0..2) {
                val gpuLinear = gpu.get(gpuOffset + channel)
                if (!gpuLinear.isFinite()) {
                    return RawDevelopSampledParityEvidence(
                        accepted = false,
                        sampledPixels = coordinates.size / 2,
                        meanAbsoluteCodeDifference = Double.POSITIVE_INFINITY,
                        maximumAbsoluteCodeDifference = Int.MAX_VALUE,
                        finiteGpuSamples = finite
                    )
                }
                finite++
                val gpuCode = NeoLogCurve.quantize(gpuLinear * exposureScale, variant, 10)
                val cpuCode = NeoLogCurve.quantize(cpu[cpuOffset + channel] * exposureScale, variant, 10)
                val difference = abs(gpuCode - cpuCode)
                sumAbsolute += difference.toDouble()
                if (difference > maximumAbsolute) maximumAbsolute = difference
            }
            sourceOffset += 2
            sample++
        }
        val componentCount = sample * 3
        val mean = if (componentCount == 0) Double.POSITIVE_INFINITY else sumAbsolute / componentCount
        return RawDevelopSampledParityEvidence(
            accepted = componentCount > 0 && mean <= meanTolerance && maximumAbsolute <= maximumTolerance,
            sampledPixels = sample,
            meanAbsoluteCodeDifference = mean,
            maximumAbsoluteCodeDifference = maximumAbsolute,
            finiteGpuSamples = finite
        )
    }

    internal fun stratifiedCoordinates(width: Int, height: Int): IntArray {
        require(width > 0 && height > 0) { "RAW parity geometry must be positive" }
        val border = if (width >= 9 && height >= 9) 4 else 0
        val usableWidth = (width - border * 2).coerceAtLeast(1)
        val usableHeight = (height - border * 2).coerceAtLeast(1)
        val columns = min(GRID_COLUMNS, usableWidth)
        val rows = min((MAX_SAMPLE_PIXELS / columns).coerceAtLeast(1), usableHeight)
        val coordinates = IntArray(columns * rows * 2)
        var offset = 0
        for (row in 0 until rows) {
            val y = border + if (rows == 1) {
                (usableHeight - 1) / 2
            } else {
                ((usableHeight - 1).toLong() * row / (rows - 1)).toInt()
            }
            for (column in 0 until columns) {
                val x = border + if (columns == 1) {
                    (usableWidth - 1) / 2
                } else {
                    ((usableWidth - 1).toLong() * column / (columns - 1)).toInt()
                }
                coordinates[offset++] = x
                coordinates[offset++] = y
            }
        }
        return coordinates
    }
}

/** One reusable, encoder-sized direct P010 buffer. The sink must consume it synchronously. */
internal class RawDevelopDirectP010Workspace {
    private var buffer: ByteBuffer? = null

    @Synchronized
    fun use(width: Int, height: Int, operation: (ByteBuffer) -> Unit) {
        val required = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(width, height).toInt()
        val current = buffer
        val active = if (current != null && current.capacity() >= required) {
            current
        } else {
            ByteBuffer.allocateDirect(required).order(ByteOrder.LITTLE_ENDIAN).also { buffer = it }
        }
        val frame = active.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            clear()
            limit(required)
        }.slice().order(ByteOrder.LITTLE_ENDIAN)
        operation(frame)
    }
}

/** Exclusive wall-time attribution for one RAW conversion. Percentages are measurements, not quotas. */
internal class RawConversionStageTelemetry(private val route: String) {
    private var cpuNanos = 0L
    private var gpuNanos = 0L
    private var encoderNanos = 0L
    private var copyWaitNanos = 0L
    private var frames = 0L

    fun addCpu(nanos: Long) { cpuNanos = saturatedAdd(cpuNanos, nanos) }
    fun addGpu(nanos: Long) { gpuNanos = saturatedAdd(gpuNanos, nanos) }
    fun addEncoder(nanos: Long) { encoderNanos = saturatedAdd(encoderNanos, nanos) }
    fun addCopyWait(nanos: Long) { copyWaitNanos = saturatedAdd(copyWaitNanos, nanos) }
    fun frameCompleted() { frames = saturatedAdd(frames, 1L) }

    fun logFinal() {
        val total = cpuNanos + gpuNanos + encoderNanos + copyWaitNanos
        fun percentage(value: Long): String = if (total <= 0L) "0.00" else "%.2f".format(
            java.util.Locale.US,
            value.toDouble() * 100.0 / total.toDouble()
        )
        NeoLogger.i(
            TAG,
            "RAW_CONVERSION_STAGE_SHARE route=$route frames=$frames " +
                "cpuPct=${percentage(cpuNanos)} gpuPct=${percentage(gpuNanos)} " +
                "hardwareEncoderPct=${percentage(encoderNanos)} copyWaitPct=${percentage(copyWaitNanos)} " +
                "cpuMs=${cpuNanos / 1_000_000.0} gpuMs=${gpuNanos / 1_000_000.0} " +
                "hardwareEncoderMs=${encoderNanos / 1_000_000.0} copyWaitMs=${copyWaitNanos / 1_000_000.0}"
        )
    }

    private fun saturatedAdd(a: Long, b: Long): Long =
        if (b <= 0L) a else if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

    private companion object {
        const val TAG = "RawConversionWorkload"
    }
}
