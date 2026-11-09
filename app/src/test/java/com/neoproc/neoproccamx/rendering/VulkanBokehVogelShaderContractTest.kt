package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VulkanBokehVogelShaderContractTest {

    @Test
    fun bokehShaderUsesVogelSpiralCocDepthAndApertureBladeSimulation() {
        val shader = sourceFile("app/src/main/cpp/shaders/vulkan/bokeh_vogel_spiral.comp")
            .readText()

        assertTrue(shader.contains("GOLDEN_ANGLE"))
        assertTrue(shader.contains("circleOfConfusionRadius"))
        assertTrue(shader.contains("vogel"))
        assertTrue(shader.contains("chromaticAberration"))
        assertTrue(shader.contains("apertureBlade"))
        assertTrue(shader.contains("layout(local_size_x = 16, local_size_y = 16"))
    }

    private fun sourceFile(path: String): File =
        File(path).takeIf { it.exists() }
            ?: File(path.removePrefix("app/")).takeIf { it.exists() }
            ?: error("Missing shader file: $path")
}
