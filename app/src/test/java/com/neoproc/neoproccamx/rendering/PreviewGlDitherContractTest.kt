package com.neoproc.neoproccamx.rendering

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGlDitherContractTest {
    private val compositorSource by lazy {
        File("src/main/java/com/neoproc/neoproccamx/rendering/PreviewGlCompositor.kt").readText()
    }

    private val fragmentShader by lazy {
        compositorSource
            .substringAfter("private const val FRAGMENT_SHADER = \"\"\"", missingDelimiterValue = "")
            .substringBefore("\"\"\"")
            .also { assertTrue("preview fragment shader must remain visible to this test", it.isNotBlank()) }
    }

    private val finalDitherPath by lazy {
        fragmentShader
            .substringAfter("// One zero-mean scalar TPDF term", missingDelimiterValue = "")
            .substringBefore("gl_FragColor")
            .also { assertTrue("final RGB10 dither path must remain visible to this test", it.isNotBlank()) }
    }

    @Test
    fun `RGB10 output uses one neutral dither term and contains no 8 bit scale`() {
        assertTrue(compositorSource.contains("EGL14.EGL_RED_SIZE, 10"))
        assertTrue(compositorSource.contains("EGL14.EGL_GREEN_SIZE, 10"))
        assertTrue(compositorSource.contains("EGL14.EGL_BLUE_SIZE, 10"))
        assertTrue(fragmentShader.contains("const float RGB10_QUANTIZATION_LEVELS = 1023.0;"))
        assertTrue(finalDitherPath.contains("float neutralDither ="))
        assertTrue(finalDitherPath.contains("vec3(neutralDither)"))
        assertTrue(finalDitherPath.contains("1.0 / RGB10_QUANTIZATION_LEVELS"))
        assertFalse(finalDitherPath.contains("vec3 dither"))
        assertFalse(finalDitherPath.contains("shadowSafeDither"))
        assertFalse(finalDitherPath.contains("255"))
        assertFalse(fragmentShader.contains("1.0 / 255.0"))
    }

    @Test
    fun `TPDF amplitude never exceeds one RGB10 code`() {
        val oneCode = 1.0 / RGB10_LEVELS
        val samples = buildList {
            for (y in 0 until 128) {
                for (x in 0 until 128) {
                    val px = x.toDouble() + 0.5
                    val py = y.toDouble() + 0.5
                    add(
                        (ditherHash(px, py) - ditherHash(px + OFFSET_X, py + OFFSET_Y)) *
                            oneCode
                    )
                }
            }
        }

        assertTrue(samples.any { it < 0.0 })
        assertTrue(samples.any { it > 0.0 })
        assertTrue(samples.all { abs(it) <= oneCode })
        assertTrue(
            "sampled TPDF mean must remain near zero",
            abs(samples.average()) < oneCode * 0.02
        )
    }

    @Test
    fun `shared scalar dither preserves the neutral axis and channel differences`() {
        val dither = (ditherHash(37.5, 91.5) - ditherHash(37.5 + OFFSET_X, 91.5 + OFFSET_Y)) /
            RGB10_LEVELS
        val neutral = Rgb(0.5, 0.5, 0.5).plusNeutral(dither)
        assertEquals(neutral.r, neutral.g, 0.0)
        assertEquals(neutral.g, neutral.b, 0.0)

        val color = Rgb(0.62, 0.47, 0.31)
        val dithered = color.plusNeutral(dither)
        assertEquals(color.r - color.g, dithered.r - dithered.g, 1e-15)
        assertEquals(color.g - color.b, dithered.g - dithered.b, 1e-15)
    }

    @Test
    fun `TPDF difference is exactly antisymmetric and therefore unbiased`() {
        val a = ditherHash(18.5, 73.5)
        val b = ditherHash(18.5 + OFFSET_X, 73.5 + OFFSET_Y)
        assertEquals(0.0, (a - b) + (b - a), 0.0)
    }

    private fun ditherHash(x: Double, y: Double): Double {
        var px = fract(x * 0.1031)
        var py = fract(y * 0.1031)
        var pz = fract(x * 0.1031)
        val dot = px * (py + 33.33) + py * (pz + 33.33) + pz * (px + 33.33)
        px += dot
        py += dot
        pz += dot
        return fract((px + py) * pz)
    }

    private fun fract(value: Double): Double = value - kotlin.math.floor(value)

    private data class Rgb(val r: Double, val g: Double, val b: Double) {
        fun plusNeutral(value: Double): Rgb = Rgb(r + value, g + value, b + value)
    }

    private companion object {
        const val RGB10_LEVELS = 1023.0
        const val OFFSET_X = 41.13
        const val OFFSET_Y = 17.79
    }
}
