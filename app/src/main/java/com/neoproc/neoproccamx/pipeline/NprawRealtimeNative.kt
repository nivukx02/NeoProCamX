package com.neoproc.neoproccamx.pipeline

import java.nio.ByteBuffer

/** Exact native analysis/copy paths for the lossless realtime NPRAW packet. */
internal object NprawRealtimeNative {
    internal data class ExactStats(
        val minimum: Int,
        val maximum: Int,
        val sampledDifferenceSum: Long,
        val sampledDifferenceCount: Int,
        val crc32: Long
    )

    internal data class BlockRangeStats(
        val minimum: Int,
        val maximum: Int,
        val sampledDifferenceSum: Long,
        val sampledDifferenceCount: Int,
        val crc32: Long,
        val payloadBytes: Int
    )

    @Volatile
    private var available = try {
        System.loadLibrary("neoproc_vulkan")
        true
    } catch (_: Throwable) {
        false
    }

    private val threadStats = object : ThreadLocal<LongArray>() {
        override fun initialValue(): LongArray = LongArray(STATS_COUNT)
    }
    private val blockRangeThreadStats = object : ThreadLocal<LongArray>() {
        override fun initialValue(): LongArray = LongArray(BLOCK_RANGE_STATS_COUNT)
    }

    fun prewarm(): Boolean = available

    fun packLinear16Payload(
        source: ByteBuffer,
        destination: ByteBuffer,
        destinationOffset: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): ExactStats? {
        if (!available || !source.isDirect || !destination.isDirect) return null
        val stats = threadStats.get() ?: LongArray(STATS_COUNT).also(threadStats::set)
        stats.fill(0L)
        val packed = try {
            nativePackLinear16Payload(
                sourceBuffer = source,
                sourceLimit = source.limit(),
                destinationBuffer = destination,
                destinationOffset = destinationOffset,
                width = width,
                height = height,
                rowStride = rowStride,
                pixelStride = pixelStride,
                statsOut = stats
            )
        } catch (_: UnsatisfiedLinkError) {
            available = false
            false
        } catch (_: LinkageError) {
            available = false
            false
        }
        if (!packed) return null
        return ExactStats(
            minimum = stats[STAT_MINIMUM].toInt(),
            maximum = stats[STAT_MAXIMUM].toInt(),
            sampledDifferenceSum = stats[STAT_DIFFERENCE_SUM],
            sampledDifferenceCount = stats[STAT_DIFFERENCE_COUNT].toInt(),
            crc32 = stats[STAT_CRC32]
        )
    }

    /**
     * Packs a RAW16 camera plane into the NPRAW 12-bit byte layout without an intermediate
     * [IntArray] or heap payload. The operation is bit-exact only when every source code fits in
     * 12 bits; native code verifies that invariant and returns null instead of clipping a sample.
     */
    fun packLinear12Payload(
        source: ByteBuffer,
        destination: ByteBuffer,
        destinationOffset: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): ExactStats? {
        if (!available || !source.isDirect || !destination.isDirect) return null
        val stats = threadStats.get() ?: LongArray(STATS_COUNT).also(threadStats::set)
        stats.fill(0L)
        val packed = try {
            nativePackLinear12Payload(
                sourceBuffer = source,
                sourceLimit = source.limit(),
                destinationBuffer = destination,
                destinationOffset = destinationOffset,
                width = width,
                height = height,
                rowStride = rowStride,
                pixelStride = pixelStride,
                statsOut = stats
            )
        } catch (_: UnsatisfiedLinkError) {
            available = false
            false
        } catch (_: LinkageError) {
            available = false
            false
        }
        if (!packed) return null
        return ExactStats(
            minimum = stats[STAT_MINIMUM].toInt(),
            maximum = stats[STAT_MAXIMUM].toInt(),
            sampledDifferenceSum = stats[STAT_DIFFERENCE_SUM],
            sampledDifferenceCount = stats[STAT_DIFFERENCE_COUNT].toInt(),
            crc32 = stats[STAT_CRC32]
        )
    }

    /**
     * Encodes a RAW plane with independent 64x4 range blocks. Each block stores its exact minimum
     * and bit-packs unsigned residuals at the smallest sufficient width. No sensor code is altered.
     */
    fun packBlockRangePayload(
        source: ByteBuffer,
        destination: ByteBuffer,
        destinationOffset: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        sensorWhiteLevel: Int
    ): BlockRangeStats? {
        if (!available || !source.isDirect || !destination.isDirect) return null
        val stats = blockRangeThreadStats.get()
            ?: LongArray(BLOCK_RANGE_STATS_COUNT).also(blockRangeThreadStats::set)
        stats.fill(0L)
        val packed = try {
            nativePackBlockRangePayload(
                sourceBuffer = source,
                sourceLimit = source.limit(),
                destinationBuffer = destination,
                destinationOffset = destinationOffset,
                width = width,
                height = height,
                rowStride = rowStride,
                pixelStride = pixelStride,
                sensorWhiteLevel = sensorWhiteLevel,
                statsOut = stats
            )
        } catch (_: UnsatisfiedLinkError) {
            available = false
            false
        } catch (_: LinkageError) {
            available = false
            false
        }
        if (!packed || stats[STAT_PAYLOAD_BYTES] !in 1..Int.MAX_VALUE.toLong()) return null
        return BlockRangeStats(
            minimum = stats[STAT_MINIMUM].toInt(),
            maximum = stats[STAT_MAXIMUM].toInt(),
            sampledDifferenceSum = stats[STAT_DIFFERENCE_SUM],
            sampledDifferenceCount = stats[STAT_DIFFERENCE_COUNT].toInt(),
            crc32 = stats[STAT_CRC32],
            payloadBytes = stats[STAT_PAYLOAD_BYTES].toInt()
        )
    }

    /**
     * Analyze a tightly packed, camera-owned RAW16 plane in place. No full-frame copy is made.
     * The caller must retain the owning Image until its storage write completes.
     */
    fun analyzeContiguousLinear16Payload(
        source: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): ExactStats? {
        val expectedRowBytes = width.toLong() * 2L
        val payloadBytes = expectedRowBytes * height.toLong()
        if (!available || !source.isDirect || width <= 0 || height <= 0 ||
            pixelStride != 2 || rowStride.toLong() != expectedRowBytes ||
            payloadBytes <= 0L || payloadBytes > Int.MAX_VALUE ||
            source.limit().toLong() < payloadBytes
        ) return null

        val stats = threadStats.get() ?: LongArray(STATS_COUNT).also(threadStats::set)
        stats.fill(0L)
        val analyzed = try {
            nativeAnalyzeContiguousLinear16Payload(
                sourceBuffer = source,
                sourceLimit = source.limit(),
                width = width,
                height = height,
                rowStride = rowStride,
                pixelStride = pixelStride,
                statsOut = stats
            )
        } catch (_: UnsatisfiedLinkError) {
            available = false
            false
        } catch (_: LinkageError) {
            available = false
            false
        }
        if (!analyzed) return null
        return ExactStats(
            minimum = stats[STAT_MINIMUM].toInt(),
            maximum = stats[STAT_MAXIMUM].toInt(),
            sampledDifferenceSum = stats[STAT_DIFFERENCE_SUM],
            sampledDifferenceCount = stats[STAT_DIFFERENCE_COUNT].toInt(),
            crc32 = stats[STAT_CRC32]
        )
    }

    @JvmStatic
    private external fun nativePackLinear16Payload(
        sourceBuffer: ByteBuffer,
        sourceLimit: Int,
        destinationBuffer: ByteBuffer,
        destinationOffset: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        statsOut: LongArray
    ): Boolean

    @JvmStatic
    private external fun nativePackLinear12Payload(
        sourceBuffer: ByteBuffer,
        sourceLimit: Int,
        destinationBuffer: ByteBuffer,
        destinationOffset: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        statsOut: LongArray
    ): Boolean

    @JvmStatic
    private external fun nativePackBlockRangePayload(
        sourceBuffer: ByteBuffer,
        sourceLimit: Int,
        destinationBuffer: ByteBuffer,
        destinationOffset: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        sensorWhiteLevel: Int,
        statsOut: LongArray
    ): Boolean

    @JvmStatic
    private external fun nativeAnalyzeContiguousLinear16Payload(
        sourceBuffer: ByteBuffer,
        sourceLimit: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        statsOut: LongArray
    ): Boolean

    private const val STAT_MINIMUM = 0
    private const val STAT_MAXIMUM = 1
    private const val STAT_DIFFERENCE_SUM = 2
    private const val STAT_DIFFERENCE_COUNT = 3
    private const val STAT_CRC32 = 4
    private const val STAT_PAYLOAD_BYTES = 5
    private const val STATS_COUNT = 5
    private const val BLOCK_RANGE_STATS_COUNT = 6
}
