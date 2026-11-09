package com.neoproc.neoproccamx.ui

/**
 * Serializes the short commit phase of an asynchronous state restore.
 *
 * Resolution/loading work may run off the main thread, but only the latest issued lease may publish
 * its result. User mutations invalidate the active lease before changing state. The shared monitor
 * gives the two possible orderings deterministic semantics: either the restore commits first and the
 * user mutation wins afterwards, or the mutation invalidates the lease and the restore is rejected.
 */
internal class LatestRestoreOwnership {
    internal class Lease internal constructor(val generation: Long)

    private val monitor = Any()
    private var generation = 0L

    fun begin(): Lease = synchronized(monitor) {
        generation += 1L
        Lease(generation)
    }

    fun invalidate() {
        synchronized(monitor) {
            generation += 1L
        }
    }

    fun isCurrent(lease: Lease): Boolean = synchronized(monitor) {
        generation == lease.generation
    }

    fun commitIfCurrent(
        lease: Lease,
        additionalValidation: () -> Boolean = { true },
        commit: () -> Unit
    ): Boolean = synchronized(monitor) {
        if (generation != lease.generation || !additionalValidation()) {
            false
        } else {
            commit()
            true
        }
    }
}
