package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.ImaxStrictProfile
import java.util.Locale

data class SubjectDetailEnhancementRequest(
    val profile: VideoDetailProfile,
    val maskAvailable: Boolean,
    val maskTrackingId: Int?,
    val maskConfidence: Float,
    val motionScore: Float,
    val noiseFloor: Float,
    val skinToneConfidence: Float
)

data class SubjectDetailEnhancementPlan(
    val profile: VideoDetailProfile,
    val subjectDetailRequested: Boolean,
    val subjectDetailApplied: Boolean,
    val subjectMaskPresent: Boolean,
    val additiveDetailGain: Float,
    val maskFeather: Float,
    val haloGuard: Float,
    val textureGuard: Float,
    val naturalZeroSharpening: Boolean,
    val signature: String
) {
    fun toMetadata(): Map<String, String> = linkedMapOf(
        "selectedDetailProfile" to profile.name,
        "subjectDetailRequested" to subjectDetailRequested.toString(),
        "subjectDetailApplied" to subjectDetailApplied.toString(),
        "subjectMaskPresent" to subjectMaskPresent.toString(),
        "subjectDetailGain" to formatSubjectDetailValue(additiveDetailGain),
        "naturalZeroSharpening" to naturalZeroSharpening.toString(),
        "subjectDetailSignature" to signature
    )
}

internal object SubjectDetailEnhancementPolicy {
    const val MAX_ADDITIVE_DETAIL_GAIN = ImaxStrictProfile.MAX_ADDITIVE_DETAIL_GAIN
    private const val MIN_RELIABLE_MASK_CONFIDENCE = ImaxStrictProfile.MIN_MASK_CONFIDENCE

    fun resolve(request: SubjectDetailEnhancementRequest): SubjectDetailEnhancementPlan {
        val requested = request.profile == VideoDetailProfile.FOCUSED_DETAIL
        val confidence = request.maskConfidence.coerceIn(0f, 1f)
        val motion = request.motionScore.coerceIn(0f, 1f)
        val noise = request.noiseFloor.coerceIn(0f, 1f)
        val skin = request.skinToneConfidence.coerceIn(0f, 1f)
        val reliableMask = request.maskAvailable &&
            request.maskTrackingId != null &&
            confidence >= MIN_RELIABLE_MASK_CONFIDENCE
        val validMask = requested && reliableMask
        val suppression = (motion * 0.12f + noise * 0.16f + skin * 0.14f).coerceIn(0f, 1f)
        val gain = if (validMask) {
            (MAX_ADDITIVE_DETAIL_GAIN * confidence * (1f - suppression * 0.38f))
                .coerceIn(0f, MAX_ADDITIVE_DETAIL_GAIN)
        } else {
            0f
        }
        val feather = (0.14f + motion * 0.12f + noise * 0.16f).coerceIn(0.14f, 0.46f)
        val haloGuard = (0.68f + suppression * 0.08f).coerceIn(0.68f, 0.995f)
        val textureGuard = (0.64f + skin * 0.06f + noise * 0.04f + motion * 0.03f)
            .coerceIn(0.64f, 0.995f)
        val naturalZero = request.profile == VideoDetailProfile.NATURAL_DETAIL
        return SubjectDetailEnhancementPlan(
            profile = request.profile,
            subjectDetailRequested = requested,
            subjectDetailApplied = validMask && gain > 0f,
            subjectMaskPresent = reliableMask,
            additiveDetailGain = gain,
            maskFeather = feather,
            haloGuard = haloGuard,
            textureGuard = textureGuard,
            naturalZeroSharpening = naturalZero,
            signature = listOf(
                "profile=${request.profile.name}",
                "requested=$requested",
                "mask=$reliableMask",
                "gain=${formatSubjectDetailValue(gain)}",
                "halo=${formatSubjectDetailValue(haloGuard)}",
                "texture=${formatSubjectDetailValue(textureGuard)}"
            ).joinToString(separator = "|")
        )
    }
}

private fun formatSubjectDetailValue(value: Float): String =
    String.format(Locale.US, "%.6f", value.coerceIn(0f, 2f))
