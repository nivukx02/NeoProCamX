package com.neoproc.neoproccamx.ui.overlays

import com.neoproc.neoproccamx.ai.DetectedFace

internal enum class SubjectAssistCueType {
    FACE,
    EYE
}

internal data class SubjectAssistCue(
    val type: SubjectAssistCueType,
    val normalizedX: Float,
    val normalizedY: Float
)

internal object SubjectAssistOverlayPolicy {
    fun resolve(
        faces: List<DetectedFace>,
        faceDetectionEnabled: Boolean,
        eyeAutofocusEnabled: Boolean
    ): List<SubjectAssistCue> {
        if (!faceDetectionEnabled && !eyeAutofocusEnabled) return emptyList()
        return faces.flatMap { face ->
            val cues = mutableListOf<SubjectAssistCue>()
            val leftEye = face.leftEye
            val rightEye = face.rightEye
            if (faceDetectionEnabled) {
                cues += SubjectAssistCue(
                    type = SubjectAssistCueType.FACE,
                    normalizedX = ((face.bounds.left + face.bounds.right) * 0.5f).coerceIn(0f, 1f),
                    normalizedY = ((face.bounds.top + face.bounds.bottom) * 0.5f).coerceIn(0f, 1f)
                )
            }
            if (eyeAutofocusEnabled && leftEye != null && rightEye != null) {
                cues += SubjectAssistCue(
                    type = SubjectAssistCueType.EYE,
                    normalizedX = ((leftEye.x + rightEye.x) * 0.5f).coerceIn(0f, 1f),
                    normalizedY = ((leftEye.y + rightEye.y) * 0.5f).coerceIn(0f, 1f)
                )
            }
            cues
        }
    }
}
