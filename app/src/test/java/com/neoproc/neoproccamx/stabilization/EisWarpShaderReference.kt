package com.neoproc.neoproccamx.stabilization

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * CP-QV19 — host-side Kotlin port of the dormant `eis_warp.comp` GPU per-scanline EIS warp shader
 * (`app/src/main/cpp/shaders/vulkan/eis_warp.comp`). TESTS ONLY: it exists solely so
 * [EisWarpShaderGoldenTest] can foundation-lock the shader's EXACT resample math (identity passthrough,
 * inverse warp coordinate, never-reveal-border clamp, bilinear fetch) before device-side activation —
 * pairing with the CP-QV18 CPU-grid goldens in [EisPipelineFoundationGoldenTest] so the whole gyro-EIS
 * chain (CPU grid producer -> GPU per-scanline warp) is locked end to end.
 *
 * Faithful to the shader's ACTUAL math (transcribed line-by-line from eis_warp.comp):
 *
 *  • USE_EIS_WARP == false: `imageStore(uOutput, p, imageLoad(uInput, p))` — bit-for-bit passthrough.
 *
 *  • USE_EIS_WARP == true, per output pixel p=(x,y):
 *      row     = clamp(y, 0, max(gridRows-1, 0))
 *      corr    = grid[row] = (dx, dy, roll)                                       // uRowGrid.rgb
 *      cr, sr  = cos(roll), sin(roll)
 *      d       = p - center                                                       // center = (centerX, centerY)
 *      rotated = (cr*d.x - sr*d.y, sr*d.x + cr*d.y) + center                       // in-plane rotation about center
 *      src     = rotated - (dx, dy)                                               // inverse (counter) translate
 *      m       = max(cropMarginPixels, 0)
 *      lo      = (m, m)
 *      hi      = max(size - 1 - m, lo)                                            // per-axis, componentwise max
 *      src     = clamp(src, lo, hi)                                               // never-reveal-border
 *      out[p]  = bilinear(uInput, src)
 *
 *  • bilinear(): the fetch coordinate is ADDITIONALLY clamped to [0, size-1] inside sampleInputBilinear,
 *    then a standard 4-tap mix with the +1 neighbours clamped to size-1.
 *
 * A uniform translation grid (dx, dy, roll=0) therefore makes the output sample the source shifted by
 * +(dx, dy): out[p] == source[p - (dx, dy)], i.e. the scene content moves by +(dx, dy) on screen.
 */
object EisWarpShaderReference {

    /** One packed grid row `(dx, dy, roll, alpha=1)` — matches [EisWarpGrid.packGridRgba]. */
    data class Sample(val x: Float, val y: Float)

    /**
     * The shader's inverse-warp source coordinate for output pixel (px, py), BEFORE bilinear fetch but
     * AFTER the crop-margin clamp — i.e. the exact `src` the shader passes to sampleInputBilinear.
     * [packedGrid] is the [EisWarpGrid.packGridRgba] buffer (rgba, one row per scanline).
     */
    fun sourceCoord(
        px: Int,
        py: Int,
        width: Int,
        height: Int,
        packedGrid: FloatArray,
        gridRows: Int,
        cropMarginPixels: Float,
        centerX: Float,
        centerY: Float,
    ): Sample {
        // int row = clamp(p.y, 0, max(gridRows - 1, 0));
        val maxRow = maxOf(gridRows - 1, 0)
        val row = py.coerceIn(0, maxRow)
        val dx = packedGrid[row * 4]
        val dy = packedGrid[row * 4 + 1]
        val roll = packedGrid[row * 4 + 2]

        val cr = cos(roll.toDouble()).toFloat()
        val sr = sin(roll.toDouble()).toFloat()
        val dX = px - centerX
        val dY = py - centerY
        val rotX = cr * dX - sr * dY + centerX
        val rotY = sr * dX + cr * dY + centerY
        var srcX = rotX - dx
        var srcY = rotY - dy

        // Crop-margin clamp: src can never leave [m, size-1-m] (or [m, m] if the margin swallows the frame).
        val m = maxOf(cropMarginPixels, 0f)
        val loX = m
        val loY = m
        val hiX = maxOf(width - 1f - m, loX)
        val hiY = maxOf(height - 1f - m, loY)
        srcX = srcX.coerceIn(loX, hiX)
        srcY = srcY.coerceIn(loY, hiY)
        return Sample(srcX, srcY)
    }

    /** Port of `sampleInputBilinear`: clamp to [0, size-1], 4-tap bilinear with +1 neighbours clamped. */
    fun sampleInputBilinear(input: FloatArray, width: Int, height: Int, cx: Float, cy: Float): Float {
        val clX = cx.coerceIn(0f, width - 1f)
        val clY = cy.coerceIn(0f, height - 1f)
        val bx = floor(clX).toInt()
        val by = floor(clY).toInt()
        val fx = clX - bx
        val fy = clY - by
        val bx1 = minOf(bx + 1, width - 1)
        val by1 = minOf(by + 1, height - 1)
        val a = input[by * width + bx]
        val b = input[by * width + bx1]
        val c = input[by1 * width + bx]
        val d = input[by1 * width + bx1]
        val top = a + (b - a) * fx
        val bot = c + (d - c) * fx
        return top + (bot - top) * fy
    }

    /**
     * Full single-channel port of eis_warp.comp's `main()` for every output pixel.
     *
     * With [useEisWarp] == false this is the identity passthrough (`out[p] = in[p]`), byte-identical to
     * the input. With it true it resamples through [packedGrid] using [sourceCoord] + [sampleInputBilinear].
     */
    fun warp(
        input: FloatArray,
        width: Int,
        height: Int,
        useEisWarp: Boolean,
        packedGrid: FloatArray,
        gridRows: Int,
        cropMarginPixels: Float,
        centerX: Float,
        centerY: Float,
    ): FloatArray {
        val out = FloatArray(width * height)
        for (py in 0 until height) {
            for (px in 0 until width) {
                val idx = py * width + px
                if (!useEisWarp) {
                    out[idx] = input[idx]
                    continue
                }
                val src = sourceCoord(px, py, width, height, packedGrid, gridRows, cropMarginPixels, centerX, centerY)
                out[idx] = sampleInputBilinear(input, width, height, src.x, src.y)
            }
        }
        return out
    }
}
