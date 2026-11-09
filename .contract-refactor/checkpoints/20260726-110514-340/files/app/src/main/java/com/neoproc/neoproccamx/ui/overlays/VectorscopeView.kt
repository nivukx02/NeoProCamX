package com.neoproc.neoproccamx.ui.overlays

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.neoproc.neoproccamx.pipeline.VectorscopeMath
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * s8-f01 (CP-U1) — opt-in broadcast-style vectorscope overlay. DEFAULT OFF and NOT wired into any
 * layout, so it adds zero preview cost until an owner enables it. All math is delegated to the pure
 * [VectorscopeMath]; this view only rasterises the points + graticule on a Canvas. When [vectorscopeEnabled] is
 * false (or no data is fed) [onDraw] returns immediately.
 */
class VectorscopeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    @Volatile
    var vectorscopeEnabled: Boolean = false

    private var points: List<VectorscopeMath.Point> = emptyList()

    private val graticulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 200, 200, 200)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val traceePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 120, 255, 120)
        strokeWidth = 2f
    }
    private val iLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 170, 120)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    /** Feed an ARGB pixel buffer; subsamples and maps via [VectorscopeMath]. */
    fun updateFromPixels(pixels: IntArray, width: Int, height: Int, downsample: Int = 4) {
        if (!vectorscopeEnabled || pixels.isEmpty() || width <= 0 || height <= 0) return
        val step = downsample.coerceAtLeast(1)
        val mapped = ArrayList<VectorscopeMath.Point>()
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val p = pixels[y * width + x]
                mapped.add(VectorscopeMath.map((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF))
                x += step
            }
            y += step
        }
        points = mapped
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!vectorscopeEnabled || points.isEmpty()) return
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) * 0.9f

        canvas.drawCircle(cx, cy, r, graticulePaint)
        // I-line reference (skin axis ≈123°), drawn through the centre.
        val rad = Math.toRadians(VectorscopeMath.I_LINE_DEG.toDouble())
        val ix = (cos(rad) * r).toFloat()
        val iy = (sin(rad) * r).toFloat()
        canvas.drawLine(cx - ix, cy + iy, cx + ix, cy - iy, iLinePaint)

        for (p in points) {
            val a = Math.toRadians(p.angleDeg.toDouble())
            val px = cx + (cos(a) * r * p.radius01).toFloat()
            val py = cy - (sin(a) * r * p.radius01).toFloat()
            canvas.drawPoint(px, py, traceePaint)
        }
    }
}
