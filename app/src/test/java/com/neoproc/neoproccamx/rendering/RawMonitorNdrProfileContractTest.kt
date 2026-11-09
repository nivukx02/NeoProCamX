package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RawMonitorNdrProfileContractTest {
    private val shader by lazy {
        File("src/main/cpp/shaders/vulkan/raw_monitor_tonemap.comp").readText()
    }
    private val bridge by lazy {
        File("src/main/cpp/vulkan_bridge.cpp").readText()
    }

    @Test
    fun `NDR and NIVUKX have distinct bounded project-owned character`() {
        assertTrue(shader.contains("vec3 ndrProfileChromaCharacter"))
        assertTrue(shader.contains("params.character.w"))
        assertTrue(shader.contains("params.detailProfile.y"))
        assertTrue(shader.contains("params.character.z"))
        assertTrue(shader.contains("params.toneProfile"))
        assertTrue(shader.contains("skinProtection"))
        assertTrue(shader.contains("compressRec709GamutPreserveLuma"))
        assertFalse(shader.contains("Dolby", ignoreCase = true))
        assertFalse(shader.contains("HLG", ignoreCase = true))
    }

    @Test
    fun `local detail is edge aware shadow guarded and halo bounded`() {
        val detail = shader.substring(
            shader.indexOf("float ndrLocalDetailStops"),
            shader.indexOf("void main()")
        )
        assertTrue(detail.contains("edgeConfidence"))
        assertTrue(detail.contains("shadowNoiseGuard"))
        assertTrue(detail.contains("highlightGuard"))
        assertTrue(detail.contains("clamp(detail, -0.22, 0.22)"))
        assertTrue(shader.contains("params.detailProfile.z"))
        assertTrue(shader.contains("params.detailProfile.w"))
    }

    @Test
    fun `CPU fallback mirrors GPU profile and spatial limits`() {
        assertTrue(bridge.contains("npRawNdrProfileChromaCharacter"))
        assertTrue(bridge.contains("npRawNdrProfileMidtoneContrast"))
        assertTrue(bridge.contains("npRawNdrLocalDetailStops"))
        assertTrue(bridge.contains("correction.chromaBoost"))
        assertTrue(bridge.contains("correction.localDetailStrength"))
        assertTrue(bridge.contains("correction.localDetailUpperStops"))
        assertTrue(bridge.contains("std::clamp(detail, -0.22f, 0.22f)"))
    }
}
