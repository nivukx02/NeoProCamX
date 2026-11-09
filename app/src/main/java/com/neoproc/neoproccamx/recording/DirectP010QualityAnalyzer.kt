package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Sparse quality analysis over a direct P010 buffer; no frame-sized heap mirror is created. */
internal object DirectP010QualityAnalyzer {
    private const val TEMPORAL_LUMA_MOTION_THRESHOLD = 22
    private const val TEMPORAL_CHROMA_MOTION_THRESHOLD = 34
    private const val NEUTRAL_CHROMA = 512

    fun measureNoise(
        payload: ByteBuffer,
        previousP010Payload: ByteBuffer?,
        width: Int,
        height: Int
    ): VideoNoiseMetrics {
        val current = payload.slice().order(ByteOrder.LITTLE_ENDIAN)
        val previous = previousP010Payload?.slice()?.order(ByteOrder.LITTLE_ENDIAN)
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val lumaSamples = safeWidth * safeHeight
        if (current.remaining() < lumaSamples * Short.SIZE_BYTES) return VideoNoiseMetrics.EMPTY
        val lumaStride = max(1, lumaSamples / 4096)
        var lumaNoiseSum = 0.0
        var lumaNoiseCount = 0
        var temporalSum = 0.0
        var rgbVarianceSum = 0.0
        var rgbVarianceCount = 0
        var temporalCount = 0
        var sampleIndex = 0
        while (sampleIndex < lumaSamples) {
            val offset = sampleIndex * Short.SIZE_BYTES
            val y = readP010(current, offset)
            val row = sampleIndex / safeWidth
            val column = sampleIndex % safeWidth
            if (row > 0 && row < safeHeight - 1 && column > 0 && column < safeWidth - 1) {
                val left = readP010(current, offset - Short.SIZE_BYTES)
                val right = readP010(current, offset + Short.SIZE_BYTES)
                val above = readP010(current, offset - safeWidth * Short.SIZE_BYTES)
                val below = readP010(current, offset + safeWidth * Short.SIZE_BYTES)
                val horizontalResidual = abs(y * 2 - left - right) * 0.5
                val verticalResidual = abs(y * 2 - above - below) * 0.5
                lumaNoiseSum += minOf(horizontalResidual, verticalResidual, 96.0)
                lumaNoiseCount += 1
            }
            if (previous != null && previous.remaining() == current.remaining()) {
                val previousY = readP010(previous, offset)
                val lumaDelta = abs(y - previousY)
                val chromaIndex = chromaPairOffsetForLumaSample(safeWidth, safeHeight, sampleIndex)
                if (chromaIndex + Short.SIZE_BYTES < current.remaining()) {
                    val u = readP010(current, chromaIndex)
                    val v = readP010(current, chromaIndex + Short.SIZE_BYTES)
                    val previousU = readP010(previous, chromaIndex)
                    val previousV = readP010(previous, chromaIndex + Short.SIZE_BYTES)
                    val chromaDelta = maxOf(abs(u - previousU), abs(v - previousV))
                    if (lumaDelta <= TEMPORAL_LUMA_MOTION_THRESHOLD &&
                        chromaDelta <= TEMPORAL_CHROMA_MOTION_THRESHOLD
                    ) {
                        temporalSum += lumaDelta.toDouble()
                        rgbVarianceSum += rgbFrameDifference(
                            y,
                            u,
                            v,
                            previousY,
                            previousU,
                            previousV
                        )
                        rgbVarianceCount += 1
                        temporalCount += 1
                    }
                }
            }
            sampleIndex += lumaStride
        }

        val chromaBase = lumaSamples * Short.SIZE_BYTES
        val chromaSamples = (current.remaining() - chromaBase) / (Short.SIZE_BYTES * 2)
        val chromaStride = max(1, chromaSamples / 2048)
        var chromaNoiseSum = 0.0
        var chromaCount = 0
        var chromaIndex = 0
        while (chromaIndex < chromaSamples) {
            val offset = chromaBase + chromaIndex * Short.SIZE_BYTES * 2
            val u = readP010(current, offset)
            val v = readP010(current, offset + Short.SIZE_BYTES)
            val chromaRowWidth = (safeWidth + 1) / 2
            val chromaRow = chromaIndex / chromaRowWidth
            val chromaColumn = chromaIndex % chromaRowWidth
            val chromaHeight = (safeHeight + 1) / 2
            if (chromaRow > 0 && chromaRow < chromaHeight - 1 &&
                chromaColumn > 0 && chromaColumn < chromaRowWidth - 1
            ) {
                val leftOffset = offset - Short.SIZE_BYTES * 2
                val rightOffset = offset + Short.SIZE_BYTES * 2
                val aboveOffset = offset - chromaRowWidth * Short.SIZE_BYTES * 2
                val belowOffset = offset + chromaRowWidth * Short.SIZE_BYTES * 2
                val uHorizontal = abs(
                    u * 2 - readP010(current, leftOffset) - readP010(current, rightOffset)
                ) * 0.5
                val uVertical = abs(
                    u * 2 - readP010(current, aboveOffset) - readP010(current, belowOffset)
                ) * 0.5
                val vHorizontal = abs(
                    v * 2 - readP010(current, leftOffset + Short.SIZE_BYTES) -
                        readP010(current, rightOffset + Short.SIZE_BYTES)
                ) * 0.5
                val vVertical = abs(
                    v * 2 - readP010(current, aboveOffset + Short.SIZE_BYTES) -
                        readP010(current, belowOffset + Short.SIZE_BYTES)
                ) * 0.5
                chromaNoiseSum += minOf(uHorizontal, uVertical, 128.0)
                chromaNoiseSum += minOf(vHorizontal, vVertical, 128.0)
                chromaCount += 2
            } else if (chromaSamples < 9 && (chromaRow > 0 || chromaColumn > 0)) {
                var uResidual = 0
                var vResidual = 0
                if (chromaColumn > 0) {
                    val leftOffset = offset - Short.SIZE_BYTES * 2
                    uResidual = maxOf(uResidual, abs(u - readP010(current, leftOffset)))
                    vResidual = maxOf(
                        vResidual,
                        abs(v - readP010(current, leftOffset + Short.SIZE_BYTES))
                    )
                }
                if (chromaRow > 0) {
                    val aboveOffset = offset - chromaRowWidth * Short.SIZE_BYTES * 2
                    uResidual = maxOf(uResidual, abs(u - readP010(current, aboveOffset)))
                    vResidual = maxOf(
                        vResidual,
                        abs(v - readP010(current, aboveOffset + Short.SIZE_BYTES))
                    )
                }
                chromaNoiseSum += minOf(uResidual.toDouble(), 128.0)
                chromaNoiseSum += minOf(vResidual.toDouble(), 128.0)
                chromaCount += 2
            }
            if (previous != null && previous.remaining() == current.remaining()) {
                val uDelta = abs(u - readP010(previous, offset))
                val vDelta = abs(v - readP010(previous, offset + Short.SIZE_BYTES))
                if (uDelta <= TEMPORAL_CHROMA_MOTION_THRESHOLD) {
                    temporalSum += uDelta.toDouble()
                    temporalCount += 1
                }
                if (vDelta <= TEMPORAL_CHROMA_MOTION_THRESHOLD) {
                    temporalSum += vDelta.toDouble()
                    temporalCount += 1
                }
            }
            chromaIndex += chromaStride
        }

        return VideoNoiseMetrics(
            chromaNoise = if (chromaCount == 0) 0f else (chromaNoiseSum / chromaCount / 512.0).toFloat(),
            lumaNoise = if (lumaNoiseCount == 0) 0f else (lumaNoiseSum / lumaNoiseCount / 1023.0).toFloat(),
            temporalInstability = if (temporalCount == 0) 0f else (temporalSum / temporalCount / 1023.0).toFloat(),
            frameToFrameRgbVariance = if (rgbVarianceCount == 0) 0f else (rgbVarianceSum / rgbVarianceCount).toFloat()
        ).clamped()
    }

    private fun readP010(payload: ByteBuffer, offset: Int): Int =
        ((payload.getShort(offset).toInt() and 0xFFFF) ushr 6).coerceIn(0, 1023)

    private fun chromaPairOffsetForLumaSample(width: Int, height: Int, sampleIndex: Int): Int {
        val row = sampleIndex / width
        val column = sampleIndex % width
        val chromaWidth = (width + 1) / 2
        val chromaRow = row.coerceIn(0, height - 1) / 2
        val chromaColumn = column.coerceIn(0, width - 1) / 2
        return width * height * Short.SIZE_BYTES +
            (chromaRow * chromaWidth + chromaColumn) * Short.SIZE_BYTES * 2
    }

    private fun rgbFrameDifference(
        y: Int,
        u: Int,
        v: Int,
        previousY: Int,
        previousU: Int,
        previousV: Int
    ): Double {
        val current = yuvToRgb(y, u, v)
        val previous = yuvToRgb(previousY, previousU, previousV)
        return (abs(current[0] - previous[0]) +
            abs(current[1] - previous[1]) +
            abs(current[2] - previous[2])) / 3.0
    }

    private fun yuvToRgb(y: Int, u: Int, v: Int): DoubleArray {
        val yf = y.toDouble() / 1023.0
        val uf = (u - NEUTRAL_CHROMA).toDouble() / 512.0
        val vf = (v - NEUTRAL_CHROMA).toDouble() / 512.0
        return doubleArrayOf(
            (yf + 1.5748 * vf).coerceIn(0.0, 1.0),
            (yf - 0.1873 * uf - 0.4681 * vf).coerceIn(0.0, 1.0),
            (yf + 1.8556 * uf).coerceIn(0.0, 1.0)
        )
    }
}
