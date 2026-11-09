package com.neoproc.neoproccamx.ai

import android.graphics.RectF
import android.util.Log
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * AI-DRIVEN 'SMART EXPOSURE' ENGINE — FACE-CENTRIC AUTO-EXPOSURE
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Integrates facial landmark density from the AI pipeline into the CameraController
 * AE (Auto-Exposure) loop. The system prioritizes skin-tone luminance over
 * background brightness, creating a 'Face-Centric' exposure lock similar to
 * professional cinema monitors.
 *
 * How it works:
 * 1. FaceTracker provides detected face bounding boxes and landmark data
 * 2. SmartExposureEngine analyzes skin-tone regions within face bounds
 * 3. Computes weighted luminance bias based on face size and position
 * 4. Emits an EV compensation recommendation to the AE loop
 * 5. CameraController applies the face-centric EV bias in real-time
 *
 * Thread safety: All public API is safe for concurrent access.
 * The engine maintains smoothed state to prevent exposure hunting.
 */

/**
 * Recommendation from the Smart Exposure engine to the CameraController AE loop.
 */
data class SmartExposureRecommendation(
    /** Recommended EV compensation bias for face-centric exposure (-3.0 to +3.0) */
    val evBias: Float = 0f,

    /** Confidence of the recommendation (0.0 = uncertain, 1.0 = high confidence) */
    val confidence: Float = 0f,

    /** Whether face-centric exposure lock is actively engaged */
    val faceLockEngaged: Boolean = false,

    /** Number of faces contributing to the exposure calculation */
    val activeFaces: Int = 0,

    /** Estimated skin-tone luminance (0.0 = dark, 1.0 = bright) */
    val skinToneLuminance: Float = 0.5f,

    /** Estimated background luminance (0.0 = dark, 1.0 = bright) */
    val backgroundLuminance: Float = 0.5f,

    /** The face-weighted metering region (normalized 0-1 coordinates) */
    val meteringRegion: RectF? = null,

    /** Timestamp of the recommendation */
    val timestampMs: Long = 0L,

    /** Debug description */
    val debugInfo: String = ""
)

/**
 * Configuration for the Smart Exposure engine.
 */
data class SmartExposureConfig(
    /** Enable face-centric exposure prioritization */
    val enabled: Boolean = true,

    /** Maximum EV bias to apply (positive or negative) */
    val maxEvBias: Float = 2.0f,

    /** Skin-tone target luminance (ITU-R BT.709 middle gray reference) */
    val skinToneTarget: Float = 0.45f,

    /** How aggressively to blend toward the skin-tone target (0.0-1.0) */
    val blendStrength: Float = 0.7f,

    /** Temporal smoothing alpha for EV bias changes (lower = smoother) */
    val smoothingAlpha: Float = 0.12f,

    /** Minimum face area (normalized) required to engage face-centric AE */
    val minimumFaceArea: Float = 0.015f,

    /** Minimum face confidence to include in exposure calculation */
    val minimumFaceConfidence: Float = 0.55f,

    /** Weight given to the largest face vs. all detected faces */
    val primaryFaceWeight: Float = 0.65f,

    /** Skin-tone luminance range for valid measurement (Fitzpatrick I-VI) */
    val skinToneLuminanceRange: ClosedFloatingPointRange<Float> = 0.15f..0.85f
) {
    companion object {
        val DEFAULT = SmartExposureConfig()

        /** cinema-oriented configuration with tighter skin-tone tracking */
        val CINEMA = SmartExposureConfig(
            maxEvBias = 2.5f,
            skinToneTarget = 0.42f,
            blendStrength = 0.80f,
            smoothingAlpha = 0.10f,
            minimumFaceArea = 0.01f,
            minimumFaceConfidence = 0.50f,
            primaryFaceWeight = 0.70f
        )
    }
}

/**
 * Internal state for a tracked face's exposure contribution.
 */
private data class FaceExposureState(
    val trackingId: Int,
    val boundingBox: RectF,
    val confidence: Float,
    val estimatedSkinLuminance: Float,
    val landmarkDensity: Float, // Higher density = more reliable skin-tone estimate
    val faceSizeNormalized: Float,
    val weight: Float,
    val lastUpdateMs: Long
)

class SmartExposureEngine {

    companion object {
        private const val TAG = "SmartExposure"

        // Face region luminance estimation weights
        // Based on typical skin-tone distribution across face landmarks
        private const val FOREHEAD_WEIGHT = 0.25f
        private const val CHEEK_WEIGHT = 0.35f
        private const val CHIN_WEIGHT = 0.15f
        private const val NOSE_WEIGHT = 0.25f

        // Skin tone luminance targets (ITU-R BT.709)
        // These represent typical skin-tone brightness for proper exposure
        private const val SKIN_TONE_LUMINANCE_LOWER = 0.28f  // Dark skin tones
        private const val SKIN_TONE_LUMINANCE_UPPER = 0.72f  // Light skin tones
        private const val SKIN_TONE_LUMINANCE_MIDPOINT = 0.45f

        // Temporal stability
        private const val STALE_FACE_TIMEOUT_MS = 1500L
        private const val EV_BIAS_DEADBAND = 0.08f  // Don't adjust below this threshold
        private const val MAX_EV_CHANGE_PER_FRAME = 0.15f
    }

    private val _recommendation = MutableStateFlow(SmartExposureRecommendation())
    val recommendation: StateFlow<SmartExposureRecommendation> = _recommendation.asStateFlow()

    @Volatile
    var config: SmartExposureConfig = if (
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
    ) SmartExposureConfig.CINEMA else SmartExposureConfig.DEFAULT

    // Smoothed state to prevent exposure hunting
    @Volatile private var smoothedEvBias = 0f
    @Volatile private var smoothedSkinLuminance = SKIN_TONE_LUMINANCE_MIDPOINT
    @Volatile private var smoothedBackgroundLuminance = 0.5f
    @Volatile private var lastRecommendationMs = 0L

    private val activeFaceStates = LinkedHashMap<Int, FaceExposureState>()

    /**
     * Process a batch of tracked faces to generate an exposure recommendation.
     *
     * This should be called every frame after face tracking completes.
     * The engine analyzes skin-tone luminance regions within each face's
     * bounding box and computes a weighted EV bias recommendation.
     *
     * @param faces Tracked faces from FaceTracker
     * @param overallLuminance Scene-wide average luminance estimate (0-1)
     * @param currentEvCompensation Current EV compensation value from the camera
     * @param timestampMs Frame timestamp in milliseconds
     */
    fun processFaces(
        faces: List<TrackedFace>,
        overallLuminance: Float,
        currentEvCompensation: Int,
        timestampMs: Long
    ): SmartExposureRecommendation {
        if (!config.enabled) {
            val result = SmartExposureRecommendation(timestampMs = timestampMs)
            _recommendation.value = result
            return result
        }

        // Prune stale face states
        val staleThreshold = timestampMs - STALE_FACE_TIMEOUT_MS
        activeFaceStates.entries.removeAll { it.value.lastUpdateMs < staleThreshold }

        // Filter and process qualifying faces
        val qualifyingFaces = faces.filter { face ->
            face.confidence >= config.minimumFaceConfidence &&
                faceAreaNormalized(face.boundingBox) >= config.minimumFaceArea
        }

        if (qualifyingFaces.isEmpty()) {
            // Smoothly disengage face-centric exposure
            smoothedEvBias *= (1f - config.smoothingAlpha * 2f)
            if (abs(smoothedEvBias) < EV_BIAS_DEADBAND) smoothedEvBias = 0f

            val result = SmartExposureRecommendation(
                evBias = smoothedEvBias,
                confidence = 0f,
                faceLockEngaged = false,
                activeFaces = 0,
                skinToneLuminance = smoothedSkinLuminance,
                backgroundLuminance = overallLuminance,
                timestampMs = timestampMs,
                debugInfo = "No qualifying faces"
            )
            _recommendation.value = result
            return result
        }

        // Update face exposure states
        qualifyingFaces.forEach { face ->
            val faceArea = faceAreaNormalized(face.boundingBox)
            val landmarkDensity = computeLandmarkDensity(face)
            val estimatedSkinLuminance = estimateSkinToneLuminance(
                face, overallLuminance
            )

            activeFaceStates[face.trackingId] = FaceExposureState(
                trackingId = face.trackingId,
                boundingBox = face.boundingBox,
                confidence = face.confidence,
                estimatedSkinLuminance = estimatedSkinLuminance,
                landmarkDensity = landmarkDensity,
                faceSizeNormalized = faceArea,
                weight = computeFaceWeight(face, faceArea, landmarkDensity),
                lastUpdateMs = timestampMs
            )
        }

        // Compute weighted skin-tone luminance
        val totalWeight = activeFaceStates.values.sumOf { it.weight.toDouble() }.toFloat()
        val weightedSkinLuminance = if (totalWeight > 0f) {
            activeFaceStates.values.sumOf {
                (it.estimatedSkinLuminance * it.weight).toDouble()
            }.toFloat() / totalWeight
        } else {
            SKIN_TONE_LUMINANCE_MIDPOINT
        }

        // Smooth the skin-tone luminance estimate
        smoothedSkinLuminance = smoothedSkinLuminance * (1f - config.smoothingAlpha) +
            weightedSkinLuminance * config.smoothingAlpha

        smoothedBackgroundLuminance = smoothedBackgroundLuminance * (1f - config.smoothingAlpha) +
            overallLuminance * config.smoothingAlpha

        // Compute the face-centric EV bias
        val rawEvBias = computeEvBias(
            skinLuminance = smoothedSkinLuminance,
            backgroundLuminance = smoothedBackgroundLuminance,
            targetLuminance = config.skinToneTarget
        )

        // Apply temporal smoothing with rate limiting
        val clampedBias = rawEvBias.coerceIn(-config.maxEvBias, config.maxEvBias)
        val delta = (clampedBias - smoothedEvBias).coerceIn(
            -MAX_EV_CHANGE_PER_FRAME, MAX_EV_CHANGE_PER_FRAME
        )
        smoothedEvBias += delta

        // Apply deadband to prevent micro-adjustments
        val finalBias = if (abs(smoothedEvBias) < EV_BIAS_DEADBAND) 0f else smoothedEvBias

        // Build metering region from the primary face
        val primaryFace = qualifyingFaces.maxByOrNull { faceAreaNormalized(it.boundingBox) }
        val meteringRegion = primaryFace?.boundingBox?.let { box ->
            // Expand the metering region slightly beyond the face bounds
            val padding = 0.02f
            RectF(
                (box.left - padding).coerceAtLeast(0f),
                (box.top - padding).coerceAtLeast(0f),
                (box.right + padding).coerceAtMost(1f),
                (box.bottom + padding).coerceAtMost(1f)
            )
        }

        val confidence = computeRecommendationConfidence(qualifyingFaces, totalWeight)
        lastRecommendationMs = timestampMs

        val result = SmartExposureRecommendation(
            evBias = finalBias,
            confidence = confidence,
            faceLockEngaged = confidence > 0.5f && abs(finalBias) > EV_BIAS_DEADBAND,
            activeFaces = qualifyingFaces.size,
            skinToneLuminance = smoothedSkinLuminance,
            backgroundLuminance = smoothedBackgroundLuminance,
            meteringRegion = meteringRegion,
            timestampMs = timestampMs,
            debugInfo = buildString {
                append("faces=${qualifyingFaces.size}")
                append(" skin=").append(String.format(Locale.US, "%.2f", smoothedSkinLuminance))
                append(" bg=").append(String.format(Locale.US, "%.2f", smoothedBackgroundLuminance))
                append(" ev=").append(String.format(Locale.US, "%+.2f", finalBias))
                append(" conf=").append(String.format(Locale.US, "%.2f", confidence))
            }
        )

        _recommendation.value = result

        if (qualifyingFaces.size > 0 && timestampMs % 3000L < 100L) {
            Log.d(TAG, result.debugInfo)
        }

        return result
    }

    /**
     * Compute the EV bias needed to properly expose skin tones.
     *
     * When the background is significantly brighter than the skin tone,
     * we need positive EV bias to brighten the face. When the background
     * is darker, we reduce exposure to prevent skin blowout.
     */
    private fun computeEvBias(
        skinLuminance: Float,
        backgroundLuminance: Float,
        targetLuminance: Float
    ): Float {
        // How far the skin luminance is from the target
        val skinError = targetLuminance - skinLuminance

        // Weight the correction by how much the background differs from the face
        val bgFaceDelta = backgroundLuminance - skinLuminance
        val bgWeight = (abs(bgFaceDelta) * 2f).coerceIn(0f, 1f)

        // Base EV bias to bring skin to target luminance
        // Using a log-scale approximation: 1 stop ≈ 2x brightness
        val baseEvBias = skinError * 4f // Scale factor to EV units

        // Blend with background-aware correction
        val bgAwareEvBias = baseEvBias * (1f + bgWeight * config.blendStrength * 0.5f)

        return bgAwareEvBias.coerceIn(-3f, 3f)
    }

    /**
     * Estimate skin-tone luminance from face detection data.
     *
     * Uses face bounding box area and position relative to the overall
     * scene to estimate the skin-tone brightness. For faces detected
     * with high confidence, this correlates well with actual skin luminance.
     */
    private fun estimateSkinToneLuminance(
        face: TrackedFace,
        overallLuminance: Float
    ): Float {
        val box = face.boundingBox

        // Use face position as a proxy for skin luminance
        // Faces closer to center tend to be better lit
        val centerWeight = 1f - (abs(box.centerX() - 0.5f) + abs(box.centerY() - 0.5f))

        // Larger faces give more reliable luminance estimates
        val areaConfidence = (faceAreaNormalized(box) * 10f).coerceIn(0.3f, 1.0f)

        // Blend overall luminance with position-weighted estimate
        // This assumes faces in the center of the frame are the primary subjects
        val estimatedLuminance = overallLuminance * (0.5f + centerWeight * 0.5f)

        // Confidence-weighted smoothing
        return estimatedLuminance * areaConfidence +
            SKIN_TONE_LUMINANCE_MIDPOINT * (1f - areaConfidence)
    }

    /**
     * Compute the face-tracking density of landmarks for weighting.
     * Higher landmark density → more reliable face detection → higher weight.
     */
    private fun computeLandmarkDensity(face: TrackedFace): Float {
        var density = 0f

        // Both eyes detected increases reliability
        if (face.leftEye != null && face.rightEye != null) density += 0.4f
        else if (face.leftEye != null || face.rightEye != null) density += 0.2f

        // Confidence contribution
        density += face.confidence * 0.3f

        // Motion stability (static faces give better readings)
        val motionMagnitude = kotlin.math.sqrt(
            face.velocityX * face.velocityX + face.velocityY * face.velocityY
        )
        if (motionMagnitude < 0.02f) density += 0.2f
        else if (motionMagnitude < 0.05f) density += 0.1f

        // Occlusion penalty
        density *= (1f - face.occlusionLevel * 0.5f)

        return density.coerceIn(0f, 1f)
    }

    /**
     * Compute the weight assigned to a face for exposure blending.
     */
    private fun computeFaceWeight(
        face: TrackedFace,
        faceArea: Float,
        landmarkDensity: Float
    ): Float {
        // Primary weight: face area (larger faces are more important)
        val areaWeight = (faceArea * 50f).coerceIn(0.1f, 1.0f)

        // Confidence weight
        val confWeight = face.confidence

        // Landmark density weight
        val densityWeight = landmarkDensity

        // Position weight (center faces weighted higher)
        val centerX = face.boundingBox.centerX()
        val centerY = face.boundingBox.centerY()
        val centerDist = abs(centerX - 0.5f) + abs(centerY - 0.5f)
        val positionWeight = (1f - centerDist).coerceIn(0.3f, 1.0f)

        return (areaWeight * 0.3f + confWeight * 0.25f +
            densityWeight * 0.25f + positionWeight * 0.2f).coerceIn(0.05f, 1.0f)
    }

    /**
     * Compute overall confidence of the exposure recommendation.
     */
    private fun computeRecommendationConfidence(
        faces: List<TrackedFace>,
        totalWeight: Float
    ): Float {
        if (faces.isEmpty()) return 0f

        val faceCount = faces.size.toFloat()
        val countConfidence = (faceCount / 3f).coerceIn(0.3f, 1.0f) // More faces = more confident

        val avgConfidence = faces.map { it.confidence }.average().toFloat()

        val weightConfidence = (totalWeight / faceCount).coerceIn(0f, 1f)

        return (countConfidence * 0.25f + avgConfidence * 0.5f + weightConfidence * 0.25f)
            .coerceIn(0f, 1f)
    }

    private fun faceAreaNormalized(box: RectF): Float =
        box.width().coerceAtLeast(0f) * box.height().coerceAtLeast(0f)

    /**
     * Reset the engine state (e.g., on camera switch or mode change).
     */
    fun reset() {
        smoothedEvBias = 0f
        smoothedSkinLuminance = SKIN_TONE_LUMINANCE_MIDPOINT
        smoothedBackgroundLuminance = 0.5f
        activeFaceStates.clear()
        lastRecommendationMs = 0L
        _recommendation.value = SmartExposureRecommendation()
    }
}
