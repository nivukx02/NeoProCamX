package com.neoproc.neoproccamx.utils

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * ClickDebouncer — Prevents UI spam and accidental double-clicks.
 *
 * Thread-safe debouncing utility that ensures minimum intervals between operations.
 * Supports per-action debouncing with configurable timeouts.
 */
class ClickDebouncer(private val defaultMinIntervalMs: Long = 500L) {

    private val lastClickTimes = ConcurrentHashMap<String, Long>()

    /**
     * Check if a click is allowed for the given action ID.
     * @param actionId Unique identifier for the action (e.g., "capture", "focus")
     * @param minIntervalMs Minimum interval in milliseconds (uses default if not specified)
     * @return true if the click is allowed, false if it should be debounced
     */
    fun allowClick(actionId: String, minIntervalMs: Long = defaultMinIntervalMs): Boolean {
        val currentTime = SystemClock.elapsedRealtime()
        val lastTime = lastClickTimes[actionId] ?: 0L
        
        if (currentTime - lastTime >= minIntervalMs) {
            lastClickTimes[actionId] = currentTime
            return true
        }
        
        return false
    }

    /**
     * Reset the debounce timer for a specific action.
     */
    fun reset(actionId: String) {
        lastClickTimes.remove(actionId)
    }

    /**
     * Reset all debounce timers.
     */
    fun resetAll() {
        lastClickTimes.clear()
    }

    /**
     * Get the time since the last click for a specific action.
     * @return milliseconds since last click, or Long.MAX_VALUE if never clicked
     */
    fun getTimeSinceLastClick(actionId: String): Long {
        val lastTime = lastClickTimes[actionId] ?: return Long.MAX_VALUE
        return SystemClock.elapsedRealtime() - lastTime
    }
}
