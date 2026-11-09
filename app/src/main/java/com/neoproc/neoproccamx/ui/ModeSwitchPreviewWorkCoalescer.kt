package com.neoproc.neoproccamx.ui

internal enum class PreviewCompositorRefresh {
    NONE,
    GEOMETRY,
    LUT
}

internal enum class PreviewPipelineRefresh {
    NONE,
    STANDARD,
    FORCE_SESSION
}

internal data class ModeSwitchPreviewWorkFlush(
    val compositorRefresh: PreviewCompositorRefresh = PreviewCompositorRefresh.NONE,
    val previewPipelineRefresh: PreviewPipelineRefresh = PreviewPipelineRefresh.NONE
) {
    companion object {
        val None = ModeSwitchPreviewWorkFlush()
    }
}

internal class ModeSwitchPreviewWorkCoalescer {
    private var depth = 0
    private var pendingCompositorRefresh = PreviewCompositorRefresh.NONE
    private var pendingPreviewPipelineRefresh = PreviewPipelineRefresh.NONE

    fun begin() {
        depth += 1
    }

    fun requestCompositorRefresh(rebuildLut: Boolean): Boolean {
        if (depth <= 0) return false
        pendingCompositorRefresh = when {
            rebuildLut -> PreviewCompositorRefresh.LUT
            pendingCompositorRefresh == PreviewCompositorRefresh.NONE -> PreviewCompositorRefresh.GEOMETRY
            else -> pendingCompositorRefresh
        }
        return true
    }

    fun requestPreviewPipelineRefresh(forceSessionRefresh: Boolean): Boolean {
        if (depth <= 0) return false
        pendingPreviewPipelineRefresh = when {
            forceSessionRefresh -> PreviewPipelineRefresh.FORCE_SESSION
            pendingPreviewPipelineRefresh == PreviewPipelineRefresh.NONE -> PreviewPipelineRefresh.STANDARD
            else -> pendingPreviewPipelineRefresh
        }
        return true
    }

    fun end(): ModeSwitchPreviewWorkFlush {
        if (depth <= 0) return ModeSwitchPreviewWorkFlush.None
        depth -= 1
        if (depth > 0) return ModeSwitchPreviewWorkFlush.None
        return ModeSwitchPreviewWorkFlush(
            compositorRefresh = pendingCompositorRefresh,
            previewPipelineRefresh = pendingPreviewPipelineRefresh
        ).also {
            pendingCompositorRefresh = PreviewCompositorRefresh.NONE
            pendingPreviewPipelineRefresh = PreviewPipelineRefresh.NONE
        }
    }

    /**
     * Abandons every open nesting level and all work derived from the failed transition. Repeated
     * calls are intentionally harmless so every failure boundary can clean up unconditionally.
     */
    fun abort() {
        depth = 0
        pendingCompositorRefresh = PreviewCompositorRefresh.NONE
        pendingPreviewPipelineRefresh = PreviewPipelineRefresh.NONE
    }
}
