package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test

class CinematicNoiseReductionPassTest {

    @Test
    fun process_preservesColorInsteadOfReturningGrayscaleFrame() {
        val width = 8
        val height = 8
        val pixels = IntArray(width * height) { index ->
            val noise = (index % 3) * 8
            argb(190 + noise, 56 + noise / 2, 36)
        }

        val processed = CinematicNoiseReductionPass.process(
            inputPixels = pixels,
            width = width,
            height = height,
            config = CinematicNoiseReductionPass.Config(
                detailPreservationPercent = 60,
                pyramidLevels = 3,
                blurRadius = 2
            )
        )

        val center = processed[4 * width + 4]
        val red = (center shr 16) and 0xFF
        val green = (center shr 8) and 0xFF
        val blue = center and 0xFF

        assertTrue("Expected red-dominant chroma to survive, got r=$red g=$green b=$blue", red > green + 40)
        assertTrue("Expected green above blue after denoise, got g=$green b=$blue", green > blue)
    }
}

private fun argb(red: Int, green: Int, blue: Int): Int =
    (0xFF shl 24) or
        (red.coerceIn(0, 255) shl 16) or
        (green.coerceIn(0, 255) shl 8) or
        blue.coerceIn(0, 255)
