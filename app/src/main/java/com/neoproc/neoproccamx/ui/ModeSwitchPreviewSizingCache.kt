package com.neoproc.neoproccamx.ui

import android.util.DisplayMetrics
import android.util.Size

/** Reuses settled preview sizing only during one synchronous mode-switch flush. */
internal class ModeSwitchPreviewSizingCache {
    private data class AnalysisKey(val width: Int, val height: Int, val nativePreview: Boolean)

    private var active = false
    private var previewSize: Size? = null
    private val analysisSizes = HashMap<AnalysisKey, Size>(2)

    fun setActive(active: Boolean) {
        this.active = active
        previewSize = null
        analysisSizes.clear()
    }

    fun previewSize(resolve: () -> Size): Size {
        if (!active) return resolve()
        return previewSize ?: resolve().also { previewSize = it }
    }

    fun analysisSize(
        previewSize: Size,
        nativePreview: Boolean,
        resolve: () -> Size
    ): Size {
        if (!active) return resolve()
        val key = AnalysisKey(previewSize.width, previewSize.height, nativePreview)
        return analysisSizes[key] ?: resolve().also { analysisSizes[key] = it }
    }

    companion object {
        fun mobileDisplayBudget(metrics: DisplayMetrics): Size = Size(
            maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1),
            minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        )
    }
}
