package com.neoproc.neoproccamx.ui.overlays

import android.content.Context
import android.graphics.*
import android.view.View
import com.neoproc.neoproccamx.camera.FrameGeometry
import kotlin.math.abs

/**
 * ZebraExposureOverlay — Shows overexposed areas with animated stripes.
 *
 * Detects pixels above a luminance threshold and overlays animated zebra stripes
 * to indicate areas that are likely overexposed.
 */
class ZebraExposureOverlay(context: Context) : View(context) {

    private val paint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(120, 255, 0, 0)
    }

    private val stripePaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 255, 255, 255)
    }

    private var overexposedRegions: List<RectF> = emptyList()
    private var upperThreshold = 240
    private var lowerThreshold = 235
    private var animationOffset = 0f
    private val stripeRect = RectF()

    init {
        // Start animation
        post(object : Runnable {
            override fun run() {
                animationOffset += 5f
                if (animationOffset > 20f) animationOffset = 0f
                invalidate()
                postDelayed(this, 50)
            }
        })
    }

    fun setThresholds(upper: Int, lower: Int) {
        upperThreshold = upper.coerceIn(200, 255)
        lowerThreshold = lower.coerceIn(200, upperThreshold)
        invalidate()
    }

    fun updateOverexposedRegions(regions: List<RectF>) {
        overexposedRegions = regions
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        overexposedRegions.forEach { region ->
            // Draw base overlay
            canvas.drawRect(region, paint)

            // Draw animated stripes
            val stripeWidth = 4f
            val stripeSpacing = 8f
            val totalWidth = stripeWidth + stripeSpacing

            var x = region.left + animationOffset
            while (x < region.right) {
                stripeRect.set(
                    x,
                    region.top,
                    minOf(x + stripeWidth, region.right),
                    region.bottom
                )
                canvas.drawRect(stripeRect, stripePaint)
                x += totalWidth
            }
        }
    }
}
