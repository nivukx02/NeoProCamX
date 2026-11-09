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

    @Test
    fun `abort clears every nested level and discards pending work`() {
        val coalescer = ModeSwitchPreviewWorkCoalescer()

        coalescer.begin()
        coalescer.begin()
        assertTrue(coalescer.requestCompositorRefresh(rebuildLut = true))
        assertTrue(coalescer.requestPreviewPipelineRefresh(forceSessionRefresh = true))

        coalescer.abort()

        assertEquals(ModeSwitchPreviewWorkFlush.None, coalescer.end())
        assertFalse(coalescer.requestCompositorRefresh(rebuildLut = false))
        assertFalse(coalescer.requestPreviewPipelineRefresh(forceSessionRefresh = false))
    }

    @Test
    fun `abort is idempotent and coalescer is reusable`() {
        val coalescer = ModeSwitchPreviewWorkCoalescer()
        coalescer.begin()
        coalescer.requestCompositorRefresh(rebuildLut = true)

        coalescer.abort()
        coalescer.abort()
        coalescer.begin()
        assertTrue(coalescer.requestPreviewPipelineRefresh(forceSessionRefresh = false))

        assertEquals(
            ModeSwitchPreviewWorkFlush(
                previewPipelineRefresh = PreviewPipelineRefresh.STANDARD
            ),
            coalescer.end()
        )
    }
}
