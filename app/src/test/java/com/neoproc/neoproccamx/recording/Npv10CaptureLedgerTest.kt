package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10CaptureLedgerTest {
    @Test
    fun `exact ingress accepted and committed lineage passes`() {
        val ledger = Npv10CaptureLedger().apply { reset(25) }
        val periodNs = 40_000_000L
        repeat(4) { index ->
            val timestampNs = 1_000_000_000L + index * periodNs
            ledger.recordIngress(timestampNs)
            ledger.recordAccepted(timestampNs)
            ledger.recordCommitted(timestampNs, 128L)
        }

        val snapshot = ledger.snapshot()
        assertEquals(4L, snapshot.ingressFrames)
        assertTrue(snapshot.failureReasons(128L).isEmpty())
    }

    @Test
    fun `lost prefix tail and async failure cannot self certify`() {
        val ledger = Npv10CaptureLedger().apply { reset(25) }
        val timestamps = listOf(1_000_000_000L, 1_040_000_000L, 1_080_000_000L)
        timestamps.forEach(ledger::recordIngress)
        timestamps.drop(1).forEach(ledger::recordAccepted)
        ledger.recordCommitted(timestamps[1], 128L)

        val failures = ledger.snapshot().failureReasons(128L)
        assertTrue(failures.any { it.startsWith("ingressAcceptedMismatch=") })
        assertTrue(failures.any { it.startsWith("acceptedCommittedMismatch=") })
        assertTrue(failures.contains("timestampLineageHashMismatch"))
        assertTrue(failures.contains("timestampBoundaryMismatch"))
    }

    @Test
    fun `zero nonmonotonic and cadence gap are terminal`() {
        val ledger = Npv10CaptureLedger().apply { reset(25) }
        listOf(0L, 1_000_000_000L, 1_080_000_000L, 1_040_000_000L).forEach { timestampNs ->
            ledger.recordIngress(timestampNs)
            ledger.recordAccepted(timestampNs)
            ledger.recordCommitted(timestampNs, 16L)
        }

        val failures = ledger.snapshot().failureReasons(16L)
        assertTrue(failures.any { it.startsWith("invalidTimestamps=") })
        assertTrue(failures.any { it.startsWith("nonMonotonicIngress=") })
        assertTrue(failures.any { it.startsWith("missingCadenceFrames=") })
    }

    @Test
    fun `intentional pause starts a fresh cadence segment`() {
        val ledger = Npv10CaptureLedger().apply { reset(25) }
        ledger.recordIngress(1_000_000_000L)
        ledger.recordAccepted(1_000_000_000L)
        ledger.recordCommitted(1_000_000_000L, 32L)
        ledger.beginCadenceSegment()
        ledger.recordIngress(9_000_000_000L)
        ledger.recordAccepted(9_000_000_000L)
        ledger.recordCommitted(9_000_000_000L, 32L)

        assertTrue(ledger.snapshot().failureReasons(32L).isEmpty())
    }

    @Test
    fun `terminal boundary never hides a gap that is present in the saved footage`() {
        val ledger = Npv10CaptureLedger().apply { reset(25) }
        val timestamps = listOf(
            1_000_000_000L,
            1_040_000_000L,
            // A genuine five-period gap inside active capture remains terminal.
            1_240_000_000L,
            // A final five-period interval is also visible in the saved media.
            1_440_000_000L
        )
        timestamps.forEach { timestampNs ->
            ledger.recordIngress(timestampNs)
            ledger.recordAccepted(timestampNs)
            ledger.recordCommitted(timestampNs, 32L)
        }

        assertEquals(8L, ledger.snapshot().missingCadenceFrames)
        assertEquals(0L, ledger.closeTerminalCadenceSegment(1_440_000_000L))
        val snapshot = ledger.snapshot()
        assertEquals(4L, snapshot.ingressFrames)
        assertEquals(4L, snapshot.acceptedFrames)
        assertEquals(4L, snapshot.committedFrames)
        assertEquals(8L, snapshot.missingCadenceFrames)
        assertEquals(1_440_000_000L, snapshot.terminalCadenceBoundaryTimestampNs)
        assertEquals(0L, snapshot.terminalCadenceExcludedMissingFrames)
        assertEquals(listOf("missingCadenceFrames=8"), snapshot.failureReasons(32L))
    }

    @Test
    fun `terminal cadence close requires exact committed tail and rejects later ingress`() {
        val ledger = Npv10CaptureLedger().apply { reset(25) }
        listOf(1_000_000_000L, 1_200_000_000L).forEach { timestampNs ->
            ledger.recordIngress(timestampNs)
            ledger.recordAccepted(timestampNs)
            ledger.recordCommitted(timestampNs, 16L)
        }

        assertEquals(null, ledger.closeTerminalCadenceSegment(1_199_999_999L))
        assertEquals(4L, ledger.snapshot().missingCadenceFrames)
        assertEquals(0L, ledger.closeTerminalCadenceSegment(1_200_000_000L))
        val closedSnapshot = ledger.snapshot()
        assertEquals(4L, closedSnapshot.missingCadenceFrames)
        assertEquals(1_200_000_000L, closedSnapshot.terminalCadenceBoundaryTimestampNs)
        assertEquals(0L, closedSnapshot.terminalCadenceExcludedMissingFrames)
        val lateIngress = runCatching { ledger.recordIngress(1_240_000_000L) }.exceptionOrNull()
        assertTrue(lateIngress is IllegalStateException)

        val incomplete = Npv10CaptureLedger().apply { reset(25) }
        incomplete.recordIngress(2_000_000_000L)
        incomplete.recordAccepted(2_000_000_000L)
        assertEquals(null, incomplete.closeTerminalCadenceSegment(2_000_000_000L))

        val reorderedAcceptedTail = Npv10CaptureLedger().apply { reset(25) }
        listOf(3_000_000_000L, 3_200_000_000L).forEach(reorderedAcceptedTail::recordIngress)
        reorderedAcceptedTail.recordAccepted(3_200_000_000L)
        reorderedAcceptedTail.recordAccepted(3_000_000_000L)
        reorderedAcceptedTail.recordCommitted(3_000_000_000L, 16L)
        reorderedAcceptedTail.recordCommitted(3_200_000_000L, 16L)
        assertEquals(null, reorderedAcceptedTail.closeTerminalCadenceSegment(3_200_000_000L))
        assertEquals(4L, reorderedAcceptedTail.snapshot().missingCadenceFrames)
    }
}
