package com.neoproc.neoproccamx.ui.controls

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.core.graphics.drawable.toDrawable
import com.neoproc.neoproccamx.ui.CameraHapticAction

class ReferenceAdjustSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(205, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(1.5f)
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2f)
    }
    private val selectedTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(246, 205, 77)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(3.2f)
    }
    private var externalChangeListener: OnSeekBarChangeListener? = null
    private var hapticCallback: ((CameraHapticAction) -> Unit)? = null
    private var trackingTouch = false
    private var lastBoundaryHapticProgress: Int? = null
    private var dragStartX = 0f
    private var dragStartProgress = 0
    private var continuousTickOffsetPx = 0f
    private var snapAnimator: ValueAnimator? = null

    init {
        progressDrawable = Color.TRANSPARENT.toDrawable()
        thumb = Color.TRANSPARENT.toDrawable()
        background = null
        thumbOffset = 0
        splitTrack = false
        minHeight = dp(54f).toInt()
        minimumHeight = dp(54f).toInt()
        setPadding(dp(8f).toInt(), paddingTop, dp(8f).toInt(), paddingBottom)
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
                snapAnimator?.cancel()
                snapAnimator = null
                continuousTickOffsetPx = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
                isPressed = true
                trackingTouch = true
                lastBoundaryHapticProgress = null
                dragStartX = event.x
                dragStartProgress = progress
                externalChangeListener?.onStartTrackingTouch(this)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateProgressFromDrag(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateProgressFromDrag(event.x)
                finishTrackingTouch(snapToTick = true)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                finishTrackingTouch(snapToTick = true)
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
        val start = paddingLeft.toFloat() + dp(1f)
        val end = width.toFloat() - paddingRight - dp(1f)
        if (end <= start) return

        val centerX = (start + end) * 0.5f
        val centerY = height * 0.5f
        val spacing = dp(TICK_SPACING_DP)
        val visibleRadius = (((end - start) * 0.5f) / spacing).toInt() + 1
        for (offset in -visibleRadius..visibleRadius) {
            val valueIndex = progress + offset
            if (valueIndex !in 0..max) continue
            val x = centerX + offset * spacing + continuousTickOffsetPx
            if (x < start || x > end) continue
            val distanceFraction = (kotlin.math.abs(x - centerX) / ((end - start) * 0.5f)).coerceIn(0f, 1f)
            val edgeAlpha = ((1f - distanceFraction * distanceFraction) * 235f).toInt().coerceIn(55, 235)
            val major = valueIndex % 5 == 0
            val halfLength = dp(if (major) 10f else 6f)
            val paint = if (major) majorTickPaint else tickPaint
            paint.alpha = edgeAlpha
            canvas.drawLine(
                x,
                centerY - halfLength,
                x,
                centerY + halfLength,
                paint
            )
        }
        tickPaint.alpha = 255
        majorTickPaint.alpha = 255
        val selectedHalfLength = dp(16f)
        canvas.drawLine(
            centerX,
            centerY - selectedHalfLength,
            centerX,
            centerY + selectedHalfLength,
            selectedTickPaint
        )
    }

    private fun updateProgressFromDrag(x: Float) {
        val spacingPx = dp(TICK_SPACING_DP)
        val deltaX = x - dragStartX
        val newProgress = ReferenceAdjustBarPolicy.progressForDragDelta(
            startProgress = dragStartProgress,
            deltaX = deltaX,
            tickSpacingPx = spacingPx,
            max = max
        )
        continuousTickOffsetPx = ReferenceAdjustBarPolicy.continuousTickOffsetPx(
            startProgress = dragStartProgress,
            currentProgress = newProgress,
            deltaX = deltaX,
            tickSpacingPx = spacingPx
        )
        if (progress != newProgress) {
            progress = newProgress
            emitProgressHaptic(newProgress)
            externalChangeListener?.onProgressChanged(this, newProgress, true)
        }
        postInvalidateOnAnimation()
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

    private fun finishTrackingTouch(snapToTick: Boolean) {
        if (trackingTouch) {
            externalChangeListener?.onStopTrackingTouch(this)
        }
        trackingTouch = false
        lastBoundaryHapticProgress = null
        isPressed = false
        parent?.requestDisallowInterceptTouchEvent(false)
        if (snapToTick) animateToSelectedTick()
    }

    private fun animateToSelectedTick() {
        snapAnimator?.cancel()
        val startOffset = continuousTickOffsetPx
        if (kotlin.math.abs(startOffset) < 0.1f) {
            continuousTickOffsetPx = 0f
            postInvalidateOnAnimation()
            return
        }
        snapAnimator = ValueAnimator.ofFloat(startOffset, 0f).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { animator ->
                continuousTickOffsetPx = animator.animatedValue as Float
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        snapAnimator?.cancel()
        snapAnimator = null
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val TICK_SPACING_DP = 13f
        const val SNAP_DURATION_MS = 90L
    }
}
