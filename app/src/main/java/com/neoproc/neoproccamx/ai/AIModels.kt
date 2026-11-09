package com.neoproc.neoproccamx.ai

import android.graphics.Bitmap
import android.graphics.RectF
import com.neoproc.neoproccamx.camera.FrameGeometry
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.max
import kotlin.math.roundToInt

private fun strictHighEndEnabled(): Boolean =
    HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * SCENE TYPES - PRO cinema-oriented SCENE DETECTION (UPGRADED)
 * ════════════════════════════════════════════════════════════════════════════════
 * 
 * Extended scene types for automatic camera optimization with hierarchical classification.
 * Each scene type maps to specific exposure, color, and processing settings.
 */
enum class SceneType {
    /** Default/unknown scene */
    GENERAL,
    /** Low light / nighttime scene */
    NIGHT,
    /** Extreme low light (< 1 lux) */
    NIGHT_EXTREME,
    /** Portrait with person(s) */
    PORTRAIT,
    /** Portrait in low light conditions */
    PORTRAIT_LOW_LIGHT,
    /** Group portrait (3+ people) */
    PORTRAIT_GROUP,
    /** Natural landscape (mountains, forests, etc.) */
    LANDSCAPE,
    /** Water landscape (ocean, lake, waterfall) */
    WATER_LANDSCAPE,
    /** Indoor environment */
    INDOOR,
    /** Outdoor environment */
    OUTDOOR,
    /** Street/urban scene */
    STREET,
    /** High dynamic range / backlit scene */
    BACKLIGHT,
    /** Sunset or sunrise */
    SUNSET,
    /** Golden hour photography */
    GOLDEN_HOUR,
    /** Blue hour photography */
    BLUE_HOUR,
    /** Food photography */
    FOOD,
    /** Document / text scanning */
    DOCUMENT,
    /** Action / sports photography */
    ACTION,
    /** Macro / close-up photography */
    MACRO,
    /** Architecture / buildings */
    ARCHITECTURE,
    /** Very bright conditions */
    BRIGHT,
    /** Beach/snow (extreme brightness) */
    BRIGHT_EXTREME,
    /** Concert/stage with mixed lighting */
    CONCERT,
    /** Fireworks/light trails */
    FIREWORKS,
    /** Astrophotography/starry night */
    ASTRO
}

/**
 * Scene confidence for ensemble classification
 */
data class SceneClassification(
    val primaryScene: SceneType,
    val confidence: Float,
    val secondaryScenes: List<Pair<SceneType, Float>> = emptyList(),
    val temporalStability: Float = 0f
)

enum class SubjectType {
    EYE,
    FACE,
    BODY,
    OBJECT,
    /** Multi-person scene */
    GROUP,
    /** Full body subject */
    FULL_BODY,
    /** Upper body only */
    UPPER_BODY,
    /**
     * AF-SD (F10): whole head — emitted (gated by HeadSubjectFlag) when a face is present but the
     * eyes are unusable (profile / averted). Priority sits between FACE and BODY via
     * FocusEngine.subjectPriority(); appended at the END so existing SubjectType ordinals are
     * unchanged.
     */
    HEAD
}

enum class SubjectCategory {
    HUMAN,
    ANIMAL,
    OBJECT,
    /** Pet animal (higher priority) */
    PET,
    /** Vehicle */
    VEHICLE,
    /** Natural object (plants, flowers) */
    NATURE
}

/**
 * Motion prediction state for advanced tracking
 */
enum class MotionPattern {
    STATIC,
    LINEAR,
    ACCELERATING,
    DECELERATING,
    ERRATIC,
    CIRCULAR
}

data class EyePoint(
    val x: Float,
    val y: Float,
    val confidence: Float,
    val isOpen: Boolean = true,
    val gazeX: Float = 0.5f,
    val gazeY: Float = 0.5f,
    val subPixelX: Float = x,
    val subPixelY: Float = y,
    val depthZ: Float = 0f,
    val gazeVectorX: Float = 0f,
    val gazeVectorY: Float = 0f,
    val gazeVectorZ: Float = 1f,
    val irisDisplacementX: Float = 0f,
    val irisDisplacementY: Float = 0f,
    val spatialConfidence: Float = confidence
)

data class TrackedFace(
    val trackingId: Int,
    val boundingBox: RectF,
    val leftEye: EyePoint?,
    val rightEye: EyePoint?,
    val confidence: Float,
    val velocityX: Float,
    val velocityY: Float,
    val timestampMs: Long,
    val accelerationX: Float = 0f,
    val accelerationY: Float = 0f,
    val predictedX: Float = boundingBox.centerX(),
    val predictedY: Float = boundingBox.centerY(),
    val motionPattern: MotionPattern = MotionPattern.STATIC,
    val age: Float = -1f,
    val isSmiling: Boolean = false,
    val headPoseYaw: Float = 0f,
    val headPosePitch: Float = 0f,
    val headPoseRoll: Float = 0f,
    val occlusionLevel: Float = 0f,
    val faceDepth: Float = 0f,
    val predictedZ: Float = faceDepth,
    val velocityZ: Float = 0f,
    val meshConfidence: Float = confidence,
    val gazeConfidence: Float = listOfNotNull(leftEye?.spatialConfidence, rightEye?.spatialConfidence)
        .takeIf { it.isNotEmpty() }
        ?.average()
        ?.toFloat()
        ?: confidence,
    val headRotationMatrix: FloatArray? = null
)

data class PoseLandmarkPoint(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
    val confidence: Float = 1f,
    val velocityX: Float = 0f,
    val velocityY: Float = 0f,
    val velocityZ: Float = 0f,
    val flexionDegrees: Float? = null,
    val angularVelocityDegPerSec: Float = 0f,
    val kineticConfidence: Float = confidence
)

data class JointFlexionState(
    val jointName: String,
    val jointIndex: Int,
    val angleDegrees: Float,
    val angularVelocityDegPerSec: Float,
    val confidence: Float
)

data class TrackedPose(
    val trackingId: Int,
    val boundingBox: RectF,
    val landmarks: List<PoseLandmarkPoint>,
    val confidence: Float,
    val velocityX: Float,
    val velocityY: Float,
    val timestampMs: Long,
    val accelerationX: Float = 0f,
    val accelerationY: Float = 0f,
    val motionPattern: MotionPattern = MotionPattern.STATIC,
    val poseType: PoseType = PoseType.UNKNOWN,
    val skeletonValid: Boolean = false,
    val velocityZ: Float = 0f,
    val torsoDepth: Float = 0f,
    val predictedZ: Float = torsoDepth,
    val jointFlexions: Map<String, JointFlexionState> = emptyMap(),
    val kineticEnergy: Float = 0f,
    val torsoStateVector: FloatArray? = null,
    val skeletonStability: Float = confidence,
    val fallbackUsed: Boolean = false
)

enum class PoseType {
    UNKNOWN,
    STANDING,
    SITTING,
    LYING,
    WALKING,
    RUNNING,
    JUMPING,
    DANCING
}

data class SegmentationData(
    val maskWidth: Int,
    val maskHeight: Int,
    val foregroundMask: ByteArray,
    val approximateDepth: FloatArray,
    val foregroundCoverage: Float,
    val subjectDistanceScore: Float,
    val rotationDegrees: Int = 0,
    val multiClassMask: ByteArray? = null,
    val classLabels: List<String> = emptyList(),
    val edgeRefinementApplied: Boolean = false,
    val temporalCoherence: Float = 0f,
    val confidenceMap: FloatArray? = null,
    val classConfidenceChannels: Array<FloatArray>? = null,
    val guideWidth: Int = maskWidth,
    val guideHeight: Int = maskHeight,
    val guideLuma: FloatArray? = null,
    val temporalMotionMean: Float = 0f,
    val temporalHistoryWeightMean: Float = 0f,
    val matteBackend: String = if (edgeRefinementApplied) "EDGE_REFINEMENT" else "FALLBACK"
) {
    fun sampleForegroundConfidence(x: Float, y: Float): Float {
        if (foregroundMask.isEmpty() || maskWidth <= 0 || maskHeight <= 0) return 0f
        val sourcePoint = inverseRotateNormalizedPoint(
            x.coerceIn(0f, 1f),
            y.coerceIn(0f, 1f),
            rotationDegrees
        )
        val ix = (sourcePoint.first * (maskWidth - 1)).roundToInt().coerceIn(0, maskWidth - 1)
        val iy = (sourcePoint.second * (maskHeight - 1)).roundToInt().coerceIn(0, maskHeight - 1)
        val idx = (iy * maskWidth) + ix
        return if (confidenceMap != null && idx < confidenceMap.size) {
            val strictBias = if (strictHighEndEnabled()) 0.04f else 0f
            ((confidenceMap[idx] + strictBias) * ((foregroundMask[idx].toInt() and 0xFF) / 255f))
                .coerceIn(0f, 1f)
        } else {
            ((foregroundMask[idx].toInt() and 0xFF) / 255f).coerceIn(0f, 1f)
        }
    }
    
    fun sampleClassId(x: Float, y: Float): Int {
        if (multiClassMask == null || maskWidth <= 0 || maskHeight <= 0) return 0
        val sourcePoint = inverseRotateNormalizedPoint(
            x.coerceIn(0f, 1f),
            y.coerceIn(0f, 1f),
            rotationDegrees
        )
        val ix = (sourcePoint.first * (maskWidth - 1)).roundToInt().coerceIn(0, maskWidth - 1)
        val iy = (sourcePoint.second * (maskHeight - 1)).roundToInt().coerceIn(0, maskHeight - 1)
        return multiClassMask[(iy * maskWidth) + ix].toInt() and 0xFF
    }
}

data class DepthData(
    val mapWidth: Int,
    val mapHeight: Int,
    val normalizedDepth: FloatArray,
    val rotationDegrees: Int = 0,
    val nearestDepth: Float = 1f,
    val farthestDepth: Float = 0f,
    val confidenceMap: FloatArray? = null,
    val edgeMap: FloatArray? = null,
    val temporallyRefined: Boolean = false,
    val multiFrameFusion: Boolean = false,
    val absoluteDepthMeters: Float? = null,
    val highResolutionDepth: FloatArray? = null,
    val highResolutionWidth: Int = mapWidth,
    val highResolutionHeight: Int = mapHeight,
    val lumaGuide: FloatArray? = null,
    val temporalMotionMean: Float = 0f,
    val temporalHistoryWeightMean: Float = 0f,
    val circleOfConfusionRadius: FloatArray? = null,
    val bokehRenderBackend: String = "CPU_EDGE_AWARE",
    /**
     * CP94 (s7-f03) — true ONLY when this depth field came from the synthetic radial fallback
     * (model unavailable / inference failed), NOT from MiDaS. Defaults false so every existing
     * construction site and the live MiDaS path are unchanged bit-for-bit; consumers may
     * down-weight a synthetic field via DepthTemporalGatePolicy.confidenceScaleFor (gated OFF).
     */
    val syntheticFallback: Boolean = false
) {
    fun sampleNormalizedDepth(x: Float, y: Float): Float {
        if (normalizedDepth.isEmpty() || mapWidth <= 0 || mapHeight <= 0) return 0f
        val sourcePoint = inverseRotateNormalizedPoint(
            x.coerceIn(0f, 1f),
            y.coerceIn(0f, 1f),
            rotationDegrees
        )
        val ix = (sourcePoint.first * (mapWidth - 1)).roundToInt().coerceIn(0, mapWidth - 1)
        val iy = (sourcePoint.second * (mapHeight - 1)).roundToInt().coerceIn(0, mapHeight - 1)
        return normalizedDepth[(iy * mapWidth) + ix].coerceIn(0f, 1f)
    }
    
    fun sampleDepthConfidence(x: Float, y: Float): Float {
        if (confidenceMap == null || mapWidth <= 0 || mapHeight <= 0) return 1f
        val sourcePoint = inverseRotateNormalizedPoint(
            x.coerceIn(0f, 1f),
            y.coerceIn(0f, 1f),
            rotationDegrees
        )
        val ix = (sourcePoint.first * (mapWidth - 1)).roundToInt().coerceIn(0, mapWidth - 1)
        val iy = (sourcePoint.second * (mapHeight - 1)).roundToInt().coerceIn(0, mapHeight - 1)
        return confidenceMap[(iy * mapWidth) + ix].coerceIn(0f, 1f)
    }

    fun averageDepth(bounds: RectF, grid: Int = 5): Float {
        if (normalizedDepth.isEmpty() || bounds.width() <= 0f || bounds.height() <= 0f) return 0f
        val steps = if (strictHighEndEnabled()) max(2, max(grid, 6)) else max(2, grid)
        var sum = 0f
        var weightSum = 0f
        for (yStep in 0 until steps) {
            val ny = bounds.top + bounds.height() * ((yStep + 0.5f) / steps.toFloat())
            for (xStep in 0 until steps) {
                val nx = bounds.left + bounds.width() * ((xStep + 0.5f) / steps.toFloat())
                val depth = sampleNormalizedDepth(nx, ny)
                val weight = if (confidenceMap != null) sampleDepthConfidence(nx, ny) else 1f
                sum += depth * weight
                weightSum += weight
            }
        }
        return if (weightSum == 0f) 0f else (sum / weightSum).coerceIn(0f, 1f)
    }
    
    fun medianDepth(bounds: RectF, grid: Int = 5): Float {
        if (normalizedDepth.isEmpty() || bounds.width() <= 0f || bounds.height() <= 0f) return 0f
        val steps = if (strictHighEndEnabled()) max(2, max(grid, 6)) else max(2, grid)
        val samples = ArrayList<Float>(steps * steps)
        for (yStep in 0 until steps) {
            val ny = bounds.top + bounds.height() * ((yStep + 0.5f) / steps.toFloat())
            for (xStep in 0 until steps) {
                val nx = bounds.left + bounds.width() * ((xStep + 0.5f) / steps.toFloat())
                samples.add(sampleNormalizedDepth(nx, ny))
            }
        }
        samples.sort()
        return if (samples.isEmpty()) 0f else samples[samples.size / 2]
    }
}

data class PrimarySubject(
    val trackingId: Int,
    val bounds: RectF,
    val subjectType: SubjectType,
    val category: SubjectCategory,
    val confidence: Float,
    val focusX: Float,
    val focusY: Float,
    val exposureX: Float,
    val exposureY: Float,
    val depthScore: Float,
    val priority: Float = 1f,
    val attentionScore: Float = 1f,
    val stabilityScore: Float = 1f,
    val saliencyScore: Float = 1f,
    val emotionalScore: Float = 0f
) {
    fun toMeteringTarget(hdrSuggested: Boolean = false, lowLightSuggested: Boolean = false): SubjectMeteringTarget {
        return SubjectMeteringTarget(
            focusX = focusX,
            focusY = focusY,
            subjectBounds = bounds,
            subjectType = subjectType,
            category = category,
            trackingId = trackingId,
            confidence = confidence,
            focusDepthScore = depthScore,
            hdrSuggested = hdrSuggested,
            lowLightSuggested = lowLightSuggested
        )
    }
}

data class SubjectMask(
    val maskWidth: Int,
    val maskHeight: Int,
    val alphaMask: ByteArray,
    val trackingId: Int,
    val category: SubjectCategory,
    val intensity: Float
)

data class SubjectMeteringTarget(
    val focusX: Float,
    val focusY: Float,
    val subjectBounds: RectF,
    val subjectType: SubjectType,
    val category: SubjectCategory,
    val trackingId: Int,
    val confidence: Float,
    val focusDepthScore: Float,
    val hdrSuggested: Boolean,
    val lowLightSuggested: Boolean,
    // AF-SD (F12): per-frame scene contrast in [0,1] (1.0 == neutral). Trailing default keeps every
    // existing construction site bit-for-bit; filled by the gated producer in CameraViewModel.
    val sceneContrast: Float = AIFocusSceneContrastPolicy.NEUTRAL_CONTRAST
)

data class SubjectAwarenessResult(
    val focusPoint: FocusPoint?,
    val primarySubject: PrimarySubject?,
    val subjectMask: SubjectMask?,
    val hdrSuggested: Boolean,
    val lowLightSuggested: Boolean
)

data class FocusPoint(
    val focusX: Float,
    val focusY: Float,
    val confidence: Float,
    val subjectType: SubjectType,
    val trackingId: Int
)

data class EnhancementResult(
    val processedBitmap: Bitmap,
    val estimatedNoiseLevel: Float,
    val hdrBoost: Float,
    val highlightRecovery: Float,
    val shadowRecovery: Float = 0f,
    val sharpnessScore: Float = 0f,
    val colorVibrance: Float = 0f,
    val processingTimeMs: Long = 0L,
    val algorithmsApplied: List<String> = emptyList()
)

data class AICameraFrameResult(
    val timestampMs: Long,
    val faces: List<TrackedFace>,
    val pose: TrackedPose?,
    val segmentation: SegmentationData?,
    val depthData: DepthData?,
    val sceneType: SceneType,
    val sceneClassification: SceneClassification? = null,
    val focusPoint: FocusPoint?,
    val primarySubject: PrimarySubject?,
    val subjectMask: SubjectMask?,
    val hdrSuggested: Boolean,
    val lowLightSuggested: Boolean,
    val enhancementResult: EnhancementResult?,
    val frameGeometry: FrameGeometry? = null,
    val processingLatencyMs: Long = 0L,
    val confidenceScore: Float = 0f,
    val aiFeatureQualityReport: AIFeatureQualityReport = AIFeatureQualityReport(),
    val stabilityMetrics: StabilityMetrics? = null,
    val objects: List<TrackedObject> = emptyList(),
    /**
     * s7-f01 / CP92 — bounded-staleness metadata: age of the OLDEST contributing stage in the
     * fused bundle (0 == all stages fresh this frame). Both default 0L so every existing
     * construction site is unchanged bit-for-bit; populated only on the CP92 flag-ON path.
     */
    val oldestStageAgeFrames: Long = 0L,
    val oldestStageAgeMs: Long = 0L
)

data class StabilityMetrics(
    val focusStability: Float,
    val exposureStability: Float,
    val sceneStability: Float,
    val trackingStability: Float,
    val overallStability: Float
)

data class TrackedObject(
    val trackingId: Int,
    val boundingBox: RectF,
    val confidence: Float,
    val classId: Int,
    val className: String,
    val velocityX: Float,
    val velocityY: Float,
    val timestampMs: Long,
    val accelerationX: Float = 0f,
    val accelerationY: Float = 0f,
    val predictedX: Float = boundingBox.centerX(),
    val predictedY: Float = boundingBox.centerY(),
    val motionPattern: MotionPattern = MotionPattern.STATIC,
    val subjectType: SubjectType = SubjectType.OBJECT,
    val category: SubjectCategory = SubjectCategory.OBJECT,
    val predictedBounds: RectF = boundingBox,
    val trackedFrames: Int = 1,
    val missedFrames: Int = 0,
    val selectionScore: Float = 0f,
    val focusDistanceHint: Float = 0.5f
)

internal data class MotionState(
    var centerX: Float,
    var centerY: Float,
    var velocityX: Float,
    var velocityY: Float,
    var confidence: Float,
    var updatedAtMs: Long,
    var width: Float = 0f,
    var height: Float = 0f,
    var hitStreak: Int = 1,
    var missCount: Int = 0,
    var lastMatchedAtMs: Long = updatedAtMs,
    var accelerationX: Float = 0f,
    var accelerationY: Float = 0f,
    var prevVelocityX: Float = 0f,
    var prevVelocityY: Float = 0f,
    var motionPattern: MotionPattern = MotionPattern.STATIC,
    var kalmanCovarianceP: FloatArray = FloatArray(36) { if (it % 7 == 0) 1f else 0f },
    var kalmanState: FloatArray =
        floatArrayOf(centerX, centerY, velocityX, velocityY, accelerationX, accelerationY)
)

internal data class DetectionMatch<T>(
    val id: Int,
    val item: T
)

internal fun inverseRotateNormalizedPoint(
    x: Float,
    y: Float,
    rotationDegrees: Int
): Pair<Float, Float> {
    return when (((rotationDegrees % 360) + 360) % 360) {
        90 -> y to (1f - x)
        180 -> (1f - x) to (1f - y)
        270 -> (1f - y) to x
        else -> x to y
    }
}
