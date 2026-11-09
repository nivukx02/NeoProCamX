package com.neoproc.neoproccamx.camera

import android.hardware.camera2.params.TonemapCurve
import com.neoproc.neoproccamx.ai.NLogMath
import com.neoproc.neoproccamx.ai.NLogVariant

/**
 * TonemapCurveFactory — cinema-oriented tonemap curve builder.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * EXTRACTED FROM CameraController — Phase 2 God-Class Decomposition
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Produces TonemapCurve objects for LOG, HDR, and RAW preview/recording looks.
 * All methods are pure functions with no mutable state — ideal for unit testing
 * and reuse across pipeline stages.
 *
 * Thread safety: Stateless. All methods are safe to call from any thread.
 */
object TonemapCurveFactory {
    private val N_LOG_CURVE_INPUTS = floatArrayOf(
        0f,
        0.001f,
        0.006f,
        0.018f,
        NLogMath.SCENE_GRAY_18,
        0.32f,
        0.50f,
        0.72f,
        0.92f,
        1f
    )

    // ── Preview Curves ───────────────────────────────────────────────────────

    /**
     * Phase 1b: Linear 1:1 tonemap curve (ISP Bypass)
     * No tone mapping at all — input directly maps to output (gamma 1.0)
     * Used for RAW/LOG workflows to preserve bit-depth and maximize dynamic range
     */
    fun buildLinearBypassCurve(maxCurvePoints: Int): TonemapCurve {
        val baseCurve = floatArrayOf(
            0f,    0f,       // Linear: input=output
            1f,    1f        // Perfect 1:1 mapping
        )
        return buildCurve(baseCurve, maxCurvePoints)
    }

    /**
     * Filmic Log curve with deep shadow lift, smooth midtones,
     * and extended highlight rolloff for world-class color science.
     */
    fun buildPreviewLogCurve(maxCurvePoints: Int): TonemapCurve {
        return buildCurve(previewLogCurvePoints(), maxCurvePoints)
    }

    /**
     * High-contrast HDR curve with deep blacks and extended
     * highlight protection for maximum dynamic range display.
     */
    fun buildPreviewHdrCurve(maxCurvePoints: Int): TonemapCurve {
        val baseCurve = floatArrayOf(
            0f,    0.015f,
            0.04f, 0.200f,
            0.12f, 0.400f,
            0.24f, 0.580f,
            0.40f, 0.740f,
            0.58f, 0.850f,
            0.74f, 0.920f,
            0.88f, 0.965f,
            0.98f, 0.990f,
            1f,    1.000f
        )
        return buildCurve(baseCurve, maxCurvePoints)
    }

    /**
     * Maximum dynamic range RAW curve with extended shadow detail and
     * smooth highlight rolloff to preserve all 12-bit sensor latitude.
     */
    fun buildPreviewRawCurve(maxCurvePoints: Int): TonemapCurve {
        val baseCurve = floatArrayOf(
            0f,    0.075f,
            0.03f, 0.300f,
            0.10f, 0.490f,
            0.22f, 0.660f,
            0.38f, 0.800f,
            0.54f, 0.895f,
            0.70f, 0.955f,
            0.84f, 0.985f,
            0.95f, 0.998f,
            1f,    1.000f
        )
        return buildCurve(baseCurve, maxCurvePoints)
    }

    // ── Recording Curves ─────────────────────────────────────────────────────

    fun buildRecordingLogCurve(maxCurvePoints: Int): TonemapCurve {
        return buildCurve(recordingLogCurvePoints(), maxCurvePoints)
    }

    fun buildRecordingHdrCurve(maxCurvePoints: Int): TonemapCurve {
        val baseCurve = floatArrayOf(
            0f, 0.01f,
            0.05f, 0.11f,
            0.14f, 0.25f,
            0.28f, 0.43f,
            0.46f, 0.60f,
            0.68f, 0.75f,
            0.88f, 0.88f,
            1f, 0.95f
        )
        return buildCurve(baseCurve, maxCurvePoints)
    }

    fun buildRecordingRawCurve(maxCurvePoints: Int): TonemapCurve {
        val baseCurve = floatArrayOf(
            0f, 0.015f,
            0.03f, 0.12f,
            0.10f, 0.26f,
            0.22f, 0.42f,
            0.38f, 0.58f,
            0.56f, 0.72f,
            0.76f, 0.84f,
            0.93f, 0.93f,
            1f, 0.97f
        )
        return buildCurve(baseCurve, maxCurvePoints)
    }

    // ── Resampling Engine ────────────────────────────────────────────────────

    private fun buildCurve(baseCurve: FloatArray, maxCurvePoints: Int): TonemapCurve {
        val availablePoints = maxCurvePoints.coerceAtLeast(2)
        val sourcePoints = baseCurve.size / 2
        val curve = if (availablePoints >= sourcePoints) {
            baseCurve
        } else {
            resample(baseCurve, availablePoints)
        }
        return TonemapCurve(curve, curve, curve)
    }

    internal fun previewLogCurvePoints(variant: NLogVariant = NLogVariant.BASE): FloatArray =
        nLogCurvePoints(variant)

    internal fun recordingLogCurvePoints(variant: NLogVariant = NLogVariant.BASE): FloatArray =
        nLogCurvePoints(variant)

    private fun nLogCurvePoints(variant: NLogVariant): FloatArray {
        val output = FloatArray(N_LOG_CURVE_INPUTS.size * 2)
        N_LOG_CURVE_INPUTS.forEachIndexed { index, linear ->
            output[index * 2] = linear
            output[index * 2 + 1] = NLogMath.encode(linear, variant)
        }
        return output
    }

    /**
     * Resample a tonemap curve defined as [x0, y0, x1, y1, ...] pairs
     * to the target [points] count using linear interpolation.
     */
    fun resample(source: FloatArray, points: Int): FloatArray {
        val pointCount = points.coerceAtLeast(2)
        val output = FloatArray(pointCount * 2)
        for (index in 0 until pointCount) {
            val x = index.toFloat() / (pointCount - 1).toFloat()
            output[index * 2] = x
            output[index * 2 + 1] = interpolateY(source, x)
        }
        return output
    }

    /**
     * Linear interpolation of Y value for a given X from a sorted
     * [x0, y0, x1, y1, ...] control-point array.
     */
    fun interpolateY(source: FloatArray, x: Float): Float {
        val pointCount = source.size / 2
        if (pointCount < 2) return x
        if (x <= source[0]) return source[1]
        val lastX = source[(pointCount - 1) * 2]
        if (x >= lastX) return source[(pointCount - 1) * 2 + 1]
        for (index in 0 until pointCount - 1) {
            val x0 = source[index * 2]
            val y0 = source[index * 2 + 1]
            val x1 = source[(index + 1) * 2]
            val y1 = source[(index + 1) * 2 + 1]
            if (x <= x1) {
                val span = (x1 - x0).coerceAtLeast(1e-6f)
                val t = ((x - x0) / span).coerceIn(0f, 1f)
                return y0 + (y1 - y0) * t
            }
        }
        return source[(pointCount - 1) * 2 + 1]
    }
}
