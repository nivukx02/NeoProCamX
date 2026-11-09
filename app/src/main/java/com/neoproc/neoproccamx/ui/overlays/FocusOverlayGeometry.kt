package com.neoproc.neoproccamx.ui.overlays

import android.graphics.PointF
import android.graphics.RectF
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.rendering.PreviewContentBoundsResolver
import com.neoproc.neoproccamx.rendering.PreviewTransformMath
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

internal data class FocusOverlayLayout(
    val previewRect: RectF,
    val focusCenter: PointF,
    val focusRadiusPx: Float,
    val sliderRect: RectF?,
    val lockBadgeRect: RectF?
)

internal data class FocusSliderSize(
    val widthPx: Float,
    val heightPx: Float
)

internal object FocusOverlayGeometry {

    private const val FOCUS_RADIUS_DP = 32.5f
    private const val FOCUS_HIT_SLOP_MULTIPLIER = 1.4f
    private const val STRICT_FOCUS_HIT_SLOP_MULTIPLIER = 1.25f
    private const val SLIDER_HEIGHT_DP = 60f
    private const val SLIDER_WIDTH_DP = 6f
    private const val SLIDER_OFFSET_DP = 12f
    private const val SLIDER_TOUCH_PADDING_DP = 28f
    private const val STRICT_SLIDER_TOUCH_PADDING_DP = 22f
    private const val PREVIEW_EDGE_PADDING_DP = 8f
    private const val STRICT_PREVIEW_EDGE_PADDING_DP = 10f
    private const val LOCK_BADGE_WIDTH_DP = 54f
    private const val LOCK_BADGE_HEIGHT_DP = 24f
    private const val LOCK_BADGE_OFFSET_DP = 10f

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun focusHitSlopMultiplier(): Float =
        if (strictHighEndEnabled()) STRICT_FOCUS_HIT_SLOP_MULTIPLIER else FOCUS_HIT_SLOP_MULTIPLIER

    private fun sliderTouchPaddingDp(): Float =
        if (strictHighEndEnabled()) STRICT_SLIDER_TOUCH_PADDING_DP else SLIDER_TOUCH_PADDING_DP

    private fun previewEdgePaddingDp(): Float =
        if (strictHighEndEnabled()) STRICT_PREVIEW_EDGE_PADDING_DP else PREVIEW_EDGE_PADDING_DP

    fun resolvePreviewRect(
        viewWidth: Float,
        viewHeight: Float,
        previewFillViewport: Boolean,
        previewAspectRatio: Float?,
        previewSourceAspectRatio: Float?
    ): RectF {
        val bounds = resolvePreviewBounds(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            previewFillViewport = previewFillViewport,
            previewAspectRatio = previewAspectRatio,
            previewSourceAspectRatio = previewSourceAspectRatio
        )
        return RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    fun resolvePreviewBounds(
        viewWidth: Float,
        viewHeight: Float,
        previewFillViewport: Boolean,
        previewAspectRatio: Float?,
        previewSourceAspectRatio: Float?
    ): PreviewTransformMath.RectBounds {
        val safeViewWidth = viewWidth.coerceAtLeast(1f)
        val safeViewHeight = viewHeight.coerceAtLeast(1f)
        if (previewFillViewport) {
            return PreviewTransformMath.RectBounds(0f, 0f, safeViewWidth, safeViewHeight)
        }
        val aspect = (previewAspectRatio ?: previewSourceAspectRatio)?.takeIf { it > 0.01f }
            ?: return PreviewTransformMath.RectBounds(0f, 0f, safeViewWidth, safeViewHeight)
        return PreviewContentBoundsResolver.resolve(
            viewWidth = safeViewWidth,
            viewHeight = safeViewHeight,
            targetAspect = aspect,
            fillViewport = false
        )
    }

    fun resolveLayout(
        viewWidth: Float,
        viewHeight: Float,
        previewFillViewport: Boolean,
        previewAspectRatio: Float?,
        previewSourceAspectRatio: Float?,
        state: FocusOverlayState,
        density: Float
    ): FocusOverlayLayout {
        val previewRect = resolvePreviewRect(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            previewFillViewport = previewFillViewport,
            previewAspectRatio = previewAspectRatio,
            previewSourceAspectRatio = previewSourceAspectRatio
        )
        val focusCenter = PointF(
            previewRect.left + previewRect.width() * state.normalizedX,
            previewRect.top + previewRect.height() * state.normalizedY
        )
        val focusRadiusPx = dp(FOCUS_RADIUS_DP, density)
        val sliderRect = if (state.showsExposureControls) {
            resolveSliderRect(previewRect, focusCenter, focusRadiusPx, density)
        } else {
            null
        }
        val lockBadgeRect = if (state.visible && state.locked) {
            resolveLockBadgeRect(focusCenter, focusRadiusPx, density)
        } else {
            null
        }
        return FocusOverlayLayout(
            previewRect = previewRect,
            focusCenter = focusCenter,
            focusRadiusPx = focusRadiusPx,
            sliderRect = sliderRect,
            lockBadgeRect = lockBadgeRect
        )
    }

    fun isFocusUiHit(
        x: Float,
        y: Float,
        layout: FocusOverlayLayout,
        state: FocusOverlayState
    ): Boolean {
        if (!state.visible) return false
        val hitSlopMultiplier = focusHitSlopMultiplier()
        return abs(x - layout.focusCenter.x) <= layout.focusRadiusPx * hitSlopMultiplier &&
            abs(y - layout.focusCenter.y) <= layout.focusRadiusPx * hitSlopMultiplier
    }

    fun focusEvValueForTouch(
        x: Float,
        y: Float,
        layout: FocusOverlayLayout,
        state: FocusOverlayState,
        density: Float
    ): Int? {
        if (!state.showsExposureControls || state.exposureMax <= state.exposureMin) return null
        val slider = layout.sliderRect ?: return null
        val touchPadding = dp(sliderTouchPaddingDp(), density)
        if (x < slider.left - touchPadding || x > slider.right + touchPadding) return null
        // MIS-TOUCH GUARD: gate the vertical axis too. Without this the EV hit area was a
        // full-preview-height column (X-gated only), so any tap/drag above or below the visible
        // slider bar changed exposure. Bound it to the slider bar plus the same touchPadding on
        // top/bottom so only touches on the actual adjust bar move EV (extremes still reachable at
        // the padded edges via the coerceIn clamp below).
        if (y < slider.top - touchPadding || y > slider.bottom + touchPadding) return null
        val clampedY = y.coerceIn(slider.top, slider.bottom)
        val t = 1f - ((clampedY - slider.top) / slider.height().coerceAtLeast(1f))
        return (state.exposureMin + t * (state.exposureMax - state.exposureMin))
            .roundToInt()
            .coerceIn(state.exposureMin, state.exposureMax)
    }

    fun normalizeTouchPoint(x: Float, y: Float, previewRect: RectF): PointF {
        val nx = ((x - previewRect.left) / previewRect.width().coerceAtLeast(1f)).coerceIn(0f, 1f)
        val ny = ((y - previewRect.top) / previewRect.height().coerceAtLeast(1f)).coerceIn(0f, 1f)
        return PointF(nx, ny)
    }

    fun normalizeTouchPointOrNull(x: Float, y: Float, previewRect: RectF): PointF? {
        if (
            x < previewRect.left ||
            x > previewRect.right ||
            y < previewRect.top ||
            y > previewRect.bottom
        ) {
            return null
        }
        return normalizeTouchPoint(x, y, previewRect)
    }

    fun focusThumbCenterY(layout: FocusOverlayLayout, state: FocusOverlayState): Float? {
        val slider = layout.sliderRect ?: return null
        if (state.exposureMax <= state.exposureMin) return slider.bottom
        return slider.bottom -
            ((state.exposureValue - state.exposureMin).toFloat() /
                (state.exposureMax - state.exposureMin).toFloat()) * slider.height()
    }

    fun resolveSliderSize(density: Float): FocusSliderSize =
        FocusSliderSize(
            widthPx = dp(SLIDER_WIDTH_DP, density),
            heightPx = dp(SLIDER_HEIGHT_DP, density)
        )

    private fun resolveSliderRect(
        previewRect: RectF,
        center: PointF,
        focusRadiusPx: Float,
        density: Float
    ): RectF {
        val sliderSize = resolveSliderSize(density)
        val sliderHeight = sliderSize.heightPx
        val sliderWidth = sliderSize.widthPx
        val edgePadding = dp(previewEdgePaddingDp(), density)
        val offset = dp(SLIDER_OFFSET_DP, density)
        val left = resolveSliderLeft(
            previewLeft = previewRect.left,
            previewRight = previewRect.right,
            centerX = center.x,
            focusRadiusPx = focusRadiusPx,
            sliderWidth = sliderWidth,
            edgePadding = edgePadding,
            offset = offset
        )
        val top = (center.y - sliderHeight / 2f).coerceAtLeast(previewRect.top + edgePadding)
        val bottom = min(previewRect.bottom - edgePadding, top + sliderHeight)
        return RectF(left, bottom - sliderHeight, left + sliderWidth, bottom)
    }

    internal fun resolveSliderLeft(
        previewLeft: Float,
        previewRight: Float,
        centerX: Float,
        focusRadiusPx: Float,
        sliderWidth: Float,
        edgePadding: Float,
        offset: Float
    ): Float {
        val preferredLeft = centerX + focusRadiusPx + offset
        val preferredRight = preferredLeft + sliderWidth
        val rawLeft = if (preferredRight <= previewRight - edgePadding) {
            preferredLeft
        } else {
            centerX - focusRadiusPx - offset - sliderWidth
        }
        val minLeft = previewLeft + edgePadding
        val maxLeft = previewRight - edgePadding - sliderWidth
        return if (maxLeft >= minLeft) {
            rawLeft.coerceIn(minLeft, maxLeft)
        } else {
            ((previewLeft + previewRight) * 0.5f) - sliderWidth / 2f
        }
    }

    private fun resolveLockBadgeRect(
        center: PointF,
        focusRadiusPx: Float,
        density: Float
    ): RectF {
        val width = dp(LOCK_BADGE_WIDTH_DP, density)
        val height = dp(LOCK_BADGE_HEIGHT_DP, density)
        val left = center.x - width / 2f
        val top = center.y + focusRadiusPx + dp(LOCK_BADGE_OFFSET_DP, density)
        return RectF(left, top, left + width, top + height)
    }

    private fun dp(value: Float, density: Float): Float = value * density
}
