package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraReleaseAcknowledgementGateTest {
    @Test fun `device close is definitive when vendor omits a session callback`() {
        val gate = CameraReleaseAcknowledgementGate()
        val device = Any()
        val firstSession = Any()
        val secondSession = Any()
        val ticket = gate.begin(device, listOf(firstSession, secondSession))

        gate.acknowledgeDevice(Any())
        gate.acknowledgeSession(firstSession)
        gate.acknowledgeDevice(device)
        assertTrue(gate.await(ticket, 0L))
        assertFalse(gate.isFullyAcknowledged(ticket))

        gate.acknowledgeSession(secondSession)
        assertTrue(gate.isFullyAcknowledged(ticket))
    }

    @Test fun `session only release still requires every matching callback`() {
        val gate = CameraReleaseAcknowledgementGate()
        val session = Any()
        val ticket = gate.begin(device = null, sessions = listOf(session))

        assertFalse(gate.await(ticket, 0L))
        gate.acknowledgeSession(session)
        assertTrue(gate.await(ticket, 0L))
    }

    @Test fun `stale callbacks cannot release a later generation`() {
        val gate = CameraReleaseAcknowledgementGate()
        val staleDevice = Any()
        val staleSession = Any()
        gate.begin(staleDevice, listOf(staleSession))
        val liveDevice = Any()
        val liveSession = Any()
        val liveTicket = gate.begin(liveDevice, listOf(liveSession))

        gate.acknowledgeDevice(staleDevice)
        gate.acknowledgeSession(staleSession)
        assertFalse(gate.await(liveTicket, 0L))

        gate.acknowledgeDevice(liveDevice)
        assertTrue(gate.await(liveTicket, 0L))
        assertFalse(gate.isFullyAcknowledged(liveTicket))

        gate.acknowledgeSession(liveSession)
        assertTrue(gate.isFullyAcknowledged(liveTicket))
    }
}
