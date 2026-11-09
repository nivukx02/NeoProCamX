package com.neoproc.neoproccamx.recording

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Establishes a strict happens-before boundary on a recorder's serial storage executor.
 *
 * Cancelling a future does not prove that a native fallocate/delete operation has stopped. The
 * no-op submitted here can run only after every previously submitted storage task has returned, so
 * a competing high-bandwidth recorder never measures or reserves space while the old owner can
 * still create, extend, or delete an extent.
 */
internal object StorageReservationHandoffBarrier {
    fun await(
        executor: ExecutorService,
        timeoutMs: Long,
        ownerLabel: String
    ) {
        require(timeoutMs > 0L) { "Storage reservation handoff timeout must be positive" }
        val barrier = try {
            executor.submit<Unit> { Unit }
        } catch (failure: RejectedExecutionException) {
            throw IllegalStateException(
                "$ownerLabel storage reservation executor rejected the handoff barrier",
                failure
            )
        }
        try {
            barrier.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (failure: InterruptedException) {
            barrier.cancel(true)
            Thread.currentThread().interrupt()
            throw IllegalStateException(
                "$ownerLabel storage reservation handoff was interrupted",
                failure
            )
        } catch (failure: TimeoutException) {
            barrier.cancel(true)
            throw IllegalStateException(
                "$ownerLabel storage reservation handoff exceeded ${timeoutMs}ms",
                failure
            )
        } catch (failure: ExecutionException) {
            throw IllegalStateException(
                "$ownerLabel storage reservation handoff failed",
                failure.cause ?: failure
            )
        }
    }
}
