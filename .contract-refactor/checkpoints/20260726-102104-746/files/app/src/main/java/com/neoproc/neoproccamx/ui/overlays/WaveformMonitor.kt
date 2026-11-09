package com.neoproc.neoproccamx.ui.overlays

import android.content.Context
import android.graphics.*
import android.view.View
import com.neoproc.neoproccamx.camera.FrameGeometry
import kotlin.math.min

/**
 * WaveformMonitor — Real-time luminance waveform display.
 *
 * Displays a column-based histogram showing luminance distribution across the image.
 * Each column represents a luminance level (0-255), with height proportional to pixel count.
 */
class WaveformMonitor(context: Context) : View(context) {

    private val paint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.GREEN
        strokeWidth = 1f
    }

    private val backgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 0, 0, 0)
    }

    private var histogram = IntArray(256)
    private var maxCount = 1

    fun updateHistogram(newHistogram: IntArray) {
        newHistogram.copyInto(histogram)
        maxCount = histogram.maxOrNull() ?: 1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw semi-transparent background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val columnWidth = width / 256f
        val scaleY = height / maxCount.toFloat()

        // Draw waveform columns
        for (i in 0 until 256) {
            val x = i * columnWidth
            val barHeight = histogram[i] * scaleY
            val y = height - barHeight

            paint.color = when {
                i < 64 -> Color.BLUE      // Shadows
                i < 128 -> Color.CYAN     // Midtones
                i < 192 -> Color.YELLOW   // Highlights
                else -> Color.RED         // Overexposed
            }

            canvas.drawRect(x, y, x + columnWidth, height.toFloat(), paint)
        }
    }
}
