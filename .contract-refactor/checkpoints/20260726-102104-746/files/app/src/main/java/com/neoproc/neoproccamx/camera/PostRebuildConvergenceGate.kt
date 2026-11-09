package com.neoproc.neoproccamx.camera

/**
 * RC5 AWB-washout deferral gate.
 *
 * A same-sensor rebuild (SDR<->HDR toggle, resolution change, record-start) restarts HAL 3A on the
 * SAME scene, but the last pre-rebuild capture result is still cached. If the AWB lock re-engages
 * off that STALE "converged" signal on the first fresh-session frame — while fresh AE is still
 * overshooting — it latches the washed-out white point for the whole clip.
 *
 * This gate defers the lock until a GENUINE post-rebuild convergence: the AWB must be seen
 * un-converged at least once on the fresh session (proving the stale converged state was flushed)
 * and then converge again. A bounded [maxDeferFrames] safety cap guarantees the lock is ALWAYS
 * eventually permitted, so the gate can only ever DELAY the lock by a short bounded window — never
 * prevent it, never widen drift.
 *
 * Disarmed (or never armed) it is a pure identity: [lockPermitted] is always true and [update] is a
 * no-op returning false. Drive [update] once per capture result from the camera thread.
 */
internal class PostRebuildConvergenceGate(
    private val maxDeferFrames: Int = DEFAULT_MAX_DEFER_FRAMES
) {
    /** True whenever the AWB lock may engage. False only while a fresh convergence is pending. */
    var lockPermitted: Boolean = true
        private set

    private var awaiting = false
    private var sawFreshUnconverged = false
    private var framesSinceRebuild = 0

    /** Arm for a same-sensor rebuild: suppress the lock until genuine re-convergence. */
    fun armForRebuild() {
        awaiting = true
        sawFreshUnconverged = false
        framesSinceRebuild = 0
        lockPermitted = false
    }

    /** Disarm (flag OFF / different sensor): restore identity behaviour immediately. */
    fun disarm() {
        awaiting = false
        sawFreshUnconverged = false
        framesSinceRebuild = 0
        lockPermitted = true
    }

    /** @return true if [lockPermitted] changed this frame (caller should rebuild the request). */
    fun update(awbConvergedRaw: Boolean): Boolean {
        if (!awaiting) return false
        framesSinceRebuild++
        if (!awbConvergedRaw) sawFreshUnconverged = true
        val genuineReconvergence = sawFreshUnconverged && awbConvergedRaw
        val safetyCapReached = framesSinceRebuild >= maxDeferFrames
        if (genuineReconvergence || safetyCapReached) {
            awaiting = false
            lockPermitted = true
            return true
        }
        return false
    }

    companion object {
        /** ~1s at 30fps: bounds the deferral so the lock is always eventually permitted. */
        const val DEFAULT_MAX_DEFER_FRAMES = 30
    }
}
