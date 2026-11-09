package com.neoproc.neoproccamx.rendering

import kotlin.math.abs

/**
 * s1-f07 / CP10c — host-side faithful reference of demosaic_vulkan.comp (the RAW-preview cinema
 * debayer the native bridge dispatches). It ports the shader math LINE-FOR-LINE so [RawDemosaicGoldenTest]
 * can lock the algorithm on the JVM WITHOUT a device:
 *   - WB-before-demosaic + per-CFA black subtraction (CP14 ordering, inside [calibratedRaw]),
 *   - hard-gradient edge-directed green (CP14; Hamilton-Adams-style `h<v ? gH : v<h ? gV : gA`),
 *   - sensor→XYZ→ACEScg via the CP11 forward matrices.
 *
 * The native default clears the dark-current map (binding 2) and hot-pixel mask (binding 3) to zero, so
 * this reference models them as zero (no defect contribution, no thermal scaling) — matching what the
 * gated dispatch actually reads. The mosaic is a single-channel u16 plane, row-major.
 */
object RawDemosaicReference {

    class Mosaic(val width: Int, val height: Int, val raw: IntArray) {
        init { require(raw.size == width * height) { "mosaic size mismatch" } }
    }

    /** Mirrors the std140 `RawDemosaicUniforms` inputs. Matrices are row-major 3×3. */
    class Params(
        val bayerPattern: Int,           // 0=RGGB 1=GRBG 2=GBRG 3=BGGR
        val whiteLevel: Int,
        val blackLevelRggb: FloatArray,  // 4: R, Gr, Gb, B
        val whiteBalanceRggb: FloatArray, // 4: R, Gr, Gb, B
        val mSensorToXyz: FloatArray,    // row-major 3×3
        val mXyzToAcescg: FloatArray     // row-major 3×3
    ) {
        init {
            require(blackLevelRggb.size == 4)
            require(whiteBalanceRggb.size == 4)
            require(mSensorToXyz.size == 9)
            require(mXyzToAcescg.size == 9)
        }
    }

    // ─────────────────────────── shader: bayerChannel ───────────────────────────
    // Returns 0=Red, 1=Green-on-red-row (Gr), 2=Green-on-blue-row (Gb), 3=Blue.
    fun bayerChannel(x: Int, y: Int, pattern: Int): Int {
        val px = x and 1
        val py = y and 1
        val idx = py * 2 + px
        return when (pattern) {
            0 -> idx // RGGB
            1 -> if (idx == 0) 1 else if (idx == 1) 0 else if (idx == 2) 3 else 2 // GRBG
            2 -> if (idx == 0) 2 else if (idx == 1) 3 else if (idx == 2) 0 else 1 // GBRG
            else -> if (idx == 0) 3 else if (idx == 1) 2 else if (idx == 2) 1 else 0 // BGGR
        }
    }

    private fun clampCoord(v: Int, max: Int): Int = v.coerceIn(0, max - 1)

    private fun loadRaw(m: Mosaic, x: Int, y: Int): Float {
        val cx = clampCoord(x, m.width)
        val cy = clampCoord(y, m.height)
        return m.raw[cy * m.width + cx].toFloat()
    }

    private fun blackForChannel(p: Params, channel: Int): Float = p.blackLevelRggb[channel]
    private fun wbForChannel(p: Params, channel: Int): Float = p.whiteBalanceRggb[channel]

    // ─────────────────────────── shader: calibratedRaw ───────────────────────────
    // raw - black (- darkCurrent[0]); hot-pixel mask is zero so no neighbour replacement; normalize by
    // (whiteLevel - black) clamped to [0,1], THEN apply per-CFA WB (WB-before-demosaic — CP14).
    // The resulting WB-amplified highlight headroom is intentionally not upper-clamped by demosaicRgb.
    fun calibratedRaw(m: Mosaic, p: Params, x: Int, y: Int): Float {
        val cx = clampCoord(x, m.width)
        val cy = clampCoord(y, m.height)
        val channel = bayerChannel(cx, cy, p.bayerPattern)
        val raw = loadRaw(m, cx, cy)
        val black = blackForChannel(p, channel)
        val value = raw - black // darkCurrent = 0 (cleared map)
        val denom = maxOf(p.whiteLevel.toFloat() - black, 1.0f)
        return (value / denom).coerceIn(0.0f, 1.0f) * wbForChannel(p, channel)
    }

    fun horizontalGradient(m: Mosaic, p: Params, x: Int, y: Int, center: Float): Float {
        val rM2 = calibratedRaw(m, p, x - 2, y)
        val rP2 = calibratedRaw(m, p, x + 2, y)
        val gM1 = calibratedRaw(m, p, x - 1, y)
        val gP1 = calibratedRaw(m, p, x + 1, y)
        return abs((rM2 + 2.0f * center + rP2) - (2.0f * gM1 + 2.0f * gP1))
    }

    fun verticalGradient(m: Mosaic, p: Params, x: Int, y: Int, center: Float): Float {
        val rM2 = calibratedRaw(m, p, x, y - 2)
        val rP2 = calibratedRaw(m, p, x, y + 2)
        val gM1 = calibratedRaw(m, p, x, y - 1)
        val gP1 = calibratedRaw(m, p, x, y + 1)
        return abs((rM2 + 2.0f * center + rP2) - (2.0f * gM1 + 2.0f * gP1))
    }

    // ─────────────────────────── shader: reconstructGreen ───────────────────────────
    fun reconstructGreen(m: Mosaic, p: Params, x: Int, y: Int, center: Float): Float {
        val h = horizontalGradient(m, p, x, y, center)
        val v = verticalGradient(m, p, x, y, center)
        val gH = 0.5f * (calibratedRaw(m, p, x - 1, y) + calibratedRaw(m, p, x + 1, y)) +
            0.25f * (2.0f * center - calibratedRaw(m, p, x - 2, y) - calibratedRaw(m, p, x + 2, y))
        val gV = 0.5f * (calibratedRaw(m, p, x, y - 1) + calibratedRaw(m, p, x, y + 1)) +
            0.25f * (2.0f * center - calibratedRaw(m, p, x, y - 2) - calibratedRaw(m, p, x, y + 2))
        val gA = 0.25f * (
            calibratedRaw(m, p, x - 1, y) + calibratedRaw(m, p, x + 1, y) +
                calibratedRaw(m, p, x, y - 1) + calibratedRaw(m, p, x, y + 1)
            ) + 0.125f * (
            4.0f * center -
                calibratedRaw(m, p, x - 2, y) - calibratedRaw(m, p, x + 2, y) -
                calibratedRaw(m, p, x, y - 2) - calibratedRaw(m, p, x, y + 2)
            )
        return if (h < v) gH else if (v < h) gV else gA
    }

    /** Edge-directed reconstructed green per pixel (sampled value at green sites). */
    fun reconstructGreenField(m: Mosaic, p: Params): FloatArray {
        val out = FloatArray(m.width * m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            val channel = bayerChannel(x, y, p.bayerPattern)
            val center = calibratedRaw(m, p, x, y)
            out[y * m.width + x] = if (channel == 1 || channel == 2) center else reconstructGreen(m, p, x, y, center)
        }
        return out
    }

    /** Non-directional bilinear green per pixel — the baseline the edge-directed shader must beat. */
    fun bilinearGreenField(m: Mosaic, p: Params): FloatArray {
        val out = FloatArray(m.width * m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            val channel = bayerChannel(x, y, p.bayerPattern)
            val center = calibratedRaw(m, p, x, y)
            out[y * m.width + x] = if (channel == 1 || channel == 2) {
                center
            } else {
                0.25f * (
                    calibratedRaw(m, p, x - 1, y) + calibratedRaw(m, p, x + 1, y) +
                        calibratedRaw(m, p, x, y - 1) + calibratedRaw(m, p, x, y + 1)
                    )
            }
        }
        return out
    }

    // ─────────────────────────── shader: demosaicRgb ───────────────────────────
    private fun demosaicRgb(m: Mosaic, p: Params, x: Int, y: Int): FloatArray {
        val channel = bayerChannel(x, y, p.bayerPattern)
        val center = calibratedRaw(m, p, x, y)
        val green = if (channel == 1 || channel == 2) center else reconstructGreen(m, p, x, y, center)
        var red = green
        var blue = green
        when (channel) {
            0 -> { // Red site
                red = center
                blue = 0.25f * (
                    calibratedRaw(m, p, x - 1, y - 1) + calibratedRaw(m, p, x + 1, y - 1) +
                        calibratedRaw(m, p, x - 1, y + 1) + calibratedRaw(m, p, x + 1, y + 1)
                    ) + (center - green) * 0.5f
            }
            3 -> { // Blue site
                blue = center
                red = 0.25f * (
                    calibratedRaw(m, p, x - 1, y - 1) + calibratedRaw(m, p, x + 1, y - 1) +
                        calibratedRaw(m, p, x - 1, y + 1) + calibratedRaw(m, p, x + 1, y + 1)
                    ) + (center - green) * 0.5f
            }
            else -> { // Green site
                val greenOnRedRow = channel == 1
                red = if (greenOnRedRow) {
                    0.5f * (calibratedRaw(m, p, x - 1, y) + calibratedRaw(m, p, x + 1, y))
                } else {
                    0.5f * (calibratedRaw(m, p, x, y - 1) + calibratedRaw(m, p, x, y + 1))
                }
                blue = if (greenOnRedRow) {
                    0.5f * (calibratedRaw(m, p, x, y - 1) + calibratedRaw(m, p, x, y + 1))
                } else {
                    0.5f * (calibratedRaw(m, p, x - 1, y) + calibratedRaw(m, p, x + 1, y))
                }
            }
        }
        return floatArrayOf(
            maxOf(red, 0.0f),
            maxOf(green, 0.0f),
            maxOf(blue, 0.0f)
        )
    }

    /** Sensor-RGB demosaic before the color matrices (w*h*3, row-major RGB). */
    fun demosaicSensorRgb(m: Mosaic, p: Params): FloatArray {
        val out = FloatArray(m.width * m.height * 3)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            val rgb = demosaicRgb(m, p, x, y)
            val o = (y * m.width + x) * 3
            out[o] = rgb[0]; out[o + 1] = rgb[1]; out[o + 2] = rgb[2]
        }
        return out
    }

    private fun mul3(mtx: FloatArray, v0: Float, v1: Float, v2: Float): FloatArray = floatArrayOf(
        mtx[0] * v0 + mtx[1] * v1 + mtx[2] * v2,
        mtx[3] * v0 + mtx[4] * v1 + mtx[5] * v2,
        mtx[6] * v0 + mtx[7] * v1 + mtx[8] * v2
    )

    /** Full demosaic → ACEScg (w*h*3, row-major RGB; max(acescg, 0) like the shader). */
    fun demosaicAcesCg(m: Mosaic, p: Params): FloatArray {
        val out = FloatArray(m.width * m.height * 3)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            val rgb = demosaicRgb(m, p, x, y)
            val xyz = mul3(p.mSensorToXyz, rgb[0], rgb[1], rgb[2])
            val aces = mul3(p.mXyzToAcescg, xyz[0], xyz[1], xyz[2])
            val o = (y * m.width + x) * 3
            out[o] = maxOf(aces[0], 0.0f)
            out[o + 1] = maxOf(aces[1], 0.0f)
            out[o + 2] = maxOf(aces[2], 0.0f)
        }
        return out
    }
}
