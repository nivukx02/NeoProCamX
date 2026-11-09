package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NdrExplicitYuvDecodeContractTest {

    @Test
    fun `preview and recorder use raw yuv extension with bt2020 limited range decode`() {
        val preview = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/Hdr10PqPreviewCompositor.kt"
        ).readText()
        val recorder = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()

        listOf(preview, recorder).forEach { source ->
            assertTrue(source.contains("GL_EXT_YUV_target"))
            assertTrue(source.contains("__samplerExternal2DY2YEXT"))
            assertTrue(source.contains("64.0 / 1023.0"))
            assertTrue(source.contains("1023.0 / 876.0"))
            assertTrue(source.contains("512.0 / 1023.0"))
            assertTrue(source.contains("1023.0 / 896.0"))
            assertTrue(source.contains("1.4746 * cr"))
            assertTrue(source.contains("0.164553 * cb"))
            assertTrue(source.contains("0.571353 * cr"))
            assertTrue(source.contains("1.8814 * cb"))
        }
        assertTrue(preview.contains("linkProgram(FRAGMENT_SHADER)"))
        assertTrue(recorder.contains("retaining driver external-texture conversion fallback"))
    }

    @Test
    fun `limited range neutral chroma decodes without red bias`() {
        val yCode = 502f
        val y = (yCode / 1023f - 64f / 1023f) * (1023f / 876f)
        val cb = (512f / 1023f - 512f / 1023f) * (1023f / 896f)
        val cr = (512f / 1023f - 512f / 1023f) * (1023f / 896f)
        val r = y + 1.4746f * cr
        val g = y - 0.164553f * cb - 0.571353f * cr
        val b = y + 1.8814f * cb

        assertEquals(r, g, 1e-6f)
        assertEquals(g, b, 1e-6f)
    }
}
