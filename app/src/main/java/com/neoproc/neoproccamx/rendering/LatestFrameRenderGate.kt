package com.neoproc.neoproccamx.rendering

/**
 * Coalesces latest-image render notifications without losing a request that arrives during a draw.
 *
 * SurfaceTexture.updateTexImage() already latches the newest queued buffer. Posting one render task
 * per callback therefore adds latency without preserving any additional frame. The owner calls
 * [onDispatchStarted] immediately before drawing so a concurrent state change can schedule one
 * follow-up task while the current draw is in progress.
 */
internal class LatestFrameRenderGate {
    private var dispatchQueued = false

    @Synchronized
    fun requestDispatch(): Boolean {
        if (dispatchQueued) return false
        dispatchQueued = true
        return true
    }

    @Synchronized
    fun onDispatchStarted() {
        dispatchQueued = false
    }

    @Synchronized
    fun cancelPendingDispatch() {
        dispatchQueued = false
    }
}
