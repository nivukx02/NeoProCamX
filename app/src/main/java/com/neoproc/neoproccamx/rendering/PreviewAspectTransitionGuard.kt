package com.neoproc.neoproccamx.rendering

internal object PreviewAspectTransitionGuard {
    const val ASPECT_TRANSITION_HOLD_MS = 220L

    fun resolveDisplayContentAspectOverride(
        requestedContentAspect: Float?,
        @Suppress("UNUSED_PARAMETER")
        previousPresentedContentAspect: Float? = null,
        @Suppress("UNUSED_PARAMETER")
        transitionElapsedMs: Long? = null,
        @Suppress("UNUSED_PARAMETER")
        geometryOutputAspect: Float?,
        @Suppress("UNUSED_PARAMETER")
        geometrySensorAspect: Float?,
        @Suppress("UNUSED_PARAMETER")
        streamWidth: Int,
        @Suppress("UNUSED_PARAMETER")
        streamHeight: Int,
        @Suppress("UNUSED_PARAMETER")
        nativeCompositorActive: Boolean
    ): Float? {
        // Aspect changes no longer replace the Camera2 transport Surface. Commit the requested
        // presentation immediately; retaining the previous aspect for a timed handoff could leave
        // direct preview permanently stale when no later layout/frame callback occurred.
        return requestedContentAspect?.takeValidAspect()
    }

    private fun Float.takeValidAspect(): Float? =
        takeIf { isFinite() && it > 0f }

}
