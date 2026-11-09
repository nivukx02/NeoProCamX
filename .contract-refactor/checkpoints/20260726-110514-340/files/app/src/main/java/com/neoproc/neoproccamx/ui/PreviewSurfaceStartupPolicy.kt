package com.neoproc.neoproccamx.ui

internal object PreviewSurfaceStartupPolicy {
    // PERF (startup): 0 with the Surface-identity latch in MainActivity — there is no surfaceChanged
    // storm left to debounce, so the camera opens on the next message-loop tick after the first valid
    // Surface (openFromStablePreviewSurface still re-validates identity + validity before opening).
    const val STABLE_SURFACE_OPEN_DELAY_MS: Long = 0L
    const val CACHED_SURFACE_PROBE_DELAY_MS: Long = 180L
    const val CACHED_SURFACE_PROBE_MAX_ATTEMPTS: Int = 60

    fun canUseCachedSurfaceForOpen(
        surfaceCallbackDelivered: Boolean,
        surfaceValid: Boolean,
        viewAttached: Boolean = false,
        viewHasSize: Boolean = false
    ): Boolean =
        surfaceValid &&
            (
                surfaceCallbackDelivered ||
                    canRecoverCachedSurfaceAfterLayout(
                        surfaceCallbackDelivered = surfaceCallbackDelivered,
                        surfaceValid = surfaceValid,
                        viewAttached = viewAttached,
                        viewHasSize = viewHasSize
                    )
                )

    fun canProbeCachedSurfaceAfterLayout(
        surfaceCallbackDelivered: Boolean,
        surfaceValid: Boolean,
        viewAttached: Boolean,
        viewHasSize: Boolean
    ): Boolean = canRecoverCachedSurfaceAfterLayout(
        surfaceCallbackDelivered = surfaceCallbackDelivered,
        surfaceValid = surfaceValid,
        viewAttached = viewAttached,
        viewHasSize = viewHasSize
    )

    private fun canRecoverCachedSurfaceAfterLayout(
        surfaceCallbackDelivered: Boolean,
        surfaceValid: Boolean,
        viewAttached: Boolean,
        viewHasSize: Boolean
    ): Boolean = !surfaceCallbackDelivered && surfaceValid && viewAttached && viewHasSize

    fun shouldScheduleOpenFromSurfaceCallback(
        surfaceValid: Boolean,
        hasCameraPermission: Boolean
    ): Boolean = surfaceValid && hasCameraPermission

    fun shouldScheduleCachedSurfaceProbe(
        attemptsCompleted: Int,
        probeAlreadyScheduled: Boolean
    ): Boolean = !probeAlreadyScheduled && attemptsCompleted < CACHED_SURFACE_PROBE_MAX_ATTEMPTS

    fun canUseCallbackSurfaceWhenHolderCacheMissing(
        surfaceCallbackDelivered: Boolean,
        callbackSurfaceValid: Boolean
    ): Boolean = surfaceCallbackDelivered && callbackSurfaceValid
}
