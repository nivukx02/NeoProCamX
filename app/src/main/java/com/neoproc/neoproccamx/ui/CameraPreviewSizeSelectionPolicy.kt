package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.recording.RecordingSelectionPolicy
import kotlin.math.abs

internal object CameraPreviewSizeSelectionPolicy {

    fun chooseSizeForAspect(
        sizes: List<Size>,
        targetAspect: Float?,
        maxArea: Int = Int.MAX_VALUE
    ): Size? {
        val filtered = sizes.filter {
            val normalized = RecordingSelectionPolicy.normalizeForOrientation(it)
            normalized.width * normalized.height <= maxArea
        }
        val candidates = if (filtered.isNotEmpty()) filtered else sizes
        val aspect = targetAspect ?: return candidates.maxByOrNull { it.width * it.height }
        return candidates.minWithOrNull(
            compareBy<Size> {
                val normalized = RecordingSelectionPolicy.normalizeForOrientation(it)
                abs((normalized.width.toFloat() / normalized.height.toFloat()) - aspect)
            }.thenByDescending {
                val normalized = RecordingSelectionPolicy.normalizeForOrientation(it)
                normalized.width * normalized.height
            }
        )
    }

    fun distinctNormalizedSizes(vararg groups: List<Size>): List<Size> =
        buildList {
            groups.forEach(::addAll)
        }.distinctBy {
            val normalized = RecordingSelectionPolicy.normalizeForOrientation(it)
            "${normalized.width}x${normalized.height}"
        }.sortedByDescending {
            val normalized = RecordingSelectionPolicy.normalizeForOrientation(it)
            normalized.width.toLong() * normalized.height.toLong()
        }

    fun findNormalizedSizeMatch(
        sizes: List<Size>,
        target: Size
    ): Size? =
        sizes.firstOrNull {
            val normalized = RecordingSelectionPolicy.normalizeForOrientation(it)
            normalized.width == target.width && normalized.height == target.height
        }
}
