package com.neoproc.neoproccamx.ai

import kotlin.math.sqrt

/**
 * s7-f05 / CP95 — pure face<->body handoff + body-centroid gating/smoothing policy.
 *
 * Gated behind [PoseHandoffFlag]; never invoked while the flag is OFF, so the live BODY
 * contribution is unchanged by default.
 *
 * Design constraints (match FaceEyeFocusTargetPolicy):
 *  - PURE & STATELESS: previous anchor passed in explicitly; same inputs -> same output.
 *  - NO RectF method calls (centerX/width return 0 under the mockable android.jar); only the
 *    landmark floats and the supplied bbox-center floats are used.
 *  - Confidence-gated centroid: keypoints below [KEYPOINT_VISIBILITY_FLOOR] are excluded; if too
 *    few survive, falls back to the supplied predicted bbox center (no drag from noisy limbs).
 *  - Continuity-bounded handoff: when the face anchor is weak/absent, transitions toward the
 *    body centroid but never jumps further than [MAX_HANDOFF_STEP] per call (no AF snap).
 *  - Jitter smoothing: EMA blend with the previous anchor.
 */
internal data class PoseHandoffAnchor(
    val focusX: Float,
    val focusY: Float,
    val confidence: Float,
    val usedBodyFallback: Boolean
)

internal object PoseFaceHandoffPolicy {
    /** Face confidence at/above which the face anchor fully owns the target (no body handoff). */
    const val FACE_OWN_CONFIDENCE = 0.55f
    /** Face confidence below which the body anchor fully owns the target. */
    const val FACE_DROP_CONFIDENCE = 0.20f
    /** Per-call maximum positional move of the blended anchor (continuity bound). */
    const val MAX_HANDOFF_STEP = 0.06f
    /** Visibility floor for a keypoint to contribute to the body centroid. */
    const val KEYPOINT_VISIBILITY_FLOOR = 0.30f
    /** Minimum number of gated keypoints required to trust the keypoint centroid. */
    const val MIN_GATED_KEYPOINTS = 4
    /** EMA smoothing alpha for the blended anchor (lower = smoother). */
    const val SMOOTHING_ALPHA = 0.35f

    /**
     * @param landmarks pose landmarks (normalized x/y in 0..1, visibility 0..1).
     * @param bboxCenterX predicted body bbox center X (already produced by FocusEngine).
     * @param bboxCenterY predicted body bbox center Y.
     * @param bodyConfidence pose.confidence (0..1).
     * @param faceAnchorX the current face focus anchor X, or null if no usable face this frame.
     * @param faceAnchorY the current face focus anchor Y, or null.
     * @param faceConfidence the current best face confidence (0..1), 0 if none.
     * @param previousAnchor last emitted anchor for continuity/smoothing, or null on first frame.
     */
    fun resolveBodyAnchor(
        landmarks: List<PoseLandmarkPoint>,
        bboxCenterX: Float,
        bboxCenterY: Float,
        bodyConfidence: Float,
        faceAnchorX: Float?,
        faceAnchorY: Float?,
        faceConfidence: Float,
        previousAnchor: PoseHandoffAnchor?
    ): PoseHandoffAnchor {
        val gatedCentroid = confidenceGatedCentroid(landmarks)
        val bodyX = gatedCentroid?.first ?: bboxCenterX.coerceIn(0f, 1f)
        val bodyY = gatedCentroid?.second ?: bboxCenterY.coerceIn(0f, 1f)
        val usedBodyFallback = gatedCentroid == null

        // Face->body cross-fade weight: 1 = pure face, 0 = pure body.
        val faceWeight = when {
            faceAnchorX == null || faceAnchorY == null -> 0f
            faceConfidence >= FACE_OWN_CONFIDENCE -> 1f
            faceConfidence <= FACE_DROP_CONFIDENCE -> 0f
            else -> ((faceConfidence - FACE_DROP_CONFIDENCE) /
                (FACE_OWN_CONFIDENCE - FACE_DROP_CONFIDENCE)).coerceIn(0f, 1f)
        }

        val targetX = if (faceAnchorX != null) faceAnchorX * faceWeight + bodyX * (1f - faceWeight) else bodyX
        val targetY = if (faceAnchorY != null) faceAnchorY * faceWeight + bodyY * (1f - faceWeight) else bodyY

        // Continuity bound + EMA smoothing against the previous emitted anchor.
        val prevX = previousAnchor?.focusX ?: targetX
        val prevY = previousAnchor?.focusY ?: targetY
        val smoothedX = prevX + (targetX - prevX) * SMOOTHING_ALPHA
        val smoothedY = prevY + (targetY - prevY) * SMOOTHING_ALPHA
        val (stepX, stepY) = clampStep(prevX, prevY, smoothedX, smoothedY, MAX_HANDOFF_STEP)

        val confidence = (faceConfidence * faceWeight + bodyConfidence * (1f - faceWeight))
            .coerceIn(0f, 1f)
        return PoseHandoffAnchor(
            focusX = stepX.coerceIn(0f, 1f),
            focusY = stepY.coerceIn(0f, 1f),
            confidence = confidence,
            usedBodyFallback = usedBodyFallback
        )
    }

    private fun confidenceGatedCentroid(landmarks: List<PoseLandmarkPoint>): Pair<Float, Float>? {
        if (landmarks.isEmpty()) return null
        var sumX = 0f
        var sumY = 0f
        var n = 0
        for (lm in landmarks) {
            if (lm.visibility >= KEYPOINT_VISIBILITY_FLOOR) {
                sumX += lm.x
                sumY += lm.y
                n++
            }
        }
        if (n < MIN_GATED_KEYPOINTS) return null
        return (sumX / n).coerceIn(0f, 1f) to (sumY / n).coerceIn(0f, 1f)
    }

    private fun clampStep(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        maxStep: Float
    ): Pair<Float, Float> {
        val dx = toX - fromX
        val dy = toY - fromY
        val dist = sqrt(dx * dx + dy * dy)
        if (dist <= maxStep || dist <= 1.0e-6f) return toX to toY
        val scale = maxStep / dist
        return (fromX + dx * scale) to (fromY + dy * scale)
    }
}
