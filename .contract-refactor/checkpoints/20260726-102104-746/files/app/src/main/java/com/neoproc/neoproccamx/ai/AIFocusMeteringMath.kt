package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.*

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * PROFESSIONAL FOCUS & METERING - cinema-oriented ALGORITHMS (UPGRADED)
 * ════════════════════════════════════════════════════════════════════════════════
 * 
 * Advanced metering and focus algorithms:
 * - Professional metering modes (matrix, center-weighted, spot)
 * - Depth-aware exposure calculation
 * - HDR scene analysis
 * - Advanced focus confidence metrics
 */

internal data class AIFloatRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun centerX(): Float = (left + right) * 0.5f
    fun centerY(): Float = (top + bottom) * 0.5f
    fun width(): Float = right - left
    fun height(): Float = bottom - top
    fun area(): Float = width() * height()

    fun expanded(padding: Float): AIFloatRect =
        AIFloatRect(
            left = (left - padding).coerceIn(0f, 1f),
            top = (top - padding).coerceIn(0f, 1f),
            right = (right + padding).coerceIn(0f, 1f),
            bottom = (bottom + padding).coerceIn(0f, 1f)
        )

    fun normalizedSafe(): AIFloatRect =
        AIFloatRect(
            left = left.coerceIn(0f, 1f),
            top = top.coerceIn(0f, 1f),
            right = right.coerceIn(0f, 1f),
            bottom = bottom.coerceIn(0f, 1f)
        )

    fun intersected(other: AIFloatRect): AIFloatRect {
        val outLeft = maxOf(left, other.left)
        val outTop = maxOf(top, other.top)
        val outRight = minOf(right, other.right)
        val outBottom = minOf(bottom, other.bottom)
        return if (outRight <= outLeft || outBottom <= outTop) this else AIFloatRect(
            left = outLeft,
            top = outTop,
            right = outRight,
            bottom = outBottom
        )
    }
}

internal data class AIMeteringRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun width(): Int = right - left
    fun height(): Int = bottom - top
    fun area(): Int = width() * height()
}

/**
 * Metering modes for exposure calculation
 */
enum class MeteringMode {
    /** Evaluative/Matrix metering (entire frame) */
    MATRIX,
    /** Center-weighted metering */
    CENTER_WEIGHTED,
    /** Spot metering (small area) */
    SPOT,
    /** Partial metering (larger than spot) */
    PARTIAL,
    /** Subject-aware metering */
    SUBJECT_AWARE
}

/**
 * HDR scene analysis result
 */
internal data class HdrSceneAnalysis(
    val dynamicRangeStops: Float,
    val highlightClipping: Float,
    val shadowClipping: Float,
    val contrastRatio: Float,
    val hdrRecommended: Boolean,
    val exposureCompensation: Float
)

/**
 * Focus confidence metrics
 */
internal data class FocusConfidence(
    val sharpness: Float,
    val contrast: Float,
    val depthConfidence: Float,
    val subjectConfidence: Float,
    val overallConfidence: Float
)

internal object AIFocusMeteringMath {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
    
    // ════════════════════════════════════════════════════════════════════════════
    // SMOOTHING
    // ════════════════════════════════════════════════════════════════════════════
    
    fun smoothingAlpha(subjectType: SubjectType, confidence: Float = 1f): Float {
        val base = when (subjectType) {
            SubjectType.EYE -> 0.76f
            SubjectType.FACE -> 0.64f
            SubjectType.HEAD -> 0.60f
            SubjectType.BODY -> 0.46f
            SubjectType.OBJECT -> 0.50f
            SubjectType.GROUP -> 0.38f
            SubjectType.FULL_BODY -> 0.44f
            SubjectType.UPPER_BODY -> 0.52f
        }
        val confidenceScale = 0.62f + confidence.coerceIn(0f, 1f) * 0.38f
        return base * confidenceScale
    }

    fun smoothBounds(
        current: AIFloatRect?,
        target: AIFloatRect,
        subjectType: SubjectType,
        confidence: Float = 1f
    ): AIFloatRect {
        val normalizedTarget = target.normalizedSafe()
        if (current == null) return normalizedTarget
        val alpha = smoothingAlpha(subjectType, confidence)
        return AIFloatRect(
            left = lerp(current.left, normalizedTarget.left, alpha),
            top = lerp(current.top, normalizedTarget.top, alpha),
            right = lerp(current.right, normalizedTarget.right, alpha),
            bottom = lerp(current.bottom, normalizedTarget.bottom, alpha)
        ).normalizedSafe()
    }

    fun smoothFocusPoint(
        current: Pair<Float, Float>?,
        targetX: Float,
        targetY: Float,
        subjectType: SubjectType,
        confidence: Float = 1f
    ): Pair<Float, Float> {
        val normalizedTargetX = targetX.coerceIn(0f, 1f)
        val normalizedTargetY = targetY.coerceIn(0f, 1f)
        if (current == null) return normalizedTargetX to normalizedTargetY

        val alpha = smoothingAlpha(subjectType, confidence)
        return lerp(current.first, normalizedTargetX, alpha).coerceIn(0f, 1f) to
            lerp(current.second, normalizedTargetY, alpha).coerceIn(0f, 1f)
    }

    fun movementMagnitude(previous: AIFloatRect?, current: AIFloatRect): Float {
        previous ?: return 1f
        val dx = current.centerX() - previous.centerX()
        val dy = current.centerY() - previous.centerY()
        val previousArea = previous.area()
        val currentArea = current.area()
        val sizeDelta = abs(currentArea - previousArea)
        return sqrt((dx * dx) + (dy * dy)) + sizeDelta * 0.35f
    }

    // ════════════════════════════════════════════════════════════════════════════
    // PROFESSIONAL METERING ALGORITHMS
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Matrix/Evaluative metering - analyze entire frame with zone weighting
     */
    fun calculateMatrixMetering(
        depthData: DepthData?,
        segmentationData: SegmentationData?,
        zones: Int = 8
    ): AIFloatRect {
        val effectiveZones = if (strictHighEndEnabled()) {
            zones.coerceIn(10, 16)
        } else {
            zones.coerceAtLeast(1)
        }
        // Divide frame into zones and weight each zone
        var weightedX = 0f
        var weightedY = 0f
        var totalWeight = 0f
        
        val step = 1f / effectiveZones.toFloat()
        for (iy in 0 until effectiveZones) {
            val cy = (iy + 0.5f) * step
            for (ix in 0 until effectiveZones) {
                val cx = (ix + 0.5f) * step
                
                // Calculate zone weight based on:
                // 1. Distance from center (gaussian)
                val dx = cx - 0.5f
                val dy = cy - 0.5f
                val distSq = dx * dx + dy * dy
                val centerWeight = exp(-distSq / 0.18f)
                
                // 2. Depth (closer = higher weight)
                val depthWeight = if (depthData != null) {
                    1f - depthData.sampleNormalizedDepth(cx, cy) * 0.5f
                } else {
                    1f
                }
                
                // 3. Segmentation (foreground = higher weight)
                val segWeight = if (segmentationData != null) {
                    0.5f + segmentationData.sampleForegroundConfidence(cx, cy) * 0.5f
                } else {
                    1f
                }
                
                val zoneWeight = centerWeight * depthWeight * segWeight
                weightedX += cx * zoneWeight
                weightedY += cy * zoneWeight
                totalWeight += zoneWeight
            }
        }
        
        if (totalWeight > 0f) {
            weightedX /= totalWeight
            weightedY /= totalWeight
        } else {
            weightedX = 0.5f
            weightedY = 0.5f
        }
        
        // Return region around weighted center
        val size = 0.4f
        val half = size / 2f
        return AIFloatRect(
            left = (weightedX - half).coerceIn(0f, 1f),
            top = (weightedY - half).coerceIn(0f, 1f),
            right = (weightedX + half).coerceIn(0f, 1f),
            bottom = (weightedY + half).coerceIn(0f, 1f)
        )
    }

    /**
     * Center-weighted metering - emphasize center, fade to edges
     */
    fun calculateCenterWeightedMetering(
        centerX: Float = 0.5f,
        centerY: Float = 0.5f,
        innerRadius: Float = 0.25f,
        outerRadius: Float = 0.45f
    ): AIFloatRect {
        // Center-weighted uses a larger area than spot but prioritizes center
        val size = if (strictHighEndEnabled()) {
            max(outerRadius, innerRadius + 0.24f).coerceAtMost(0.50f)
        } else {
            outerRadius
        }
        return AIFloatRect(
            left = (centerX - size).coerceIn(0f, 1f),
            top = (centerY - size).coerceIn(0f, 1f),
            right = (centerX + size).coerceIn(0f, 1f),
            bottom = (centerY + size).coerceIn(0f, 1f)
        )
    }

    /**
     * Spot metering - small precise area
     */
    fun calculateSpotMetering(
        spotX: Float,
        spotY: Float,
        spotSize: Float = 0.08f
    ): AIFloatRect {
        val half = spotSize / 2f
        return AIFloatRect(
            left = (spotX - half).coerceIn(0f, 1f),
            top = (spotY - half).coerceIn(0f, 1f),
            right = (spotX + half).coerceIn(0f, 1f),
            bottom = (spotY + half).coerceIn(0f, 1f)
        )
    }

    // ════════════════════════════════════════════════════════════════════════════
    // DEPTH-AWARE EXPOSURE
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Calculate exposure region with depth awareness
     * Prioritizes properly exposing subjects at similar depth
     */
    fun calculateDepthAwareExposure(
        subjectBounds: RectF,
        depthData: DepthData?,
        depthTolerance: Float = 0.15f
    ): RectF {
        if (depthData == null) {
            return RectF(
                (subjectBounds.left - 0.12f).coerceIn(0f, 1f),
                (subjectBounds.top - 0.12f).coerceIn(0f, 1f),
                (subjectBounds.right + 0.12f).coerceIn(0f, 1f),
                (subjectBounds.bottom + 0.12f).coerceIn(0f, 1f)
            )
        }
        
        // Get subject's depth
        val subjectDepth = depthData.averageDepth(subjectBounds, grid = 5)
        val subjectDepthConfidence = depthData.sampleDepthConfidence(
            subjectBounds.centerX(),
            subjectBounds.centerY()
        )
        val confidenceWeightedTolerance = (
            depthTolerance + (1f - subjectDepthConfidence) * 0.12f
            ).coerceIn(0.08f, 0.30f)
        
        // Expand region but only include areas at similar depth
        val expansion = 0.20f
        val expanded = RectF(
            (subjectBounds.left - expansion).coerceIn(0f, 1f),
            (subjectBounds.top - expansion).coerceIn(0f, 1f),
            (subjectBounds.right + expansion).coerceIn(0f, 1f),
            (subjectBounds.bottom + expansion).coerceIn(0f, 1f)
        )
        
        // Sample depth at edges to verify consistency
        val topDepth = depthData.sampleNormalizedDepth(expanded.centerX(), expanded.top)
        val bottomDepth = depthData.sampleNormalizedDepth(expanded.centerX(), expanded.bottom)
        val leftDepth = depthData.sampleNormalizedDepth(expanded.left, expanded.centerY())
        val rightDepth = depthData.sampleNormalizedDepth(expanded.right, expanded.centerY())
        
        // Contract region adaptively when surrounding depth becomes inconsistent.
        var contractionScore = 0f
        fun accumulateContraction(sampleDepth: Float, x: Float, y: Float) {
            val diff = abs(sampleDepth - subjectDepth)
            val confidence = depthData.sampleDepthConfidence(x, y)
            val normalizedDiff = (diff / confidenceWeightedTolerance).coerceAtLeast(0f)
            val confidenceWeightedPenalty = ((normalizedDiff - 1f).coerceAtLeast(0f)) *
                (0.45f + confidence * 0.55f)
            contractionScore += confidenceWeightedPenalty.coerceIn(0f, 1.2f)
        }

        accumulateContraction(topDepth, expanded.centerX(), expanded.top)
        accumulateContraction(bottomDepth, expanded.centerX(), expanded.bottom)
        accumulateContraction(leftDepth, expanded.left, expanded.centerY())
        accumulateContraction(rightDepth, expanded.right, expanded.centerY())
        val contraction = (1f - contractionScore * 0.15f).coerceIn(0.55f, 1f)
        val finalExpansion = expansion * contraction
        
        return RectF(
            (subjectBounds.left - finalExpansion).coerceIn(0f, 1f),
            (subjectBounds.top - finalExpansion).coerceIn(0f, 1f),
            (subjectBounds.right + finalExpansion).coerceIn(0f, 1f),
            (subjectBounds.bottom + finalExpansion).coerceIn(0f, 1f)
        )
    }

    // ════════════════════════════════════════════════════════════════════════════
    // HDR SCENE ANALYSIS
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Analyze scene for HDR characteristics
     * Estimates dynamic range and provides recommendations
     */
    fun analyzeHdrScene(
        depthData: DepthData?,
        segmentationData: SegmentationData?,
        sceneType: SceneType
    ): HdrSceneAnalysis {
        // Estimate dynamic range based on scene type
        val baseDynamicRange = when (sceneType) {
            SceneType.BACKLIGHT -> 14f
            SceneType.SUNSET, SceneType.GOLDEN_HOUR -> 12f
            SceneType.NIGHT_EXTREME, SceneType.ASTRO -> 16f
            SceneType.BRIGHT_EXTREME -> 13f
            SceneType.CONCERT, SceneType.FIREWORKS -> 15f
            SceneType.INDOOR -> 8f
            SceneType.LANDSCAPE, SceneType.WATER_LANDSCAPE -> 11f
            else -> 9f
        }
        
        // Adjust based on depth variance (more depth layers = more DR needed)
        val depthVariance = if (depthData != null) {
            val samples = mutableListOf<Float>()
            for (i in 0..15) {
                val x = (i % 4) / 3f
                val y = (i / 4) / 3f
                samples.add(depthData.sampleNormalizedDepth(x, y))
            }
            val mean = samples.average().toFloat()
            samples.map { (it - mean) * (it - mean) }.average().toFloat()
        } else {
            0.1f
        }
        
        val dynamicRange = baseDynamicRange * (1f + depthVariance * 0.5f)
        val strictDynamicRange = if (strictHighEndEnabled()) {
            dynamicRange * 1.08f
        } else {
            dynamicRange
        }
        
        // Estimate clipping risks
        val highlightClipping = when (sceneType) {
            SceneType.BACKLIGHT, SceneType.SUNSET, SceneType.BRIGHT_EXTREME -> 0.7f
            SceneType.CONCERT, SceneType.FIREWORKS -> 0.6f
            SceneType.GOLDEN_HOUR -> 0.4f
            else -> 0.2f
        }
        
        val shadowClipping = when (sceneType) {
            SceneType.NIGHT_EXTREME, SceneType.ASTRO -> 0.8f
            SceneType.NIGHT, SceneType.PORTRAIT_LOW_LIGHT -> 0.6f
            SceneType.CONCERT -> 0.5f
            else -> 0.2f
        }
        
        val contrastRatio = strictDynamicRange / 10f
        val hdrRecommended = if (strictHighEndEnabled()) {
            strictDynamicRange > 9.5f || highlightClipping > 0.45f || shadowClipping > 0.45f
        } else {
            strictDynamicRange > 10f || highlightClipping > 0.5f || shadowClipping > 0.5f
        }
        
        // Suggest exposure compensation
        val highlightPressure = ((highlightClipping - 0.45f) / 0.35f).coerceIn(0f, 1f)
        val shadowPressure = ((shadowClipping - 0.35f) / 0.45f).coerceIn(0f, 1f)
        val dynamicRangePressure = ((strictDynamicRange - 9f) / 6f).coerceIn(0f, 1f)
        val backlightWeight = when (sceneType) {
            SceneType.BACKLIGHT,
            SceneType.SUNSET,
            SceneType.GOLDEN_HOUR -> 0.22f
            else -> 0f
        }
        val exposureComp =
            (
                shadowPressure * 0.58f +
                    dynamicRangePressure * backlightWeight -
                    highlightPressure * 0.28f
                ).coerceIn(-0.35f, 0.65f)
        
        return HdrSceneAnalysis(
            dynamicRangeStops = strictDynamicRange,
            highlightClipping = highlightClipping,
            shadowClipping = shadowClipping,
            contrastRatio = contrastRatio,
            hdrRecommended = hdrRecommended,
            exposureCompensation = exposureComp
        )
    }

    // ════════════════════════════════════════════════════════════════════════════
    // FOCUS CONFIDENCE METRICS
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Calculate focus confidence from multiple factors
     */
    fun calculateFocusConfidence(
        subjectBounds: RectF,
        depthData: DepthData?,
        segmentationData: SegmentationData?,
        subjectConfidence: Float,
        estimatedSharpness: Float = 0.7f
    ): FocusConfidence {
        // Sharpness (would be calculated from image analysis in real implementation)
        val sharpness = estimatedSharpness
        
        // Contrast (estimated from bounds size - smaller = harder to focus)
        val area = subjectBounds.width() * subjectBounds.height()
        val contrast = (area / 0.25f).coerceIn(0.3f, 1f)
        
        // Depth confidence
        val depthConfidence = if (depthData != null) {
            val avgDepth = depthData.averageDepth(subjectBounds, grid = 5)
            val centerConf = depthData.sampleDepthConfidence(
                subjectBounds.centerX(),
                subjectBounds.centerY()
            )
            val cornerConfidence = (
                depthData.sampleDepthConfidence(subjectBounds.left, subjectBounds.top) +
                    depthData.sampleDepthConfidence(subjectBounds.right, subjectBounds.top) +
                    depthData.sampleDepthConfidence(subjectBounds.left, subjectBounds.bottom) +
                    depthData.sampleDepthConfidence(subjectBounds.right, subjectBounds.bottom)
                ) * 0.25f
            val localDepthVariance = abs(avgDepth - depthData.sampleNormalizedDepth(subjectBounds.centerX(), subjectBounds.centerY()))
            val consistency = (1f - localDepthVariance * 5f).coerceIn(0.25f, 1f)
            val confidenceBlend = centerConf * 0.65f + cornerConfidence * 0.35f
            // Good depth data and not at extreme distances
            val depthQuality = if (avgDepth > 0.05f && avgDepth < 0.95f) 1f else 0.5f
            confidenceBlend * depthQuality * consistency
        } else {
            0.5f
        }
        val segmentationConfidence = if (segmentationData != null) {
            segmentationData.sampleForegroundConfidence(
                subjectBounds.centerX(),
                subjectBounds.centerY()
            )
        } else {
            0.5f
        }
        
        // Overall confidence is weighted combination
        val overall = if (strictHighEndEnabled()) {
            sharpness * 0.32f +
                contrast * 0.18f +
                depthConfidence * 0.27f +
                segmentationConfidence * 0.10f +
                subjectConfidence * 0.13f
        } else {
            sharpness * 0.30f +
                contrast * 0.20f +
                depthConfidence * 0.25f +
                segmentationConfidence * 0.10f +
                subjectConfidence * 0.15f
        }
        
        return FocusConfidence(
            sharpness = sharpness,
            contrast = contrast,
            depthConfidence = depthConfidence,
            subjectConfidence = subjectConfidence,
            overallConfidence = overall.coerceIn(0f, 1f)
        )
    }

    // ════════════════════════════════════════════════════════════════════════════
    // REGION CALCULATION (LEGACY COMPATIBILITY)
    // ════════════════════════════════════════════════════════════════════════════

    fun focusRegionFor(
        subjectType: SubjectType,
        focusX: Float,
        focusY: Float,
        smoothedBounds: AIFloatRect,
        confidence: Float = 1f
    ): AIFloatRect {
        val size = when (subjectType) {
            SubjectType.EYE -> 0.16f
            SubjectType.FACE -> 0.28f
            SubjectType.HEAD -> 0.24f
            SubjectType.BODY -> 0.48f
            SubjectType.OBJECT -> 0.34f
            SubjectType.GROUP -> 0.55f
            SubjectType.FULL_BODY -> 0.50f
            SubjectType.UPPER_BODY -> 0.40f
        }
        val confidencePenaltyScale = when (subjectType) {
            SubjectType.EYE -> 0.22f
            SubjectType.FACE -> 0.18f
            SubjectType.HEAD -> 0.17f
            SubjectType.OBJECT -> 0.16f
            SubjectType.UPPER_BODY,
            SubjectType.BODY,
            SubjectType.FULL_BODY -> 0.12f
            SubjectType.GROUP -> 0.10f
        }
        val confidenceClamped = confidence.coerceIn(0f, 1f)
        val precisionTightenScale = when (subjectType) {
            SubjectType.EYE -> 0.10f
            SubjectType.FACE -> 0.06f
            SubjectType.HEAD -> 0.05f
            SubjectType.OBJECT -> 0.05f
            SubjectType.UPPER_BODY -> 0.03f
            SubjectType.BODY,
            SubjectType.FULL_BODY -> 0.02f
            SubjectType.GROUP -> 0f
        }
        val confidenceScale =
            1f + (1f - confidenceClamped) * confidencePenaltyScale - confidenceClamped * precisionTightenScale
        val dynamicSize = (size * confidenceScale).coerceIn(0.12f, 0.62f)
        val half = dynamicSize / 2f
        val subjectConstraintPadding = when (subjectType) {
            SubjectType.EYE -> 0.09f
            SubjectType.FACE -> 0.10f
            else -> 0.12f
        }
        return AIFloatRect(
            left = (focusX - half).coerceIn(0f, 1f),
            top = (focusY - half).coerceIn(0f, 1f),
            right = (focusX + half).coerceIn(0f, 1f),
            bottom = (focusY + half).coerceIn(0f, 1f)
        ).intersected(smoothedBounds.expanded(subjectConstraintPadding))
    }

    fun exposureRegionFor(
        subjectType: SubjectType,
        smoothedBounds: AIFloatRect,
        confidence: Float = 1f
    ): AIFloatRect {
        val padding = when (subjectType) {
            SubjectType.EYE -> 0.18f
            SubjectType.FACE -> 0.16f
            SubjectType.HEAD -> 0.15f
            SubjectType.BODY -> 0.12f
            SubjectType.OBJECT -> 0.10f
            SubjectType.GROUP -> 0.08f
            SubjectType.FULL_BODY -> 0.10f
            SubjectType.UPPER_BODY -> 0.14f
        }
        val confidenceScale = 1f + (1f - confidence.coerceIn(0f, 1f)) * 0.22f
        return smoothedBounds.expanded((padding * confidenceScale).coerceIn(0.08f, 0.24f))
    }

    fun normalizedRectToSensorRect(
        region: AIFloatRect,
        sensorWidth: Int,
        sensorHeight: Int
    ): AIMeteringRect =
        AIMeteringRect(
            left = (region.left * sensorWidth).toInt(),
            top = (region.top * sensorHeight).toInt(),
            right = (region.right * sensorWidth).toInt(),
            bottom = (region.bottom * sensorHeight).toInt()
        )

    fun normalizedRectToSensorRect(
        region: AIFloatRect,
        sensorBounds: AIMeteringRect
    ): AIMeteringRect {
        val sensorWidth = sensorBounds.width().coerceAtLeast(1)
        val sensorHeight = sensorBounds.height().coerceAtLeast(1)
        return AIMeteringRect(
            left = sensorBounds.left + (region.left.coerceIn(0f, 1f) * sensorWidth).toInt(),
            top = sensorBounds.top + (region.top.coerceIn(0f, 1f) * sensorHeight).toInt(),
            right = sensorBounds.left + (region.right.coerceIn(0f, 1f) * sensorWidth).toInt(),
            bottom = sensorBounds.top + (region.bottom.coerceIn(0f, 1f) * sensorHeight).toInt()
        )
    }

    fun clampMeteringRect(region: AIMeteringRect, bounds: AIMeteringRect): AIMeteringRect {
        // A degenerate/empty bounds (right<=left or bottom<=top) would make coerceIn(min,max) throw
        // an empty-range exception; collapse to the bounds instead of crashing.
        if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) return bounds
        val left = region.left.coerceIn(bounds.left, bounds.right - 1)
        val top = region.top.coerceIn(bounds.top, bounds.bottom - 1)
        val right = region.right.coerceIn(left + 1, bounds.right)
        val bottom = region.bottom.coerceIn(top + 1, bounds.bottom)
        return AIMeteringRect(left, top, right, bottom)
    }

    fun regionsChanged(
        lastFocusRegion: AIMeteringRect?,
        lastExposureRegion: AIMeteringRect?,
        focusRegion: AIMeteringRect,
        exposureRegion: AIMeteringRect,
        pixelTolerance: Int = 24
    ): Boolean {
        val focusChanged = rectDelta(lastFocusRegion, focusRegion) > pixelTolerance
        val exposureChanged = rectDelta(lastExposureRegion, exposureRegion) > pixelTolerance
        return focusChanged || exposureChanged
    }

    // ════════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ════════════════════════════════════════════════════════════════════════════

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun rectDelta(previous: AIMeteringRect?, current: AIMeteringRect): Int {
        previous ?: return Int.MAX_VALUE
        return maxOf(
            abs(previous.left - current.left),
            abs(previous.top - current.top),
            abs(previous.right - current.right),
            abs(previous.bottom - current.bottom)
        )
    }
}
