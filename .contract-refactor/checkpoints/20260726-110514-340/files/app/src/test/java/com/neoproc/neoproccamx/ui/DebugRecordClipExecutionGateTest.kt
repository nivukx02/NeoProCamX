package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugRecordClipExecutionGateTest {
    @Test
    fun `one command can claim exactly one start observer and stop`() {
        val gate = DebugRecordClipExecutionGate()
        val token = gate.beginCommand()

        assertTrue(gate.tryClaimStart(token))
        assertFalse(gate.tryClaimStart(token))
        assertTrue(gate.tryClaimObserver(token))
        assertFalse(gate.tryClaimObserver(token))
        assertTrue(gate.tryClaimStop(token))
        assertFalse(gate.tryClaimStop(token))
        assertTrue(gate.completeCommand(token))
        assertFalse(gate.completeCommand(token))
    }

    @Test
    fun `stale callbacks cannot control a newer command`() {
        val gate = DebugRecordClipExecutionGate()
        val staleToken = gate.beginCommand()
        val currentToken = gate.beginCommand()

        assertFalse(gate.tryClaimStart(staleToken))
        assertFalse(gate.tryClaimObserver(staleToken))
        assertFalse(gate.tryClaimStop(staleToken))
        assertFalse(gate.completeCommand(staleToken))
        assertTrue(gate.tryClaimStart(currentToken))
    }
}
