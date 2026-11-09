package com.neoproc.neoproccamx.camera

/**
 * Policy for determining retry attempts
 */
class CameraRetryPolicy {

    /** Maximum retry attempts */
    var maxRetryAttempts: Int = 3

    /** Base delay between retries (ms) */
    var baseRetryDelayMs: Long = 1000

    /**
     * Determines if another retry attempt should be made
     */
    fun shouldRetryAttempt(attemptCount: Int): Boolean {
        return attemptCount < maxRetryAttempts
    }

    /**
     * Calculates exponential backoff delay
     */
    fun exponentialBackoffMs(attemptCount: Int): Long {
        return baseRetryDelayMs * (1L shl (attemptCount - 1)) // 1, 2, 4, 8... seconds
    }
}
