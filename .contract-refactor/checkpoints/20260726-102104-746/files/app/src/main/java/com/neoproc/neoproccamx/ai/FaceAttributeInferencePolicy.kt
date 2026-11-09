package com.neoproc.neoproccamx.ai

object FaceAttributeInferencePolicy {
    const val UNKNOWN_AGE = -1

    fun resolve(
        hasDedicatedAttributeModel: Boolean,
        aspectRatio: Float,
        smileProbability: Float,
        occlusion: AIFaceDetectionEngine.OcclusionInfo,
        pose: AIFaceDetectionEngine.FacePose
    ): AIFaceDetectionEngine.FaceAttributes {
        val emotions = emotionDistribution(smileProbability, occlusion.overallOcclusion)
        val dominantEmotion = emotions.maxByOrNull { it.value }?.key ?: AIFaceDetectionEngine.Emotion.NEUTRAL

        return AIFaceDetectionEngine.FaceAttributes(
            estimatedAge = UNKNOWN_AGE,
            ageConfidence = 0f,
            gender = AIFaceDetectionEngine.Gender.UNKNOWN,
            genderConfidence = 0f,
            emotions = emotions,
            dominantEmotion = dominantEmotion,
            pose = pose,
            occlusion = occlusion
        )
    }

    private fun emotionDistribution(
        smileProbability: Float,
        occlusion: Float
    ): Map<AIFaceDetectionEngine.Emotion, Float> {
        val visibleWeight = (1f - occlusion).coerceIn(0.25f, 1f)
        val happy = (smileProbability.coerceIn(0f, 1f) * visibleWeight).coerceIn(0f, 0.82f)
        val surprised = (0.05f + smileProbability.coerceIn(0f, 1f) * 0.04f).coerceAtMost(0.12f)
        val neutral = (1f - happy - surprised).coerceAtLeast(0.08f)
        val residual = (1f - happy - surprised - neutral).coerceAtLeast(0f)
        val lowConfidenceEmotion = residual / 5f

        val raw = linkedMapOf(
            AIFaceDetectionEngine.Emotion.NEUTRAL to neutral,
            AIFaceDetectionEngine.Emotion.HAPPY to happy,
            AIFaceDetectionEngine.Emotion.SURPRISED to surprised,
            AIFaceDetectionEngine.Emotion.SAD to lowConfidenceEmotion,
            AIFaceDetectionEngine.Emotion.ANGRY to lowConfidenceEmotion,
            AIFaceDetectionEngine.Emotion.FEARFUL to lowConfidenceEmotion,
            AIFaceDetectionEngine.Emotion.DISGUSTED to lowConfidenceEmotion,
            AIFaceDetectionEngine.Emotion.CONTEMPT to lowConfidenceEmotion
        )
        val sum = raw.values.sum().takeIf { it > 0f } ?: 1f
        return raw.mapValues { (_, value) -> value / sum }
    }
}
