package com.neoproc.neoproccamx.ui.overlays

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.roundToInt

/**
 * FalseColorOverlay — Real-time false colour exposure monitoring.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * CINEMA MONITORING STANDARD
 * ═════════════════════════════════════════════════════════════════════════════════════
 * Professional cinematographers rely on false colour to judge exposure at a glance:
 *
 *   Magenta    ~ 0–1%    (pure black / crushed)
 *   Blue       ~ 1–3%    (deep shadows)
 *   Cyan       ~ 3–10%   (shadows with detail)
 *   Green      ~ 10–20%  (ideal skin-tone exposure)
 *   Yellow     ~ 20–40%  (midtone)
 *   Orange     ~ 40–60%  (highlights with detail)
 *   Red        ~ 60–90%  (bright highlights)
 *   Pink/White ~ 90–100% (clipped / overexposed)
 *
 * This overlay paints a translucent false-colour map over the camera preview.  It is
 * intended to be used as a full-screen overlay with PorterDuff.Mode.MULTIPLY or
 * SRC_OVER depending on the desired visibility.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * PERFORMANCE
 * ═════════════════════════════════════════════════════════════════════════════════════
 * The overlay receives a low-resolution luminance mask (e.g. 64×36) and upscales it
 * via bilinear hardware filtering — no per-pixel CPU work during draw().  The heavy
 * lifting (scene analysis → luma map) happens in FramePipelineManager on a background
 * thread.
 */
class FalseColorOverlay(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** Cached gradient for the legend bar. */
    private val legendGradient = LinearGradient(
        0f, 0f, 1f, 0f,
        intArrayOf(
            COLOR_MAGENTA,
            COLOR_BLUE,
            COLOR_CYAN,
            COLOR_GREEN,
            COLOR_YELLOW,
            COLOR_ORANGE,
            COLOR_RED,
            COLOR_PINK
        ),
        floatArrayOf(0.00f, 0.03f, 0.10f, 0.20f, 0.40f, 0.60f, 0.90f, 1.00f),
        Shader.TileMode.CLAMP
    )

    @Volatile private var falseColorEnabled = false
    @Volatile private var showLegend = true
    @Volatile private var lumaMap: ByteArray? = null
    @Volatile private var mapWidth = 0
    @Volatile private var mapHeight = 0

    /** Opacity 0.0–1.0 of the false colour layer. */
    @Volatile private var overlayOpacity = 0.55f

    companion object {
        // IRE-based cinema false colour palette (ARGB)
        val COLOR_MAGENTA = Color.argb(255, 255, 0, 255)
        val COLOR_BLUE = Color.argb(255, 0, 0, 255)
        val COLOR_CYAN = Color.argb(255, 0, 255, 255)
        val COLOR_GREEN = Color.argb(255, 0, 255, 0)
        val COLOR_YELLOW = Color.argb(255, 255, 255, 0)
        val COLOR_ORANGE = Color.argb(255, 255, 128, 0)
        val COLOR_RED = Color.argb(255, 255, 0, 0)
        val COLOR_PINK = Color.argb(255, 255, 192, 192)
    }

    fun setOverlayEnabled(enabled: Boolean) {
        if (falseColorEnabled != enabled) {
            falseColorEnabled = enabled
            invalidate()
        }
    }

    fun setLegendVisible(visible: Boolean) {
        if (showLegend != visible) {
            showLegend = visible
            invalidate()
        }
    }

    fun setOpacity(opacity: Float) {
        overlayOpacity = opacity.coerceIn(0.1f, 1.0f)
        invalidate()
    }

    /**
     * Updates the luminance map that drives false colour generation.
     * @param map linear luma 0–255, row-major, dimensions [width × height].
     */
    fun updateLumaMap(map: ByteArray, width: Int, height: Int) {
        lumaMap = map
        mapWidth = width
        mapHeight = height
        if (falseColorEnabled) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!falseColorEnabled) return

        drawFalseColorLayer(canvas)
        if (showLegend) drawLegend(canvas)
    }

    private fun drawFalseColorLayer(canvas: Canvas) {
        val map = lumaMap ?: return
        val w = mapWidth
        val h = mapHeight
        if (w <= 0 || h <= 0) return

        val cellW = width.toFloat() / w
        val cellH = height.toFloat() / h

        val alpha = (255 * overlayOpacity).roundToInt()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val luma = map[y * w + x].toInt() and 0xFF
                val color = lumaToFalseColor(luma, alpha)
                paint.color = color
                val left = x * cellW
                val top = y * cellH
                canvas.drawRect(left, top, left + cellW, top + cellH, paint)
            }
        }
    }

    private fun drawLegend(canvas: Canvas) {
        val barHeight = 40f
        val barMargin = 20f

        paint.shader = legendGradient
        paint.alpha = (255 * 0.9f).roundToInt()
        canvas.drawRect(
            barMargin,
            height - barHeight - barMargin,
            width - barMargin,
            height - barMargin,
            paint
        )
        paint.shader = null

        // Ticks
        paint.color = Color.WHITE
        paint.strokeWidth = 2f
        val tickY = height - barHeight - barMargin
        for (i in 0..10) {
            val x = barMargin + (width - 2 * barMargin) * i / 10f
            canvas.drawLine(x, tickY, x, tickY + 8f, paint)
        }
    }

    private fun lumaToFalseColor(luma: Int, alpha: Int): Int {
        val base = when {
            luma < 3 -> COLOR_MAGENTA
            luma < 10 -> COLOR_BLUE
            luma < 26 -> COLOR_CYAN
            luma < 51 -> COLOR_GREEN
            luma < 102 -> COLOR_YELLOW
            luma < 153 -> COLOR_ORANGE
            luma < 230 -> COLOR_RED
            else -> COLOR_PINK
        }
        return (base and 0x00FFFFFF) or (alpha shl 24)
    }
}
