package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RawHighlightRecoveryContractTest {

    @Test
    fun invalidConfigurationNormalizesToSafeFiniteShaderParameters() {
        val parameters = HighlightRecoveryConfig(
            threshold = Float.NaN,
            strength = Float.POSITIVE_INFINITY,
            shoulderCompression = true
        ).shaderParameters()

        assertArrayEquals(floatArrayOf(1f, 0f, 1f, 0f), parameters, 0f)
    }

    @Test
    fun cinemaConfigurationEnablesBoundedPreviewRecovery() {
        val parameters = HighlightRecoveryConfig.CINEMA.shaderParameters()

        assertTrue(parameters[0] in 0.5f..<1f)
        assertTrue(parameters[1] in 0f..1f)
        assertEquals(1f, parameters[2], 0f)
        assertEquals(1f, parameters[3], 0f)
    }

    @Test
    fun shaderRecoveryIsPresentationOnlyAndHueSafe() {
        val shader = File("src/main/cpp/shaders/vulkan/demosaic_vulkan.comp").readText()

        assertTrue(shader.contains("vec3 reconstructSensorHighlights"))
        assertTrue(shader.contains("supportedClip"))
        assertTrue(shader.contains("compressedPeak / max(peak"))
        assertTrue(shader.contains("vec3 sensorRgb = reconstructSensorHighlights("))
        assertTrue(shader.contains("demosaicRgbFromTile(localCenter, bayerChannel(gid))"))
        assertTrue(shader.contains("* lensShadingRgb(gid)"))
    }
}
