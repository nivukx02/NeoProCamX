package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NivukxDrShaderContractTest {

    private val shaders = listOf(
        "nivukx_analyse.comp",
        "nivukx_denoise.comp",
        "nivukx_apply.frag",
        "nivukx_sharpen.frag"
    )

    @Test
    fun nivukxShadersExistAndDeclareProjectOwnedElitePipeline() {
        shaders.forEach { name ->
            val source = shader(name)

            assertTrue("$name must declare the elite pipeline marker", source.contains("NIVUKX_DR_ELITE"))
            assertFalse("$name must not contain vendor profile claims", source.contains("Dolby"))
            assertFalse("$name must not contain vendor RPU claims", source.contains("RPU"))
            assertFalse("$name must not contain IMAX certification claims", source.contains("IMAX"))
        }
    }

    @Test
    fun applyShaderContainsThreeAnchorToneMapAndJzAzBzGamutCompression() {
        val source = shader("nivukx_apply.frag")

        assertTrue(source.contains("nivukxThreeAnchorToneMap"))
        assertTrue(source.contains("jzazbz"))
        assertTrue(source.contains("radialChromaCompress"))
        assertTrue(source.contains("T(L) toe knee shoulder"))
        assertTrue(source.contains("kalmanSmoothAnchors"))
    }

    @Test
    fun denoiseShaderContainsRobustBurstAndEdgeAwareNoiseModel() {
        val source = shader("nivukx_denoise.comp")

        assertTrue(source.contains("tukeyBiweight"))
        assertTrue(source.contains("edgeAwareSpatialDenoise"))
        assertTrue(source.contains("localSnrDb"))
        assertTrue(source.contains("microTextureReinject"))
    }

    @Test
    fun sharpenShaderSharpensJzOnlyAndProtectsChromaAndSpeculars() {
        val source = shader("nivukx_sharpen.frag")

        assertTrue(source.contains("laplacianBand01"))
        assertTrue(source.contains("sigmoidalLocalContrast"))
        assertTrue(source.contains("noChromaSharpen"))
        assertTrue(source.contains("specularProtect"))
        assertTrue(source.contains("maxOvershoot = 0.04"))
    }

    @Test
    fun analyseShaderExtractsPercentilesAndMetadataTiles() {
        val source = shader("nivukx_analyse.comp")

        assertTrue(source.contains("percentileP01"))
        assertTrue(source.contains("percentileP9999"))
        assertTrue(source.contains("rtileDeltas"))
        assertTrue(source.contains("saliencyClass"))
    }

    private fun shader(name: String): String {
        val file = File("src/main/cpp/shaders/vulkan/$name")
        assertTrue("$name must exist", file.isFile)
        return file.readText()
    }
}
