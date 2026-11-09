package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs

internal enum class FocusTouchMoveEffect {
    NONE,
    CANCEL_ADJUST_AND_LOCK,
    CANCEL_LOCK_ONLY
}

internal object TouchFocusGesturePolicy {

    private const val BASE_TAP_AFTER_NAVIGATION_SUPPRESSION_MS = 260L
    private const val FOCUS_LOCK_HOLD_DELAY_MS = 3000L
    private const val MIN_HOLD_TOLERANCE_PX = 8f

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun tapAfterNavigationSuppressionMs(): Long =
        if (strictHighEndEnabled()) 300L else BASE_TAP_AFTER_NAVIGATION_SUPPRESSION_MS

    fun clampDisplayExposure(value: Int, lower: Int, upper: Int): Int =
        value.coerceIn(lower, upper)

    fun focusLockHoldDelayMs(): Long = FOCUS_LOCK_HOLD_DELAY_MS

    fun movementEffect(
        deltaX: Float,
        deltaY: Float,
        holdTolerancePx: Float,
        isScaling: Boolean
    ): FocusTouchMoveEffect {
        val strictHighEnd = strictHighEndEnabled()
        val toleranceScale = if (strictHighEnd) 0.88f else 1f
        val safeHoldTolerance = holdTolerancePx.coerceAtLeast(MIN_HOLD_TOLERANCE_PX) * toleranceScale
        return when {
            isScaling || abs(deltaX) > safeHoldTolerance -> FocusTouchMoveEffect.CANCEL_ADJUST_AND_LOCK
            abs(deltaY) > safeHoldTolerance * if (strictHighEnd) 1.75f else 2f ->
                FocusTouchMoveEffect.CANCEL_LOCK_ONLY
            else -> FocusTouchMoveEffect.NONE
        }
    }

    fun shouldIgnoreTapAfterNavigation(
        nowMs: Long,
        lastGestureNavigateMs: Long
    ): Boolean = nowMs - lastGestureNavigateMs < tapAfterNavigationSuppressionMs()
}
