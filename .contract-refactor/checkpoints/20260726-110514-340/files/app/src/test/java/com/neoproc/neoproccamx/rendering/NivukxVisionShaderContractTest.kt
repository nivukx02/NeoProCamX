package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NivukxVisionShaderContractTest {

    private val shaders = listOf(
        "nivukx_vision_v1_to_v4.frag",
        "nivukx_vision_look.frag",
        "nivukx_vision_denoise.comp",
        "nivukx_vision_sharpen.frag"
    )

    @Test
    fun visionShadersExistAndStayNeoOwned() {
        shaders.forEach { name ->
            val source = shader(name)

            assertTrue(source.contains("NIVUKX_VISION_ELITE"))
            assertFalse(source.contains("Dolby"))
            assertFalse(source.contains("HDR10+"))
            assertFalse(source.contains("IMAX"))
            assertFalse(source.contains("RPU"))
        }
    }

    @Test
    fun v1ToV4ShaderUsesJzAzBzRadialCompressionAndNearWhiteGuard() {
        val source = shader("nivukx_vision_v1_to_v4.frag")

        assertTrue(source.contains("ap1ToXyz"))
        assertTrue(source.contains("xyzToJzAzBz"))
        assertTrue(source.contains("radialChromaCompressVision"))
        assertTrue(source.contains("nearWhiteGuardScale"))
        assertTrue(source.contains("huePreservedExactly"))
    }

    @Test
    fun denoiseAndSharpenShadersProtectTextureChromaAndSpeculars() {
        val denoise = shader("nivukx_vision_denoise.comp")
        val sharpen = shader("nivukx_vision_sharpen.frag")

        assertTrue(denoise.contains("tukeyBiweightBurstMerge"))
        assertTrue(denoise.contains("jzazbzEdgeAwareDenoise"))
        assertTrue(denoise.contains("microTextureReinject"))
        assertTrue(sharpen.contains("jzBand01Only"))
        assertTrue(sharpen.contains("noChromaSharpen"))
        assertTrue(sharpen.contains("maxOvershoot = 0.04"))
    }

    private fun shader(name: String): String {
        val file = File("src/main/cpp/shaders/vulkan/$name")
        assertTrue("$name must exist", file.isFile)
        return file.readText()
    }
}
