package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s4-f04 / CP35 — locks the HDR-merge push-constant ABI ("ABI is sacred"). The GLSL `HdrParams`
 * block in hdr_merge.comp must match the native `HdrPushConstants` C++ struct field-for-field, with
 * the new `outputAcesCg16f` gate APPENDED last (so the existing 80-byte 8-bit layout is unchanged)
 * and the struct size-locked to 84 bytes.
 */
class HdrMergePushConstantLayoutTest {

    private val shader: String by lazy { File("src/main/cpp/shaders/vulkan/hdr_merge.comp").readText() }
    private val bridge: String by lazy { File("src/main/cpp/hdr_merge_native.cpp").readText() }

    private fun hdrParamsBody(): String {
        val s = shader.indexOf("HdrParams")
        val open = shader.indexOf('{', s); val close = shader.indexOf('}', open)
        return shader.substring(open + 1, close)
    }

    @Test
    fun glslScalarFieldsAreInOrderWithGateAppended() {
        // parseBlock skips the [8] arrays (regex), leaving the scalar slots in declaration order.
        val fields = GpuPushConstantLayout.parseBlock(shader, "HdrParams")
        assertEquals(
            listOf("width", "height", "frameCount", "fastApproximation", "outputAcesCg16f"),
            fields.map { it.name }
        )
        fields.forEach { assertEquals("uint", it.type) }
        // The arrays must sit between fastApproximation and the appended gate, untouched.
        val body = hdrParamsBody()
        val iFast = body.indexOf("fastApproximation")
        val iExp = body.indexOf("exposureScales[8]")
        val iNorm = body.indexOf("normalizationFactors[8]")
        val iGate = body.indexOf("outputAcesCg16f")
        assertTrue("arrays present and gate appended last", iFast < iExp && iExp < iNorm && iNorm < iGate)
    }

    @Test
    fun nativeStructMatchesAndIsSizeLocked() {
        assertTrue("native struct must exist", bridge.contains("struct HdrPushConstants"))
        val start = bridge.indexOf("struct HdrPushConstants")
        val end = bridge.indexOf("};", start)
        val body = bridge.substring(start, end)
        val order = listOf("width", "height", "frameCount", "fastApproximation",
            "exposureScales", "normalizationFactors", "outputAcesCg16f")
        var cursor = -1
        order.forEach { f ->
            val at = body.indexOf(f)
            assertTrue("native struct must declare '$f' in order", at > cursor)
            cursor = at
        }
        assertTrue("native struct must be size-locked to 84 bytes",
            bridge.contains("static_assert(sizeof(HdrPushConstants) == 84"))
    }
}
