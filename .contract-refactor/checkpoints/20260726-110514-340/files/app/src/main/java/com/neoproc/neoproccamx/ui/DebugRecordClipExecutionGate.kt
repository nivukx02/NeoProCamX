package com.neoproc.neoproccamx.ui

/**
 * Owns the one-shot lifecycle of a debug record_clip command.
 *
 * Activity callbacks are intentionally delayed while Camera2 and the packet writer settle. A
 * generation token prevents callbacks from an older command from controlling a newer recording,
 * while the individual claims guarantee that retries cannot issue a second start or stop.
 */
internal class DebugRecordClipExecutionGate {
    private var generation = 0L
    private var active = false
    private var startClaimed = false
    private var observerClaimed = false
    private var stopClaimed = false

    fun beginCommand(): Long {
        generation += 1L
        active = true
        startClaimed = false
        observerClaimed = false
        stopClaimed = false
        return generation
    }

    fun isActive(token: Long): Boolean = active && token == generation

    fun tryClaimStart(token: Long): Boolean {
        if (!isActive(token) || startClaimed) return false
        startClaimed = true
        return true
    }

    fun tryClaimObserver(token: Long): Boolean {
        if (!isActive(token) || observerClaimed) return false
        observerClaimed = true
        return true
    }

    fun tryClaimStop(token: Long): Boolean {
        if (!isActive(token) || stopClaimed) return false
        stopClaimed = true
        return true
    }

    fun completeCommand(token: Long): Boolean {
        if (!isActive(token)) return false
        active = false
        return true
    }

    fun cancelCurrent() {
        active = false
    }
}
