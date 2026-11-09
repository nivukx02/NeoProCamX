package com.neoproc.neoproccamx.ui.controls

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.neoproc.neoproccamx.color.ColorChannel
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.*

/**
 * ToneCurveView — Interactive tone curve editor.
 *
 * Draws and handles editing of RGB/Luma tone curves identical to
 * a professional curve editor.
 *
 * Features:
 *  - 4 channels: ALL (luma), RED, GREEN, BLUE
 *  - Drag control points to reshape the curve
 *  - Double-tap a point to delete it
 *  - Tap empty area to add a control point
 *  - Catmull-Rom spline interpolation through control points
 *  - Listener callback fires on every curve change
 */
class ToneCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    // ─── Config ───────────────────────────────────────────────────────────────

    interface OnCurveChangedListener {
        fun onCurveChanged(channel: ColorChannel, points: List<Pair<Float, Float>>)
    }
    var onCurveChangedListener: OnCurveChangedListener? = null

    private var activeChannel: ColorChannel = ColorChannel.ALL

    private val channelColors = mapOf(
        ColorChannel.ALL   to Color.WHITE,
        ColorChannel.R   to Color.rgb(255, 80, 80),
        ColorChannel.G to Color.rgb(80, 220, 80),
        ColorChannel.B  to Color.rgb(80, 120, 255)
    )

    // Control points per channel: (input 0..1, output 0..1)
    private val curvePoints = mutableMapOf(
        ColorChannel.ALL   to mutableListOf(Pair(0f, 0f), Pair(1f, 1f)),
        ColorChannel.R   to mutableListOf(Pair(0f, 0f), Pair(1f, 1f)),
        ColorChannel.G to mutableListOf(Pair(0f, 0f), Pair(1f, 1f)),
        ColorChannel.B  to mutableListOf(Pair(0f, 0f), Pair(1f, 1f))
    )

    private var dragPointIndex = -1
    private val TOUCH_RADIUS_DP = 18f
    private val touchRadius get() = TOUCH_RADIUS_DP * resources.displayMetrics.density

    // ─── Paints ───────────────────────────────────────────────────────────────

    private val bgPaint = Paint().apply {
        color = Color.argb(200, 20, 20, 20)
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val diagonalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pointBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1.5f
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(100, 255, 255, 255)
        strokeWidth = 1f
    }

    // ─── Channel Tabs ─────────────────────────────────────────────────────────

    private val tabLabels = listOf("ALL", "R", "G", "B")
    private val tabChannels = listOf(
        ColorChannel.ALL,
        ColorChannel.R,
        ColorChannel.G,
        ColorChannel.B
    )
    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        typeface = Typeface.MONOSPACE
    }
    private val tabBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val TAB_HEIGHT = 44f

    // ─── Draw ─────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val curveH = h - TAB_HEIGHT
        val pad = 20f

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Tabs
        drawTabs(canvas, w)

        // Curve area bounds
        val left = pad; val right = w - pad
        val top = TAB_HEIGHT + pad; val bottom = curveH - pad
        val cw = right - left; val ch = bottom - top

        // Grid (5x5)
        for (i in 1..4) {
            val gx = left + i * cw / 5f
            val gy = top + i * ch / 5f
            canvas.drawLine(gx, top, gx, bottom, gridPaint)
            canvas.drawLine(left, gy, right, gy, gridPaint)
        }

        // Diagonal reference
        canvas.drawLine(left, bottom, right, top, diagonalPaint)

        // Border
        canvas.drawRect(left, top, right, bottom, borderPaint)

        // Draw all channels lightly
        for (ch2 in ColorChannel.values()) {
            if (ch2 == activeChannel) continue
            val pts = curvePoints[ch2] ?: continue
            val alpha = 40
            curvePaint.color = channelColors.getValue(ch2) or (alpha shl 24)
            curvePaint.strokeWidth = 1f
            drawSpline(canvas, pts, left, top, cw, ch, curvePaint)
        }

        // Draw active channel curve
        val activePts = curvePoints[activeChannel] ?: return
        curvePaint.color = channelColors.getValue(activeChannel)
        curvePaint.alpha = 255
        curvePaint.strokeWidth = 2.5f
        drawSpline(canvas, activePts, left, top, cw, ch, curvePaint)

        // Draw control points
        for ((i, pt) in activePts.withIndex()) {
            val px = left + pt.first * cw
            val py = bottom - pt.second * ch
            pointPaint.color = channelColors.getValue(activeChannel)
            if (i == dragPointIndex) {
                canvas.drawCircle(px, py, 9f, pointPaint)
            } else {
                canvas.drawCircle(px, py, 6f, pointPaint)
            }
            canvas.drawCircle(px, py, 6f, pointBorderPaint)
        }
    }

    private fun drawTabs(canvas: Canvas, w: Float) {
        val tabW = w / tabLabels.size
        for ((i, label) in tabLabels.withIndex()) {
            val ch = tabChannels[i]
            val isActive = ch == activeChannel
            val bgColor = if (isActive) channelColors.getValue(ch).let {
                Color.argb(80, Color.red(it), Color.green(it), Color.blue(it))
            } else Color.argb(30, 255, 255, 255)

            tabBgPaint.color = bgColor
            canvas.drawRect(i * tabW, 0f, (i + 1) * tabW, TAB_HEIGHT, tabBgPaint)

            tabPaint.color = channelColors.getValue(ch)
            tabPaint.alpha = if (isActive) 255 else 120
            val tw = tabPaint.measureText(label)
            canvas.drawText(label, i * tabW + (tabW - tw) / 2f, TAB_HEIGHT - 10f, tabPaint)
        }
    }

    // Catmull-Rom spline through control points
    private fun drawSpline(
        canvas: Canvas,
        points: List<Pair<Float, Float>>,
        left: Float, top: Float, cw: Float, ch: Float,
        paint: Paint
    ) {
        if (points.size < 2) return
        val sorted = points.sortedBy { it.first }
        val path = Path()

        val steps = if (strictHighEndEnabled()) 512 else 256
        for (step in 0..steps) {
            val t = step.toFloat() / steps
            val y = catmullRomSample(sorted, t)
            val px = left + t * cw
            val py = top + ch - y * ch
            if (step == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, paint)
    }

    private fun catmullRomSample(points: List<Pair<Float, Float>>, x: Float): Float {
        if (points.size == 1) return points[0].second
        if (x <= points.first().first) return points.first().second
        if (x >= points.last().first) return points.last().second

        // Find segment
        var i = 1
        while (i < points.size && points[i].first < x) i++
        i = i.coerceIn(1, points.size - 1)

        val p0 = points.getOrElse(i - 2) { points.first() }
        val p1 = points[i - 1]
        val p2 = points[i]
        val p3 = points.getOrElse(i + 1) { points.last() }

        val t = (x - p1.first) / (p2.first - p1.first)
        val t2 = t * t; val t3 = t2 * t

        return 0.5f * (
            (2 * p1.second) +
            (-p0.second + p2.second) * t +
            (2 * p0.second - 5 * p1.second + 4 * p2.second - p3.second) * t2 +
            (-p0.second + 3 * p1.second - 3 * p2.second + p3.second) * t3
        ).coerceIn(0f, 1f)
    }

    // ─── Touch Handling ───────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        val curveH = h - TAB_HEIGHT
        val pad = 20f
        val left = pad; val right = w - pad
        val top = TAB_HEIGHT + pad; val bottom = curveH - pad
        val cw = right - left; val ch = bottom - top

        val tx = event.x; val ty = event.y

        // Tab switching
        if (ty < TAB_HEIGHT && event.action == MotionEvent.ACTION_DOWN) {
            val tabIdx = (tx / (w / tabLabels.size)).toInt().coerceIn(0, tabLabels.size - 1)
            activeChannel = tabChannels[tabIdx]
            invalidate()
            return true
        }

        // Normalize to curve space
        val nx = ((tx - left) / cw).coerceIn(0f, 1f)
        val ny = 1f - ((ty - top) / ch).coerceIn(0f, 1f)

        val pts = curvePoints[activeChannel] ?: return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Find nearest point
                dragPointIndex = -1
                for ((i, pt) in pts.withIndex()) {
                    val dx = (pt.first - nx) * cw
                    val dy = (pt.second - ny) * ch
                    if (sqrt(dx * dx + dy * dy) < touchRadius) {
                        dragPointIndex = i
                        break
                    }
                }
                // Add new point if none found
                if (dragPointIndex < 0) {
                    pts.add(Pair(nx, ny))
                    pts.sortBy { it.first }
                    dragPointIndex = pts.indexOfFirst { abs(it.first - nx) < 0.01f }
                    invalidate()
                    notifyCurveChanged()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragPointIndex >= 0 && dragPointIndex < pts.size) {
                    val constrained = constrainControlPoint(
                        index = dragPointIndex,
                        proposedX = nx,
                        proposedY = ny,
                        points = pts
                    )
                    pts[dragPointIndex] = constrained
                    invalidate()
                    notifyCurveChanged()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                dragPointIndex = -1
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun notifyCurveChanged() {
        val pts = curvePoints[activeChannel] ?: return
        onCurveChangedListener?.onCurveChanged(activeChannel, pts.toList())
    }

    private fun constrainControlPoint(
        index: Int,
        proposedX: Float,
        proposedY: Float,
        points: List<Pair<Float, Float>>
    ): Pair<Float, Float> {
        val y = proposedY.coerceIn(0f, 1f)
        val epsilon = 0.001f
        val x = when (index) {
            0 -> 0f
            points.lastIndex -> 1f
            else -> {
                val minX = points[index - 1].first + epsilon
                val maxX = points[index + 1].first - epsilon
                proposedX.coerceIn(minX, maxX)
            }
        }.coerceIn(0f, 1f)
        return Pair(x, y)
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    fun setChannel(channel: ColorChannel) {
        activeChannel = channel
        invalidate()
    }

    fun resetChannel(channel: ColorChannel) {
        curvePoints[channel] = mutableListOf(Pair(0f, 0f), Pair(1f, 1f))
        invalidate()
        notifyCurveChanged()
    }

    fun resetAll() {
        ColorChannel.values().forEach { resetChannel(it) }
    }

    fun setPoints(channel: ColorChannel, points: List<Pair<Float, Float>>) {
        curvePoints[channel] = points.sortedBy { it.first }.toMutableList()
        invalidate()
    }
}
