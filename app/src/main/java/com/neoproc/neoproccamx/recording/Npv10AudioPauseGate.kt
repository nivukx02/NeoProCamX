package com.neoproc.neoproccamx.recording

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Acknowledged two-way gate between recording lifecycle calls and the NPV10 audio worker.
 *
 * A request is complete only after the capture thread has crossed the corresponding PCM boundary.
 * This prevents the UI/video state from claiming Paused while the AAC lane is still accepting
 * microphone samples. Termination wakes every waiter so stop, abort, and worker failure cannot
 * strand a lifecycle coroutine behind a dead audio thread.
 */
internal class Npv10AudioPauseGate {
    private val lock = ReentrantLock()
    private val stateChanged = lock.newCondition()

    @Volatile private var active = false
    @Volatile private var pauseRequested = false
    @Volatile private var pauseAcknowledged = false

    fun activate() = lock.withLock {
        check(!active) { "NPV10 audio pause gate is already active" }
        pauseRequested = false
        pauseAcknowledged = false
        active = true
        stateChanged.signalAll()
    }

    fun requestPause(timeoutMs: Long): Boolean = requestAndAwait(
        paused = true,
        timeoutMs = timeoutMs
    )

    fun requestResume(timeoutMs: Long): Boolean = requestAndAwait(
        paused = false,
        timeoutMs = timeoutMs
    )

    /** Hot-path read used by the single capture worker before admitting PCM to AAC. */
    fun shouldDiscardPcm(): Boolean = pauseRequested

    /** Called only by the capture worker after it has crossed a no-more-PCM admission boundary. */
    fun acknowledge(paused: Boolean) = lock.withLock {
        if (!active || pauseRequested != paused) return@withLock
        if (pauseAcknowledged != paused) {
            pauseAcknowledged = paused
            stateChanged.signalAll()
        }
    }

    fun terminate() = lock.withLock {
        active = false
        pauseRequested = false
        pauseAcknowledged = false
        stateChanged.signalAll()
    }

    private fun requestAndAwait(paused: Boolean, timeoutMs: Long): Boolean {
        require(timeoutMs > 0L) { "NPV10 audio pause acknowledgement timeout must be positive" }
        return lock.withLock {
            if (!active) return@withLock false
            pauseRequested = paused
            stateChanged.signalAll()
            var remainingNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (active && pauseAcknowledged != paused && remainingNs > 0L) {
                remainingNs = stateChanged.awaitNanos(remainingNs)
            }
            active && pauseAcknowledged == paused
        }
    }
}
