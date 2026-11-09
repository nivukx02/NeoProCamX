package com.neoproc.neoproccamx.recording

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Scene/detail evidence measured directly from the 10-bit source frame. */
internal data class P010SceneDetailMetrics(
    val meanLuma: Float,
    val shadowFraction: Float,
    val highlightFraction: Float,
    val edgeDensity: Float,
    val textureConfidence: Float
)

/**
 * Lightweight proprietary scene analyzer for offline conversion. It samples the source P010 luma plane
 * and distinguishes fine texture from flat noise pressure before denoise/sharpen values are selected.
 * No image is resized, generated, or replaced.
 */
internal object P010SceneDetailAnalyzer {
    fun analyze(payload: ByteArray, width: Int, height: Int): P010SceneDetailMetrics? =
        analyze(width, height, payload.size) { pixelIndex -> readP010(payload, pixelIndex) }

    fun analyze(payload: ByteBuffer, width: Int, height: Int): P010SceneDetailMetrics? {
        val source = payload.slice().order(ByteOrder.LITTLE_ENDIAN)
        return analyze(width, height, source.remaining()) { pixelIndex ->
            readP010(source, pixelIndex)
        }
    }

    private inline fun analyze(
        width: Int,
        height: Int,
        payloadBytes: Int,
        readSample: (Int) -> Int
    ): P010SceneDetailMetrics? {
        if (width < 3 || height < 3) return null
        val lumaBytes = width.toLong() * height.toLong() * 2L
        if (lumaBytes > payloadBytes.toLong()) return null

        val step = max(1, max(width, height) / 256)
        var samples = 0
        var lumaSum = 0.0
        var shadows = 0
        var highlights = 0
        var edges = 0
        var texture = 0
        for (y in step until height step step) {
            for (x in step until width step step) {
                val current = readSample(y * width + x)
                val left = readSample(y * width + (x - step))
                val above = readSample((y - step) * width + x)
                val normalized = current / 1023f
                val gradient = (abs(current - left) + abs(current - above)) / (2f * 1023f)
                lumaSum += normalized
                if (normalized < 0.12f) shadows++
                if (normalized > 0.90f) highlights++
                if (gradient >= 0.035f) edges++
                // Fine texture lives above quantization/noise-floor movement but below a hard edge.
                if (gradient in 0.006f..0.055f) texture++
                samples++
            }
        }
        if (samples == 0) return null
        val inv = 1f / samples.toFloat()
        val textureFraction = texture * inv
        val edgeFraction = edges * inv
        return P010SceneDetailMetrics(
            meanLuma = (lumaSum / samples.toDouble()).toFloat().coerceIn(0f, 1f),
            shadowFraction = (shadows * inv).coerceIn(0f, 1f),
            highlightFraction = (highlights * inv).coerceIn(0f, 1f),
            edgeDensity = edgeFraction.coerceIn(0f, 1f),
            textureConfidence = (textureFraction * 2.4f + edgeFraction * 0.35f).coerceIn(0f, 1f)
        )
    }

    private fun readP010(payload: ByteArray, pixelIndex: Int): Int {
        val offset = pixelIndex * 2
        val word = (payload[offset].toInt() and 0xFF) or
            ((payload[offset + 1].toInt() and 0xFF) shl 8)
        return (word ushr 6).coerceIn(0, 1023)
    }

    private fun readP010(payload: ByteBuffer, pixelIndex: Int): Int =
        ((payload.getShort(pixelIndex * Short.SIZE_BYTES).toInt() and 0xFFFF) ushr 6)
            .coerceIn(0, 1023)
}
