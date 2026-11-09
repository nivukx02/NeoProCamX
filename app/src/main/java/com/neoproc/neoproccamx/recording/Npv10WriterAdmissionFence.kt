package com.neoproc.neoproccamx.recording

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Keeps the writer alive while native slot admission waits, without holding its commit monitor.
 *
 * Always acquire this fence before the writer monitor. Close takes the exclusive side before
 * entering that monitor, so an admission can finish without waiting on a monitor held by close.
 * Fair admission prevents newly arriving frames from indefinitely delaying an already queued close.
 * The writer remains responsible for its closed/session checks inside its own monitor.
 */
internal class Npv10WriterAdmissionFence {
    private val lifecycle = ReentrantReadWriteLock(true)

    fun <T> withAdmission(action: () -> T): T = lifecycle.readLock().withLock(action)

    fun <T> withClose(action: () -> T): T {
        check(lifecycle.readHoldCount == 0) {
            "NPV10 close cannot upgrade an active admission lease"
        }
        return lifecycle.writeLock().withLock(action)
    }
}
