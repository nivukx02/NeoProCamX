package com.neoproc.neoproccamx.rendering

import java.io.Closeable
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Linearizable ownership boundary between Camera2 session replacement and ordinary hardware-buffer
 * imports. A submission keeps a read lease through native completion and telemetry publication;
 * cleanup/reset takes the write lease, so a retired completion cannot mutate the replacement
 * session's counters or race destruction of imported native images.
 */
internal class VulkanCameraImportSessionGate {
    internal class SubmissionLease private constructor(
        private val readLock: ReentrantReadWriteLock.ReadLock,
        private val ownerThread: Thread
    ) : Closeable {
        private var closed = false

        override fun close() {
            check(Thread.currentThread() === ownerThread) {
                "Camera import submission lease must be released by its acquiring thread"
            }
            if (!closed) {
                closed = true
                readLock.unlock()
            }
        }

        companion object {
            fun create(readLock: ReentrantReadWriteLock.ReadLock): SubmissionLease =
                SubmissionLease(readLock, Thread.currentThread())
        }
    }

    private val boundary = ReentrantReadWriteLock(true)
    @Volatile private var generation = NO_GENERATION

    fun beginSession(sessionGeneration: Long): Boolean {
        if (sessionGeneration < 0L) return false
        val write = boundary.writeLock()
        write.lock()
        return try {
            if (generation > sessionGeneration) {
                false
            } else {
                generation = sessionGeneration
                true
            }
        } finally {
            write.unlock()
        }
    }

    fun acquireSubmission(expectedGeneration: Long): SubmissionLease? {
        if (expectedGeneration < 0L) return null
        val read = boundary.readLock()
        read.lock()
        if (generation != expectedGeneration) {
            read.unlock()
            return null
        }
        return SubmissionLease.create(read)
    }

    fun <T> withExclusiveMutation(block: () -> T): T {
        val write = boundary.writeLock()
        write.lock()
        return try {
            block()
        } finally {
            write.unlock()
        }
    }

    fun activeGeneration(): Long = generation

    private companion object {
        const val NO_GENERATION = -1L
    }
}
