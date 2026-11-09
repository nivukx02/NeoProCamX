package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordDevelopCodecLifecyclePolicyTest {
    @Test
    fun `first codec creation has no artificial delay`() {
        assertEquals(
            0L,
            RecordDevelopCodecLifecyclePolicy.requiredWaitMs(
                lastReleaseElapsedMs = null,
                nowElapsedMs = 1_000L
            )
        )
    }

    @Test
    fun `rapid reopen waits only for remaining quiescence`() {
        assertEquals(
            1_896L,
            RecordDevelopCodecLifecyclePolicy.requiredWaitMs(
                lastReleaseElapsedMs = 1_000L,
                nowElapsedMs = 1_104L
            )
        )
    }

    @Test
    fun `settled codec service reopens immediately`() {
        assertEquals(
            0L,
            RecordDevelopCodecLifecyclePolicy.requiredWaitMs(
                lastReleaseElapsedMs = 1_000L,
                nowElapsedMs = 3_000L
            )
        )
    }

    @Test
    fun `elapsed clock rollback fails safe to full quiescence`() {
        assertEquals(
            RecordDevelopCodecLifecyclePolicy.MINIMUM_RELEASE_QUIESCENCE_MS,
            RecordDevelopCodecLifecyclePolicy.requiredWaitMs(
                lastReleaseElapsedMs = 2_000L,
                nowElapsedMs = 1_900L
            )
        )
    }
}
