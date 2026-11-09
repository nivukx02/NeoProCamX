package com.neoproc.neoproccamx.pipeline

internal object AsyncDirectIODrainPolicy {
    const val DEFAULT_DRAIN_TIMEOUT_MS: Long = 15_000L
    const val DRAIN_POLL_MS: Long = 10L

    fun shouldWaitForDrain(
        queuedWrites: Int,
        activeWrites: Int,
        elapsedMs: Long,
        timeoutMs: Long = DEFAULT_DRAIN_TIMEOUT_MS
    ): Boolean {
        val hasPendingWork = queuedWrites > 0 || activeWrites > 0
        return hasPendingWork && elapsedMs < timeoutMs
    }
}
