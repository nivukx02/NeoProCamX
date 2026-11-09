package com.neoproc.neoproccamx.camera

import android.os.Handler
import android.os.SystemClock
import android.util.Log

/**
 * PreviewHealthMonitor — Preview freeze detection and recovery orchestration.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * EXTRACTED FROM CameraController — Phase 2 God-Class Decomposition
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Tracks YUV frame and CaptureResult arrivals to detect preview freezes.
 * Runs a periodic watchdog timer on the camera background thread and
 * delegates recovery to a provided callback.
 *
 * Thread safety: All timing reads are from monotonic clocks. State is
 * accessed exclusively from the camera background thread (bgHandler).
 */
class PreviewHealthMonitor(
    private val tag: String = "PreviewHealthMonitor",
    private val watchdogIntervalMs: Long = 5000L,
    private val maxConsecutiveRecoveries: Int = 3
) {

    // ── Tracking State ───────────────────────────────────────────────────────
    @Volatile var lastYuvFrameArrivalMs: Long = 0L
        private set
    @Volatile var lastCaptureResultArrivalMs: Long = 0L
        private set
    @Volatile var hasReceivedInitialFrame: Boolean = false
        private set
    @Volatile var hasReceivedInitialCaptureResult: Boolean = false
        private set
    @Volatile var consecutiveRecoveries: Int = 0
        private set

    // #17 fix: @Volatile so the guarded self-repost below and stop() (which can be invoked off the camera
    // thread, e.g. from closeCamera on Dispatchers.Default) observe each other's writes. Any tick whose
    // identity no longer matches this field skips its repost and dies, so a superseded/stopped watchdog
    // self-terminates on its next tick and orphans cannot accumulate.
    @Volatile
    private var watchdogRunnable: Runnable? = null

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Start the watchdog timer on the given handler.
     * @param handler Background handler to run the watchdog on.
     * @param isRecoveryInFlight True while an earlier request/session recovery is still settling.
     * @param onFreezeDetected Called when a freeze is detected. Return true if recovery was initiated.
     * @param onFatalFreeze Called when max recovery attempts are exhausted.
     */
    fun start(
        handler: Handler,
        isRecoveryInFlight: () -> Boolean = { false },
        onFreezeDetected: () -> Boolean,
        onFatalFreeze: () -> Unit
    ) {
        reset()
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                // #17 fix: bail without reposting if this runnable was superseded by a newer start() or
                // cancelled by stop() (identity no longer registered), so orphan ticks self-terminate.
                if (watchdogRunnable !== this) return
                checkFrameArrival(isRecoveryInFlight, onFreezeDetected, onFatalFreeze)
                // Re-check after checkFrameArrival: onFatalFreeze may have called stop() and nulled the field.
                if (watchdogRunnable === this) {
                    handler.postDelayed(this, watchdogIntervalMs / 2)
                }
            }
        }
        watchdogRunnable = runnable
        handler.postDelayed(runnable, watchdogIntervalMs)
    }

    /**
     * Stop the watchdog timer.
     */
    fun stop(handler: Handler?) {
        // #17 fix: null the identity FIRST so an in-flight run() observes watchdogRunnable !== this and skips
        // its repost, THEN remove any already-queued post. Order matters because run() may execute
        // concurrently with stop() when stop() is called off the camera thread.
        val runnable = watchdogRunnable
        watchdogRunnable = null
        handler?.let { h -> runnable?.let { h.removeCallbacks(it) } }
    }

    /**
     * Record a YUV frame arrival. Called from ImageReader callback.
     */
    fun trackYuvFrame() {
        lastYuvFrameArrivalMs = SystemClock.uptimeMillis()
        if (!hasReceivedInitialFrame) {
            hasReceivedInitialFrame = true
            Log.d(tag, "Initial YUV frame received")
        }
        consecutiveRecoveries = 0
    }

    /**
     * Record a CaptureResult arrival. Called from CaptureCallback.
     */
    fun trackCaptureResult() {
        lastCaptureResultArrivalMs = SystemClock.uptimeMillis()
        hasReceivedInitialCaptureResult = true
        consecutiveRecoveries = 0
    }

    /**
     * Check if there has been recent preview activity within [withinMs].
     */
    fun hasRecentActivity(withinMs: Long = 750L): Boolean {
        return PreviewWatchdogPolicy.hasRecentActivity(
            hasReceivedYuvFrame = hasReceivedInitialFrame,
            hasReceivedCaptureResult = hasReceivedInitialCaptureResult,
            lastYuvFrameArrivalMs = lastYuvFrameArrivalMs,
            lastCaptureResultArrivalMs = lastCaptureResultArrivalMs,
            nowMs = SystemClock.uptimeMillis(),
            recentWindowMs = withinMs
        )
    }

    /**
     * Reset all tracking state. Call before rebuilding a session
     * when you need to verify that new frames arrive.
     */
    fun reset() {
        lastYuvFrameArrivalMs = 0L
        lastCaptureResultArrivalMs = 0L
        hasReceivedInitialFrame = false
        hasReceivedInitialCaptureResult = false
        consecutiveRecoveries = 0
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun checkFrameArrival(
        isRecoveryInFlight: () -> Boolean,
        onFreezeDetected: () -> Boolean,
        onFatalFreeze: () -> Unit
    ) {
        val uptimeMs = SystemClock.uptimeMillis()
        val shouldRecover = PreviewWatchdogPolicy.shouldRecover(
            hasReceivedYuvFrame = hasReceivedInitialFrame,
            hasReceivedCaptureResult = hasReceivedInitialCaptureResult,
            lastYuvFrameArrivalMs = lastYuvFrameArrivalMs,
            lastCaptureResultArrivalMs = lastCaptureResultArrivalMs,
            nowMs = uptimeMs,
            thresholdMs = watchdogIntervalMs
        )

        if (shouldRecover) {
            val lastActivity = maxOf(lastYuvFrameArrivalMs, lastCaptureResultArrivalMs)
            val timeSinceMs = uptimeMs - lastActivity
            Log.w(tag, "Preview freeze detected: no activity for ${timeSinceMs}ms")

            if (isRecoveryInFlight()) {
                Log.d(tag, "Preview recovery still in flight; deferring duplicate watchdog action")
                return
            }
            if (consecutiveRecoveries >= maxConsecutiveRecoveries) {
                Log.e(tag, "FATAL: Recovery limit reached ($consecutiveRecoveries)")
                onFatalFreeze()
                return
            }

            if (onFreezeDetected()) {
                consecutiveRecoveries++
                Log.i(tag, "Session recovery initiated (attempt $consecutiveRecoveries)")
            }
        }
    }
}
