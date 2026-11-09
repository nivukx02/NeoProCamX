package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.rendering.Hdr10PqPreviewCompositor
import org.junit.Assert.assertEquals
import org.junit.Test

class NdrPreviewRenderPolicyTest {
    @Test
    fun `delivery mode maps supported NDR looks without changing aliases`() {
        assertEquals(
            Hdr10PqPreviewCompositor.MODE_NDR10_PLUS,
            NdrPreviewRenderPolicy.deliveryMode(AppMode.VIDEO, false, false, "NDR10+")
        )
        listOf("NIVUKXVISION", "APEX_SHARP", "APEX_ZERO", "APEX_CINEMA").forEach { mode ->
            assertEquals(
                Hdr10PqPreviewCompositor.MODE_NIVUKX_VISION,
                NdrPreviewRenderPolicy.deliveryMode(AppMode.VIDEO, false, false, mode)
            )
        }
    }

    @Test
    fun `delivery mode rejects non video log raw and SDR states`() {
        assertEquals(-1, NdrPreviewRenderPolicy.deliveryMode(AppMode.PRO, false, false, "NDR10+"))
        assertEquals(-1, NdrPreviewRenderPolicy.deliveryMode(AppMode.VIDEO, true, false, "NDR10+"))
        assertEquals(-1, NdrPreviewRenderPolicy.deliveryMode(AppMode.VIDEO, false, true, "NDR10+"))
        assertEquals(-1, NdrPreviewRenderPolicy.deliveryMode(AppMode.VIDEO, false, false, "OFF"))
    }
}
