package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CP-QV16 — source-contract test locking the `denoise.comp` push_constant block layout to the 24-byte
 * `VkPushConstantRange` the C++ bridge declares for the gated spatial-denoise pipeline. If either side
 * drifts (a reordered/added field, a wrong `size`), this fails, because the native
 * `vkCmdPushConstants(..., 0, 24, &denoiseParams)` writes a struct whose field order MUST match the
 * shader's `params` block exactly (5 float + 1 int = 24 bytes, std430 scalar-aligned).
 */
class DenoiseSpatialPushConstantLayoutTest {

    private val shader: String by lazy { File("src/main/cpp/shaders/vulkan/denoise.comp").readText() }
    private val bridge: String by lazy { File("src/main/cpp/vulkan_bridge.cpp").readText() }

    @Test
    fun denoiseCompPushConstantBlockIsFiveFloatsThenOneIntInOrder() {
        // Extract the `layout(push_constant) uniform PushConstants { ... } params;` body.
        val marker = "layout(push_constant) uniform PushConstants {"
        val start = shader.indexOf(marker)
        assertTrue("denoise.comp must declare a push_constant PushConstants block", start >= 0)
        val bodyStart = start + marker.length
        val bodyEnd = shader.indexOf("} params;", bodyStart)
        assertTrue("PushConstants block must close with `} params;`", bodyEnd > bodyStart)
        val body = shader.substring(bodyStart, bodyEnd)

        // Parse `<type> <name>;` field declarations in order (ignore // comments).
        val fieldRegex = Regex("""(?m)^\s*(float|int)\s+(\w+)\s*;""")
        val fields = fieldRegex.findAll(body)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        val expected = listOf(
            "float" to "spatialSigma",
            "float" to "rangeSigma",
            "float" to "chromaSigma",
            "float" to "detailPreserve",
            "float" to "noiseLevel",
            "int" to "passIndex"
        )
        assertEquals("denoise.comp push_constant fields (type,name) in declaration order", expected, fields)

        // 5 float + 1 int, scalar-aligned = 6 * 4 = 24 bytes.
        val sizeBytes = fields.sumOf { 4 }
        assertEquals("denoise.comp push_constant block must be 24 bytes", 24, sizeBytes)
    }

    @Test
    fun bridgeDeclaresA24BytePushRangeAndPushes24Bytes() {
        // The pipeline layout reserves exactly 24 bytes for the compute stage.
        assertTrue(
            "bridge must set the denoise_spatial VkPushConstantRange size to 24",
            bridge.contains("pushRange.size = 24;")
        )
        // The dispatch pushes exactly 24 bytes of the mirroring struct.
        assertTrue(
            "bridge must push 24 bytes of denoiseParams to the denoise_spatial layout",
            bridge.contains("0, 24, &denoiseParams);")
        )
        // The mirroring C++ struct must carry the same 5 floats then 1 int, in order.
        val structMarker = "float spatialSigma; float rangeSigma; float chromaSigma; float detailPreserve; float noiseLevel; int32_t passIndex;"
        assertTrue(
            "bridge denoiseParams struct must mirror the shader field order (5 float + int)",
            bridge.contains(structMarker)
        )
    }
}
