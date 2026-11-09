package com.neoproc.neoproccamx.ai

import kotlin.math.sqrt

object FaceEmbeddingMath {
    const val EMBEDDING_SIZE = 128
    private const val GRID_SIZE = 8
    private const val GRID_CELLS = GRID_SIZE * GRID_SIZE
    private const val EPSILON = 1e-8f

    fun embeddingFromArgb(
        pixels: IntArray,
        width: Int,
        height: Int
    ): FloatArray {
        require(width > 0 && height > 0) { "Face embedding input dimensions must be positive" }
        require(pixels.size >= width * height) {
            "Face embedding pixel array is smaller than width * height"
        }

        val luma = FloatArray(GRID_CELLS)
        val cb = FloatArray(GRID_CELLS)
        val cr = FloatArray(GRID_CELLS)

        for (gy in 0 until GRID_SIZE) {
            val y0 = gy * height / GRID_SIZE
            val y1 = maxOf((gy + 1) * height / GRID_SIZE, y0 + 1).coerceAtMost(height)
            for (gx in 0 until GRID_SIZE) {
                val x0 = gx * width / GRID_SIZE
                val x1 = maxOf((gx + 1) * width / GRID_SIZE, x0 + 1).coerceAtMost(width)
                var sumLuma = 0f
                var sumCb = 0f
                var sumCr = 0f
                var count = 0

                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) {
                        val argb = pixels[row + x]
                        val r = ((argb ushr 16) and 0xFF) / 255f
                        val g = ((argb ushr 8) and 0xFF) / 255f
                        val b = (argb and 0xFF) / 255f
                        val y709 = rec709Luma(r, g, b)

                        sumLuma += y709
                        sumCb += b - y709
                        sumCr += r - y709
                        count++
                    }
                }

                val index = gy * GRID_SIZE + gx
                val scale = 1f / count.coerceAtLeast(1)
                luma[index] = sumLuma * scale
                cb[index] = sumCb * scale
                cr[index] = sumCr * scale
            }
        }

        val meanLuma = luma.average().toFloat()
        val meanCb = cb.average().toFloat()
        val meanCr = cr.average().toFloat()
        val features = FloatArray(EMBEDDING_SIZE)

        for (i in 0 until GRID_CELLS) {
            features[i] =
                ((luma[i] - meanLuma) * 1.75f) +
                ((cr[i] - meanCr) * 0.45f) +
                ((cb[i] - meanCb) * 0.25f)
        }

        var featureIndex = GRID_CELLS
        for (gy in 0 until GRID_SIZE) {
            for (gx in 0 until GRID_SIZE step 2) {
                val left = gy * GRID_SIZE + gx
                val right = gy * GRID_SIZE + minOf(gx + 1, GRID_SIZE - 1)
                features[featureIndex++] =
                    (luma[right] - luma[left]) +
                    0.35f * (cr[right] - cr[left]) +
                    0.20f * (cb[right] - cb[left])
            }
        }

        for (gy in 0 until GRID_SIZE step 2) {
            for (gx in 0 until GRID_SIZE) {
                val top = gy * GRID_SIZE + gx
                val bottom = minOf(gy + 1, GRID_SIZE - 1) * GRID_SIZE + gx
                features[featureIndex++] =
                    (luma[bottom] - luma[top]) +
                    0.35f * (cr[bottom] - cr[top]) +
                    0.20f * (cb[bottom] - cb[top])
            }
        }

        return normalize(features)
    }

    fun cosineSimilarity(first: FloatArray, second: FloatArray): Float {
        if (first.size != second.size || first.isEmpty()) return 0f

        var dot = 0f
        var firstNorm = 0f
        var secondNorm = 0f
        for (i in first.indices) {
            dot += first[i] * second[i]
            firstNorm += first[i] * first[i]
            secondNorm += second[i] * second[i]
        }

        return if (firstNorm > EPSILON && secondNorm > EPSILON) {
            dot / (sqrt(firstNorm) * sqrt(secondNorm))
        } else {
            0f
        }
    }

    private fun rec709Luma(red: Float, green: Float, blue: Float): Float =
        0.2126f * red + 0.7152f * green + 0.0722f * blue

    private fun normalize(values: FloatArray): FloatArray {
        var sumSquares = 0f
        for (value in values) {
            sumSquares += value * value
        }

        if (sumSquares <= EPSILON) {
            values[0] = 1f
            return values
        }

        val invNorm = 1f / sqrt(sumSquares)
        for (i in values.indices) {
            values[i] *= invNorm
        }
        return values
    }
}
