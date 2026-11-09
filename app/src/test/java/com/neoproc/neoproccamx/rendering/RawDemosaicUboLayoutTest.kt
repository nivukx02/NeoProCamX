package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s1-f07 / CP10c — golden-locks the Kotlin↔GLSL↔C++ ABI of the RAW-preview demosaic UBO. Parses the
 * `RawDemosaicUniforms` std140 block straight out of demosaic_vulkan.comp, asserts the field order and
 * the std140 byte offsets/size, and pins the matching C++ `static_assert`/`offsetof` guards so any
 * reorder/type/padding drift fails either the JVM test suite or the native build first.
 */
class RawDemosaicUboLayoutTest {

    private val shader: String =
        File("src/main/cpp/shaders/vulkan/demosaic_vulkan.comp").readText()

    @Test
    fun uboFieldOrderMatchesTheAbi() {
        val fields = GpuPushConstantLayout.parseBlock(shader, "RawDemosaicUniforms")
        val expected = listOf(
            "vec4" to "blackLevelRggb",
            "vec4" to "whiteBalanceRggb",
            "vec4" to "exposureThermal",
            "mat3" to "M_SENSOR_TO_XYZ",
            "mat3" to "M_XYZ_TO_ACESCG",
            "int" to "bayerPattern",
            "int" to "width",
            "int" to "height",
            "int" to "whiteLevel",
            "vec4" to "lensShadingGridRggb0",
            "vec4" to "lensShadingGridRggb1",
            "vec4" to "lensShadingGridRggb2",
            "vec4" to "lensShadingGridRggb3",
            "vec4" to "lensShadingGridRggb4",
            "vec4" to "lensShadingGridRggb5",
            "vec4" to "lensShadingGridRggb6",
            "vec4" to "lensShadingGridRggb7",
            "vec4" to "lensShadingGridRggb8",
            "ivec4" to "lensShadingInfo",
            "vec4" to "highlightRecovery",
            "vec4" to "calibrationScaleRggb",
            "vec4" to "calibrationOffsetRggb",
            "mat3" to "M_SENSOR_TO_ACESCG_FAST"
        )
        assertEquals(expected, fields.map { it.type to it.name })
    }

    @Test
    fun std140OffsetsAndSizeAreLockedTo416Bytes() {
        val fields = GpuPushConstantLayout.parseBlock(shader, "RawDemosaicUniforms")
        val layout = GpuPushConstantLayout.std140Layout(fields).associateBy { it.field.name }

        assertEquals(0, layout.getValue("blackLevelRggb").offset)
        assertEquals(16, layout.getValue("whiteBalanceRggb").offset)
        assertEquals(32, layout.getValue("exposureThermal").offset)
        assertEquals(48, layout.getValue("M_SENSOR_TO_XYZ").offset)
        assertEquals(96, layout.getValue("M_XYZ_TO_ACESCG").offset)
        assertEquals(144, layout.getValue("bayerPattern").offset)
        assertEquals(148, layout.getValue("width").offset)
        assertEquals(152, layout.getValue("height").offset)
        assertEquals(156, layout.getValue("whiteLevel").offset)
        assertEquals(160, layout.getValue("lensShadingGridRggb0").offset)
        assertEquals(288, layout.getValue("lensShadingGridRggb8").offset)
        assertEquals(304, layout.getValue("lensShadingInfo").offset)
        assertEquals(320, layout.getValue("highlightRecovery").offset)
        assertEquals(336, layout.getValue("calibrationScaleRggb").offset)
        assertEquals(352, layout.getValue("calibrationOffsetRggb").offset)
        assertEquals(368, layout.getValue("M_SENSOR_TO_ACESCG_FAST").offset)

        // mat3 occupies three vec4-padded columns (48 bytes), not 36.
        assertEquals(48, layout.getValue("M_SENSOR_TO_XYZ").size)
        assertEquals(16, layout.getValue("lensShadingGridRggb0").size)
        assertEquals(16, layout.getValue("lensShadingGridRggb8").size)
        assertEquals(48, layout.getValue("M_SENSOR_TO_ACESCG_FAST").size)
        assertEquals(416, GpuPushConstantLayout.std140BlockSize(fields))
    }

    @Test
    fun nativeStructStaticallyLocksTheSameLayout() {
        val bridge = File("src/main/cpp/vulkan_bridge.cpp").readText()
        assertTrue("native must define RawDemosaicUniforms", bridge.contains("struct RawDemosaicUniforms"))
        assertTrue("native size must be locked to 416",
            bridge.contains("static_assert(sizeof(RawDemosaicUniforms) == 416"))
        assertTrue(bridge.contains("offsetof(RawDemosaicUniforms, whiteBalanceRggb) == 16"))
        assertTrue(bridge.contains("offsetof(RawDemosaicUniforms, exposureThermal) == 32"))
        assertTrue(bridge.contains("offsetof(RawDemosaicUniforms, mSensorToXyz) == 48"))
        assertTrue(bridge.contains("offsetof(RawDemosaicUniforms, mXyzToAcescg) == 96"))
        assertTrue(bridge.contains("offsetof(RawDemosaicUniforms, bayerPattern) == 144"))
        assertTrue(bridge.contains("offsetof(RawDemosaicUniforms, whiteLevel) == 156"))
        assertTrue(bridge.contains("offsetof(RawDemosaicUniforms, lensShadingGridRggb) == 160"))
        assertTrue(bridge.contains("offsetof(RawDemosaicUniforms, lensShadingInfo) == 304"))
        assertTrue(bridge.contains("offsetof(RawDemosaicUniforms, highlightRecovery) == 320"))
        assertTrue(bridge.contains("offsetof(RawDemosaicUniforms, calibrationScaleRggb) == 336"))
        assertTrue(bridge.contains("offsetof(RawDemosaicUniforms, calibrationOffsetRggb) == 352"))
        assertTrue(bridge.contains("offsetof(RawDemosaicUniforms, mSensorToAcescgFast) == 368"))
        // The column-major std140 mat3 transpose helper must remain (row-major JVM → column-major GLSL).
        assertTrue(bridge.contains("packRawDemosaicMatrixColumnMajor"))
        assertTrue(bridge.contains("multiplyRawDemosaicMatrices"))
    }
}
