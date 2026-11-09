package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestFrameRenderGateTest {
    @Test
    fun `bursty latest-image callbacks queue one render task`() {
        val gate = LatestFrameRenderGate()

        assertTrue(gate.requestDispatch())
        repeat(32) { assertFalse(gate.requestDispatch()) }
    }

    @Test
    fun `request arriving during a draw schedules one follow-up`() {
        val gate = LatestFrameRenderGate()
        assertTrue(gate.requestDispatch())

        gate.onDispatchStarted()

        assertTrue(gate.requestDispatch())
        assertFalse(gate.requestDispatch())
    }

    @Test
    fun `failed handler post releases the gate for recovery`() {
        val gate = LatestFrameRenderGate()
        assertTrue(gate.requestDispatch())

        gate.cancelPendingDispatch()

        assertTrue(gate.requestDispatch())
    }
}
