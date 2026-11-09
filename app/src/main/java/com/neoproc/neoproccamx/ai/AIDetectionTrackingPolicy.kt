package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.util.Locale

internal enum class AIDetectionKind {
    FACE,
    OBJECT
}

internal data class AIDetectionTrackingProfile(
    val staleTtlMs: Long,
    val predictionLeadMs: Long,
    val minConfidence: Float,
    val minAreaFraction: Float,
    val baseMatchIou: Float,
    val minMotionSmoothing: Float,
    val maxMotionSmoothing: Float,
    val missDamping: Float,
    val continuityBonus: Float,
    val eyePredictionBlend: Float = 0f,
    val defaultLabel: String = "Object",
    val adaptiveLeadScale: Float = 0.35f,
    val maxLeadMs: Long = 180L,
    val outlierGateFactor: Float = 2.2f,
    val erraticPenalty: Float = 0.08f
)

internal data class AIDetectionBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun width(): Float = (right - left).coerceAtLeast(0f)
    fun height(): Float = (bottom - top).coerceAtLeast(0f)
    fun centerX(): Float = (left + right) * 0.5f
    fun centerY(): Float = (top + bottom) * 0.5f
    fun toRectF(): RectF = RectF(left, top, right, bottom)

    companion object {
        fun fromRectF(rect: RectF): AIDetectionBox =
            AIDetectionBox(
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom
            )
    }
}

/**
 * HIGH-END detection tracking policy with adaptive prediction lead, outlier gating,
 * and motion-aware matching thresholds for stable real-time tracking.
 */
internal object AIDetectionTrackingPolicy {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private val faceProfile = AIDetectionTrackingProfile(
        staleTtlMs = 650L,
        predictionLeadMs = 80L,
        minConfidence = 0.48f,
        minAreaFraction = 0.0012f,
        baseMatchIou = 0.17f,
        minMotionSmoothing = 0.34f,
        maxMotionSmoothing = 0.54f,
        missDamping = 0.82f,
        continuityBonus = 0.10f,
        eyePredictionBlend = 0.34f,
        defaultLabel = "Face",
        adaptiveLeadScale = 0.28f,
        maxLeadMs = 130L,
        outlierGateFactor = 1.8f,
        erraticPenalty = 0.05f
    )

    private val objectProfile = AIDetectionTrackingProfile(
        staleTtlMs = 900L,
        predictionLeadMs = 120L,
        minConfidence = 0.38f,
        minAreaFraction = 0.0015f,
        baseMatchIou = 0.16f,
        minMotionSmoothing = 0.30f,
        maxMotionSmoothing = 0.48f,
        missDamping = 0.78f,
        continuityBonus = 0.10f,
        defaultLabel = "Object",
        adaptiveLeadScale = 0.45f,
        maxLeadMs = 220L,
        outlierGateFactor = 2.2f,
        erraticPenalty = 0.12f
    )

    private val strictFaceProfile = faceProfile.copy(
        staleTtlMs = 760L,
        predictionLeadMs = 72L,
        minMotionSmoothing = 0.40f,
        maxMotionSmoothing = 0.62f,
        missDamping = 0.86f,
        continuityBonus = 0.13f,
        adaptiveLeadScale = 0.22f,
        maxLeadMs = 150L,
        outlierGateFactor = 1.65f,
        erraticPenalty = 0.04f
    )

    private val strictObjectProfile = objectProfile.copy(
        staleTtlMs = 1_050L,
        predictionLeadMs = 104L,
        minMotionSmoothing = 0.35f,
        maxMotionSmoothing = 0.56f,
        missDamping = 0.82f,
        continuityBonus = 0.13f,
        adaptiveLeadScale = 0.36f,
        maxLeadMs = 260L,
        outlierGateFactor = 1.95f,
        erraticPenalty = 0.10f
    )

    fun profile(kind: AIDetectionKind): AIDetectionTrackingProfile =
        when (kind) {
            AIDetectionKind.FACE -> if (strictHighEndEnabled()) strictFaceProfile else faceProfile
            AIDetectionKind.OBJECT -> if (strictHighEndEnabled()) strictObjectProfile else objectProfile
        }

    fun deriveFaceConfidence(
        boundingBox: RectF,
        leftEye: EyePoint?,
        rightEye: EyePoint?
    ): Float = deriveFaceConfidence(AIDetectionBox.fromRectF(boundingBox), leftEye, rightEye)

    fun deriveFaceConfidence(
        boundingBox: AIDetectionBox,
        leftEye: EyePoint?,
        rightEye: EyePoint?
    ): Float {
        val area = (boundingBox.width() * boundingBox.height()).coerceIn(0f, 1f)
        val areaScore = ((area - faceProfile.minAreaFraction) / 0.06f).coerceIn(0f, 1f)
        val eyeSupport = when {
            leftEye != null && rightEye != null -> 1f
            leftEye != null || rightEye != null -> 0.72f
            else -> 0.36f
        }
        val eyeConfidence = listOfNotNull(leftEye?.confidence, rightEye?.confidence)
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat()
            ?: 0.45f
        return (
            0.20f +
                eyeSupport * 0.34f +
                eyeConfidence * 0.26f +
                areaScore * 0.20f
            ).coerceIn(0f, 1f)
    }

    fun shouldAcceptDetection(
        profile: AIDetectionTrackingProfile,
        boundingBox: RectF,
        confidence: Float
    ): Boolean = shouldAcceptDetection(
        profile = profile,
        boundingBox = AIDetectionBox.fromRectF(boundingBox),
        confidence = confidence
    )

    fun shouldAcceptDetection(
        profile: AIDetectionTrackingProfile,
        boundingBox: AIDetectionBox,
        confidence: Float
    ): Boolean {
        if (boundingBox.width() <= 0f || boundingBox.height() <= 0f) return false
        val area = boundingBox.width() * boundingBox.height()
        val aspect = boundingBox.width() / boundingBox.height().coerceAtLeast(0.0001f)
        if (aspect < 0.12f || aspect > 8.0f) return false
        return confidence >= profile.minConfidence && area >= profile.minAreaFraction
    }

    fun pruneStaleTrackingStates(
        trackingStates: MutableMap<Int, MotionState>,
        timestampMs: Long,
        profile: AIDetectionTrackingProfile
    ): List<Int> {
        val staleIds = trackingStates
            .filterValues { state ->
                val ttl = profile.staleTtlMs + state.hitStreak.coerceAtMost(6) * 60L
                timestampMs - state.lastMatchedAtMs > ttl
            }
            .keys
            .toList()
        staleIds.forEach(trackingStates::remove)
        return staleIds
    }

    fun motionSmoothing(
        profile: AIDetectionTrackingProfile,
        confidence: Float
    ): Float =
        (
            profile.minMotionSmoothing +
                (profile.maxMotionSmoothing - profile.minMotionSmoothing) *
                confidence.coerceIn(0f, 1f)
            ).coerceIn(profile.minMotionSmoothing, profile.maxMotionSmoothing)

    fun matchThreshold(
        profile: AIDetectionTrackingProfile,
        confidence: Float,
        areaFraction: Float,
        state: MotionState,
        timestampMs: Long
    ): Float {
        val weakConfidencePenalty = ((0.78f - confidence).coerceAtLeast(0f) * 0.78f)
        val tinyDetectionPenalty = when {
            areaFraction < profile.minAreaFraction * 2f -> 0.14f
            areaFraction < profile.minAreaFraction * 3f -> 0.06f
            else -> 0f
        }
        val continuityRelaxation = continuityScore(profile, state, timestampMs).coerceAtLeast(0f) * 0.65f
        val motionPenalty = if (state.motionPattern == MotionPattern.ERRATIC) profile.erraticPenalty else 0f
        return (profile.baseMatchIou + weakConfidencePenalty + tinyDetectionPenalty - continuityRelaxation)
            .plus(motionPenalty)
            .coerceIn(profile.baseMatchIou - 0.04f, 0.65f)
    }

    fun matchTrackingId(
        candidateIds: Set<Int>,
        trackingStates: Map<Int, MotionState>,
        detectionBounds: RectF,
        confidence: Float,
        profile: AIDetectionTrackingProfile,
        timestampMs: Long,
        candidateScoreAdjuster: (Int) -> Float = { 0f }
    ): Int? = matchTrackingId(
        candidateIds = candidateIds,
        trackingStates = trackingStates,
        detectionBounds = AIDetectionBox.fromRectF(detectionBounds),
        confidence = confidence,
        profile = profile,
        timestampMs = timestampMs,
        candidateScoreAdjuster = candidateScoreAdjuster
    )

    fun matchTrackingId(
        candidateIds: Set<Int>,
        trackingStates: Map<Int, MotionState>,
        detectionBounds: AIDetectionBox,
        confidence: Float,
        profile: AIDetectionTrackingProfile,
        timestampMs: Long,
        candidateScoreAdjuster: (Int) -> Float = { 0f }
    ): Int? {
        val areaFraction = detectionBounds.width() * detectionBounds.height()
        var bestId: Int? = null
        var bestScore = Float.NEGATIVE_INFINITY
        candidateIds.forEach { candidateId ->
            val state = trackingStates[candidateId] ?: return@forEach
            val adaptiveLeadMs = adaptivePredictionLeadMs(profile, state)
            val predicted = predictedRect(detectionBounds, state, adaptiveLeadMs)
            if (isOutlierMatch(predicted, detectionBounds, profile)) return@forEach
            val score = weightedMatchScore(predicted, detectionBounds) +
                continuityScore(profile, state, timestampMs) +
                candidateScoreAdjuster(candidateId)
            if (score > bestScore) {
                bestScore = score
                bestId = candidateId
            }
        }
        return bestId?.takeIf { candidateId ->
            val state = trackingStates[candidateId] ?: return@takeIf false
            bestScore >= matchThreshold(profile, confidence, areaFraction, state, timestampMs)
        }
    }

    fun stabilizedBounds(
        detectionBounds: RectF,
        state: MotionState,
        profile: AIDetectionTrackingProfile
    ): RectF = stabilizedBounds(
        detectionBounds = AIDetectionBox.fromRectF(detectionBounds),
        state = state,
        profile = profile
    ).toRectF()

    fun stabilizedBounds(
        detectionBounds: AIDetectionBox,
        state: MotionState,
        profile: AIDetectionTrackingProfile
    ): AIDetectionBox {
        val predictedCenter = TrackingMath.predictCenter(state, profile.predictionLeadMs)
        val width = state.width.takeIf { it > 0f } ?: detectionBounds.width()
        val height = state.height.takeIf { it > 0f } ?: detectionBounds.height()
        return AIDetectionBox(
            (predictedCenter.first - width / 2f).coerceIn(0f, 1f),
            (predictedCenter.second - height / 2f).coerceIn(0f, 1f),
            (predictedCenter.first + width / 2f).coerceIn(0f, 1f),
            (predictedCenter.second + height / 2f).coerceIn(0f, 1f)
        )
    }

    fun registerTrackingMisses(
        trackingStates: MutableMap<Int, MotionState>,
        unmatchedIds: Set<Int>,
        timestampMs: Long,
        profile: AIDetectionTrackingProfile
    ) {
        unmatchedIds.forEach { unmatchedId ->
            val state = trackingStates[unmatchedId] ?: return@forEach
            trackingStates[unmatchedId] = TrackingMath.noteTrackingMiss(
                current = state,
                nowMs = timestampMs,
                predictionLeadMs = profile.predictionLeadMs,
                damping = profile.missDamping
            )
        }
    }

    fun smoothEyePoint(
        point: EyePoint,
        state: MotionState,
        profile: AIDetectionTrackingProfile
    ): EyePoint {
        val predicted = TrackingMath.predictCenter(state, profile.predictionLeadMs / 2L)
        val blend = profile.eyePredictionBlend.coerceIn(0f, 1f)
        return point.copy(
            x = (point.x * (1f - blend) + predicted.first * blend).coerceIn(0f, 1f),
            y = (point.y * (1f - blend) + predicted.second * blend).coerceIn(0f, 1f)
        )
    }

    fun sanitizeObjectLabel(
        primaryLabel: String?,
        fallbackLabel: String?,
        confidence: Float,
        profile: AIDetectionTrackingProfile
    ): String {
        val normalizedPrimary = normalizeLabel(primaryLabel)
        val normalizedFallback = normalizeLabel(fallbackLabel)
        val primaryGeneric = isGenericLabel(normalizedPrimary, profile.defaultLabel)
        val fallbackGeneric = isGenericLabel(normalizedFallback, profile.defaultLabel)

        val resolved = when {
            !primaryGeneric && normalizedPrimary != null -> normalizedPrimary
            !fallbackGeneric && normalizedFallback != null && confidence >= profile.minConfidence -> normalizedFallback
            normalizedPrimary != null && normalizedPrimary.isNotBlank() -> normalizedPrimary
            normalizedFallback != null && normalizedFallback.isNotBlank() -> normalizedFallback
            else -> profile.defaultLabel
        }
        return resolved.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase(Locale.US) else character.toString()
        }
    }

    fun labelAffinity(
        trackedLabel: String?,
        detectionLabel: String?,
        profile: AIDetectionTrackingProfile
    ): Float {
        val tracked = normalizeLabel(trackedLabel)
        val incoming = normalizeLabel(detectionLabel)
        if (tracked == null || incoming == null) return 0f
        if (tracked == incoming) return 0.08f
        if (isGenericLabel(tracked, profile.defaultLabel) || isGenericLabel(incoming, profile.defaultLabel)) {
            return 0f
        }
        return if (tracked.contains(incoming) || incoming.contains(tracked)) 0.03f else -0.03f
    }

    private fun normalizeLabel(label: String?): String? =
        label
            ?.trim()
            ?.replace('_', ' ')
            ?.takeIf { it.isNotEmpty() }

    private fun isGenericLabel(label: String?, defaultLabel: String): Boolean {
        if (label == null) return true
        return when (label.lowercase(Locale.US)) {
            defaultLabel.lowercase(Locale.US),
            "object",
            "objects",
            "item",
            "thing",
            "unknown",
            "entity" -> true
            else -> false
        }
    }

    private fun predictedRect(
        reference: AIDetectionBox,
        state: MotionState,
        predictionLeadMs: Long
    ): AIDetectionBox {
        val predicted = TrackingMath.predictCenter(state, predictionLeadMs)
        val predictedWidth = state.width.takeIf { it > 0f } ?: reference.width()
        val predictedHeight = state.height.takeIf { it > 0f } ?: reference.height()
        val halfWidth = predictedWidth / 2f
        val halfHeight = predictedHeight / 2f
        return AIDetectionBox(
            (predicted.first - halfWidth).coerceIn(0f, 1f),
            (predicted.second - halfHeight).coerceIn(0f, 1f),
            (predicted.first + halfWidth).coerceIn(0f, 1f),
            (predicted.second + halfHeight).coerceIn(0f, 1f)
        )
    }

    private fun weightedMatchScore(
        predicted: AIDetectionBox,
        detection: AIDetectionBox
    ): Float {
        val overlap = iou(predicted, detection)
        val centerAffinity = centerAffinity(predicted, detection)
        val sizeAffinity = sizeAffinity(predicted, detection)
        return overlap * 0.56f + centerAffinity * 0.26f + sizeAffinity * 0.18f
    }

    private fun adaptivePredictionLeadMs(
        profile: AIDetectionTrackingProfile,
        state: MotionState
    ): Long {
        val speed = kotlin.math.sqrt(state.velocityX * state.velocityX + state.velocityY * state.velocityY)
        val speedNorm = (speed / 0.25f).coerceIn(0f, 1f)
        val adaptive = profile.predictionLeadMs + (profile.predictionLeadMs * profile.adaptiveLeadScale * speedNorm).toLong()
        return adaptive.coerceIn(40L, profile.maxLeadMs)
    }

    private fun isOutlierMatch(
        predicted: AIDetectionBox,
        detection: AIDetectionBox,
        profile: AIDetectionTrackingProfile
    ): Boolean {
        val dx = predicted.centerX() - detection.centerX()
        val dy = predicted.centerY() - detection.centerY()
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        val gate = maxOf(predicted.width(), predicted.height(), detection.width(), detection.height())
            .coerceAtLeast(0.03f) * profile.outlierGateFactor
        return distance > gate
    }

    private fun continuityScore(
        profile: AIDetectionTrackingProfile,
        state: MotionState,
        timestampMs: Long
    ): Float {
        val ageMs = (timestampMs - state.lastMatchedAtMs).coerceAtLeast(0L)
        val freshness = 1f - (ageMs.toFloat() / profile.staleTtlMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
        val continuityFreshness = if (state.hitStreak >= 2) freshness * profile.continuityBonus else 0f
        val streakBonus = (state.hitStreak - 1).coerceAtLeast(0).coerceAtMost(6) * 0.012f
        val missPenalty = state.missCount.coerceAtMost(5) * 0.018f
        return (continuityFreshness + streakBonus - missPenalty).coerceIn(-0.12f, 0.18f)
    }

    private fun centerAffinity(a: AIDetectionBox, b: AIDetectionBox): Float {
        val dx = a.centerX() - b.centerX()
        val dy = a.centerY() - b.centerY()
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        val span = maxOf(a.width(), a.height(), b.width(), b.height()).coerceAtLeast(0.04f) * 1.6f
        return (1f - distance / span).coerceIn(0f, 1f)
    }

    private fun sizeAffinity(a: AIDetectionBox, b: AIDetectionBox): Float {
        val widthRatio = minOf(a.width(), b.width()) / maxOf(a.width(), b.width(), 0.0001f)
        val heightRatio = minOf(a.height(), b.height()) / maxOf(a.height(), b.height(), 0.0001f)
        return ((widthRatio + heightRatio) * 0.5f).coerceIn(0f, 1f)
    }

    private fun iou(a: AIDetectionBox, b: AIDetectionBox): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val width = (right - left).coerceAtLeast(0f)
        val height = (bottom - top).coerceAtLeast(0f)
        val intersection = width * height
        if (intersection <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        if (union <= 0f) return 0f
        return intersection / union
    }
}
