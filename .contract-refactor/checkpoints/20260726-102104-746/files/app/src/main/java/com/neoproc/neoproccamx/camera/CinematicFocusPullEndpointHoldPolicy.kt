package com.neoproc.neoproccamx.camera

/**
 * CP-QV20 — pure endpoint-hold decision for the cinema focus-pull.
 *
 * When a triggered pull COMPLETES on a given preview frame (the control stepped to B and then
 * deactivated), the manual-lens distance must be **persisted** at that endpoint so the lens holds B
 * instead of reverting to autofocus and re-acquiring (a visible focus snap-back). This isolates that
 * one decision as a pure function so it is fully JVM-unit-testable independent of the (non-JVM)
 * [CameraController] request pipeline.
 *
 * Contract:
 *  - Only a *just-completed* pull persists a new manual-focus distance:
 *    `wasPulling && !isPullingNow && steppedOrNull != null` and the endpoint is a valid diopter
 *    (finite, non-negative). In that case return the stepped endpoint.
 *  - In every other case (still pulling, no pull, no step, invalid endpoint) return the incoming
 *    [manFocusDist] unchanged, so nothing is persisted and the caller's behavior is untouched.
 *
 * This function performs no gating itself; the caller invokes it strictly inside the
 * [CinematicFocusPullFlag]-ON branch, so with the flag OFF it is never reached.
 */
internal object CinematicFocusPullEndpointHoldPolicy {

    /**
     * @param wasPulling whether the control reported an active pull BEFORE stepping this frame.
     * @param isPullingNow whether the control still reports an active pull AFTER stepping this frame.
     * @param steppedOrNull the diopter the control produced this frame (null when it produced none).
     * @param manFocusDist the current persisted manual-focus distance (the fallback / no-change value).
     * @return the manual-focus distance to persist: the pull endpoint on the completing frame,
     *         otherwise [manFocusDist] unchanged.
     */
    fun resolveHold(
        wasPulling: Boolean,
        isPullingNow: Boolean,
        steppedOrNull: Float?,
        manFocusDist: Float
    ): Float {
        val justCompleted = wasPulling && !isPullingNow
        if (!justCompleted) return manFocusDist
        val endpoint = steppedOrNull ?: return manFocusDist
        if (!endpoint.isFinite() || endpoint < 0f) return manFocusDist
        return endpoint
    }
}
