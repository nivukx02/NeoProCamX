package com.neoproc.neoproccamx.ui

import kotlin.math.abs

internal data class CameraModeRailItem(
    val preset: CameraModePreset,
    val left: Int,
    val right: Int
) {
    val center: Int = (left + right) / 2
}

internal object CameraModeRailCenterSelectionPolicy {
    fun resolveCenteredPreset(
        viewportScrollX: Int,
        viewportWidth: Int,
        items: List<CameraModeRailItem>
    ): CameraModePreset? {
        if (viewportWidth <= 0 || items.isEmpty()) return null
        val viewportCenter = viewportScrollX + viewportWidth / 2
        return items.minByOrNull { item -> abs(item.center - viewportCenter) }?.preset
    }

    fun scrollTargetForCenteredItem(
        viewportWidth: Int,
        contentWidth: Int,
        item: CameraModeRailItem
    ): Int {
        if (viewportWidth <= 0) return 0
        val maxScroll = (contentWidth - viewportWidth).coerceAtLeast(0)
        val target = item.center - viewportWidth / 2
        return target.coerceIn(0, maxScroll)
    }
}
