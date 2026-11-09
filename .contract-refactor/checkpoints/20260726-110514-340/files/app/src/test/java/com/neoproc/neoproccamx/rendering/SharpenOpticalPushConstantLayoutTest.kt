package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CP-QV17 — source-contract test locking the `sharpen.comp` push_constant block layout to the 24-byte
 * `VkPushConstantRange` the C++ bridge declares for the gated optical-recovery sharpener. If either side
 * drifts (a reordered/added field, a wrong `size`), this fails, because the native
 * `vkCmdPushConstants(..., 0, 24, &sharpenParams)` writes a struct whose field order MUST match the
 * shader's `params` block exactly (6 float = 24 bytes, std430 scalar-aligned). Unlike CP-QV16's
 * denoise.comp (5 float + 1 int) all six sharpen fields are float.
 */
class SharpenOpticalPushConstantLayoutTest {

    private val shader: String by lazy { File("src/main/cpp/shaders/vulkan/sharpen.comp").readText() }
    private val bridge: String by lazy { File("src/main/cpp/vulkan_bridge.cpp").readText() }

    @Test
    fun sharpenCompPushConstantBlockIsSixFloatsInOrder() {
        // Extract the `layout(push_constant) uniform PushConstants { ... } params;` body.
        val marker = "layout(push_constant) uniform PushConstants {"
        val start = shader.indexOf(marker)
        assertTrue("sharpen.comp must declare a push_constant PushConstants block", start >= 0)
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
            "float" to "amount",
            "float" to "radius",
            "float" to "threshold",
            "float" to "detailAmount",
            "float" to "edgeProtection",
            "float" to "microContrast"
        )
        assertEquals("sharpen.comp push_constant fields (type,name) in declaration order", expected, fields)

        // 6 float, scalar-aligned = 6 * 4 = 24 bytes.
        val sizeBytes = fields.sumOf { 4 }
        assertEquals("sharpen.comp push_constant block must be 24 bytes", 24, sizeBytes)
    }

    @Test
    fun bridgeDeclaresA24BytePushRangeAndPushes24Bytes() {
        // The pipeline layout reserves exactly 24 bytes for the compute stage. (There are two 24-byte
        // ranges in the bridge — CP-QV16 denoise + CP-QV17 sharpen — so just require it is present.)
        assertTrue(
            "bridge must set a 24-byte VkPushConstantRange size",
            bridge.contains("pushRange.size = 24;")
        )
        // The sharpen dispatch pushes exactly 24 bytes of the mirroring struct.
        assertTrue(
            "bridge must push 24 bytes of sharpenParams to the sharpen_optical layout",
            bridge.contains("0, 24, &sharpenParams);")
        )
        // The mirroring C++ struct must carry the same 6 floats, in order.
        val structMarker = "float amount; float radius; float threshold; float detailAmount; float edgeProtection; float microContrast;"
        assertTrue(
            "bridge sharpenParams struct must mirror the shader field order (6 float)",
            bridge.contains(structMarker)
        )
    }
}
