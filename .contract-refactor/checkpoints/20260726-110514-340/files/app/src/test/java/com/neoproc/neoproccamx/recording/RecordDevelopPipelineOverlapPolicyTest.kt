package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the resident-frame cap decision for develop pipeline overlap ([RecordDevelopPipelineOverlapPolicy]). */
class RecordDevelopPipelineOverlapPolicyTest {

    private val frame = 152L * 1024 * 1024 // a full-sensor uncompressed develop frame.

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
    fun neverBelowConservativeFloorAndNeverAboveMaxDepth() {
        // Tiny frames could fit dozens, but the read-ahead cap holds it at MAX_DEPTH.
        val many = RecordDevelopPipelineOverlapPolicy.maxResidentFrames(
            residentBytesPerFrame = 1L * 1024 * 1024,
            conservativeMaxFrames = 1,
            maxMemoryBytes = 1024L * 1024 * 1024,
            flagEnabled = true
        )
        assertEquals(RecordDevelopPipelineOverlapPolicy.MAX_DEPTH, many)

        // A huge frame that doesn't gain anything stays at the conservative floor (never lower).
        val huge = RecordDevelopPipelineOverlapPolicy.maxResidentFrames(
            residentBytesPerFrame = 900L * 1024 * 1024,
            conservativeMaxFrames = 1,
            maxMemoryBytes = 534L * 1024 * 1024,
            flagEnabled = true
        )
        assertEquals(1, huge)
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
