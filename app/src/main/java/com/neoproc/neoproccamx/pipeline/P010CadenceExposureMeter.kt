package com.neoproc.neoproccamx.pipeline

import java.nio.ByteBuffer

internal data class P010CadenceExposureMetrics(
    val averageLuma: Float,
    val highlightClipFraction: Float,
    val sampleCount: Int
)

/**
 * Allocation-free, whole-frame P010 meter for the NPV10 recording ownership path.
 *
 * Once the recorder owns a P010 Image, the ordinary scene-analysis path correctly returns without
 * touching it. The 1K/30 cadence guard still needs a fresh brightness signal, however. Sampling a
 * fixed 32x18 grid every sixth frame costs only 576 absolute reads and never copies, closes, mutates,
 * or changes the recorded ten-bit buffer.
 */
internal object P010CadenceExposureMeter {
    const val FRAME_STRIDE = 6L
    private const val GRID_COLUMNS = 32
    private const val GRID_ROWS = 18
    private const val HIGHLIGHT_CODE_10 = 896 // 224 on the established 8-bit analysis scale.

    fun shouldMeasure(width: Int, height: Int, frameSequence: Long): Boolean =
        minOf(width, height) == 720 &&
            maxOf(width, height) == 1280 &&
            frameSequence > 0L &&
            frameSequence % FRAME_STRIDE == 0L

    fun measure(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): P010CadenceExposureMetrics? {
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride < 2) return null
        val baseOffset = buffer.position()
        val limit = buffer.limit()
        if (baseOffset < 0 || baseOffset >= limit) return null

        val columns = minOf(GRID_COLUMNS, width)
        val rows = minOf(GRID_ROWS, height)
        var sumLuma8 = 0L
        var highlights = 0
        var samples = 0
        for (gridY in 0 until rows) {
            val sourceY = (((gridY * 2L + 1L) * height) / (rows * 2L))
                .toInt()
                .coerceIn(0, height - 1)
            val rowOffset = baseOffset.toLong() + sourceY.toLong() * rowStride.toLong()
            for (gridX in 0 until columns) {
                val sourceX = (((gridX * 2L + 1L) * width) / (columns * 2L))
                    .toInt()
                    .coerceIn(0, width - 1)
                val indexLong = rowOffset + sourceX.toLong() * pixelStride.toLong()
                if (indexLong < baseOffset.toLong() || indexLong + 1L >= limit.toLong()) continue
                val index = indexLong.toInt()
                val littleEndianWord =
                    (buffer.get(index).toInt() and 0xFF) or
                        ((buffer.get(index + 1).toInt() and 0xFF) shl 8)
                val code10 = (littleEndianWord ushr 6).coerceIn(0, 1023)
                sumLuma8 += ((code10 + 2) ushr 2).coerceIn(0, 255)
                if (code10 >= HIGHLIGHT_CODE_10) highlights++
                samples++
            }
        }
        if (samples == 0) return null
        return P010CadenceExposureMetrics(
            averageLuma = (sumLuma8.toDouble() / samples.toDouble() / 255.0)
                .toFloat()
                .coerceIn(0f, 1f),
            highlightClipFraction = (highlights.toFloat() / samples.toFloat()).coerceIn(0f, 1f),
            sampleCount = samples
        )
    }
}
