package com.neoproc.neoproccamx.recording

import java.io.InterruptedIOException
import java.util.concurrent.locks.LockSupport

/**
 * Reserves one logical extent without issuing a multi-GiB syscall beside live frame writes.
 *
 * The existing RAW allocator uses the same 128-MiB physical slices. A separate executor alone
 * cannot prevent one large fallocate from monopolising the filesystem's inode/storage work.
 * Each successful syscall proves only its own contiguous prefix; a later failure never proves
 * the unallocated suffix. Callers retain their reservation safety budget and fatal-error policy.
 */
internal object Npv10RollingExtentAllocator {
    const val MAX_SLICE_BYTES = RecordingPreallocationPolicy.RAW_ROLLING_ALLOCATION_SLICE_BYTES
    // Live extension shares the recording inode with O_DIRECT frame writes. A yield is merely a
    // scheduler hint and can immediately reacquire the filesystem lock for all 80 old slices.
    // Keep the logical 10-GiB extent, but bound each live syscall and leave a real service window
    // between slices. The confirmed prefix is published BEFORE pacing, never after the full extent.
    const val LIVE_SLICE_BYTES = 32L * 1024L * 1024L
    const val LIVE_SERVICE_WINDOW_NS = 1_000_000L

    data class Proof(
        val initialReservedBytes: Long,
        val allocatedBytes: Long,
        val confirmedEndBytes: Long,
        val completedSlices: Int
    )

    fun allocate(
        initialReservedBytes: Long,
        growthBytes: Long,
        allocateRange: (offsetBytes: Long, lengthBytes: Long) -> Unit,
        onSliceConfirmed: (confirmedEndBytes: Long, lengthBytes: Long) -> Unit = { _, _ -> },
        yieldBetweenSlices: () -> Unit = Thread::yield,
        sliceLimitBytes: Long = MAX_SLICE_BYTES
    ): Proof {
        require(initialReservedBytes >= 0L && growthBytes >= 0L) {
            "NPV10 extent bounds must be non-negative"
        }
        require(growthBytes <= RecordingPreallocationPolicy.HARD_MAX_BYTES) {
            "NPV10 allocation must represent one bounded logical extent"
        }
        require(sliceLimitBytes in 1L..MAX_SLICE_BYTES) {
            "NPV10 physical slice limit is outside the bounded allocation contract"
        }
        require(growthBytes == 0L || (growthBytes - 1L) / sliceLimitBytes < Int.MAX_VALUE) {
            "NPV10 physical slice count exceeds its evidence counter"
        }
        // Validate the complete range before the first external write, including 32-bit devices.
        val target = Math.addExact(initialReservedBytes, growthBytes)
        var confirmed = initialReservedBytes
        var slices = 0
        while (confirmed < target) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("NPV10 rolling extent allocation interrupted")
            }
            val length = minOf(target - confirmed, sliceLimitBytes)
            allocateRange(confirmed, length)
            confirmed += length
            slices = Math.incrementExact(slices)
            onSliceConfirmed(confirmed, length)
            if (confirmed < target) yieldBetweenSlices()
        }
        return Proof(initialReservedBytes, growthBytes, confirmed, slices)
    }

    /**
     * Only the reservation worker parks: frame admission, compression and writing never wait here.
     * Recheck the monotonic deadline after spurious wakeups and preserve cancellation's interrupt.
     * This is cooperative I/O scheduling, not a promise that Android will schedule within 1 ms.
     */
    fun paceLiveAllocation(
        nanoTime: () -> Long = System::nanoTime,
        parkNanos: (Long) -> Unit = LockSupport::parkNanos
    ) {
        val began = nanoTime()
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("NPV10 live allocation pacing interrupted")
            }
            val elapsed = (nanoTime() - began).coerceAtLeast(0L)
            if (elapsed >= LIVE_SERVICE_WINDOW_NS) return
            parkNanos(LIVE_SERVICE_WINDOW_NS - elapsed)
        }
    }
}
