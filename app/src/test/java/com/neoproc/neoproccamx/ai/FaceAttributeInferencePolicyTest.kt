package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class FaceAttributeInferencePolicyTest {

    @Test
    fun withoutDedicatedModel_doesNotInventAgeOrGender() {
        val attributes = FaceAttributeInferencePolicy.resolve(
            hasDedicatedAttributeModel = false,
            aspectRatio = 1.42f,
            smileProbability = 0.2f,
            occlusion = AIFaceDetectionEngine.OcclusionInfo(
                leftEyeOccluded = false,
                rightEyeOccluded = false,
                noseOccluded = false,
                mouthOccluded = false,
                overallOcclusion = 0f
            ),
            pose = AIFaceDetectionEngine.FacePose(0f, 0f, 0f)
        )

        assertEquals(-1, attributes.estimatedAge)
        assertEquals(0f, attributes.ageConfidence, 0f)
        assertEquals(AIFaceDetectionEngine.Gender.UNKNOWN, attributes.gender)
        assertEquals(0f, attributes.genderConfidence, 0f)
    }

    @Test
    fun emotionDistributionIsNormalizedAndReflectsSmileEvidence() {
        val attributes = FaceAttributeInferencePolicy.resolve(
            hasDedicatedAttributeModel = false,
            aspectRatio = 1.2f,
            smileProbability = 0.72f,
            occlusion = AIFaceDetectionEngine.OcclusionInfo(
                leftEyeOccluded = false,
                rightEyeOccluded = false,
                noseOccluded = false,
                mouthOccluded = false,
                overallOcclusion = 0f
            ),
            pose = AIFaceDetectionEngine.FacePose(0f, 0f, 0f)
        )

        val sum = attributes.emotions.values.sum()
        assertEquals(1f, sum, 1e-5f)
        assertEquals(AIFaceDetectionEngine.Emotion.HAPPY, attributes.dominantEmotion)
    }
}
