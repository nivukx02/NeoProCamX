package com.neoproc.neoproccamx.pipeline

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * s8-f01 (CP-U1) — pure-Kotlin polar mapping behind the opt-in vectorscope: RGB → BT.709 Y'CbCr →
 * (angle, radius). A vectorscope shows hue as angle and saturation as radius; the skin-tone "I-line"
 * sits at ≈123°. This is the JVM-testable core (no android.graphics); VectorscopeView only renders the
 * points it returns.
 */
internal object VectorscopeMath {

    /** Standard skin-tone reference (the "I" axis) on a vectorscope graticule. */
    const val I_LINE_DEG: Float = 123f

    /** Chroma magnitude of a fully-saturated 100% primary, used to normalise radius to ~[0,1]. */
    private const val MAX_CHROMA = 0.5f

    data class Point(val angleDeg: Float, val radius01: Float, val cb: Float, val cr: Float)

    enum class BarTarget { RED, YELLOW, GREEN, CYAN, BLUE, MAGENTA }

    /** Map an 8-bit RGB sample to its vectorscope point (BT.709 Cb/Cr, angle in [0,360)). */
    fun map(r8: Int, g8: Int, b8: Int): Point {
        val r = r8 / 255f
        val g = g8 / 255f
        val b = b8 / 255f
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val cb = (b - y) / 1.8556f
        val cr = (r - y) / 1.5748f
        var angle = Math.toDegrees(atan2(cr.toDouble(), cb.toDouble())).toFloat()
        if (angle < 0f) angle += 360f
        val radius = (sqrt(cb * cb + cr * cr) / MAX_CHROMA).coerceIn(0f, 1f)
        return Point(angle, radius, cb, cr)
    }

    /** The canonical 75% colour-bar target point for [bar] (amplitude 0.75 → code 191). */
    fun targetBox75(bar: BarTarget): Point {
        val c = (0.75f * 255f).toInt() // 191
        return when (bar) {
            BarTarget.RED -> map(c, 0, 0)
            BarTarget.YELLOW -> map(c, c, 0)
            BarTarget.GREEN -> map(0, c, 0)
            BarTarget.CYAN -> map(0, c, c)
            BarTarget.BLUE -> map(0, 0, c)
            BarTarget.MAGENTA -> map(c, 0, c)
        }
    }
}
