package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSurfaceRetirementCoordinatorTest {

    @Test
    fun `replacement waits for the exact Camera2 retirement`() {
        val coordinator = PreviewSurfaceRetirementCoordinator<Any>()
        val ticket = coordinator.begin()
        val replacement = Any()

        assertTrue(coordinator.deferReplacement(replacement))
        val completion = coordinator.complete(ticket)

        assertTrue(completion.accepted)
        assertSame(replacement, completion.replacement)
        assertFalse(coordinator.deferReplacement(Any()))
    }

    @Test
    fun `latest replacement wins while old Camera2 ownership drains`() {
        val coordinator = PreviewSurfaceRetirementCoordinator<Any>()
        val ticket = coordinator.begin()
        val first = Any()
        val latest = Any()

        assertTrue(coordinator.deferReplacement(first))
        assertTrue(coordinator.deferReplacement(latest))

        assertSame(latest, coordinator.complete(ticket).replacement)
    }

    @Test
    fun `stale close completion cannot consume a newer retirement`() {
        val coordinator = PreviewSurfaceRetirementCoordinator<Any>()
        val staleTicket = coordinator.begin()
        coordinator.deferReplacement(Any())
        val currentTicket = coordinator.begin()
        val currentReplacement = Any()
        coordinator.deferReplacement(currentReplacement)

        val staleCompletion = coordinator.complete(staleTicket)

        assertFalse(staleCompletion.accepted)
        assertNull(staleCompletion.replacement)
        assertTrue(coordinator.isCurrent(currentTicket))
        assertSame(currentReplacement, coordinator.complete(currentTicket).replacement)
    }

    @Test
    fun `retirement may complete without a replacement`() {
        val coordinator = PreviewSurfaceRetirementCoordinator<Any>()
        val completion = coordinator.complete(coordinator.begin())

        assertTrue(completion.accepted)
        assertNull(completion.replacement)
        assertNull(coordinator.currentTicket())
    }
}
