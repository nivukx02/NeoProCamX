package com.neoproc.neoproccamx.rendering

import kotlin.math.floor

/** Compact 3x3 RGGB calibration grid used by the live RAW monitor demosaic. */
internal object RawLensShadingGridPolicy {
    const val GRID_SIDE = 3
    const val CHANNEL_COUNT = 4
    const val FLOAT_COUNT = GRID_SIDE * GRID_SIDE * CHANNEL_COUNT

    fun identityGrid(): FloatArray = FloatArray(FLOAT_COUNT) { 1f }

    /**
     * Bilinearly resample the Camera2 lens-shading map to a fixed 3x3 grid. Nine vec4 samples are
     * sufficient for the smooth, low-frequency optical colour-shading field and keep the per-frame UBO
     * small. Channel order is Camera2 RGGB: R, G-even, G-odd, B.
     */
    fun resample(
        rows: Int,
        columns: Int,
        gainAt: (channel: Int, column: Int, row: Int) -> Float
    ): FloatArray? {
        if (rows <= 0 || columns <= 0) return null
        val output = FloatArray(FLOAT_COUNT)
        for (gridY in 0 until GRID_SIDE) {
            val sourceY = gridY.toFloat() * (rows - 1).toFloat() / (GRID_SIDE - 1).toFloat()
            val y0 = floor(sourceY).toInt().coerceIn(0, rows - 1)
            val y1 = (y0 + 1).coerceAtMost(rows - 1)
            val fy = sourceY - y0.toFloat()
            for (gridX in 0 until GRID_SIDE) {
                val sourceX = gridX.toFloat() * (columns - 1).toFloat() / (GRID_SIDE - 1).toFloat()
                val x0 = floor(sourceX).toInt().coerceIn(0, columns - 1)
                val x1 = (x0 + 1).coerceAtMost(columns - 1)
                val fx = sourceX - x0.toFloat()
                for (channel in 0 until CHANNEL_COUNT) {
                    val g00 = gainAt(channel, x0, y0)
                    val g10 = gainAt(channel, x1, y0)
                    val g01 = gainAt(channel, x0, y1)
                    val g11 = gainAt(channel, x1, y1)
                    if (!g00.isFinite() || !g10.isFinite() || !g01.isFinite() || !g11.isFinite()) {
                        return null
                    }
                    val top = g00 + (g10 - g00) * fx
                    val bottom = g01 + (g11 - g01) * fx
                    val gain = top + (bottom - top) * fy
                    output[((gridY * GRID_SIDE + gridX) * CHANNEL_COUNT) + channel] =
                        gain.coerceIn(0.25f, 8f)
                }
            }
        }
        return output
    }
}
