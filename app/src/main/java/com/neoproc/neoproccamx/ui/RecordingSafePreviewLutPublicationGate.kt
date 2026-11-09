package com.neoproc.neoproccamx.ui

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Retains only the newest resolved preview LUT while a recorder owns render-profile identity.
 *
 * A nullable LUT is a real publication (disable), so it is wrapped instead of using null as both a
 * value and an empty marker. Float payloads are copied at defer and claim boundaries to prevent a
 * background resolver or native upload from mutating the retained publication.
 */
internal class RecordingSafePreviewLutPublicationGate {
    internal class Publication private constructor(
        val sequence: Long,
        private val data: FloatArray?,
        private val size: Int,
        private val hasLut: Boolean
    ) {
        fun copyForApply(): Pair<FloatArray, Int>? =
            if (hasLut) requireNotNull(data).copyOf() to size else null

        companion object {
            fun snapshot(sequence: Long, lut: Pair<FloatArray, Int>?): Publication = Publication(
                sequence = sequence,
                data = lut?.first?.copyOf(),
                size = lut?.second ?: 0,
                hasLut = lut != null
            )
        }
    }

    private val deferred = AtomicReference<Publication?>(null)
    private val closed = AtomicBoolean(false)

    /** Latest-wins replacement; an older asynchronous LUT can never replay after a newer result. */
    fun deferLatest(sequence: Long, lut: Pair<FloatArray, Int>?): Boolean {
        require(sequence > 0L) { "Preview LUT publication sequence must be positive" }
        if (closed.get()) return false
        val incoming = Publication.snapshot(sequence, lut)
        while (!closed.get()) {
            val current = deferred.get()
            if (current != null && current.sequence > sequence) return false
            if (deferred.compareAndSet(current, incoming)) {
                if (!closed.get()) return true
                deferred.compareAndSet(incoming, null)
                return false
            }
        }
        return false
    }

    /** Claims the retained publication exactly once after every recording mutation boundary closes. */
    fun claimLatestIfSafe(blocked: Boolean, latestSequence: Long): Publication? {
        if (blocked || closed.get()) return null
        while (true) {
            val current = deferred.get() ?: return null
            when {
                current.sequence < latestSequence -> {
                    deferred.compareAndSet(current, null)
                }
                current.sequence > latestSequence -> return null
                deferred.compareAndSet(current, null) -> return current
            }
        }
    }

    /** Removes a deferred predecessor after its current-or-newer direct publication wins admission. */
    fun discardThrough(sequence: Long) {
        while (true) {
            val current = deferred.get() ?: return
            if (current.sequence > sequence) return
            if (deferred.compareAndSet(current, null)) return
        }
    }

    fun hasDeferredPublication(): Boolean = deferred.get() != null

    fun close() {
        closed.set(true)
        deferred.set(null)
    }
}
