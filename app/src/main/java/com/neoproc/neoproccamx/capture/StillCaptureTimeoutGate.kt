package com.neoproc.neoproccamx.capture

/**
 * Serializes replacement and execution of still-capture timeouts.
 *
 * Cancellation alone is insufficient once a coroutine has resumed from delay: an old processing or
 * bracket timeout can race the replacement timeout for the same capture id. A lease makes replacement
 * last-write-wins, while [runIfCurrent] keeps the final ownership check and timeout action atomic.
 */
internal class StillCaptureTimeoutGate {
    @ConsistentCopyVisibility
    data class Lease internal constructor(val generation: Long)

    private val lock = Any()
    private var generation = 0L

    fun replace(): Lease = synchronized(lock) {
        generation = nextGeneration(generation)
        Lease(generation)
    }

    fun invalidate() = synchronized(lock) {
        generation = nextGeneration(generation)
    }

    fun runIfCurrent(lease: Lease, action: () -> Unit): Boolean = synchronized(lock) {
        if (lease.generation != generation) return@synchronized false
        action()
        true
    }

    private fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L
}
