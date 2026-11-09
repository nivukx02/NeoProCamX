package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs

internal object PreviewPresentationChangeDetector {

    private const val DEFAULT_SCALE_EPSILON = 0.001f
    private const val DEFAULT_TRANSLATION_EPSILON = 0.25f
    private const val DEFAULT_ROTATION_EPSILON = 0.05f
    private const val STRICT_SCALE_EPSILON = 0.0008f
    private const val STRICT_TRANSLATION_EPSILON = 0.2f
    private const val STRICT_ROTATION_EPSILON = 0.035f

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun effectiveScaleEpsilon(epsilon: Float): Float =
        if (strictHighEndEnabled() && epsilon == DEFAULT_SCALE_EPSILON) {
            STRICT_SCALE_EPSILON
        } else {
            epsilon
        }

    private fun effectiveTranslationEpsilon(translationEpsilon: Float): Float =
        if (strictHighEndEnabled() && translationEpsilon == DEFAULT_TRANSLATION_EPSILON) {
            STRICT_TRANSLATION_EPSILON
        } else {
            translationEpsilon
        }

    private fun effectiveRotationEpsilon(rotationEpsilon: Float): Float =
        if (strictHighEndEnabled() && rotationEpsilon == DEFAULT_ROTATION_EPSILON) {
            STRICT_ROTATION_EPSILON
        } else {
            rotationEpsilon
        }

    fun hasMeaningfulChange(
        previous: PreviewSurfacePresentationState?,
        current: PreviewSurfacePresentationState,
        epsilon: Float = DEFAULT_SCALE_EPSILON,
        translationEpsilon: Float = DEFAULT_TRANSLATION_EPSILON,
        rotationEpsilon: Float = DEFAULT_ROTATION_EPSILON
    ): Boolean {
        val prior = previous ?: return true
        val scaleDeltaEpsilon = effectiveScaleEpsilon(epsilon)
        val translationDeltaEpsilon = effectiveTranslationEpsilon(translationEpsilon)
        val rotationDeltaEpsilon = effectiveRotationEpsilon(rotationEpsilon)
        return abs(prior.scaleX - current.scaleX) > scaleDeltaEpsilon ||
            abs(prior.scaleY - current.scaleY) > scaleDeltaEpsilon ||
            abs(prior.translationX - current.translationX) > translationDeltaEpsilon ||
            abs(prior.translationY - current.translationY) > translationDeltaEpsilon ||
            abs(prior.rotation - current.rotation) > rotationDeltaEpsilon
    }
}
