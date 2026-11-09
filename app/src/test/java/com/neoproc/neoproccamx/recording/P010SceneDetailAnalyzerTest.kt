package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertTrue
import org.junit.Test

class P010SceneDetailAnalyzerTest {
    @Test
    fun `textured source reports more detail than flat source`() {
        val width = 64
        val height = 48
        val flat = p010(width, height) { _, _ -> 512 }
        val textured = p010(width, height) { x, y -> if ((x + y) % 4 < 2) 450 else 574 }

        val flatMetrics = P010SceneDetailAnalyzer.analyze(flat, width, height)!!
        val textureMetrics = P010SceneDetailAnalyzer.analyze(textured, width, height)!!

        assertTrue(textureMetrics.textureConfidence > flatMetrics.textureConfidence)
        assertTrue(textureMetrics.edgeDensity > flatMetrics.edgeDensity)
    }

    @Test
    fun `shadow and highlight occupancy come from source codes`() {
        val width = 32
        val height = 24
        val payload = p010(width, height) { x, _ -> if (x < width / 2) 50 else 980 }

        val metrics = P010SceneDetailAnalyzer.analyze(payload, width, height)!!

        assertTrue(metrics.shadowFraction > 0.40f)
        assertTrue(metrics.highlightFraction > 0.40f)
    }

    private fun p010(width: Int, height: Int, value: (Int, Int) -> Int): ByteArray {
        val yBytes = width * height * 2
        val payload = ByteArray(yBytes + width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val word = value(x, y).coerceIn(0, 1023) shl 6
                val offset = (y * width + x) * 2
                payload[offset] = word.toByte()
                payload[offset + 1] = (word ushr 8).toByte()
            }
        }
        return payload
    }
}
