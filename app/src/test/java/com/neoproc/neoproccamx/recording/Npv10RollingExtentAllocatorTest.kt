package com.neoproc.neoproccamx.recording

import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10RollingExtentAllocatorTest {
    @Test
    fun liveTenGiBExtentUsesSmallerSlicesAndPublishesPrefixBeforePacing() {
        val extent = 10L * 1024L * 1024L * 1024L
        var physicalEnd = extent
        var confirmed = extent
        var paced = 0
        val proof = Npv10RollingExtentAllocator.allocate(extent, extent,
            allocateRange = { offset, length ->
                assertEquals(confirmed, offset)
                assertEquals(Npv10RollingExtentAllocator.LIVE_SLICE_BYTES, length)
                physicalEnd += length
            },
            onSliceConfirmed = { end, _ -> confirmed = end },
            yieldBetweenSlices = {
                assertEquals(physicalEnd, confirmed)
                paced++
            },
            sliceLimitBytes = Npv10RollingExtentAllocator.LIVE_SLICE_BYTES)
        assertEquals(320, proof.completedSlices)
        assertEquals(319, paced)
        assertEquals(extent * 2L, proof.confirmedEndBytes)
    }

    @Test
    fun liveServiceWindowRetriesSpuriousWakeupsWithoutBusySpinning() {
        var now = Long.MAX_VALUE - 500_000L
        val requested = mutableListOf<Long>()
        Npv10RollingExtentAllocator.paceLiveAllocation(
            nanoTime = { now },
            parkNanos = { remaining ->
                requested += remaining
                now += 250_000L
            })
        assertEquals(listOf(1_000_000L, 750_000L, 500_000L, 250_000L), requested)
    }

    @Test
    fun livePacingCancellationRetainsConfirmedPrefixAndInterrupt() {
        val slice = Npv10RollingExtentAllocator.LIVE_SLICE_BYTES
        var confirmed = 4096L
        var allocations = 0
        try {
            assertThrows(InterruptedIOException::class.java) {
                Npv10RollingExtentAllocator.allocate(4096L, 2L * slice,
                    allocateRange = { _, _ -> allocations++ },
                    onSliceConfirmed = { end, _ -> confirmed = end },
                    yieldBetweenSlices = {
                        Npv10RollingExtentAllocator.paceLiveAllocation(
                            nanoTime = { 0L },
                            parkNanos = { Thread.currentThread().interrupt() })
                    },
                    sliceLimitBytes = slice)
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, allocations)
            assertEquals(4096L + slice, confirmed)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun invalidSliceLimitsFailBeforePhysicalOrConfirmedWrites() {
        for (limit in listOf(-1L, 0L, Npv10RollingExtentAllocator.MAX_SLICE_BYTES + 1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                Npv10RollingExtentAllocator.allocate(4096L, 8192L,
                    allocateRange = { _, _ -> error("Invalid slice touched storage") },
                    onSliceConfirmed = { _, _ -> error("Invalid slice published a prefix") },
                    sliceLimitBytes = limit)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Npv10RollingExtentAllocator.allocate(0L, Int.MAX_VALUE.toLong() + 1L,
                allocateRange = { _, _ -> error("Overflowing slice counter touched storage") },
                sliceLimitBytes = 1L)
        }
    }

    @Test
    fun liveStorageBoundedTailIsNotRoundedUpAndFailureKeepsOnlyProvedPrefix() {
        val slice = Npv10RollingExtentAllocator.LIVE_SLICE_BYTES
        val ranges = mutableListOf<Pair<Long, Long>>()
        val proof = Npv10RollingExtentAllocator.allocate(8192L, slice + 4097L,
            allocateRange = { offset, length -> ranges += offset to length },
            yieldBetweenSlices = {}, sliceLimitBytes = slice)
        assertEquals(listOf(8192L to slice, 8192L + slice to 4097L), ranges)
        assertEquals(8192L + slice + 4097L, proof.confirmedEndBytes)
        var confirmed = 4096L
        var calls = 0
        val failure = IOException("live allocation ENOSPC")
        assertSame(failure, assertThrows(IOException::class.java) {
            Npv10RollingExtentAllocator.allocate(confirmed, 3L * slice,
                allocateRange = { _, _ -> if (++calls == 2) throw failure },
                onSliceConfirmed = { end, _ -> confirmed = end },
                yieldBetweenSlices = {}, sliceLimitBytes = slice)
        })
        assertEquals(2, calls)
        assertEquals(4096L + slice, confirmed)
    }

    @Test
    fun logicalTenGiBIsFullyCoveredByEightyContiguousPhysicalSlices() {
        val initial = 10L * 1024L * 1024L * 1024L
        var physicalEnd = initial
        var confirmedEnd = initial
        var allocations = 0
        var yields = 0
        val proof = Npv10RollingExtentAllocator.allocate(
            initial, initial,
            allocateRange = { offset, length ->
                assertEquals(physicalEnd, offset)
                assertEquals(confirmedEnd, offset)
                assertEquals(128L * 1024L * 1024L, length)
                physicalEnd += length
                allocations++
            },
            onSliceConfirmed = { end, length ->
                assertEquals(physicalEnd, end)
                assertEquals(length, end - confirmedEnd)
                confirmedEnd = end
            },
            yieldBetweenSlices = { yields++ }
        )
        assertEquals(initial * 2L, physicalEnd)
        assertEquals(initial * 2L, proof.confirmedEndBytes)
        assertEquals(initial, proof.allocatedBytes)
        assertEquals(80, proof.completedSlices)
        assertEquals(80, allocations)
        assertEquals(79, yields)
    }

    @Test
    fun storageBoundedFinalSliceIsNotRoundedUp() {
        val slice = Npv10RollingExtentAllocator.MAX_SLICE_BYTES
        val ranges = mutableListOf<Pair<Long, Long>>()
        val proof = Npv10RollingExtentAllocator.allocate(8192L, slice + 4097L,
            allocateRange = { offset, size -> ranges += offset to size },
            yieldBetweenSlices = {})
        assertEquals(listOf(8192L to slice, 8192L + slice to 4097L), ranges)
        assertEquals(8192L + slice + 4097L, proof.confirmedEndBytes)
        assertEquals(2, proof.completedSlices)
    }

    @Test
    fun failedSliceDoesNotPublishOrAllocateTheRemainingSuffix() {
        val failure = IOException("injected ENOSPC")
        val slice = Npv10RollingExtentAllocator.MAX_SLICE_BYTES
        var calls = 0
        var confirmed = 4096L
        val actual = assertThrows(IOException::class.java) {
            Npv10RollingExtentAllocator.allocate(4096L, 3L * slice,
                allocateRange = { _, _ -> if (++calls == 2) throw failure },
                onSliceConfirmed = { end, _ -> confirmed = end },
                yieldBetweenSlices = {})
        }
        assertSame(failure, actual)
        assertEquals(2, calls)
        assertEquals(4096L + slice, confirmed)
    }

    @Test
    fun overflowAndNegativeRangesFailBeforeAnyAllocation() {
        var touched = false
        val allocate: (Long, Long) -> Unit = { _, _ -> touched = true }
        assertThrows(ArithmeticException::class.java) {
            Npv10RollingExtentAllocator.allocate(Long.MAX_VALUE, 1L, allocate)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Npv10RollingExtentAllocator.allocate(-1L, 1L, allocate)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Npv10RollingExtentAllocator.allocate(1L, -1L, allocate)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Npv10RollingExtentAllocator.allocate(0L, Long.MAX_VALUE, allocate)
        }
        assertFalse(touched)
    }

    @Test
    fun zeroGrowthDoesNotWriteConfirmOrYield() {
        val proof = Npv10RollingExtentAllocator.allocate(4096L, 0L,
            allocateRange = { _, _ -> error("unexpected write") },
            onSliceConfirmed = { _, _ -> error("unexpected confirmation") },
            yieldBetweenSlices = { error("unexpected yield") })
        assertEquals(4096L, proof.confirmedEndBytes)
        assertEquals(0, proof.completedSlices)
    }

    @Test
    fun interruptionRetainsTheFlagAndNeverClaimsAnUnwrittenSuffix() {
        val slice = Npv10RollingExtentAllocator.MAX_SLICE_BYTES
        var calls = 0
        var confirmed = 4096L
        try {
            assertThrows(InterruptedIOException::class.java) {
                Npv10RollingExtentAllocator.allocate(4096L, slice * 2L,
                    allocateRange = { _, _ -> calls++ },
                    onSliceConfirmed = { end, _ -> confirmed = end },
                    yieldBetweenSlices = { Thread.currentThread().interrupt() })
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, calls)
            assertEquals(4096L + slice, confirmed)
        } finally {
            Thread.interrupted()
        }
    }
}
