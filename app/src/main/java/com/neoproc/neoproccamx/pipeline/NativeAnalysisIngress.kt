package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.PerceptualLumaAggregates
import com.neoproc.neoproccamx.core.PerceptualSignalMath
import com.neoproc.neoproccamx.core.PerceptualSignalSample
import java.nio.ByteBuffer

/** AOT integer gather kernels for the bounded P010 analysis proxy. */
internal object NativeAnalysisIngress {
    @Volatile
    private var available = try {
        System.loadLibrary("neoproc_vulkan")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    fun copyScaledP010Luma(
        source: ByteBuffer,
        sourceLimit: Int,
        sourceRows: IntArray,
        sourceColumns: IntArray,
        targetWidth: Int,
        targetHeight: Int,
        destination: ByteArray
    ): Boolean {
        if (!available) return false
        return try {
            nativeCopyScaledP010Luma(
                source,
                sourceLimit,
                sourceRows,
                sourceColumns,
                targetWidth,
                targetHeight,
                destination
            )
        } catch (_: Throwable) {
            available = false
            false
        }
    }

    fun copyScaledP010Chroma(
        source: ByteBuffer,
        sourceLimit: Int,
        sourceRowStride: Int,
        sourcePixelStride: Int,
        sourceRows: IntArray,
        sourceColumns: IntArray,
        targetWidth: Int,
        targetHeight: Int,
        targetRowStride: Int,
        uDestination: ByteArray,
        vDestination: ByteArray
    ): Boolean {
        if (!available) return false
        return try {
            nativeCopyScaledP010Chroma(
                source,
                sourceLimit,
                sourceRowStride,
                sourcePixelStride,
                sourceRows,
                sourceColumns,
                targetWidth,
                targetHeight,
                targetRowStride,
                uDestination,
                vDestination
            )
        } catch (_: Throwable) {
            available = false
            false
        }
    }

    fun computeSceneStats(
        width: Int,
        height: Int,
        luma: ByteArray,
        maxStatsLongEdge: Int
    ): SceneStats? {
        if (!available) return null
        val histogram = IntArray(256)
        val totals = LongArray(2)
        val completed = try {
            nativeComputeSceneStats(
                luma,
                width,
                height,
                maxStatsLongEdge,
                histogram,
                totals
            )
        } catch (_: Throwable) {
            available = false
            false
        }
        if (!completed) return null
        val sampleCount = totals[1]
        val averageLuma = if (sampleCount <= 0L) {
            0.5f
        } else {
            totals[0].toFloat() / sampleCount.toFloat() / 255f
        }
        return SceneStats(histogram, averageLuma)
    }

    fun analyzePerceptualLuma(
        luma: ByteArray,
        width: Int,
        height: Int
    ): PerceptualSignalSample? {
        if (!available) return null
        val totals = LongArray(6)
        val completed = try {
            nativeCollectPerceptualLumaAggregates(luma, width, height, totals)
        } catch (_: Throwable) {
            available = false
            false
        }
        if (!completed) return null
        return PerceptualSignalMath.analyzeLumaAggregates(
            PerceptualLumaAggregates(
                flatSum = totals[0],
                flatSumSquares = totals[1],
                flatCount = totals[2].toInt(),
                laplacianSum = totals[3],
                texturedCount = totals[4].toInt(),
                totalSamples = totals[5].toInt()
            )
        )
    }

    /** Fail closed to the byte-identical Kotlin traversal after any startup parity mismatch. */
    fun disable() {
        available = false
    }

    @JvmStatic
    private external fun nativeCopyScaledP010Luma(
        source: ByteBuffer,
        sourceLimit: Int,
        sourceRows: IntArray,
        sourceColumns: IntArray,
        targetWidth: Int,
        targetHeight: Int,
        destination: ByteArray
    ): Boolean

    @JvmStatic
    private external fun nativeCopyScaledP010Chroma(
        source: ByteBuffer,
        sourceLimit: Int,
        sourceRowStride: Int,
        sourcePixelStride: Int,
        sourceRows: IntArray,
        sourceColumns: IntArray,
        targetWidth: Int,
        targetHeight: Int,
        targetRowStride: Int,
        uDestination: ByteArray,
        vDestination: ByteArray
    ): Boolean

    @JvmStatic
    private external fun nativeComputeSceneStats(
        luma: ByteArray,
        width: Int,
        height: Int,
        maxStatsLongEdge: Int,
        histogram: IntArray,
        totals: LongArray
    ): Boolean

    @JvmStatic
    private external fun nativeCollectPerceptualLumaAggregates(
        luma: ByteArray,
        width: Int,
        height: Int,
        totals: LongArray
    ): Boolean
}
