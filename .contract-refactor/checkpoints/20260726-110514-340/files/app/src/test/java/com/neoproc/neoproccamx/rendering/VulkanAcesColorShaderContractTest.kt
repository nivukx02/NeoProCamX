package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VulkanAcesColorShaderContractTest {
    private val commonSource: String by lazy {
        File("src/main/cpp/shaders/vulkan/aces_common.glsl").readText()
    }
    private val previewSource: String by lazy {
        File("src/main/cpp/shaders/vulkan/ycbcr_preview.frag").readText()
    }
    private val denoiseSource: String by lazy {
        File("src/main/cpp/shaders/vulkan/denoise.comp").readText()
    }
    private val hdrMergeSource: String by lazy {
        File("src/main/cpp/shaders/vulkan/hdr_merge.comp").readText()
    }
    private val p010ExportSource: String by lazy {
        File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()
    }

    @Test
    fun commonShaderDefinesNonCertifiedAcesCgWorkingSpace() {
        assertTrue(commonSource.contains("NEO_ACESCG_NON_CERTIFIED"))
        assertTrue(commonSource.contains("neoRec709ToAcesCg"))
        assertTrue(commonSource.contains("neoRec2020ToAcesCg"))
        assertTrue(commonSource.contains("neoAcesCgToRec709"))
        assertTrue(commonSource.contains("neoAcesCgToRec2020"))
        assertTrue(commonSource.contains("neoAcesFittedRrtOdt"))
        assertFalse(commonSource.contains("Dolby"))
        assertFalse(commonSource.contains("HDR10+ certified"))
        assertFalse(commonSource.contains("IMAX"))
    }

    @Test
    fun previewShaderRoutesLinearProcessingThroughAcesCgHelpers() {
        assertTrue(previewSource.contains("NEO_ACESCG_NON_CERTIFIED"))
        assertTrue(previewSource.contains("neoRec709ToAcesCg"))
        assertTrue(previewSource.contains("neoAcesCgToRec709"))
        assertTrue(previewSource.contains("neoAcesFittedRrtOdt"))
        assertTrue(previewSource.indexOf("neoRec709ToAcesCg") < previewSource.indexOf("edgeAwareSpatialDenoise("))
    }

    @Test
    fun computeShadersDeclareAcesCgProcessingContracts() {
        listOf(denoiseSource, hdrMergeSource, p010ExportSource).forEach { source ->
            assertTrue(source.contains("NEO_ACESCG_NON_CERTIFIED"))
            assertTrue(source.contains("ACESCG_LINEAR_WORKING_SPACE"))
            assertFalse(source.contains("Dolby Vision"))
            assertFalse(source.contains("IMAX certified"))
            assertFalse(source.contains("HDR10+ certified"))
        }
    }
}
