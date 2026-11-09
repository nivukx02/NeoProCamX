package com.neoproc.neoproccamx.ui.controls

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.SeekBar
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.graphics.drawable.toDrawable
import com.neoproc.neoproccamx.ui.CameraHapticAction
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ReferenceAdjustSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        strokeCap = Paint.Cap.BUTT
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(246, 205, 77)
        strokeCap = Paint.Cap.BUTT
        style = Paint.Style.STROKE
        strokeWidth = dp(3.2f)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(1.2f)
    }
    private val selectedTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(246, 205, 77)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(3.2f)
    }
    private val arcRect = RectF()
    private var externalChangeListener: OnSeekBarChangeListener? = null
    private var hapticCallback: ((CameraHapticAction) -> Unit)? = null
    private var trackingTouch = false
    private var lastBoundaryHapticProgress: Int? = null

    init {
        progressDrawable = Color.TRANSPARENT.toDrawable()
        thumb = Color.TRANSPARENT.toDrawable()
        background = null
        thumbOffset = 0
        splitTrack = false
        minHeight = dp(42f).toInt()
        minimumHeight = dp(42f).toInt()
        setPadding(dp(10f).toInt(), paddingTop, dp(10f).toInt(), paddingBottom)
    }

    override fun setOnSeekBarChangeListener(listener: OnSeekBarChangeListener?) {
        externalChangeListener = listener
        super.setOnSeekBarChangeListener(null)
    }

    fun setCameraHapticCallback(callback: ((CameraHapticAction) -> Unit)?) {
        hapticCallback = callback
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isPressed = true
                trackingTouch = true
                lastBoundaryHapticProgress = null
                externalChangeListener?.onStartTrackingTouch(this)
                updateProgressFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateProgressFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateProgressFromTouch(event.x)
                finishTrackingTouch()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                finishTrackingTouch()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val start = paddingLeft + dp(1f)
        val end = width - paddingRight - dp(1f)
        if (end <= start) return

        val fraction = ReferenceAdjustBarPolicy.progressFraction(progress, max)
        val startAngle = ReferenceAdjustBarPolicy.lensRingStartAngleDegrees
        val sweep = ReferenceAdjustBarPolicy.lensRingSweepDegrees
        val selectedAngle = ReferenceAdjustBarPolicy.lensRingAngleDegrees(progress, max)

        arcRect.set(
            start,
            dp(1f),
            end,
            height * 2.08f
        )

        canvas.drawArc(arcRect, startAngle, sweep, false, basePaint)
        canvas.drawArc(arcRect, startAngle, selectedAngle - startAngle, false, activePaint)

        ReferenceAdjustBarPolicy.tickFractions(17).forEach { tickFraction ->
            val isSelected = abs(tickFraction - fraction) <= 0.035f
            val tickAngle = startAngle + (sweep * tickFraction)
            drawLensTick(canvas, arcRect, tickAngle, isSelected)
        }
    }

    private fun drawLensTick(canvas: Canvas, rect: RectF, angleDegrees: Float, selected: Boolean) {
        val angleRadians = Math.toRadians(angleDegrees.toDouble())
        val cx = rect.centerX()
        val cy = rect.centerY()
        val rx = rect.width() / 2f
        val ry = rect.height() / 2f
        val tickLength = if (selected) dp(20f) else dp(10f)
        val outerX = cx + cos(angleRadians).toFloat() * rx
        val outerY = cy + sin(angleRadians).toFloat() * ry
        val innerX = cx + cos(angleRadians).toFloat() * (rx - tickLength)
        val innerY = cy + sin(angleRadians).toFloat() * (ry - tickLength)
        canvas.drawLine(
            outerX,
            outerY,
            innerX,
            innerY,
            if (selected) selectedTickPaint else tickPaint
        )
    }

    private fun updateProgressFromTouch(x: Float) {
        val newProgress = ReferenceAdjustBarPolicy.progressForTouchX(
            x = x,
            width = width,
            paddingStart = paddingLeft,
            paddingEnd = paddingRight,
            max = max
        )
        if (progress != newProgress) {
            progress = newProgress
            emitProgressHaptic(newProgress)
            externalChangeListener?.onProgressChanged(this, newProgress, true)
        }
        invalidate()
    }

    private fun emitProgressHaptic(newProgress: Int) {
        if (!trackingTouch) return
        val atBoundary = newProgress == 0 || newProgress == max
        if (atBoundary) {
            if (lastBoundaryHapticProgress == newProgress) return
            lastBoundaryHapticProgress = newProgress
            hapticCallback?.invoke(CameraHapticAction.SLIDER_BOUNDARY_STOP)
            return
        }
        lastBoundaryHapticProgress = null
        hapticCallback?.invoke(CameraHapticAction.MANUAL_CONTROL_TICK)
    }

    private fun finishTrackingTouch() {
        if (trackingTouch) {
            externalChangeListener?.onStopTrackingTouch(this)
        }
        trackingTouch = false
        lastBoundaryHapticProgress = null
        isPressed = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
