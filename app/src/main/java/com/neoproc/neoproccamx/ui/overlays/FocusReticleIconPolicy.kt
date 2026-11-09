package com.neoproc.neoproccamx.ui.overlays

import android.graphics.RectF
import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.ui.FocusAssistVisual
import kotlin.math.min

internal object FocusReticleIconPolicy {
    private const val ICON_SCALE = 0.62f

    internal data class IconBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    fun drawableRes(visual: FocusAssistVisual): Int =
        when (visual) {
            FocusAssistVisual.TAP -> R.drawable.ic_focus_tap
            FocusAssistVisual.FACE -> R.drawable.ic_focus_face
            FocusAssistVisual.EYE -> R.drawable.ic_focus_eye
        }

    fun iconBounds(left: Float, top: Float, right: Float, bottom: Float): IconBounds {
        val boxWidth = right - left
        val boxHeight = bottom - top
        val side = min(boxWidth, boxHeight) * ICON_SCALE
        val halfSide = side * 0.5f
        val centerX = left + boxWidth * 0.5f
        val centerY = top + boxHeight * 0.5f
        return IconBounds(
            left = centerX - halfSide,
            top = centerY - halfSide,
            right = centerX + halfSide,
            bottom = centerY + halfSide
        )
    }

    fun iconBounds(box: RectF): RectF {
        val bounds = iconBounds(
            left = box.left,
            top = box.top,
            right = box.right,
            bottom = box.bottom
        )
        return RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }
}
