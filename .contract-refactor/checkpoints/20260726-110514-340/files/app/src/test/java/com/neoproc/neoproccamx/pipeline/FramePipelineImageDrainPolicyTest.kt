package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.ArrayDeque

class FramePipelineImageDrainPolicyTest {

    @Test
    fun drainsEveryQueuedItemAvailableInOneCallback() {
        val queuedItems = ArrayDeque(listOf(1, 2, 3, 4, 5))
        val consumedItems = mutableListOf<Int>()

        val drained = FramePipelineImageDrainPolicy.drainAvailable(
            maxItems = 32,
            acquire = { queuedItems.pollFirst() },
            consume = consumedItems::add
        )

        assertEquals(5, drained)
        assertEquals(listOf(1, 2, 3, 4, 5), consumedItems)
        assertEquals(0, queuedItems.size)
    }

    @Test
    fun stopsAtConfiguredDrainCap() {
        val queuedItems = ArrayDeque((1..8).toList())
        val consumedItems = mutableListOf<Int>()

        val drained = FramePipelineImageDrainPolicy.drainAvailable(
            maxItems = 3,
            acquire = { queuedItems.pollFirst() },
            consume = consumedItems::add
        )

        assertEquals(3, drained)
        assertEquals(listOf(1, 2, 3), consumedItems)
        assertEquals(5, queuedItems.size)
    }

    @Test
    fun stopsImmediatelyWhenNoItemIsAvailable() {
        var acquisitionAttempts = 0

        val drained = FramePipelineImageDrainPolicy.drainAvailable<Int>(
            maxItems = 32,
            acquire = {
                acquisitionAttempts++
                null
            },
            consume = { error("No item should be consumed") }
        )

        assertEquals(0, drained)
        assertEquals(1, acquisitionAttempts)
    }
}
