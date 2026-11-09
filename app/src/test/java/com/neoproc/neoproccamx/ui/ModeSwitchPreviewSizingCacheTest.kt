package com.neoproc.neoproccamx.ui

import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ModeSwitchPreviewSizingCacheTest {
    @Test
    fun cachesOnlyWithinActiveFlushAndSeparatesAnalysisOwners() {
        val cache = ModeSwitchPreviewSizingCache()
        var previewResolutions = 0
        val previewResolver = { Size(++previewResolutions, 1) }

        val inactiveFirst = cache.previewSize(previewResolver)
        val inactiveSecond = cache.previewSize(previewResolver)
        assertNotSame(inactiveFirst, inactiveSecond)
        assertEquals(2, previewResolutions)

        cache.setActive(true)
        val cachedPreview = cache.previewSize(previewResolver)
        assertSame(cachedPreview, cache.previewSize(previewResolver))
        assertEquals(3, previewResolutions)

        val source = Size(1920, 1080)
        var analysisResolutions = 0
        fun resolveAnalysis(): Size = Size(++analysisResolutions, 1)
        val directAnalysis = cache.analysisSize(source, false, ::resolveAnalysis)
        assertSame(directAnalysis, cache.analysisSize(source, false, ::resolveAnalysis))
        assertNotSame(directAnalysis, cache.analysisSize(source, true, ::resolveAnalysis))
        assertEquals(2, analysisResolutions)

        cache.setActive(false)
        assertNotSame(cachedPreview, cache.previewSize(previewResolver))
        cache.analysisSize(source, false, ::resolveAnalysis)
        assertEquals(4, previewResolutions)
        assertEquals(3, analysisResolutions)
    }
}
