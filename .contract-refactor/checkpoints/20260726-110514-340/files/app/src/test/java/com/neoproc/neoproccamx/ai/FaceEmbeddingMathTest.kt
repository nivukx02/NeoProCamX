package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class FaceEmbeddingMathTest {

    @Test
    fun embeddingFromArgb_isDeterministicForSamePixels() {
        val pixels = intArrayOf(
            argb(228, 186, 164), argb(224, 180, 158), argb(218, 172, 151), argb(214, 168, 147),
            argb(230, 188, 166), argb(225, 181, 159), argb(220, 174, 153), argb(216, 170, 149),
            argb(232, 190, 168), argb(226, 182, 160), argb(221, 175, 154), argb(217, 171, 150),
            argb(234, 192, 170), argb(228, 184, 162), argb(222, 176, 155), argb(218, 172, 151)
        )

        val first = FaceEmbeddingMath.embeddingFromArgb(pixels, width = 4, height = 4)
        val second = FaceEmbeddingMath.embeddingFromArgb(pixels, width = 4, height = 4)

        assertArrayEquals(first, second, 0f)
    }

    @Test
    fun embeddingFromArgb_isUnitNormalizedAndHasModelLength() {
        val pixels = IntArray(64) { index ->
            val tone = 64 + (index * 3) % 128
            argb(tone + 24, tone + 12, tone)
        }

        val embedding = FaceEmbeddingMath.embeddingFromArgb(pixels, width = 8, height = 8)
        val norm = sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()

        assertEquals(128, embedding.size)
        assertEquals(1f, norm, 1e-4f)
    }

    @Test
    fun compareEmbeddings_separatesDifferentTextureAndTone() {
        val warmFace = IntArray(64) { index ->
            val local = index % 8
            argb(214 + local, 160 + local / 2, 128 + local / 3)
        }
        val coolHighContrastFace = IntArray(64) { index ->
            val checker = if ((index + index / 8) % 2 == 0) 48 else 220
            argb(checker, checker + 12, checker + 32)
        }

        val warmEmbedding = FaceEmbeddingMath.embeddingFromArgb(warmFace, width = 8, height = 8)
        val coolEmbedding = FaceEmbeddingMath.embeddingFromArgb(coolHighContrastFace, width = 8, height = 8)

        assertTrue(FaceEmbeddingMath.cosineSimilarity(warmEmbedding, coolEmbedding) < 0.98f)
    }

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
}
