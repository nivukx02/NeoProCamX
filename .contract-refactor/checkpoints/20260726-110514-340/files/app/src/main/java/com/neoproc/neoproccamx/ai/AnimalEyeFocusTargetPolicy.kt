package com.neoproc.neoproccamx.ai

import kotlin.math.abs

/**
 * AF-SD (F08) — pure, Android-graphics-free policy that maps a tracked animal bounding box + motion
 * into a head/eye-region focus anchor, analogous to [FaceEyeFocusTargetPolicy] for humans.
 *
 * A quadruped's head leads its motion and sits in the upper-front of its silhouette, so the anchor
 * is placed in the upper band of the box, biased toward the leading horizontal edge (chosen by the
 * sign of horizontal velocity, or by the predicted-centre offset when motion is ambiguous). Tall /
 * portrait boxes (a bird or a cat sitting, facing the camera) collapse to an upper-centre anchor.
 *
 * All arithmetic is on the raw left/top/right/bottom floats (never RectF.width()/centerX(), which
 * return 0 under the mockable android.jar), so it is deterministic and unit-testable in isolation.
 */
internal data class AnimalEyeFocusTarget(
    val focusX: Float,
    val focusY: Float,
    val subjectType: SubjectType,
    val confidence: Float
)

internal object AnimalEyeFocusTargetPolicy {
    /** Anchor Y = top + boxHeight * this — the head band near the top of the silhouette. */
    private const val HEAD_UPPER_FRACTION = 0.28f
    /** Horizontal inset from the leading edge for compact / portrait boxes. */
    private const val FRONT_INSET_FRACTION = 0.30f
    /** width/height at or above this => quadruped side profile (head is well toward the leading edge). */
    private const val LANDSCAPE_ASPECT = 1.35f
    /** Tighter inset toward the leading edge for long side-profile bodies. */
    private const val LANDSCAPE_FRONT_INSET = 0.18f
    /** |velocityX| below this => no motion bias; fall back to predicted-centre offset, then box centre. */
    private const val MOTION_EPS = 0.004f
    private const val MIN_CONFIDENCE = 0.05f

    /**
     * @return the head/eye anchor for an animal box, or null when the box is degenerate or the
     *         detection is below [MIN_CONFIDENCE] (the caller then keeps the bbox-centre focus point).
     */
    fun resolve(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        predictedX: Float,
        predictedY: Float,
        velocityX: Float,
        confidence: Float
    ): AnimalEyeFocusTarget? {
        val width = right - left
        val height = bottom - top
        if (width <= 0f || height <= 0f || confidence < MIN_CONFIDENCE) return null

        val centreX = (left + right) * 0.5f
        val landscape = (width / height) >= LANDSCAPE_ASPECT

        // Which end of the box is the "front" (the head)? Prefer motion direction; if motion is
        // ambiguous, use the predicted-centre offset; otherwise no clear front (centre the anchor).
        val frontSign: Int = when {
            abs(velocityX) >= MOTION_EPS -> if (velocityX > 0f) 1 else -1
            predictedX.isFinite() && predictedX in left..right -> {
                val delta = predictedX - centreX
                when {
                    abs(delta) < 1e-6f -> 0
                    delta > 0f -> 1
                    else -> -1
                }
            }
            else -> 0
        }

        val inset = if (landscape) LANDSCAPE_FRONT_INSET else FRONT_INSET_FRACTION
        val anchorX = when {
            frontSign > 0 -> right - width * inset
            frontSign < 0 -> left + width * inset
            else -> centreX
        }
        val anchorY = top + height * HEAD_UPPER_FRACTION

        return AnimalEyeFocusTarget(
            focusX = anchorX.coerceIn(0f, 1f),
            focusY = anchorY.coerceIn(0f, 1f),
            subjectType = SubjectType.EYE,
            confidence = confidence.coerceIn(0f, 1f)
        )
    }
}
