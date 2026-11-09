package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.Npv10P010ImageAdmission
import org.junit.Assert.assertEquals
import org.junit.Test

class Npv10FirstSinkAdmissionTrackerTest {
    @Test
    fun `rejected ownership never claims the first writer timestamp`() {
        val tracker = Npv10FirstSinkAdmissionTracker()

        tracker.observe(Npv10P010ImageAdmission.ownedWithoutWriterAdmission(1_000L))

        assertEquals(0L, tracker.timestampNs())
    }

    @Test
    fun `sequence zero remains authoritative when completions are observed out of order`() {
        val tracker = Npv10FirstSinkAdmissionTracker()

        tracker.observe(Npv10P010ImageAdmission.accepted(2_000L, ingressSequence = 1L))
        tracker.observe(Npv10P010ImageAdmission.accepted(1_000L, ingressSequence = 0L))
        tracker.observe(Npv10P010ImageAdmission.accepted(3_000L, ingressSequence = 2L))

        assertEquals(1_000L, tracker.timestampNs())
    }

    @Test
    fun `duplicate sequence zero observation cannot replace the original identity`() {
        val tracker = Npv10FirstSinkAdmissionTracker()

        tracker.observe(Npv10P010ImageAdmission.accepted(1_000L, ingressSequence = 0L))
        tracker.observe(Npv10P010ImageAdmission.accepted(9_000L, ingressSequence = 0L))

        assertEquals(1_000L, tracker.timestampNs())
    }
}
