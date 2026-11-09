package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.ui.SensorCropEngine

internal object PreviewAspectResolver {
    private const val MIN_ASPECT = 0.2f
    private const val MAX_ASPECT = 5f

    private fun normalizeAspectCandidate(aspect: Float?): Float? {
        val candidate = aspect ?: return null
        return candidate.coerceIn(MIN_ASPECT, MAX_ASPECT)
    }

    fun resolveStreamAspect(
        streamWidth: Int,
        streamHeight: Int,
        boundsWidth: Int,
        boundsHeight: Int
    ): Float {
        val safeStreamWidth = streamWidth.coerceAtLeast(1)
        val safeStreamHeight = streamHeight.coerceAtLeast(1)
        val streamAspect = safeStreamWidth.toFloat() / safeStreamHeight.toFloat()
        return SensorCropEngine.normalizeLongShortRatio(streamAspect)
    }

    fun resolveDisplayStreamAspect(
        boundsWidth: Int,
        boundsHeight: Int,
        streamWidth: Int,
        streamHeight: Int
    ): Float {
        val streamAspect = resolveStreamAspect(
            streamWidth = streamWidth,
            streamHeight = streamHeight,
            boundsWidth = boundsWidth,
            boundsHeight = boundsHeight
        )
        return SensorCropEngine.displayAspectForBounds(streamAspect, boundsWidth, boundsHeight)
            ?: streamAspect
    }

    fun resolveDisplayAspect(
        boundsWidth: Int,
        boundsHeight: Int,
        streamWidth: Int,
        streamHeight: Int,
        displayContentAspectRatioOverride: Float?,
        aspectRatioOverride: Float?
    ): Float {
        val displayStreamAspect = resolveDisplayStreamAspect(
            boundsWidth = boundsWidth,
            boundsHeight = boundsHeight,
            streamWidth = streamWidth,
            streamHeight = streamHeight
        )
        val resolvedDisplayOverride =
            SensorCropEngine.displayAspectForBounds(
                displayContentAspectRatioOverride,
                boundsWidth,
                boundsHeight
            )
        val resolvedAspectOverride =
            SensorCropEngine.displayAspectForBounds(
                aspectRatioOverride,
                boundsWidth,
                boundsHeight
            )
        return normalizeAspectCandidate(
            resolvedDisplayOverride
                ?: resolvedAspectOverride
                ?: displayStreamAspect
        ) ?: displayStreamAspect
    }
}
