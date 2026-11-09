package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeSwitchPreviewWorkCoalescerTest {

    @Test
    fun `inactive coalescer lets callers run work immediately`() {
        val coalescer = ModeSwitchPreviewWorkCoalescer()

        assertFalse(coalescer.requestCompositorRefresh(rebuildLut = true))
        assertFalse(coalescer.requestPreviewPipelineRefresh(forceSessionRefresh = true))
        assertEquals(ModeSwitchPreviewWorkFlush.None, coalescer.end())
    }

    @Test
    fun `mode switch coalesces repeated preview work into one strongest flush`() {
        val coalescer = ModeSwitchPreviewWorkCoalescer()

        coalescer.begin()
        assertTrue(coalescer.requestCompositorRefresh(rebuildLut = false))
        assertTrue(coalescer.requestPreviewPipelineRefresh(forceSessionRefresh = false))
        assertTrue(coalescer.requestCompositorRefresh(rebuildLut = true))
        assertTrue(coalescer.requestPreviewPipelineRefresh(forceSessionRefresh = true))

        assertEquals(
            ModeSwitchPreviewWorkFlush(
                compositorRefresh = PreviewCompositorRefresh.LUT,
                previewPipelineRefresh = PreviewPipelineRefresh.FORCE_SESSION
            ),
            coalescer.end()
        )
        assertEquals(ModeSwitchPreviewWorkFlush.None, coalescer.end())
    }

    @Test
    fun `nested batches flush only after outer mode switch ends`() {
        val coalescer = ModeSwitchPreviewWorkCoalescer()

        coalescer.begin()
        coalescer.begin()
        assertTrue(coalescer.requestCompositorRefresh(rebuildLut = true))

        assertEquals(ModeSwitchPreviewWorkFlush.None, coalescer.end())
        assertEquals(
            ModeSwitchPreviewWorkFlush(compositorRefresh = PreviewCompositorRefresh.LUT),
            coalescer.end()
        )
    }
}
