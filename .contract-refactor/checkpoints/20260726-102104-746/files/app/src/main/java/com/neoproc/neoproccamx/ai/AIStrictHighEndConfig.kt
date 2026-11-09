package com.neoproc.neoproccamx.ai

import com.neoproc.neoproccamx.core.ImaxStrictProfile

/**
 * Strict configuration for extreme high-end AI processing capabilities.
 * Defines maximum uncompromising performance and quality parameters for AI engines.
 */
object AIStrictHighEndConfig {
    /** Maximum dimension for strict AI processing. */
    const val maxDimension: Int = ImaxStrictProfile.AI_RESOLUTION_RECORDING

    /** Quality boost multiplier for enhanced processing */
    const val qualityBoost: Float = 2.0f

    /** Target preview FPS for AI processing */
    const val targetPreviewFps: Int = 60

    /** LUT size for strict preview processing. */
    const val lutSizeForPreview: Int = ImaxStrictProfile.RUNTIME_PREVIEW_LUT_CUBE_SIZE

    /** High precision LUT size (Maximum fidelity). */
    const val highPrecisionLutSize: Int = ImaxStrictProfile.RUNTIME_PREVIEW_LUT_CUBE_SIZE

    /** Standard LUT size */
    const val standardLutSize: Int = 33

    /** Preview FPS floor */
    const val previewFpsFloor: Int = 30

    /** Maximum preview FPS */
    const val maxPreviewFps: Int = 120

    /** Cinema HDR frame count for processing (Optimized for speed) */
    const val cinemaHdrFrameCount: Int = 8

    /** Snap to cinema FPS flag */
    const val snapToCinemaFps: Boolean = true

    /** AI max dimension for strict recording processing. */
    const val aiMaxDimension: Int = ImaxStrictProfile.AI_RESOLUTION_RECORDING

    /** AI max dimension for strict preview processing. */
    const val aiPreviewMaxDimension: Int = ImaxStrictProfile.AI_RESOLUTION_PREVIEW

    /** Segmentation temporal weight (Ultra stable) */
    const val segmentationTemporalWeight: Float = 0.95f

    /** Segmentation edge kernel size (Maximum precision edge detail) */
    const val segmentationEdgeKernel: Int = 7

    /** Segmentation confidence threshold */
    const val segmentationConfidenceThreshold: Float = 0.8f

    /** Max tracked faces (Huge crowd tracking) */
    const val maxTrackedFaces: Int = 50

    /** Face detection confidence threshold */
    const val faceDetectionConfidence: Float = 0.85f

    /** Face presence confidence threshold */
    const val facePresenceConfidence: Float = 0.75f

    /** Face tracking confidence threshold (Ultra sticky) */
    const val faceTrackingConfidence: Float = 0.95f

    /** Max tracked poses */
    const val maxTrackedPoses: Int = 20

    /** Pose detection confidence threshold */
    const val poseDetectionConfidence: Float = 0.85f

    /** Pose presence confidence threshold */
    const val posePresenceConfidence: Float = 0.75f

    /** Pose tracking confidence threshold */
    const val poseTrackingConfidence: Float = 0.95f

    /** Get the number of threads for TensorFlow Lite (Reserve 1 core for OS/UI to ensure ZERO LAG) */
    fun tfliteThreadCount(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)

    // uppercase variants for previous usages:
    const val MAX_TRACKED_FACES = maxTrackedFaces
    const val FACE_DETECTION_CONFIDENCE = faceDetectionConfidence
    const val FACE_PRESENCE_CONFIDENCE = facePresenceConfidence
    const val FACE_TRACKING_CONFIDENCE = faceTrackingConfidence
    const val MAX_TRACKED_POSES = maxTrackedPoses
    const val POSE_DETECTION_CONFIDENCE = poseDetectionConfidence
    const val POSE_PRESENCE_CONFIDENCE = posePresenceConfidence
    const val POSE_TRACKING_CONFIDENCE = poseTrackingConfidence
    const val SEGMENTATION_TEMPORAL_WEIGHT = segmentationTemporalWeight
    const val SEGMENTATION_EDGE_KERNEL = segmentationEdgeKernel
    const val SEGMENTATION_CONFIDENCE_THRESHOLD = segmentationConfidenceThreshold
}
