package com.neoproc.neoproccamx.pipeline

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.graphics.Rect
import android.util.Log

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * LAYER 1: DYNAMIC FOCUS INTELLIGENCE FIELD (DFIF)
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Coordinates the computation of the Cinematic Value Map V(x,y) in [0.0, 1.0].
 * V(x,y) represents the focus and visual saliency map across the frame, combining:
 * 1. Semantic Saliency (Face metadata)
 * 2. Focus Confidence (PDAF confidence metrics)
 * 3. Spatial High-Frequency Sharpness (local edge contrast)
 * 4. Temporal Optical Flow / Motion (OIS and motion vector gradients)
 *
 * This value map directs downstream rendering resources (Layers 2-9).
 */
class CinematicValueField(val gridWidth: Int = 16, val gridHeight: Int = 16) {

    companion object {
        private const val TAG = "CinematicValueField"
        
        // Saliency Weights
        private const val WEIGHT_FACE = 0.40f
        private const val WEIGHT_PDAF = 0.25f
        private const val WEIGHT_SHARPNESS = 0.20f
        private const val WEIGHT_MOTION = 0.15f
    }

    private val valueMap = FloatArray(gridWidth * gridHeight) { 0.5f }
    private val lock = Any()

    /**
     * Update the Cinematic Value Map based on frame capture metadata and scene analyses.
     */
    fun update(
        result: TotalCaptureResult?,
        sensorWidth: Int,
        sensorHeight: Int,
        localSharpness: FloatArray?, // Size matching gridWidth * gridHeight
        localMotion: FloatArray?      // Size matching gridWidth * gridHeight
    ): FloatArray {
        synchronized(lock) {
            val faceMap = FloatArray(gridWidth * gridHeight)
            val pdafMap = FloatArray(gridWidth * gridHeight)

            // 1. Semantic Saliency (Faces)
            result?.get(CaptureResult.STATISTICS_FACES)?.let { faces ->
                for (face in faces) {
                    val bounds = face.bounds
                    val confidence = face.score.toFloat() / 100f // Normalized to 0.0 - 1.0
                    if (confidence > 0.1f) {
                        applyBoundingBoxWeight(faceMap, bounds, sensorWidth, sensorHeight, confidence)
                    }
                }
            }

            // 2. Focus Confidence (PDAF / Focal regions)
            result?.let { r ->
                val focusState = r.get(CaptureResult.CONTROL_AF_STATE)
                val focusRegions = r.get(CaptureResult.CONTROL_AF_REGIONS)
                val afWeight = if (focusState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                    focusState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) 1.0f else 0.5f

                if (focusRegions != null && focusRegions.isNotEmpty()) {
                    for (region in focusRegions) {
                        val bounds = region.rect
                        val confidence = (region.meteringWeight.toFloat() / 1000f).coerceIn(0f, 1f) * afWeight
                        applyBoundingBoxWeight(pdafMap, bounds, sensorWidth, sensorHeight, confidence)
                    }
                } else {
                    // Fallback to center-weighted focus field
                    applyCenterWeight(pdafMap, afWeight)
                }
            }

            // 3. Fusion of all streams
            for (i in 0 until gridWidth * gridHeight) {
                val sFace = faceMap[i]
                val sPdaf = pdafMap[i]
                val sSharp = localSharpness?.getOrNull(i) ?: 0.5f
                val sMotion = localMotion?.getOrNull(i) ?: 0.0f

                // V(x,y) = w_f * S_face + w_p * S_pdaf + w_s * S_sharpness + w_t * S_motion
                valueMap[i] = (WEIGHT_FACE * sFace +
                    WEIGHT_PDAF * sPdaf +
                    WEIGHT_SHARPNESS * sSharp +
                    WEIGHT_MOTION * (1.0f - sMotion).coerceIn(0f, 1f) // lower motion = higher sharpness focus stability
                ).coerceIn(0.0f, 1.0f)
            }

            return valueMap.clone()
        }
    }

    private fun applyBoundingBoxWeight(
        map: FloatArray,
        bounds: Rect,
        sensorWidth: Int,
        sensorHeight: Int,
        weight: Float
    ) {
        if (sensorWidth <= 0 || sensorHeight <= 0) return
        
        // Normalize rect coordinates to grid space
        val gridLeft = ((bounds.left.toFloat() / sensorWidth) * gridWidth).toInt().coerceIn(0, gridWidth - 1)
        val gridRight = ((bounds.right.toFloat() / sensorWidth) * gridWidth).toInt().coerceIn(0, gridWidth - 1)
        val gridTop = ((bounds.top.toFloat() / sensorHeight) * gridHeight).toInt().coerceIn(0, gridHeight - 1)
        val gridBottom = ((bounds.bottom.toFloat() / sensorHeight) * gridHeight).toInt().coerceIn(0, gridHeight - 1)

        for (y in gridTop..gridBottom) {
            for (x in gridLeft..gridRight) {
                val idx = y * gridWidth + x
                map[idx] = map[idx].coerceAtLeast(weight)
            }
        }
    }

    private fun applyCenterWeight(map: FloatArray, weight: Float) {
        val centerX = gridWidth / 2f
        val centerY = gridHeight / 2f
        val maxDist = Math.hypot(centerX.toDouble(), centerY.toDouble()).toFloat()

        for (y in 0 until gridHeight) {
            for (x in 0 until gridWidth) {
                val dist = Math.hypot((x - centerX).toDouble(), (y - centerY).toDouble()).toFloat()
                val gaussian = Math.exp(-((dist * dist) / (2f * maxDist * maxDist)).toDouble()).toFloat()
                val idx = y * gridWidth + x
                map[idx] = gaussian * weight
            }
        }
    }

    fun getValueAt(x: Int, y: Int): Float {
        synchronized(lock) {
            val cx = x.coerceIn(0, gridWidth - 1)
            val cy = y.coerceIn(0, gridHeight - 1)
            return valueMap[cy * gridWidth + cx]
        }
    }

    fun getValues(): FloatArray {
        synchronized(lock) {
            return valueMap.clone()
        }
    }
}
