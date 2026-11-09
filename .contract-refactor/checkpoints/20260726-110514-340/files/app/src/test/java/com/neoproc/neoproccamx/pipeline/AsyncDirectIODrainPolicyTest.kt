package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncDirectIODrainPolicyTest {

    @Test
    fun `close waits when queue is empty but direct io write is active`() {
        assertTrue(
            AsyncDirectIODrainPolicy.shouldWaitForDrain(
                queuedWrites = 0,
                activeWrites = 1,
                elapsedMs = 200L,
                timeoutMs = 1_000L
            )
        )
    }

    @Test
    fun `close stops waiting only after queue and active writes are drained`() {
        assertFalse(
            AsyncDirectIODrainPolicy.shouldWaitForDrain(
                queuedWrites = 0,
                activeWrites = 0,
                elapsedMs = 200L,
                timeoutMs = 1_000L
            )
        )
    }

    @Test
    fun `close stops waiting after timeout so shutdown cannot hang forever`() {
        assertFalse(
            AsyncDirectIODrainPolicy.shouldWaitForDrain(
                queuedWrites = 0,
                activeWrites = 1,
                elapsedMs = 1_001L,
                timeoutMs = 1_000L
            )
        )
    }
}
