package com.neoproc.neoproccamx.ui.controls

import kotlin.math.roundToInt

internal object SettingsChoiceDialogLayoutPolicy {
    fun resolveWidthPx(
        screenWidthPx: Int,
        density: Float,
        strictHighEnd: Boolean
    ): Int {
        val safeScreenWidth = screenWidthPx.coerceAtLeast(1)
        val safeDensity = density.coerceAtLeast(1f)
        val widthScale = if (strictHighEnd) 0.73f else 0.76f
        val maxWidth = (340f * safeDensity).roundToInt()
        val minWidth = (260f * safeDensity).roundToInt().coerceAtMost(safeScreenWidth)
        val outerMargin = (16f * safeDensity).roundToInt()
        val screenBound = (safeScreenWidth - outerMargin * 2).coerceAtLeast(minWidth)
        val upperBound = minOf(maxWidth, screenBound)

        return (safeScreenWidth * widthScale).roundToInt().coerceIn(minWidth, upperBound)
    }
}
