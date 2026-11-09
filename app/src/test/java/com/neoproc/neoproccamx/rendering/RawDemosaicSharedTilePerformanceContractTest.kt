package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDemosaicSharedTilePerformanceContractTest {
    private fun shader(): String {
        val candidates = listOf(
            File("src/main/cpp/shaders/vulkan/demosaic_vulkan.comp"),
            File("app/src/main/cpp/shaders/vulkan/demosaic_vulkan.comp")
        )
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `default path calibrates one raw-only shared halo`() {
        val shader = shader()
        assertTrue(shader.contains("#define RAW_DEMOSAIC_DEFECT_MAPS 0"))
        assertTrue(shader.contains("shared float calibratedTile[TILE_HEIGHT][TILE_WIDTH]"))
        assertTrue(shader.contains("calibrateZeroDefectSample(source)"))
        assertTrue(shader.contains("calibratedTile[tileY][tileX] ="))
    }

    @Test
    fun `output reconstruction uses bounded local tile indexes without image fallback`() {
        val shader = shader()
        val outputPhase = shader.substring(
            shader.indexOf("vec3 demosaicRgbFromTile"),
            shader.indexOf("vec3 reconstructSensorHighlights")
        )
        assertTrue(outputPhase.contains("calibratedTile[centerY][centerX]"))
        assertTrue(outputPhase.contains("calibratedTile[centerY - 2][centerX]"))
        assertTrue(outputPhase.contains("calibratedTile[centerY + 1][centerX + 1]"))
        assertFalse(outputPhase.contains("imageLoad"))
        assertFalse(outputPhase.contains("clampCoord"))
        assertFalse(outputPhase.contains("calibratedRaw"))
    }

    @Test
    fun `full defect map implementation remains compile time selectable`() {
        val shader = shader()
        assertTrue(shader.contains("#if RAW_DEMOSAIC_DEFECT_MAPS"))
        assertTrue(shader.contains("shared uint rawTile[TILE_HEIGHT][TILE_WIDTH]"))
        assertTrue(shader.contains("shared float darkTile[TILE_HEIGHT][TILE_WIDTH]"))
        assertTrue(shader.contains("shared uint hotTile[TILE_HEIGHT][TILE_WIDTH]"))
        assertTrue(shader.contains("calibrateDefectMappedSample"))
        assertTrue(shader.contains("thermalDarkScale = thermalScale()"))
    }

    @Test
    fun `committed optimized shader payload stays compact`() {
        val candidates = listOf(
            File("src/main/cpp/generated/demosaic_vulkan.comp.spv"),
            File("app/src/main/cpp/generated/demosaic_vulkan.comp.spv")
        )
        val spirv = candidates.first { it.isFile }
        assertTrue("optimized demosaic SPIR-V unexpectedly regressed to ${spirv.length()} bytes", spirv.length() < 50_000L)
    }
}
