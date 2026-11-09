package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NeoRadianceHeliosShaderContractTest {

    private val shader = File("src/main/cpp/shaders/vulkan/ycbcr_preview.frag").readText()

    @Test
    fun shaderContainsHeliosPhysicalAnchorsAndBt2100IctcpMatrices() {
        assertTrue(shader.contains("HELIOS_DIFFUSE_WHITE_NITS = 203.0"))
        assertTrue(shader.contains("HELIOS_PEAK_NITS = 1000.0"))
        assertTrue(shader.contains("HELIOS_REC2020_LUMA = vec3(0.2627, 0.6780, 0.0593)"))
        assertTrue(shader.contains("HELIOS_REC2020_TO_LMS"))
        assertTrue(shader.contains("HELIOS_LMS_TO_ICTCP"))
    }

    @Test
    fun shaderContainsLuminanceOnlyCascadeAndC1ShadowLift() {
        assertTrue(shader.contains("neoRadianceHeliosShadowLift"))
        assertTrue(shader.contains("neoRadianceHeliosCascadeLuma"))
        assertTrue(shader.contains("neoRadianceHeliosCascadeTone"))
        assertTrue(shader.contains("smoothstep(0.06, 0.10, x)"))
        assertTrue(shader.contains("rgb * (mappedY / max(y, HELIOS_EPSILON))"))
    }
}
