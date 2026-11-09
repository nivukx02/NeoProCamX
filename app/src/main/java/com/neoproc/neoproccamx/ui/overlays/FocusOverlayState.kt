package com.neoproc.neoproccamx.ui.overlays

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal data class FocusOverlayState(
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.5f,
    val visible: Boolean = false,
    val locked: Boolean = false,
    val adjustmentVisible: Boolean = false,
    val afFocused: Boolean = false,
    val exposureValue: Int = 0,
    val exposureMin: Int = -4,
    val exposureMax: Int = 4
) {
    val showsExposureControls: Boolean
        get() = visible && (locked || adjustmentVisible)

    fun showAt(
        normalizedX: Float,
        normalizedY: Float,
        locked: Boolean,
        showAdjustment: Boolean
    ): FocusOverlayState = copy(
        normalizedX = normalizedX.coerceIn(0f, 1f),
        normalizedY = normalizedY.coerceIn(0f, 1f),
        visible = true,
        locked = locked,
        adjustmentVisible = locked || showAdjustment
    )

    fun withExposureRange(value: Int, min: Int, max: Int): FocusOverlayState = copy(
        exposureValue = value.coerceIn(min, max),
        exposureMin = min,
        exposureMax = max
    )

    fun hide(): FocusOverlayState = copy(
        visible = false,
        locked = false,
        adjustmentVisible = false
    )
}

internal object FocusOverlayVisibilityPolicy {
    private const val BASIC_HIDE_DELAY_MS = 2200L
    private const val ADJUSTMENT_HIDE_DELAY_MS = 3200L

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun autoHideDelayMs(locked: Boolean, showAdjustment: Boolean): Long? = when {
        locked -> null
        showAdjustment -> if (strictHighEndEnabled()) 4200L else ADJUSTMENT_HIDE_DELAY_MS
        else -> if (strictHighEndEnabled()) 3000L else BASIC_HIDE_DELAY_MS
    }
}
