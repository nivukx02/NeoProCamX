package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the resident-frame cap decision for develop pipeline overlap ([RecordDevelopPipelineOverlapPolicy]). */
class RecordDevelopPipelineOverlapPolicyTest {

    private val frame = 152L * 1024 * 1024 // a full-sensor uncompressed develop frame.

    @Test
    fun overlapIsProductionDefaultButRemainsReversible() {
        assertTrue(RecordDevelopPipelineOverlapFlag.enabled)
        try {
            RecordDevelopPipelineOverlapFlag.set(false)
            assertFalse(RecordDevelopPipelineOverlapFlag.enabled)
        } finally {
            RecordDevelopPipelineOverlapFlag.set(true)
        }
    }

    @Test
    fun effectiveBudgetUsesTheSharedHeapGuardInsteadOfTheLegacyOneThirdClamp() {
        val maxHeap = 256L * 1024L * 1024L
        val expected = maxHeap * RecordDevelopPipelineOverlapPolicy.HEAP_NUMERATOR /
            RecordDevelopPipelineOverlapPolicy.HEAP_DENOMINATOR

        assertEquals(
            expected,
            RecordDevelopPipelineOverlapPolicy.effectiveMemoryBudget(
                requestedBudgetBytes = 384L * 1024L * 1024L,
                maxMemoryBytes = maxHeap
            )
        )
        assertTrue(expected > maxHeap / 3L)
        assertEquals(
            64L * 1024L * 1024L,
            RecordDevelopPipelineOverlapPolicy.effectiveMemoryBudget(
                requestedBudgetBytes = 64L * 1024L * 1024L,
                maxMemoryBytes = maxHeap
            )
        )
    }

    @Test
    fun nonThrowingAdmissionDistinguishesCpuFitFromGpuParityPeak() {
        val budget = 174_483_046L // 65% of a 256 MiB ART heap.
        val temporalReserve = 24_000_000L

        assertTrue(
            RecordDevelopPipelineOverlapPolicy.canFitOneFrame(
                residentBytesPerFrame = 105_000_000L,
                fixedReserveBytes = temporalReserve,
                memoryBudgetBytes = budget
            )
        )
        assertFalse(
            RecordDevelopPipelineOverlapPolicy.canFitOneFrame(
                residentBytesPerFrame = 200_000_000L,
                fixedReserveBytes = temporalReserve,
                memoryBudgetBytes = budget
            )
        )
    }

    @Test
    fun flagOffKeepsConservativeCap() {
        assertEquals(
            1,
            RecordDevelopPipelineOverlapPolicy.maxResidentFrames(
                residentBytesPerFrame = frame,
                conservativeMaxFrames = 1,
                maxMemoryBytes = 1024L * 1024 * 1024,
                flagEnabled = false
            )
        )
    }

    @Test
    fun flagOnRaisesCapToWhatTheHeapFits() {
        // 534 MiB heap * 65% = 347 MiB ceiling; 347 / 152 = 2 frames.
        val cap = RecordDevelopPipelineOverlapPolicy.maxResidentFrames(
            residentBytesPerFrame = frame,
            conservativeMaxFrames = 1,
            maxMemoryBytes = 534L * 1024 * 1024,
            flagEnabled = true
        )
        assertEquals(2, cap)
    }

    @Test
    fun neverBelowConservativeFloorAndOptionalExpansionNeverAboveMaxDepth() {
        // Tiny frames could fit dozens, but the read-ahead cap holds it at MAX_DEPTH.
        val many = RecordDevelopPipelineOverlapPolicy.maxResidentFrames(
            residentBytesPerFrame = 1L * 1024 * 1024,
            conservativeMaxFrames = 1,
            maxMemoryBytes = 1024L * 1024 * 1024,
            flagEnabled = true
        )
        assertEquals(RecordDevelopPipelineOverlapPolicy.MAX_DEPTH, many)

        // Existing small-frame pipelines may already have a safe window above MAX_DEPTH.
        // Turning the optional overlap flag on must never shrink that established window.
        val existingWideWindow = RecordDevelopPipelineOverlapPolicy.maxResidentFrames(
            residentBytesPerFrame = 1L * 1024 * 1024,
            conservativeMaxFrames = 8,
            maxMemoryBytes = 1024L * 1024 * 1024,
            memoryBudgetBytes = 16L * 1024 * 1024,
            flagEnabled = true
        )
        assertEquals(8, existingWideWindow)
    }

    @Test
    fun frameCapacityReservesTemporalStorageBeforeSizingWindow() {
        val reserve = 24L * 1024 * 1024
        assertEquals(
            2,
            RecordDevelopPipelineOverlapPolicy.frameCapacity(
                residentBytesPerFrame = frame,
                fixedReserveBytes = reserve,
                memoryBudgetBytes = reserve + frame * 2L
            )
        )
    }

    @Test
    fun budgetBelowOneCompleteFrameFailsClosed() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RecordDevelopPipelineOverlapPolicy.frameCapacity(
                residentBytesPerFrame = frame,
                fixedReserveBytes = 16L * 1024 * 1024,
                memoryBudgetBytes = frame
            )
        }
        assertTrue(error.message.orEmpty().contains("cannot hold one resident frame"))

        assertThrows(IllegalArgumentException::class.java) {
            RecordDevelopPipelineOverlapPolicy.maxResidentFrames(
                residentBytesPerFrame = 900L * 1024 * 1024,
                conservativeMaxFrames = 1,
                maxMemoryBytes = 534L * 1024 * 1024,
                flagEnabled = true
            )
        }
    }

    @Test
    fun inconsistentConservativeWindowCannotExceedHardBudget() {
        assertThrows(IllegalArgumentException::class.java) {
            RecordDevelopPipelineOverlapPolicy.maxResidentFrames(
                residentBytesPerFrame = frame,
                conservativeMaxFrames = 2,
                maxMemoryBytes = 2L * 1024 * 1024 * 1024,
                memoryBudgetBytes = 200L * 1024 * 1024,
                flagEnabled = false
            )
        }
    }

    @Test
    fun configuredMemoryBudgetRemainsAuthoritative() {
        val cap = RecordDevelopPipelineOverlapPolicy.maxResidentFrames(
            residentBytesPerFrame = frame,
            conservativeMaxFrames = 1,
            maxMemoryBytes = 2L * 1024 * 1024 * 1024,
            memoryBudgetBytes = 200L * 1024 * 1024,
            flagEnabled = true
        )
        assertEquals(1, cap)
    }

    @Test
    fun peakResidentStaysUnderHeapGuard() {
        val cap = RecordDevelopPipelineOverlapPolicy.maxResidentFrames(
            residentBytesPerFrame = frame,
            conservativeMaxFrames = 1,
            maxMemoryBytes = 534L * 1024 * 1024,
            flagEnabled = true
        )
        val ceiling = 534L * 1024 * 1024 *
            RecordDevelopPipelineOverlapPolicy.HEAP_NUMERATOR / RecordDevelopPipelineOverlapPolicy.HEAP_DENOMINATOR
        assertTrue("peak $cap*frame must stay under the heap guard", cap * frame <= ceiling)
    }
}
