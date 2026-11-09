package com.neoproc.neoproccamx.ui.controls

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.pipeline.HudIreScale

/**
 * WaveformView — Luma waveform monitor.
 *
 * Renders a classic broadcast-style luma waveform in real time.
 * Each column of the view represents a column of the camera frame,
 * and for each column all pixel luma values are scattered as dots
 * on the Y axis (0 = black, 100 = white).
 *
 * Also draws:
 *  - IRE reference lines (0, 20, 40, 60, 80, 100)
 *  - Optional Parade mode (R/G/B side by side)
 *  - Optional Vectorscope data
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    enum class WaveformMode { LUMA, PARADE, OVERLAY }

    var mode: WaveformMode = WaveformMode.LUMA
        set(v) { field = v; invalidate() }

    // Raw luma data [column][samples], values 0..255
    private var lumaData: Array<IntArray>? = null
    private var redData: Array<IntArray>? = null
    private var greenData: Array<IntArray>? = null
    private var blueData: Array<IntArray>? = null
    private var dataWidth = 0
    private var maxSamplesPerColumn = 0

    // Paints
    private val bgPaint = Paint().apply {
        color = Color.argb(210, 8, 8, 8)
        style = Paint.Style.FILL
    }
    private val irePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }
    private val ireTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        textSize = 20f
        typeface = Typeface.MONOSPACE
    }
    private val lumaDotPaint = Paint().apply {
        color = Color.argb(180, 0, 255, 0)
        style = Paint.Style.FILL
        strokeWidth = 1f
    }
    private val redDotPaint = Paint().apply {
        color = Color.argb(160, 255, 60, 60)
        style = Paint.Style.FILL
    }
    private val greenDotPaint = Paint().apply {
        color = Color.argb(160, 60, 255, 60)
        style = Paint.Style.FILL
    }
    private val blueDotPaint = Paint().apply {
        color = Color.argb(160, 60, 100, 255)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val clippingPaint = Paint().apply {
        color = Color.argb(200, 255, 0, 0)
        style = Paint.Style.FILL
    }

    // ─── Data Feed ────────────────────────────────────────────────────────────

    /**
     * Feed bitmap pixel data to compute waveform columns.
     * Call from a background thread; invalidate() is posted to main.
     */
    fun updateFromBitmap(bitmap: Bitmap, downsampleX: Int = 2) {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        updateFromPixels(
            pixels = pixels,
            width = width,
            height = height,
            downsampleX = if (strictHighEndEnabled()) downsampleX.coerceIn(1, 2) else downsampleX
        )
    }

    /**
     * Zero-copy update path that avoids per-pixel Bitmap access.
     */
    fun updateFromPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        downsampleX: Int = 2
    ) {
        if (width <= 0 || height <= 0) return
        if (pixels.size < width * height) return

        val sampleStep = if (strictHighEndEnabled()) {
            downsampleX.coerceIn(1, 2)
        } else {
            downsampleX.coerceAtLeast(1)
        }
        val cols = ((width + sampleStep - 1) / sampleStep).coerceAtLeast(1)

        val luma = Array(cols) { IntArray(height) }
        val r = Array(cols) { IntArray(height) }
        val g = Array(cols) { IntArray(height) }
        val b = Array(cols) { IntArray(height) }

        for (col in 0 until cols) {
            val sourceX = (col * sampleStep).coerceAtMost(width - 1)
            var sourceIndex = sourceX
            for (row in 0 until height) {
                val px = pixels[sourceIndex]
                val ri = (px shr 16) and 0xFF
                val gi = (px shr 8) and 0xFF
                val bi = px and 0xFF
                val l = (0.2126 * ri + 0.7152 * gi + 0.0722 * bi).toInt()
                luma[col][row] = l
                r[col][row] = ri
                g[col][row] = gi
                b[col][row] = bi
                sourceIndex += width
            }
        }

        lumaData = luma
        redData = r
        greenData = g
        blueData = b
        dataWidth = cols
        maxSamplesPerColumn = height

        postInvalidateOnAnimation()
    }

    /**
     * Lightweight feed: just column averages (less accurate but faster).
     */
    fun updateFromColumnAverages(averages: IntArray) {
        val cols = averages.size
        lumaData = Array(cols) { col -> IntArray(1) { averages[col] } }
        dataWidth = cols
        maxSamplesPerColumn = 1
        post { invalidate() }
    }

    // ─── Draw ─────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 30f
        val plotLeft = pad + 16f
        val plotRight = w - 4f
        val plotTop = 4f
        val plotBottom = h - 4f
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.drawRect(plotLeft, plotTop, plotRight, plotBottom, borderPaint)

        // IRE lines at 0, 20, 40, 60, 80, 100
        for (ire in listOf(0, 20, 40, 60, 80, 100)) {
            val y = plotBottom - (ire / 100f) * plotH
            canvas.drawLine(plotLeft, y, plotRight, y, irePaint)
            canvas.drawText("$ire", 2f, y + 6f, ireTextPaint)
        }

        // Draw waveform
        when (mode) {
            WaveformMode.LUMA -> drawChannel(canvas, lumaData, plotLeft, plotTop, plotW, plotH, lumaDotPaint)
            WaveformMode.PARADE -> drawParade(canvas, plotLeft, plotTop, plotW, plotH)
            WaveformMode.OVERLAY -> {
                drawChannel(canvas, redData, plotLeft, plotTop, plotW, plotH, redDotPaint)
                drawChannel(canvas, greenData, plotLeft, plotTop, plotW, plotH, greenDotPaint)
                drawChannel(canvas, blueData, plotLeft, plotTop, plotW, plotH, blueDotPaint)
            }
        }

        // Clipping indicator (values at 255)
        drawClippingIndicator(canvas, plotLeft, plotW)
    }

    private fun drawChannel(
        canvas: Canvas,
        data: Array<IntArray>?,
        plotLeft: Float, plotTop: Float, plotW: Float, plotH: Float,
        paint: Paint
    ) {
        data ?: return
        if (data.isEmpty()) return
        val cols = data.size

        for (col in data.indices) {
            val x = plotLeft + (col.toFloat() / cols) * plotW
            for (sample in data[col]) {
                val y = plotTop + plotH - HudIreScale.normalizedFromCode8(sample) * plotH
                canvas.drawPoint(x, y, paint)
            }
        }
    }

    private fun drawParade(
        canvas: Canvas,
        plotLeft: Float, plotTop: Float, plotW: Float, plotH: Float
    ) {
        val channelW = plotW / 3f
        val channels = listOf(
            Triple(redData, redDotPaint, 0f),
            Triple(greenData, greenDotPaint, channelW),
            Triple(blueData, blueDotPaint, channelW * 2)
        )
        for ((data, paint, offsetX) in channels) {
            data ?: continue
            val cols = data.size
            for (col in data.indices) {
                val x = plotLeft + offsetX + (col.toFloat() / cols) * channelW
                for (sample in data[col]) {
                    val y = plotTop + plotH - HudIreScale.normalizedFromCode8(sample) * plotH
                    canvas.drawPoint(x, y, paint)
                }
            }
            // Parade divider
            val divX = plotLeft + offsetX + channelW
            canvas.drawLine(divX, plotTop, divX, plotTop + plotH, borderPaint)
        }
    }

    private fun drawClippingIndicator(
        canvas: Canvas,
        plotLeft: Float, plotW: Float
    ) {
        val data = lumaData ?: return
        val clippingThreshold = if (strictHighEndEnabled()) 250 else 253
        for (col in data.indices) {
            val x = plotLeft + (col.toFloat() / data.size) * plotW
            if (data[col].any { it >= clippingThreshold }) {
                canvas.drawLine(x, 0f, x, 4f, clippingPaint)
            }
        }
    }
}
