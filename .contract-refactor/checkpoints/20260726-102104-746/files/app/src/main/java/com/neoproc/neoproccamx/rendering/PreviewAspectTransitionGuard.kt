package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.ui.SensorCropEngine
import kotlin.math.abs

internal object PreviewAspectTransitionGuard {
    const val ASPECT_TRANSITION_HOLD_MS = 220L
    private const val ASPECT_MATCH_EPSILON = 0.03f

    fun resolveDisplayContentAspectOverride(
        requestedContentAspect: Float?,
        previousPresentedContentAspect: Float? = null,
        transitionElapsedMs: Long? = null,
        geometryOutputAspect: Float?,
        geometrySensorAspect: Float?,
        streamWidth: Int,
        streamHeight: Int,
        nativeCompositorActive: Boolean
    ): Float? {
        val requested = requestedContentAspect?.takeValidAspect() ?: return null
        if (nativeCompositorActive) return requested

        val previousPresented = previousPresentedContentAspect?.takeValidAspect()
        val elapsedMs = transitionElapsedMs?.coerceAtLeast(0L)
        if (
            previousPresented != null &&
            elapsedMs != null &&
            elapsedMs < ASPECT_TRANSITION_HOLD_MS &&
            !aspectsMatch(previousPresented, requested)
        ) {
            return previousPresented
        }

        return requested
    }

    private fun Float.takeValidAspect(): Float? =
        takeIf { isFinite() && it > 0f }

    private fun aspectsMatch(first: Float, second: Float): Boolean {
        val firstNormalized = SensorCropEngine.normalizeLongShortRatio(first)
        val secondNormalized = SensorCropEngine.normalizeLongShortRatio(second)
        return abs(firstNormalized - secondNormalized) <= ASPECT_MATCH_EPSILON
    }

}
