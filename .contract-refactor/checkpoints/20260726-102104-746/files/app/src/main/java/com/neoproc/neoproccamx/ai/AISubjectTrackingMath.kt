package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.*

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * PROFESSIONAL SUBJECT TRACKING - cinema-oriented ALGORITHMS (UPGRADED)
 * ════════════════════════════════════════════════════════════════════════════════
 * 
 * Advanced subject tracking with:
 * - Multi-factor subject scoring (saliency, depth, size, centrality)
 * - Attention mechanism for subject priority
 * - Temporal coherence for stable tracking
 * - Professional subject selection algorithms
 */

internal data class TrackingRect(
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
}

internal data class TrackingUpdate(
    val displayRegion: TrackingRect,
    val persistedRegion: TrackingRect?,
    val velocityX: Float,
    val velocityY: Float,
    val missCount: Int,
    val shouldClear: Boolean
)

/**
 * Subject scoring result for priority selection
 */
internal data class SubjectScore(
    val trackingId: Int,
    val totalScore: Float,
    val saliencyScore: Float,
    val depthScore: Float,
    val sizeScore: Float,
    val centralityScore: Float,
    val motionScore: Float,
    val temporalScore: Float,
    val attentionScore: Float
)

internal object AISubjectTrackingMath {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private const val MISS_SEARCH_INCREMENT = 8
    private const val VELOCITY_DAMPING = 0.5f
    private const val VELOCITY_BLEND_OLD = 0.45f
    private const val VELOCITY_BLEND_NEW = 0.55f
    private const val TARGET_SIZE_FRACTION = 0.15f

    // ════════════════════════════════════════════════════════════════════════════
    // BASIC TRACKING
    // ════════════════════════════════════════════════════════════════════════════

    fun searchRange(baseRange: Int, missCount: Int): Int =
        baseRange + missCount * MISS_SEARCH_INCREMENT

    fun trackingConfidence(
        bestSsd: Double,
        blockSize: Int
    ): Float {
        val maxSsd = blockSize * blockSize * 3 * 255 * 255
        return (1.0 - bestSsd / maxSsd).coerceIn(0.0, 1.0).toFloat()
    }

    fun initialTrackingRegion(
        normalizedX: Float,
        normalizedY: Float,
        frameWidth: Int,
        frameHeight: Int
    ): TrackingRect {
        val cx = normalizedX * frameWidth
        val cy = normalizedY * frameHeight
        val size = minOf(frameWidth, frameHeight) * TARGET_SIZE_FRACTION
        return TrackingRect(
            left = cx - size / 2f,
            top = cy - size / 2f,
            right = cx + size / 2f,
            bottom = cy + size / 2f
        )
    }

    fun updateTrackingState(
        region: TrackingRect,
        velocityX: Float,
        velocityY: Float,
        frameWidth: Int,
        frameHeight: Int,
        bestDx: Int,
        bestDy: Int,
        confidence: Float,
        smoothAlpha: Float,
        clearThreshold: Float,
        maxMisses: Int,
        missCount: Int
    ): TrackingUpdate {
        val rawDx = velocityX + bestDx
        val rawDy = velocityY + bestDy
        val rawRegion = TrackingRect(
            left = (region.left + rawDx).coerceIn(0f, frameWidth.toFloat()),
            top = (region.top + rawDy).coerceIn(0f, frameHeight.toFloat()),
            right = (region.right + rawDx).coerceIn(0f, frameWidth.toFloat()),
            bottom = (region.bottom + rawDy).coerceIn(0f, frameHeight.toFloat())
        )
        val displayRegion = TrackingRect(
            left = lerp(region.left, rawRegion.left, smoothAlpha),
            top = lerp(region.top, rawRegion.top, smoothAlpha),
            right = lerp(region.right, rawRegion.right, smoothAlpha),
            bottom = lerp(region.bottom, rawRegion.bottom, smoothAlpha)
        )

        return if (confidence < clearThreshold) {
            val nextMissCount = missCount + 1
            TrackingUpdate(
                displayRegion = displayRegion,
                persistedRegion = if (nextMissCount >= maxMisses) null else region,
                velocityX = velocityX * VELOCITY_DAMPING,
                velocityY = velocityY * VELOCITY_DAMPING,
                missCount = nextMissCount,
                shouldClear = nextMissCount >= maxMisses
            )
        } else {
            TrackingUpdate(
                displayRegion = displayRegion,
                persistedRegion = displayRegion,
                velocityX = velocityX * VELOCITY_BLEND_OLD + rawDx * VELOCITY_BLEND_NEW,
                velocityY = velocityY * VELOCITY_BLEND_OLD + rawDy * VELOCITY_BLEND_NEW,
                missCount = 0,
                shouldClear = false
            )
        }
    }

    fun normalizedRegion(region: TrackingRect, frameWidth: Int, frameHeight: Int): TrackingRect =
        TrackingRect(
            left = region.left / frameWidth,
            top = region.top / frameHeight,
            right = region.right / frameWidth,
            bottom = region.bottom / frameHeight
        )

    // ════════════════════════════════════════════════════════════════════════════
    // ADVANCED SUBJECT SCORING
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Calculate saliency score based on visual prominence
     * Higher for subjects in focus, well-lit, high contrast
     */
    fun calculateSaliencyScore(
        bounds: RectF,
        depthData: DepthData?,
        segmentationData: SegmentationData?,
        baseConfidence: Float
    ): Float {
        val strictHighEnd = strictHighEndEnabled()
        var saliency = baseConfidence
        
        // Depth contrast (closer subjects are more salient)
        if (depthData != null) {
            val avgDepth = depthData.averageDepth(bounds, grid = 3)
            val depthConfidence = depthData.sampleDepthConfidence(bounds.centerX(), bounds.centerY())
            // Closer = higher saliency (inverted depth)
            saliency *= (1f - avgDepth * if (strictHighEnd) 0.46f else 0.5f) *
                if (strictHighEnd) depthConfidence.coerceAtLeast(0.82f) else depthConfidence
        }
        
        // Segmentation confidence (well-separated subjects are more salient)
        if (segmentationData != null) {
            val fgConfidence = segmentationData.sampleForegroundConfidence(
                bounds.centerX(), 
                bounds.centerY()
            )
            val segmentationWeight = if (strictHighEnd) {
                (0.58f + fgConfidence * 0.46f) +
                    if (segmentationData.edgeRefinementApplied) 0.02f else 0f
            } else {
                0.6f + fgConfidence * 0.4f
            }
            saliency *= segmentationWeight
        }

        return if (strictHighEnd) {
            (saliency * 1.05f).coerceIn(0f, 1f)
        } else {
            saliency.coerceIn(0f, 1f)
        }
    }

    /**
     * Calculate depth score - preference for closer subjects
     */
    fun calculateDepthScore(
        bounds: RectF,
        depthData: DepthData?
    ): Float {
        if (depthData == null) return 0.5f
        val strictHighEnd = strictHighEndEnabled()
        
        // Use median depth to be robust against outliers
        val medianDepth = depthData.medianDepth(bounds, grid = 5)
        val avgDepth = depthData.averageDepth(bounds, grid = 5)
        
        // Closer subjects score higher (invert depth)
        val depthScore = 1f - (
            medianDepth * if (strictHighEnd) 0.65f else 0.6f +
                avgDepth * if (strictHighEnd) 0.35f else 0.4f
            )
        
        // Check depth confidence
        val centerConfidence = depthData.sampleDepthConfidence(
            bounds.centerX(), 
            bounds.centerY()
        )
        
        return (depthScore * if (strictHighEnd) centerConfidence.coerceAtLeast(0.75f) else centerConfidence)
            .coerceIn(0f, 1f)
    }

    /**
     * Calculate size score - preference for appropriately sized subjects
     * Too small = likely background, too large = likely cropped
     */
    fun calculateSizeScore(bounds: RectF): Float {
        val area = bounds.width() * bounds.height()
        
        // Ideal size range: 10-40% of frame
        val idealMin = 0.10f
        val idealMax = 0.40f
        val idealCenter = 0.22f
        
        return when {
            area < idealMin -> {
                // Small subjects: linear falloff
                (area / idealMin).coerceIn(0f, 1f)
            }
            area > idealMax -> {
                // Large subjects: slower falloff
                val excess = (area - idealMax) / (1f - idealMax)
                (1f - excess * 0.5f).coerceIn(0f, 1f)
            }
            else -> {
                // In ideal range: gaussian-like peak at center
                val dist = abs(area - idealCenter) / (idealMax - idealMin)
                1f - dist * 0.3f
            }
        }
    }

    /**
     * Calculate centrality score - preference for subjects near center
     * Uses gaussian falloff from center
     */
    fun calculateCentralityScore(bounds: RectF): Float {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val centerX = 0.5f
        val centerY = 0.5f
        
        // Distance from center (normalized)
        val dx = cx - centerX
        val dy = cy - centerY
        val distSq = dx * dx + dy * dy
        
        // Gaussian falloff (sigma = 0.3)
        val sigma = 0.3f
        val sigmaSq = sigma * sigma
        val centrality = exp(-distSq / (2f * sigmaSq))
        
        return centrality.coerceIn(0f, 1f)
    }

    /**
     * Calculate motion score - preference for subjects with controlled motion
     * Static or linear motion = high score
     * Erratic motion = low score
     */
    fun calculateMotionScore(
        velocityX: Float,
        velocityY: Float,
        accelerationX: Float,
        accelerationY: Float,
        motionPattern: MotionPattern
    ): Float {
        val strictHighEnd = strictHighEndEnabled()
        val speed = sqrt(velocityX * velocityX + velocityY * velocityY)
        val accel = sqrt(accelerationX * accelerationX + accelerationY * accelerationY)
        
        // Pattern-based scoring
        val patternScore = when (motionPattern) {
            MotionPattern.STATIC -> 1.0f
            MotionPattern.LINEAR -> 0.9f
            MotionPattern.ACCELERATING -> 0.8f
            MotionPattern.DECELERATING -> 0.85f
            MotionPattern.CIRCULAR -> 0.75f
            MotionPattern.ERRATIC -> 0.4f
        }
        
        // Speed penalty (fast motion is harder to track)
        val speedPenalty = if (speed > if (strictHighEnd) 0.12f else 0.1f) {
            (1f - (speed - if (strictHighEnd) 0.12f else 0.1f).coerceAtMost(if (strictHighEnd) 0.35f else 0.4f))
        } else {
            1f
        }
        
        // Acceleration penalty
        val accelPenalty = if (accel > if (strictHighEnd) 0.06f else 0.05f) {
            (1f - (accel - if (strictHighEnd) 0.06f else 0.05f).coerceAtMost(if (strictHighEnd) 0.25f else 0.3f))
        } else {
            1f
        }
        
        return (patternScore * speedPenalty * accelPenalty).coerceIn(0f, 1f)
    }

    /**
     * Calculate temporal coherence score
     * Rewards subjects that have been tracked consistently
     */
    fun calculateTemporalScore(
        hitStreak: Int,
        missCount: Int,
        trackDurationMs: Long
    ): Float {
        val strictHighEnd = strictHighEndEnabled()
        // Streak bonus (maxes out at 10 frames)
        val streakScore = (hitStreak.coerceAtMost(if (strictHighEnd) 12 else 10) /
            (if (strictHighEnd) 12f else 10f))
        
        // Miss penalty
        val missPenalty = (1f - missCount / (if (strictHighEnd) 10f else 8f)).coerceAtLeast(0f)
        
        // Duration bonus (tracks that last longer are more reliable)
        val durationScore = (trackDurationMs / (if (strictHighEnd) 2500f else 2000f)).coerceAtMost(1f)
        
        return if (strictHighEnd) {
            (streakScore * 0.53f + missPenalty * 0.27f + durationScore * 0.2f).coerceIn(0f, 1f)
        } else {
            (streakScore * 0.5f + missPenalty * 0.3f + durationScore * 0.2f).coerceIn(0f, 1f)
        }
    }

    /**
     * Attention mechanism - calculate which subject deserves focus
     * This is a weighted combination of all factors
     */
    fun calculateAttentionScore(
        saliencyScore: Float,
        depthScore: Float,
        sizeScore: Float,
        centralityScore: Float,
        motionScore: Float,
        temporalScore: Float,
        subjectType: SubjectType,
        category: SubjectCategory
    ): Float {
        val strictHighEnd = strictHighEndEnabled()
        // Base weights for each factor
        var saliencyWeight = if (strictHighEnd) 0.24f else 0.25f
        var depthWeight = if (strictHighEnd) 0.22f else 0.20f
        var sizeWeight = 0.15f
        var centralityWeight = 0.15f
        var motionWeight = if (strictHighEnd) 0.13f else 0.15f
        var temporalWeight = if (strictHighEnd) 0.11f else 0.10f
        
        // Adjust weights based on subject type
        when (subjectType) {
            SubjectType.EYE, SubjectType.FACE, SubjectType.HEAD -> {
                // Faces/head: prioritize centrality and depth
                centralityWeight += 0.05f
                depthWeight += 0.05f
                sizeWeight -= 0.05f
                motionWeight -= 0.05f
            }
            SubjectType.BODY, SubjectType.FULL_BODY -> {
                // Bodies: balance all factors
                // No adjustment
            }
            SubjectType.OBJECT -> {
                // Objects: prioritize saliency and size
                saliencyWeight += 0.05f
                sizeWeight += 0.05f
                centralityWeight -= 0.05f
                temporalWeight -= 0.05f
            }
            SubjectType.GROUP -> {
                // Groups: prioritize size and centrality
                sizeWeight += 0.08f
                centralityWeight += 0.07f
                depthWeight -= 0.10f
                motionWeight -= 0.05f
            }
            SubjectType.UPPER_BODY -> {
                // Similar to face
                centralityWeight += 0.03f
                depthWeight += 0.03f
                sizeWeight -= 0.03f
                motionWeight -= 0.03f
            }
        }
        
        // Adjust weights based on category
        when (category) {
            SubjectCategory.HUMAN, SubjectCategory.PET -> {
                // Humans and pets: higher temporal and motion scores
                temporalWeight += 0.05f
                motionWeight += 0.05f
                sizeWeight -= 0.05f
                saliencyWeight -= 0.05f
            }
            SubjectCategory.ANIMAL -> {
                // Animals: similar to pets but slightly lower priority
                temporalWeight += 0.03f
                motionWeight += 0.03f
                saliencyWeight -= 0.03f
                sizeWeight -= 0.03f
            }
            SubjectCategory.VEHICLE -> {
                // Vehicles: size and saliency matter more
                sizeWeight += 0.08f
                saliencyWeight += 0.07f
                temporalWeight -= 0.08f
                motionWeight -= 0.07f
            }
            SubjectCategory.NATURE, SubjectCategory.OBJECT -> {
                // Static subjects: centrality and depth matter more
                centralityWeight += 0.05f
                depthWeight += 0.05f
                motionWeight -= 0.05f
                temporalWeight -= 0.05f
            }
        }
        
        // Normalize weights to sum to 1
        val totalWeight = saliencyWeight + depthWeight + sizeWeight + 
                         centralityWeight + motionWeight + temporalWeight
        saliencyWeight /= totalWeight
        depthWeight /= totalWeight
        sizeWeight /= totalWeight
        centralityWeight /= totalWeight
        motionWeight /= totalWeight
        temporalWeight /= totalWeight
        
        // Calculate weighted score
        val attentionScore = 
            saliencyScore * saliencyWeight +
            depthScore * depthWeight +
            sizeScore * sizeWeight +
            centralityScore * centralityWeight +
            motionScore * motionWeight +
            temporalScore * temporalWeight

        return if (strictHighEnd) {
            val subjectBias = when (category) {
                SubjectCategory.HUMAN, SubjectCategory.PET -> 0.03f
                SubjectCategory.ANIMAL -> 0.02f
                else -> 0f
            }
            (attentionScore + subjectBias).coerceIn(0f, 1f)
        } else {
            attentionScore.coerceIn(0f, 1f)
        }
    }

    /**
     * Comprehensive subject scoring
     * Returns detailed score breakdown for analysis
     */
    fun scoreSubject(
        trackingId: Int,
        bounds: RectF,
        velocityX: Float,
        velocityY: Float,
        accelerationX: Float,
        accelerationY: Float,
        motionPattern: MotionPattern,
        hitStreak: Int,
        missCount: Int,
        trackDurationMs: Long,
        confidence: Float,
        subjectType: SubjectType,
        category: SubjectCategory,
        depthData: DepthData?,
        segmentationData: SegmentationData?
    ): SubjectScore {
        val strictHighEnd = strictHighEndEnabled()
        val saliencyScore = calculateSaliencyScore(bounds, depthData, segmentationData, confidence)
        val depthScore = calculateDepthScore(bounds, depthData)
        val sizeScore = calculateSizeScore(bounds)
        val centralityScore = calculateCentralityScore(bounds)
        val motionScore = calculateMotionScore(velocityX, velocityY, accelerationX, accelerationY, motionPattern)
        val temporalScore = calculateTemporalScore(hitStreak, missCount, trackDurationMs)
        val attentionScore = calculateAttentionScore(
            saliencyScore, depthScore, sizeScore, centralityScore, 
            motionScore, temporalScore, subjectType, category
        )
        val totalScore = if (strictHighEnd) {
            (attentionScore * 0.94f + confidence * 0.06f).coerceIn(0f, 1f)
        } else {
            attentionScore
        }
        
        return SubjectScore(
            trackingId = trackingId,
            totalScore = totalScore,
            saliencyScore = saliencyScore,
            depthScore = depthScore,
            sizeScore = sizeScore,
            centralityScore = centralityScore,
            motionScore = motionScore,
            temporalScore = temporalScore,
            attentionScore = attentionScore
        )
    }

    /**
     * Select best subject from multiple candidates
     * Uses hysteresis to prevent flickering between subjects
     */
    fun selectBestSubject(
        candidates: List<SubjectScore>,
        currentTrackingId: Int?,
        hysteresisBonus: Float = 0.15f
    ): SubjectScore? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        val strictHighEnd = strictHighEndEnabled()
        
        // Apply hysteresis bonus to current subject if it exists
        val scoredCandidates = candidates.map { candidate ->
            if (candidate.trackingId == currentTrackingId) {
                val effectiveBonus = if (strictHighEnd) hysteresisBonus + 0.05f else hysteresisBonus
                candidate.copy(totalScore = (candidate.totalScore + effectiveBonus).coerceAtMost(1f))
            } else {
                candidate
            }
        }

        val best = scoredCandidates.maxByOrNull { it.totalScore } ?: return null
        if (!strictHighEnd || currentTrackingId == null || best.trackingId == currentTrackingId) {
            return best
        }
        val current = scoredCandidates.firstOrNull { it.trackingId == currentTrackingId } ?: return best
        return if ((best.totalScore - current.totalScore) < 0.08f) current else best
    }

    // ════════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ════════════════════════════════════════════════════════════════════════════

    private fun lerp(start: Float, end: Float, amount: Float): Float =
        start + (end - start) * amount
}
