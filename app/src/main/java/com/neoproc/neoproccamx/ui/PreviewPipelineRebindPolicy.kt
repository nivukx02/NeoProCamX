package com.neoproc.neoproccamx.ui

/** Keeps a forced Camera2 session refresh from needlessly rebinding an unchanged preview owner. */
internal object PreviewPipelineRebindPolicy {
    fun shouldRebind(
        sizeChanged: Boolean,
        ownerChanged: Boolean,
        handoffInProgress: Boolean
    ): Boolean = sizeChanged || ownerChanged || handoffInProgress
}
