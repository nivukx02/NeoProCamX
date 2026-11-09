package com.neoproc.neoproccamx.niv99

/**
 * Fixed-capacity FIFO used to move frame-ordered tuning work off the Camera2 callback thread.
 *
 * Entries are copied into preallocated slots, so the producer never retains a Camera2 object and
 * performs no steady-state allocation. The queue deliberately refuses overflow instead of
 * replacing or conflating identities: silently skipping one identity would advance NIV99 temporal
 * state against the wrong sensor frame.
 */
internal class Niv99OrderedFrameIdentityQueue(capacity: Int) {
    private val slots = Array(capacity.coerceIn(MINIMUM_CAPACITY, MAXIMUM_CAPACITY)) {
        Niv99FrameIdentity()
    }
    private var readIndex = 0
    private var writeIndex = 0
    private var count = 0

    @Synchronized
    fun offer(identity: Niv99FrameIdentity): Boolean {
        if (!identity.isComplete() || count == slots.size) return false
        slots[writeIndex].copyFrom(identity)
        writeIndex = (writeIndex + 1) % slots.size
        count += 1
        return true
    }

    @Synchronized
    fun poll(output: Niv99FrameIdentity): Boolean {
        if (count == 0) return false
        output.copyFrom(slots[readIndex])
        readIndex = (readIndex + 1) % slots.size
        count -= 1
        return true
    }

    @Synchronized
    fun size(): Int = count

    @Synchronized
    fun clear(): Int {
        val removed = count
        readIndex = 0
        writeIndex = 0
        count = 0
        return removed
    }

    private companion object {
        const val MINIMUM_CAPACITY = 2
        const val MAXIMUM_CAPACITY = 128
    }
}
