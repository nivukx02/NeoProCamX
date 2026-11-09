package com.neoproc.neoproccamx.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrRouteReadinessReplaySlotTest {
    private data class DependencyHarness(
        val slot: NdrRouteReadinessReplaySlot<String> = NdrRouteReadinessReplaySlot(),
        var runtimeReady: Boolean = false,
        var compositorReady: Boolean = false,
        val completed: MutableList<String> = mutableListOf()
    ) {
        fun runtimeEdge() {
            runtimeReady = true
            consumeOrRedefer()
        }

        fun compositorEdge() {
            compositorReady = true
            consumeOrRedefer()
        }

        private fun consumeOrRedefer() {
            val intent = slot.takeForReadinessEdge() ?: return
            if (runtimeReady && compositorReady) completed += intent
            else slot.retainLatest(intent)
        }

        fun retainAfterReadinessCallbacks(intent: String) {
            slot.retainLatest(intent)
            if (runtimeReady && compositorReady) consumeOrRedefer()
        }
    }

    @Test
    fun `runtime-first ordering re-defers once then compositor edge completes exactly once`() {
        val harness = DependencyHarness()
        harness.slot.retainLatest("latest-current-epoch")

        harness.runtimeEdge()
        assertTrue(harness.slot.hasPendingIntent())
        assertEquals(emptyList<String>(), harness.completed)

        harness.compositorEdge()
        assertFalse(harness.slot.hasPendingIntent())
        assertEquals(listOf("latest-current-epoch"), harness.completed)
        harness.compositorEdge()
        assertEquals(1, harness.completed.size)
    }

    @Test
    fun `compositor-first ordering re-defers once then runtime edge completes exactly once`() {
        val harness = DependencyHarness()
        harness.slot.retainLatest("latest-current-epoch")

        harness.compositorEdge()
        assertTrue(harness.slot.hasPendingIntent())
        assertEquals(emptyList<String>(), harness.completed)

        harness.runtimeEdge()
        assertFalse(harness.slot.hasPendingIntent())
        assertEquals(listOf("latest-current-epoch"), harness.completed)
        harness.runtimeEdge()
        assertEquals(1, harness.completed.size)
    }

    @Test
    fun `latest request coalesces and invalidation or close prevents replay`() {
        val slot = NdrRouteReadinessReplaySlot<String>()
        slot.retainLatest("old")
        slot.retainLatest("latest")
        assertEquals("latest", slot.takeForReadinessEdge())
        assertNull(slot.takeForReadinessEdge())

        slot.retainLatest("generation-old")
        slot.invalidate()
        assertNull(slot.takeForReadinessEdge())

        slot.retainLatest("lifecycle-old")
        slot.close()
        slot.retainLatest("late")
        assertNull(slot.takeForReadinessEdge())
    }

    @Test
    fun `late runtime readiness registration observes the already published generation`() = runBlocking {
        val harness = DependencyHarness()
        val readyGeneration = MutableStateFlow(0L)
        harness.slot.retainLatest("current-generation")

        // The compositor wins the startup race, so the route remains retained for runtime readiness.
        harness.compositorEdge()
        assertTrue(harness.slot.hasPendingIntent())

        // Runtime commits before the lifecycle collector is registered. StateFlow must provide that
        // already-published state immediately instead of requiring a second profile commit/event.
        readyGeneration.value = 41L
        val observedAfterRegistration = readyGeneration.first { it > 0L }
        assertEquals(41L, observedAfterRegistration)

        harness.runtimeEdge()
        assertFalse(harness.slot.hasPendingIntent())
        assertEquals(listOf("current-generation"), harness.completed)
    }

    @Test
    fun `post-retention sticky check recovers readiness callbacks that already saw an empty slot`() {
        val harness = DependencyHarness()

        // Both readiness callbacks win the race while there is no retained request to consume.
        harness.runtimeEdge()
        harness.compositorEdge()
        assertEquals(emptyList<String>(), harness.completed)

        // Production performs this sticky check immediately after retaining under the same lock.
        harness.retainAfterReadinessCallbacks("retained-after-edges")

        assertFalse(harness.slot.hasPendingIntent())
        assertEquals(listOf("retained-after-edges"), harness.completed)
    }
}
