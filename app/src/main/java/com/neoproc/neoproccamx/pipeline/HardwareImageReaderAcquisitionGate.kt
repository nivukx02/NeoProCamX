package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Exclusive ownership gate for ImageReader acquisition only.
 *
 * Camera2 may notify the listener while the deferred hardware worker is draining the same reader.
 * ImageReader does not promise concurrent acquisition safety, so those two producers must remain
 * serialized. Dispatch queue polling deliberately does not use this gate: the dispatcher owns its
 * own queue concurrency barrier, and making dequeue contend with the camera callback can delay the
 * next acquisition long enough to exhaust the ImageReader at full-frame 25 FPS.
 */
internal class HardwareImageReaderAcquisitionGate {
    private val lock = ReentrantLock()

    /** Camera callbacks never wait. A null lease means the deferred worker owns acquisition. */
    fun tryEnterListener(): Lease? = if (lock.tryLock()) Lease(lock) else null

    /** Deferred work may wait because it is already outside the Camera2 callback. */
    fun enterDeferred(): Lease {
        lock.lock()
        return Lease(lock)
    }

    /** Lifecycle clear waits for any bounded acquisition/offer section to publish ownership. */
    fun <T> withLifecycleBarrier(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    internal class Lease(
        private val ownerLock: ReentrantLock
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                ownerLock.unlock()
            }
        }
    }
}
