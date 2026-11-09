package com.neoproc.neoproccamx.pipeline

import java.nio.ByteBuffer

internal object RgbaImageCopyPolicy {
    fun copyToArgb(
        source: ByteBuffer,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceRowStride: Int,
        sourcePixelStride: Int,
        targetWidth: Int,
        targetHeight: Int
    ): IntArray {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        require(targetWidth > 0 && targetHeight > 0) { "Target dimensions must be positive" }
        require(sourcePixelStride >= 4) { "RGBA pixel stride must be at least 4 bytes" }
        require(sourceRowStride >= sourceWidth * sourcePixelStride) { "RGBA row stride is too small" }

        val duplicate = source.duplicate()
        val output = IntArray(targetWidth * targetHeight)
        var outputIndex = 0
        for (targetY in 0 until targetHeight) {
            val sourceY = centerSampleIndex(targetY, targetHeight, sourceHeight)
            val rowStart = sourceY * sourceRowStride
            for (targetX in 0 until targetWidth) {
                val sourceX = centerSampleIndex(targetX, targetWidth, sourceWidth)
                val offset = rowStart + sourceX * sourcePixelStride
                val red = duplicate.get(offset).toInt() and 0xFF
                val green = duplicate.get(offset + 1).toInt() and 0xFF
                val blue = duplicate.get(offset + 2).toInt() and 0xFF
                val alpha = duplicate.get(offset + 3).toInt() and 0xFF
                output[outputIndex++] =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return output
    }

    private fun centerSampleIndex(
        targetIndex: Int,
        targetSize: Int,
        sourceSize: Int
    ): Int =
        (((targetIndex + 0.5f) * sourceSize.toFloat() / targetSize.toFloat()))
            .toInt()
            .coerceIn(0, sourceSize - 1)
}
