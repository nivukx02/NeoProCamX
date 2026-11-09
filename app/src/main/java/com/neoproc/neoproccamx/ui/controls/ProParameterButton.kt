package com.neoproc.neoproccamx.ui.controls

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton
import com.neoproc.neoproccamx.R

/**
 * Transparent, icon-first camera parameter control used by the compact Pro row.
 *
 * The value remains the normal [text] property for accessibility and UI automation, while the
 * camera glyph is drawn deterministically so it cannot fall back to a letter-only material button.
 */
class ProParameterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val iconBounds = RectF()

    init {
        background = null
        stateListAnimator = null
        setPadding(0, 0, 0, 0)
        minWidth = 0
        minHeight = 0
        isAllCaps = false
        includeFontPadding = false
    }

    override fun onDraw(canvas: Canvas) {
        val color = when {
            isSelected -> ACCENT
            else -> currentTextColor
        }
        val pressedAlpha = if (isPressed) 0.72f else 1f
        linePaint.color = color
        linePaint.alpha = (Color.alpha(color) * pressedAlpha).toInt()
        linePaint.strokeWidth = dp(1.65f)
        glyphPaint.color = color
        glyphPaint.alpha = linePaint.alpha
        glyphPaint.textSize = dp(9.5f)
        valuePaint.color = color
        valuePaint.alpha = linePaint.alpha
        valuePaint.textSize = dp(10.5f)

        val centerX = width * 0.5f
        val iconCenterY = dp(17f)
        val halfWidth = dp(11.5f)
        val halfHeight = dp(9.5f)
        iconBounds.set(
            centerX - halfWidth,
            iconCenterY - halfHeight,
            centerX + halfWidth,
            iconCenterY + halfHeight
        )

        when (id) {
            R.id.tabEV -> drawExposureCompensation(canvas, iconBounds)
            R.id.tabISO -> drawLabelBox(canvas, iconBounds, "ISO", dp(8f))
            R.id.tabSS -> drawLabelBox(canvas, iconBounds, "S", dp(10f))
            R.id.tabWB -> drawWhiteBalance(canvas, iconBounds)
            R.id.tabMF -> drawManualFocus(canvas, centerX, iconCenterY)
            R.id.tabMeteringMode -> drawMetering(canvas, centerX, iconCenterY)
        }

        val shownValue = displayValue(text?.toString().orEmpty())
        valuePaint.typeface = android.graphics.Typeface.create(
            "sans-serif-medium",
            if (color == ACCENT) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
        canvas.drawText(shownValue, centerX, height - dp(5.5f), valuePaint)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    private fun drawExposureCompensation(canvas: Canvas, bounds: RectF) {
        canvas.drawRect(bounds, linePaint)
        canvas.drawLine(bounds.left + dp(2f), bounds.bottom - dp(2f), bounds.right - dp(2f), bounds.top + dp(2f), linePaint)
        val plusX = bounds.left + bounds.width() * 0.30f
        val plusY = bounds.top + bounds.height() * 0.30f
        canvas.drawLine(plusX - dp(2.6f), plusY, plusX + dp(2.6f), plusY, linePaint)
        canvas.drawLine(plusX, plusY - dp(2.6f), plusX, plusY + dp(2.6f), linePaint)
        val minusX = bounds.left + bounds.width() * 0.73f
        val minusY = bounds.top + bounds.height() * 0.73f
        canvas.drawLine(minusX - dp(2.7f), minusY, minusX + dp(2.7f), minusY, linePaint)
    }

    private fun drawLabelBox(canvas: Canvas, bounds: RectF, label: String, textSize: Float) {
        canvas.drawRoundRect(bounds, dp(1.2f), dp(1.2f), linePaint)
        glyphPaint.textSize = textSize
        val baseline = bounds.centerY() - (glyphPaint.ascent() + glyphPaint.descent()) * 0.5f
        canvas.drawText(label, bounds.centerX(), baseline, glyphPaint)
    }

    private fun drawWhiteBalance(canvas: Canvas, bounds: RectF) {
        drawLabelBox(canvas, bounds, "WB", dp(8.5f))
        val sunY = bounds.bottom + dp(4.2f)
        val sunRadius = dp(1.6f)
        canvas.drawArc(
            RectF(bounds.centerX() - sunRadius, sunY - sunRadius, bounds.centerX() + sunRadius, sunY + sunRadius),
            180f,
            180f,
            false,
            linePaint
        )
        canvas.drawLine(bounds.centerX() - dp(5f), sunY, bounds.centerX() - dp(3.2f), sunY, linePaint)
        canvas.drawLine(bounds.centerX() + dp(3.2f), sunY, bounds.centerX() + dp(5f), sunY, linePaint)
    }

    private fun drawManualFocus(canvas: Canvas, centerX: Float, centerY: Float) {
        canvas.drawCircle(centerX, centerY, dp(2.7f), linePaint)
        val inner = RectF(centerX - dp(7f), centerY - dp(7f), centerX + dp(7f), centerY + dp(7f))
        val outer = RectF(centerX - dp(11f), centerY - dp(11f), centerX + dp(11f), centerY + dp(11f))
        canvas.drawArc(inner, 118f, 124f, false, linePaint)
        canvas.drawArc(inner, -62f, 124f, false, linePaint)
        canvas.drawArc(outer, 128f, 104f, false, linePaint)
        canvas.drawArc(outer, -52f, 104f, false, linePaint)
    }

    private fun drawMetering(canvas: Canvas, centerX: Float, centerY: Float) {
        val left = centerX - dp(11f)
        val right = centerX + dp(11f)
        val top = centerY - dp(10f)
        val bottom = centerY + dp(10f)
        val arm = dp(5f)
        canvas.drawLine(left, top + arm, left, top, linePaint)
        canvas.drawLine(left, top, left + arm, top, linePaint)
        canvas.drawLine(right - arm, top, right, top, linePaint)
        canvas.drawLine(right, top, right, top + arm, linePaint)
        canvas.drawLine(left, bottom - arm, left, bottom, linePaint)
        canvas.drawLine(left, bottom, left + arm, bottom, linePaint)
        canvas.drawLine(right - arm, bottom, right, bottom, linePaint)
        canvas.drawLine(right, bottom, right, bottom - arm, linePaint)
        canvas.drawCircle(centerX, centerY, dp(5.2f), linePaint)
        canvas.drawCircle(centerX, centerY, dp(1.4f), linePaint)
    }

    private fun displayValue(raw: String): String {
        val lastLine = raw.lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        return when {
            id == R.id.tabEV -> lastLine.removeSuffix(" EV")
            id == R.id.tabMeteringMode && lastLine.equals("Meter", ignoreCase = true) -> "MATRIX"
            else -> lastLine
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val ACCENT: Int = 0xFFB46CFF.toInt()
    }
}
