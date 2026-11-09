package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.locks.ReentrantLock

/**
 * Serializes acquisition and ownership transfer for one ImageReader consumer path.
 *
 * ImageReader can invoke its normal listener while a lifecycle owner is synchronously draining the
 * final buffered images. Merely tracking both calls as "in flight" is not enough: two consumers can
 * acquire adjacent images and publish them to a recorder in the opposite order. This fair gate keeps
 * acquireNextImage() and the corresponding sink hand-off in one ordered critical section. It never
 * drops, coalesces, retimestamps, or changes image data.
 */
internal class OrderedImageReaderAcquisitionGate {
    private val lock = ReentrantLock(true)

    fun <T> runExclusive(operation: () -> T): T {
        lock.lock()
        return try {
            operation()
        } finally {
            lock.unlock()
        }
    }
}
