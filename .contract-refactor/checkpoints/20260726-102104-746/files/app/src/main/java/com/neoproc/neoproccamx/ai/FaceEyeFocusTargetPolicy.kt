package com.neoproc.neoproccamx.ai

import kotlin.math.abs

internal data class FaceEyeFocusTarget(
    val focusX: Float,
    val focusY: Float,
    val subjectType: SubjectType,
    val confidence: Float
)

internal object FaceEyeFocusTargetPolicy {
    private const val MIN_FACE_CONFIDENCE = 0.08f
    private const val MIN_EYE_SCORE = 0.42f
    private const val FACE_UPPER_ANCHOR_Y = 0.36f
    // AF-SD (F10): a face this strongly yawed (profile/averted) with unusable eyes focuses as HEAD.
    private const val AVERTED_YAW_DEG = 48f
    private const val OPEN_EYE_BONUS = 0.28f
    private const val CLOSED_EYE_PENALTY = 0.42f
    private const val MAX_OCCLUSION_FOR_EYE_LOCK = 0.72f

    fun resolve(
        face: TrackedFace,
        eyePriorityEnabled: Boolean
    ): FaceEyeFocusTarget? {
        if (face.confidence < MIN_FACE_CONFIDENCE || boundsWidth(face) <= 0f || boundsHeight(face) <= 0f) {
            return null
        }

        val faceTarget = stableFaceTarget(face)
        if (!eyePriorityEnabled) return faceTarget
        if (face.occlusionLevel >= MAX_OCCLUSION_FOR_EYE_LOCK) return faceTarget

        val bestEye = listOfNotNull(
            face.leftEye?.let { EyeCandidate(it, score = scoreEye(face, it, true)) },
            face.rightEye?.let { EyeCandidate(it, score = scoreEye(face, it, false)) }
        ).maxByOrNull { it.score }

        return if (bestEye != null && bestEye.score >= MIN_EYE_SCORE) {
            FaceEyeFocusTarget(
                focusX = bestEye.eye.x.coerceIn(0f, 1f),
                focusY = bestEye.eye.y.coerceIn(0f, 1f),
                subjectType = SubjectType.EYE,
                confidence = (
                    face.confidence.coerceIn(0f, 1f) * 0.34f +
                        bestEye.eye.confidence.coerceIn(0f, 1f) * 0.56f +
                        bestEye.score * 0.10f
                    ).coerceIn(0f, 1f)
            )
        } else if (HeadSubjectFlag.enabled && abs(face.headPoseYaw) >= AVERTED_YAW_DEG) {
            // AF-SD (F10): eyes unusable + strongly averted/profile => focus the HEAD, not the FACE.
            // Priority (HEAD < FACE) makes an averted face less likely to win than a frontal one.
            faceTarget.copy(subjectType = SubjectType.HEAD)
        } else {
            faceTarget
        }
    }

    private fun stableFaceTarget(face: TrackedFace): FaceEyeFocusTarget {
        val bounds = face.boundingBox
        val anchorX = face.predictedX
            .takeIf { it.isFinite() && it in 0f..1f }
            ?: boundsCenterX(face)
        val anchorY = (bounds.top + boundsHeight(face) * FACE_UPPER_ANCHOR_Y)
            .coerceIn(bounds.top, bounds.bottom)
        val confidence = (
            face.confidence.coerceIn(0f, 1f) *
                (1f - face.occlusionLevel.coerceIn(0f, 1f) * 0.45f)
            ).coerceIn(0f, 1f)
        return FaceEyeFocusTarget(
            focusX = anchorX.coerceIn(0f, 1f),
            focusY = anchorY.coerceIn(0f, 1f),
            subjectType = SubjectType.FACE,
            confidence = confidence
        )
    }

    private fun scoreEye(face: TrackedFace, eye: EyePoint, isLeftEye: Boolean): Float {
        val confidenceScore = eye.confidence.coerceIn(0f, 1f) * 0.58f
        val openScore = if (eye.isOpen) OPEN_EYE_BONUS else -CLOSED_EYE_PENALTY
        val faceScore = face.confidence.coerceIn(0f, 1f) * 0.14f
        val posePenalty = (
            abs(face.headPoseYaw) / 80f +
                abs(face.headPosePitch) / 65f +
                abs(face.headPoseRoll) / 75f
            ).coerceIn(0f, 0.38f) * 0.22f
        val occlusionPenalty = face.occlusionLevel.coerceIn(0f, 1f) * 0.35f
        val gazePenalty = (abs(eye.gazeX - 0.5f) + abs(eye.gazeY - 0.5f)).coerceIn(0f, 1f) * 0.04f
        val nearSideBonus = nearSidePoseBonus(face.headPoseYaw, isLeftEye)
        val boundsBonus = if (containsNormalized(face, eye.x, eye.y)) 0.04f else -0.12f
        return (
            confidenceScore +
                openScore +
                faceScore +
                nearSideBonus +
                boundsBonus -
                posePenalty -
                occlusionPenalty -
                gazePenalty
            ).coerceIn(0f, 1f)
    }

    private fun nearSidePoseBonus(yawDegrees: Float, isLeftEye: Boolean): Float {
        if (abs(yawDegrees) < 8f) return 0f
        val leftEyeFavored = yawDegrees < 0f
        return if (leftEyeFavored == isLeftEye) 0.04f else -0.02f
    }

    private fun boundsWidth(face: TrackedFace): Float =
        face.boundingBox.right - face.boundingBox.left

    private fun boundsHeight(face: TrackedFace): Float =
        face.boundingBox.bottom - face.boundingBox.top

    private fun boundsCenterX(face: TrackedFace): Float =
        (face.boundingBox.left + face.boundingBox.right) * 0.5f

    private fun containsNormalized(face: TrackedFace, x: Float, y: Float): Boolean =
        x >= face.boundingBox.left &&
            x <= face.boundingBox.right &&
            y >= face.boundingBox.top &&
            y <= face.boundingBox.bottom

    private data class EyeCandidate(
        val eye: EyePoint,
        val score: Float
    )
}
