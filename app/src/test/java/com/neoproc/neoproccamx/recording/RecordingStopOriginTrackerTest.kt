package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStopOriginTrackerTest {
    @Test
    fun firstStopAuthorityCannotBeOverwrittenByLaterDebugStop() {
        var elapsedMs = 100L
        val tracker = RecordingStopOriginTracker { elapsedMs }
        tracker.beginSession("lq001.05")

        val thermal = tracker.recordFirst(
            RecordingStopOrigin.THERMAL_EMERGENCY,
            "status=5\nquality preserved"
        )
        elapsedMs = 200L
        val debug = tracker.recordFirst(
            RecordingStopOrigin.DEBUG_COMMAND,
            "host timeout"
        )

        assertTrue(thermal.newlyRecorded)
        assertFalse(debug.newlyRecorded)
        assertEquals(RecordingStopOrigin.THERMAL_EMERGENCY, debug.evidence.origin)
        assertEquals(100L, debug.evidence.requestedElapsedRealtimeMs)
        assertEquals("status:5 quality preserved", debug.evidence.detail)
    }

    @Test
    fun beginSessionResetsEvidenceAndAdvancesGeneration() {
        val tracker = RecordingStopOriginTracker { 42L }
        val first = tracker.beginSession("one")
        tracker.recordFirst(RecordingStopOrigin.USER_SHUTTER)
        val second = tracker.beginSession("two")

        assertEquals(first.sessionGeneration + 1L, second.sessionGeneration)
        assertEquals(RecordingStopOrigin.NOT_REQUESTED, second.origin)
        assertEquals("two", second.validationLabel)
        assertEquals(0L, second.requestedElapsedRealtimeMs)
    }

    @Test
    fun metadataIsStableAndLineSafe() {
        val tracker = RecordingStopOriginTracker { 7L }
        tracker.beginSession("qa=run")
        val evidence = tracker.recordFirst(
            RecordingStopOrigin.NPV10_WRITER_FAILURE,
            "writer=failed\r\nretry blocked"
        ).evidence.toMetadata()

        assertEquals("qa:run", evidence["recordingStopValidationLabel"])
        assertEquals("NPV10_WRITER_FAILURE", evidence["recordingStopOrigin"])
        assertEquals("writer:failed  retry blocked", evidence["recordingStopDetail"])
        assertEquals("7", evidence["recordingStopRequestedElapsedRealtimeMs"])
    }
}
