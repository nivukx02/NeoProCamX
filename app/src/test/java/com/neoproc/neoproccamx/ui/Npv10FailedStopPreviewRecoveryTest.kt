package com.neoproc.neoproccamx.ui

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10FailedStopPreviewRecoveryTest {
    @Test
    fun `recovery drains leases then restores preview then releases ingress before audit`() = runBlocking {
        val events = mutableListOf<String>()
        assertTrue(Npv10FailedStopPreviewRecovery.restoreBeforeAudit(
            alreadyRestored = false,
            ingressQuiescent = true,
            teardownStarted = { false },
            drainAcceptedFrames = { events += "drain"; true },
            restorePreview = { events += "restore" },
            releaseIngress = { events += "release" }
        ))
        events += "full-file-audit"
        assertEquals(listOf("drain", "restore", "release", "full-file-audit"), events)
    }

    @Test
    fun `already restored preview is not restored or released twice`() = runBlocking {
        assertTrue(Npv10FailedStopPreviewRecovery.restoreBeforeAudit(
            alreadyRestored = true,
            ingressQuiescent = true,
            teardownStarted = { error("Already-restored path must be inert") },
            drainAcceptedFrames = { error("Duplicate drain") },
            restorePreview = { error("Duplicate restore") },
            releaseIngress = { error("Duplicate release") }
        ))
    }

    @Test
    fun `unproven callback quiescence never restores a reader`() = runBlocking {
        assertFalse(Npv10FailedStopPreviewRecovery.restoreBeforeAudit(
            alreadyRestored = false,
            ingressQuiescent = false,
            teardownStarted = { false },
            drainAcceptedFrames = { error("Unsafe drain admission") },
            restorePreview = { error("Unsafe reader restoration") },
            releaseIngress = { error("Unsafe ingress release") }
        ))
    }

    @Test
    fun `teardown before drain never reopens camera`() = runBlocking {
        assertFalse(Npv10FailedStopPreviewRecovery.restoreBeforeAudit(
            alreadyRestored = false,
            ingressQuiescent = true,
            teardownStarted = { true },
            drainAcceptedFrames = { error("Unexpected drain") },
            restorePreview = { error("Camera reopened after teardown") },
            releaseIngress = { error("Ingress reopened after teardown") }
        ))
    }

    @Test
    fun `teardown during drain retains the ingress seal`() = runBlocking {
        var tearingDown = false
        assertFalse(Npv10FailedStopPreviewRecovery.restoreBeforeAudit(
            alreadyRestored = false,
            ingressQuiescent = true,
            teardownStarted = { tearingDown },
            drainAcceptedFrames = { tearingDown = true; true },
            restorePreview = { error("Camera reopened after teardown") },
            releaseIngress = { error("Ingress reopened after teardown") }
        ))
    }

    @Test
    fun `teardown during restoration retains the seal until owner cleanup`() = runBlocking {
        var tearingDown = false
        assertFalse(Npv10FailedStopPreviewRecovery.restoreBeforeAudit(
            alreadyRestored = false,
            ingressQuiescent = true,
            teardownStarted = { tearingDown },
            drainAcceptedFrames = { true },
            restorePreview = { tearingDown = true },
            releaseIngress = { error("Ingress reopened during teardown") }
        ))
    }

    @Test
    fun `failed drain cannot lose image ownership through preview restoration`() = runBlocking {
        var restored = false
        var released = false
        val failure = runCatching {
            Npv10FailedStopPreviewRecovery.restoreBeforeAudit(
                false, true, { false }, { false }, { restored = true }, { released = true }
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(restored)
        assertFalse(released)
    }

    @Test
    fun `drain exception retains exact cause and does not release ingress`() = runBlocking {
        val expected = IllegalStateException("native writer failure")
        val observed = runCatching {
            Npv10FailedStopPreviewRecovery.restoreBeforeAudit(
                false, true, { false }, { throw expected },
                { error("Restore after failed drain") }, { error("Release after failed drain") }
            )
        }.exceptionOrNull()
        assertSame(expected, observed)
    }

    @Test
    fun `restore exception retains exact cause and does not release ingress`() = runBlocking {
        val expected = IllegalStateException("camera session rejected")
        val observed = runCatching {
            Npv10FailedStopPreviewRecovery.restoreBeforeAudit(
                false, true, { false }, { true }, { throw expected },
                { error("Release after failed restore") }
            )
        }.exceptionOrNull()
        assertSame(expected, observed)
    }

    @Test
    fun `cancellation is not mistaken for successful preview restoration`() = runBlocking {
        val expected = CancellationException("lifecycle cancelled")
        val observed = runCatching {
            Npv10FailedStopPreviewRecovery.restoreBeforeAudit(
                false, true, { false }, { true }, { throw expected },
                { error("Release after cancelled restore") }
            )
        }.exceptionOrNull()
        assertSame(expected, observed)
    }

    @Test
    fun `seal close failure is surfaced instead of declaring preview restored`() = runBlocking {
        val expected = IllegalStateException("seal ownership failure")
        val observed = runCatching {
            Npv10FailedStopPreviewRecovery.restoreBeforeAudit(
                false, true, { false }, { true }, {}, { throw expected }
            )
        }.exceptionOrNull()
        assertSame(expected, observed)
    }
}
