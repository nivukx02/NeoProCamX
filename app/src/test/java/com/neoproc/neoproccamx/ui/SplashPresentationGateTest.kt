package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashPresentationGateTest {
    private val session = 12L
    private val surface = 4L

    @Test
    fun replayedEventFromBeforeArmCannotDismissSplash() {
        val gate = SplashPresentationGate().apply { arm(afterSequence = 8L) }

        assertFalse(gate.accept(8L, 1_000L, session, surface, session, surface))
        assertTrue(gate.accept(9L, 2_000L, session, surface, session, surface))
    }

    @Test
    fun retiredSessionAndSurfaceEventsAreRejectedWithoutConsumingArm() {
        val gate = SplashPresentationGate().apply { arm(afterSequence = 10L) }

        assertFalse(gate.accept(11L, 1_000L, session - 1L, surface, session, surface))
        assertFalse(gate.accept(12L, 2_000L, session, surface - 1L, session, surface))
        assertTrue(gate.accept(13L, 3_000L, session, surface, session, surface))
    }

    @Test
    fun acceptedPresentationIsConsumedExactlyOnce() {
        val gate = SplashPresentationGate().apply { arm(afterSequence = 20L) }

        assertTrue(gate.accept(21L, 1_000L, session, surface, session, surface))
        assertFalse(gate.accept(22L, 2_000L, session, surface, session, surface))
    }

    @Test
    fun invalidatedWarmLaunchCannotBeDismissedByLateCallback() {
        val gate = SplashPresentationGate().apply {
            arm(afterSequence = 30L)
            invalidate()
        }

        assertFalse(gate.accept(31L, 1_000L, session, surface, session, surface))
    }

    @Test
    fun eventMustCarryRealSensorAndPipelineIdentity() {
        val gate = SplashPresentationGate().apply { arm(afterSequence = 0L) }

        assertFalse(gate.accept(1L, 0L, session, surface, session, surface))
        assertFalse(gate.accept(2L, 1_000L, 0L, surface, session, surface))
        assertFalse(gate.accept(3L, 1_000L, session, 0L, session, surface))
        assertTrue(gate.accept(4L, 1_000L, session, surface, session, surface))
    }
}
